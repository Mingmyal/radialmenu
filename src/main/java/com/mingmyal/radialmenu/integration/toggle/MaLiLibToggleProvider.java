package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 从 MaLiLib 及其附属模组(Litematica / MiniHUD / Tweakeroo 等)读取功能开关。
 * <p>
 * 难点在于 MaLiLib <b>没有</b>提供枚举配置项的公开 API:
 * <ul>
 *   <li>已注册的配置处理器存放在 {@code ConfigManager.configHandlers}
 *       ({@code Map<String, IConfigHandler>}, 私有 final 实例字段), 需要反射读取;</li>
 *   <li>每个模组的开关本身通常是某个类的
 *       {@code public static final ImmutableList<IConfigValue> OPTIONS} 字段
 *       (常见于 {@code XxxConfigs} 的内部静态类), 处理器对象上往往一个配置字段都没有,
 *       因此必须递归扫描内部类。</li>
 * </ul>
 * 另外 {@code ConfigTypeWrapper} 无条件实现了 {@code IConfigBoolean},
 * 对非布尔类型会静默返回 false 且写入无效, 因此<b>必须</b>先用
 * {@code getType().name().equals("BOOLEAN")} 过滤, 不能只靠 instanceof。
 */
public class MaLiLibToggleProvider implements ToggleSourceProvider {

    public static final String ID = "malilib";

    private static final String CONFIG_MANAGER = "fi.dy.masa.malilib.config.ConfigManager";
    private static final String I_CONFIG_VALUE = "fi.dy.masa.malilib.config.IConfigValue";
    private static final String I_CONFIG_BOOLEAN = "fi.dy.masa.malilib.config.IConfigBoolean";

