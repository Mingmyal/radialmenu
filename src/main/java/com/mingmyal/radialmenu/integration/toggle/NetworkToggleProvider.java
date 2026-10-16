package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 处理"只能触发、读不到状态"的网络切换包。
 * <p>
 * 部分模组把功能开关完全交给服务端维护, 客户端只有一个"请求切换"的 C2S 包,
 * 并且<b>没有</b>任何 S2C 包回报当前状态。典型例子是 Follow Me:
 * 跟随开关是服务端的 {@code Set<UUID>}, 客户端只能盲发一个切换请求。
 * <p>
 * 这类来源无法做成"开关类型"(那种模式每帧要读状态、并写入明确的开或关),
 * 因此在这里登记为 {@link ToggleSource#hasReadableState()} 为 {@code false} 的来源:
 * <ul>
 *   <li>只暴露"触发切换"能力, {@link #toggle()} 直接发包;</li>
 *   <li>不会出现在开关类型的来源列表里, 避免显示一个恒定的假状态;</li>
 *   <li>由"外部模组开关"动作模式使用, 点击即切换, 界面不显示任何状态。</li>
 * </ul>
 * 依赖的约定与 MaLiLib/Cloth 等提供者一致: 反射访问, 未安装时不产生类加载错误。
 */
public class NetworkToggleProvider implements ToggleSourceProvider {

    public static final String ID = "network";

    /** 一条网络切换包的声明。 */
    private record Entry(String modId, String payloadClassName, String labelKey) {}

    /**
     * 已声明的入口, 保持插入顺序。
     * <p>
     * 以不可变快照持有: 写入只发生在初始化阶段(内置声明与入口点分发),
     * 读取可能发生在后台预热线程上, 因此读取方只需读一次 volatile 字段。
     */
    private static volatile List<Entry> entries = List.of();

    /** payload 类名 -> 已解析的开关; 解析含类加载, 缓存一次即可。 */
    private static final Map<String, PacketToggle> RESOLVED = new ConcurrentHashMap<>();

    static {
        // Follow Me: 跟随开关由服务端 Set<UUID> 维护, 且该模组未定义任何 S2C 包,
        // 客户端读不到真实状态。这里只登记"触发"能力, 不伪装成可读开关。
        register("followme", "com.dawhat.followme.ToggleFollowPayload",
                "radialmenu.toggle.followme.togglefollow");
    }

    /**
     * 登记一个网络切换包。
     * <p>
     * 约定目标类满足: 公开静态字段 {@code ID}(类型为 {@code CustomPacketPayload.Type}),
     * 以及无参构造器 —— 这是 Fabric 网络包记录类的通行写法
     * (Follow Me 的 {@code ToggleFollowPayload} / {@code ToggleMoonDancePayload} 均如此)。
     *
     * @param modId            所属模组 ID
     * @param payloadClassName 网络包类全名
     * @param labelKey         展示名翻译键; 无翻译时退回由类名推导的名字
     */
    public static synchronized void register(String modId, String payloadClassName, String labelKey) {
        if (modId == null || modId.isBlank()) return;
        if (payloadClassName == null || payloadClassName.isBlank()) return;

        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(modId, payloadClassName, labelKey));
        entries = List.copyOf(next);

        // 声明变化后丢弃该目标的解析缓存
        RESOLVED.remove(payloadClassName);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.network");
    }

    @Override
    public boolean isAvailable() {
        for (Entry entry : entries) {
            if (!FabricLoader.getInstance().isModLoaded(entry.modId())) continue;
            if (toggleFor(entry).isClassPresent()) return true;
        }
        return false;
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        // 只读一次 volatile 字段, 保证本轮基于同一个快照
        List<Entry> snapshot = entries;
        for (Entry entry : snapshot) {
            try {
                if (!FabricLoader.getInstance().isModLoaded(entry.modId())) continue;

                PacketToggle toggle = toggleFor(entry);
                if (toggle.isClassPresent()) {
                    sink.accept(toggle);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error(
                        "Failed to discover network toggle '{}'", entry.payloadClassName(), t);
            }
        }
    }

    private static PacketToggle toggleFor(Entry entry) {
        return RESOLVED.computeIfAbsent(entry.payloadClassName(), key -> new PacketToggle(entry));
    }

    /** 由网络包守护的"纯触发"开关。 */
    private static final class PacketToggle implements ToggleSource {

        private final Entry entry;
        private final String fallbackName;
        private final ResourceLocation id;

        /** 解析结果; {@code resolved} 与内容分开记录, 以便把"确实不存在"也缓存下来。 */
        private volatile boolean resolved;
        private volatile CustomPacketPayload.Type<?> type;
        private volatile Constructor<?> constructor;

        /** setState 对纯触发包无意义, 只提示一次避免刷屏。 */
        private volatile boolean warnedAboutSetState;

        private Component cachedDisplay;

        private PacketToggle(Entry entry) {
            this.entry = entry;
            this.fallbackName = deriveIdPath(entry.payloadClassName());
            this.id = ReflectUtil.safeId(entry.modId(), fallbackName);
        }

        private boolean resolve() {
            if (resolved) return type != null && constructor != null;
            resolved = true;

            try {
                Class<?> payloadClass = Class.forName(
                        entry.payloadClassName(), false, NetworkToggleProvider.class.getClassLoader());

                Field idField = payloadClass.getField("ID");
                Object raw = idField.get(null);
                if (!(raw instanceof CustomPacketPayload.Type<?> packetType)) {
                    RadialCommon.LOGGER.warn(
                            "Network toggle '{}' has no usable ID field", entry.payloadClassName());
                    return false;
                }

                // 记录类的规范构造器; 该类可能不是 public, 因此显式放开访问
                Constructor<?> ctor = payloadClass.getDeclaredConstructor();
                ctor.setAccessible(true);

                type = packetType;
                constructor = ctor;
                return true;
            } catch (Throwable t) {
                RadialCommon.LOGGER.debug(
                        "Network toggle '{}' is not usable", entry.payloadClassName(), t);
                return false;
            }
        }

        /** 目标类是否存在(不代表服务器支持该包)。 */
        private boolean isClassPresent() {
            return resolve();
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component displayName() {
            if (cachedDisplay != null) return cachedDisplay;

            String key = entry.labelKey();
            if (key != null) {
                try {
                    Component translated = Component.translatable(key);
                    String text = translated.getString();
                    if (text != null && !text.isBlank() && !text.equals(key)) {
                        cachedDisplay = translated;
                        return cachedDisplay;
                    }
                } catch (Throwable ignored) {
                    // 语言未就绪时退回推导名
                }
            }

            cachedDisplay = Component.literal(fallbackName);
            return cachedDisplay;
        }

        @Override
        public String modId() {
            return entry.modId();
        }

        @Override
        public Component modName() {
            return Component.literal(ReflectUtil.modDisplayName(entry.modId()));
        }

        @Override
        public Component description() {
            return Component.literal(entry.payloadClassName());
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public boolean isAvailable() {
            if (!FabricLoader.getInstance().isModLoaded(entry.modId())) return false;
            if (!resolve()) return false;

            // 真正的可用性取决于"当前服务器是否注册了这个 C2S 通道":
            // 单人游戏或未装该模组服务端的服务器上无法发送。
            CustomPacketPayload.Type<?> packetType = type;
            try {
                return packetType != null && ClientPlayNetworking.canSend(packetType);
            } catch (Throwable t) {
                return false;
            }
        }

        /** 状态不可读: 恒为 false, 该值不会被任何界面用于显示。 */
        @Override
        public boolean getState() {
            return false;
        }

        @Override
        public boolean hasReadableState() {
            return false;
        }

        /**
         * 纯切换包没有"设置成某个值"的语义: 它只会翻转服务端状态,
         * 在不知道当前状态的前提下无法把开关置为指定的开或关。
         * 因此这里不做任何事, 只提示一次; 需要切换请用 {@link #toggle()}。
         */
        @Override
        public void setState(boolean value) {
            if (warnedAboutSetState) return;
            warnedAboutSetState = true;
            RadialCommon.LOGGER.warn(
                    "Network toggle '{}' only supports toggling; setState({}) was ignored",
                    id, value);
        }

        /** 发送切换请求。这是本来源唯一有意义的操作。 */
        @Override
        public void toggle() {
            if (!resolve()) return;

            try {
                Object payload = constructor.newInstance();
                if (payload instanceof CustomPacketPayload packet) {
                    ClientPlayNetworking.send(packet);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to send toggle packet '{}'", id, t);
            }
        }

        @Override
        public String toString() {
            return entry.modId() + ":" + fallbackName;
        }
    }

    /**
     * 由类名推导 id 路径与兜底显示名: 去掉 {@code Payload} 后缀并小写。
     * <p>
     * {@code ToggleFollowPayload} → {@code togglefollow},
     * 因此 followme 的 id 是 {@code followme:togglefollow} 而不是冗长的全名。
     */
    private static String deriveIdPath(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        if (simple.endsWith("Payload")) {
            simple = simple.substring(0, simple.length() - "Payload".length());
        }
        return ReflectUtil.sanitizePath(simple);
    }
}