    /** 每个配置库最多扫描的类数量, 防御性的上界, 避免异常配置导致长时间卡顿。 */
    private static final int MAX_CLASSES_PER_HANDLER = 64;

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.malilib");
    }

    @Override
    public boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("malilib");
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        Map<String, Object> handlers = collectHandlers();
        if (handlers.isEmpty()) return;

        Class<?> configBoolean = ReflectUtil.loadClass(I_CONFIG_BOOLEAN);

        for (Map.Entry<String, Object> entry : handlers.entrySet()) {
            String modId = entry.getKey();
            try {
                discoverInHandler(modId, entry.getValue(), configBoolean, sink);
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to scan MaLiLib configs for mod '{}'", modId, t);
            }
        }
    }

    /**
     * 反射读取 {@code ConfigManager.configHandlers}。
     * handlers 在游戏初始化阶段才注册, 因此这里读到的可能是不完整的集合;
     * 调用方(注册表)会在需要时重新发现。
     */
    private Map<String, Object> collectHandlers() {
        Map<String, Object> result = new LinkedHashMap<>();

        Class<?> managerClass = ReflectUtil.loadClass(CONFIG_MANAGER);
        if (managerClass == null) return result;

        Object instance = ReflectUtil.invoke(ReflectUtil.findMethod(managerClass, "getInstance", 0), null);
        if (instance == null) return result;

        Field field = ReflectUtil.findField(managerClass, "configHandlers");
        Object value = ReflectUtil.getField(field, instance);

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String modId && e.getValue() != null) {
                    result.put(modId, e.getValue());
                }
            }
        }
        return result;
    }

    /** 扫描单个模组的配置处理器: 自身字段 + 递归内部类。 */
    private void discoverInHandler(String modId, Object handler, Class<?> configBoolean, Consumer<ToggleSource> sink) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        List<Object> options = new ArrayList<>();

        collectOptionsFromClass(handler.getClass(), handler, options, visited, 0);
        // 处理器对象上可能没有任何配置字段, 配置通常声明在内部静态类的静态 OPTIONS 里
        scanNestedClasses(handler.getClass(), options, visited, 0);

        for (Object option : options) {
            ToggleSource source = adapt(modId, option, configBoolean);
            if (source != null) sink.accept(source);
        }
    }

    /** 递归内部类(最多两层), 找出静态声明的 OPTIONS 列表或布尔配置字段。 */
    private void scanNestedClasses(Class<?> owner, List<Object> out, Set<Class<?>> visited, int depth) {
        if (owner == null || depth > 2 || out.size() > 512) return;
        if (!visited.add(owner)) return;

        Class<?>[] nested;
        try {
            nested = owner.getDeclaredClasses();
        } catch (Throwable t) {
            return;
        }

        int scanned = 0;
        for (Class<?> inner : nested) {
            if (scanned++ > MAX_CLASSES_PER_HANDLER) break;

            // 静态内部类: 目标是其静态字段, 因此实例参数传 null
            try {
                collectOptionsFromClass(inner, null, out, visited, depth);
            } catch (Throwable ignored) {
                // 单个内部类失败不影响其它
            }
            scanNestedClasses(inner, out, visited, depth + 1);
        }
    }

    /**
     * 从类中收集配置项: 优先识别集合型字段(OPTIONS), 其次识别单个 IConfigValue 字段。
     * 字段值在 {@code target} 上读取; {@code target} 为 null 时只能取静态字段。
     */
    private void collectOptionsFromClass(
            Class<?> owner, Object target, List<Object> out, Set<Class<?>> visited, int depth) {
        if (owner == null || owner == Object.class) return;

        Field[] fields;
        try {
            fields = owner.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }

        for (Field field : fields) {
            try {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    // 静态字段: 只在 target 为 null(静态扫描)时读取, 避免重复
                    if (target != null) continue;
                } else if (target == null) {
                    continue;
                }

                field.setAccessible(true);
                Object value = field.get(target);

                for (Object candidate : ReflectUtil.expand(value)) {
                    if (isConfigValue(candidate)) {
                        out.add(candidate);
                    }
                }
            } catch (Throwable ignored) {
                // 某些字段不可访问, 跳过
            }
        }
    }

    private boolean isConfigValue(Object candidate) {
        if (candidate == null) return false;

        Class<?> configValue = ReflectUtil.loadClass(I_CONFIG_VALUE);
        return configValue != null && configValue.isInstance(candidate);
    }

    /** 把单个 MaLiLib 配置项适配成 {@link ToggleSource}; 非布尔返回 null。 */
    private ToggleSource adapt(String modId, Object option, Class<?> configBoolean) {
        if (option == null || configBoolean == null || !configBoolean.isInstance(option)) {
            return null;
        }

        // 关键: ConfigTypeWrapper 无条件实现 IConfigBoolean, 必须靠 getType() 过滤
        Method getType = ReflectUtil.findMethod(option.getClass(), "getType", 0);
        Object type = ReflectUtil.invoke(getType, option);
        if (!(type instanceof Enum<?> enumType) || !"BOOLEAN".equals(enumType.name())) {
            return null;
        }

        Method getName = ReflectUtil.findMethod(option.getClass(), "getName", 0);
        Object rawName = ReflectUtil.invoke(getName, option);
        String name = rawName instanceof String s ? s : null;
        if (name == null || name.isBlank()) return null;

        Method getBooleanValue = ReflectUtil.findMethod(option.getClass(), "getBooleanValue", 0);
        Method setBooleanValue = ReflectUtil.findMethod(option.getClass(), "setBooleanValue", 1);
        if (getBooleanValue == null || setBooleanValue == null) return null;

        // getConfigGuiDisplayName() 是 0.21.1/0.21.10 通用的显示名访问器
        // (getTranslatedName() 在 0.21.1 返回的是未翻译的键)。
        // 注意: 它内部会触发 Minecraft I18n 静态初始化, 因此不在发现阶段调用,
        // 而是延迟到 displayName() 里(那时客户端语言资源一定已就绪)。
        Method getDisplayName = ReflectUtil.findMethod(option.getClass(), "getConfigGuiDisplayName", 0);

        return new MaLiLibToggle(modId, name, option, getBooleanValue, setBooleanValue, getDisplayName);
    }

    /**
     * MaLiLib 布尔开关。
     * <p>
     * 注意: 直接改内存值不会落盘, 因此 setState 之后需要通过
     * {@code ConfigManager#onConfigsChanged(String)} 触发该模组保存。
     */
    private static final class MaLiLibToggle implements ToggleSource {

        private final String modId;
        private final String name;
        private final Object option;
        private final Method getter;
        private final Method setter;
        private final Method displayNameAccessor;
        private final ResourceLocation id;

        /** 惰性缓存的显示名(翻译在首次访问时解析)。 */
        private String cachedDisplay;

        private MaLiLibToggle(
                String modId, String name, Object option, Method getter, Method setter, Method displayNameAccessor) {
            this.modId = modId;
            this.name = name;
            this.option = option;
            this.getter = getter;
            this.setter = setter;
            this.displayNameAccessor = displayNameAccessor;
            this.id = ReflectUtil.safeId("malilib_" + modId, name);
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component displayName() {
            if (cachedDisplay == null) {
                cachedDisplay = resolveDisplayName();
            }
            return Component.literal(cachedDisplay);
        }

        /** 翻译不可用时退回配置项原名。 */
        private String resolveDisplayName() {
            try {
                if (displayNameAccessor != null) {
                    Object raw = ReflectUtil.invoke(displayNameAccessor, option);
                    if (raw instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
                // I18n 未就绪或方法缺失时退回原名
            }
            return name;
        }

        @Override
        public String modId() {
            return modId;
        }

        @Override
        public Component modName() {
            return Component.literal(ReflectUtil.modDisplayName(modId));
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean getState() {
            return ReflectUtil.invokeBoolean(getter, option);
        }

        @Override
        public void setState(boolean value) {
            ReflectUtil.invoke(setter, option, value);
            persist();
        }

        /** 通知 MaLiLib 该模组的配置已变更, 由其自身写盘。 */
        private void persist() {
            try {
                Class<?> managerClass = ReflectUtil.loadClass(CONFIG_MANAGER);
                if (managerClass == null) return;

                Object instance = ReflectUtil.invoke(ReflectUtil.findMethod(managerClass, "getInstance", 0), null);
                Method onChanged = ReflectUtil.findMethod(managerClass, "onConfigsChanged", 1);
                ReflectUtil.invoke(onChanged, instance, modId);
            } catch (Throwable t) {
                RadialCommon.LOGGER.warn("MaLiLib toggle '{}' changed but could not be saved", name, t);
            }
        }

        @Override
        public String toString() {
            return modId + ":" + name;
        }
    }

    // --- 配置界面 ---

    /**
     * MaLiLib 0.21.1 没有按模组 ID 查询配置界面的注册表
     * (0.21.10 新增的 ConfigScreenRegistry 在旧版本不存在),
     * 因此不覆写 {@code knownConfigScreenModIds}/{@code createConfigScreen},
     * 沿用接口默认的"无此能力"; 配置界面改由 {@code ModMenu} 提供者统一处理。
     */
}
