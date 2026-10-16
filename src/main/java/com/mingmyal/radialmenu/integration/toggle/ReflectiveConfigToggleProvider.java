package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 处理"自建配置"模组的通用开关来源。
 * <p>
 * MaLiLib / Cloth / YACL 这些提供者能成立, 是因为对应配置库存在全局注册表可供枚举。
 * 但大量模组(含本仓库适配的几个 Cobblemon 附属)完全不使用配置库, 各自手写
 * Gson / properties / record + 原子写, 因此既没有注册表也没有统一入口, 无法自动发现。
 * <p>
 * 本提供者的策略是: <b>把"从哪里取配置根对象"做成一张声明表</b>, 而把枚举、读写、
 * 命名与降级做成一套通用引擎。每个模组只需少量声明, 新增同类模组的成本恒定。
 * <p>
 * 两个刻意的设计取舍:
 * <ul>
 *   <li><b>根对象每次读写都重新解析</b> —— 换取对"配置实例被整体替换"的免疫
 *       (不可变 record 型配置保存后返回新实例), 代价只是几次字段/方法访问。</li>
 *   <li><b>按字段名推导访问器</b>而不是写死方法名 —— 覆盖绝大多数命名风格
 *       ({@code getX} / {@code isX} / {@code areX}), 字段直读作为最终兜底。</li>
 * </ul>
 */
public class ReflectiveConfigToggleProvider implements ToggleSourceProvider {

    public static final String ID = "reflective";

    /**
     * 单个配置对象最多扫描的字段数, 防御异常结构导致长时间卡顿。
     * 手写配置类的字段数通常在数十以内。
     */
    private static final int MAX_FIELDS_PER_TARGET = 256;

    /**
     * 已声明适配目标的模组 ID -> 目标; 保持声明顺序, 界面分组顺序稳定。
     * <p>
     * 以不可变快照的形式持有: 写入只发生在初始化阶段(内置声明与入口点分发,
     * 均在客户端主线程), 而读取发生在可能的后台预热线程上。每次写入整体替换map,
     * 读取方只需读一次 volatile 字段即可拿到一致视图, 无需加锁。
     */
    private static volatile Map<String, ReflectiveConfigTarget> targets = new LinkedHashMap<>();

    /**
     * 已解析完字段的模组 ID -> 开关列表。
     * <p>
     * 只用于去重, 不参与配额: 配置类的字段布局在运行期不变, 解析一次即可;
     * 而根对象仍会随配置被替换, 因此只缓存字段与方法引用, 不缓存实例。
     * <p>
     * 用 {@link ConcurrentHashMap} 而非普通 Map: 发现流程可能同时从后台预热线程
     * ({@code warmUpAsync}) 与渲染线程(选择界面/热路径解析)进入。
     */
    private static final Map<String, List<ReflectiveToggleSource>> RESOLVED = new ConcurrentHashMap<>();

    static {
        registerTargets();
    }

    /**
     * 声明各模组的配置根位置。
     * <p>
     * 这里只登记"能读到、也能写回"的开关。刻意不含 followme 的跟随开关:
     * 它的状态保存在服务端的 {@code Set<UUID>} 里, 且模组未定义任何 S2C 包,
     * 客户端物理上无法读取真实状态, 做成开关只会显示一个恒定的假值。
     * followme 自身经 Cloth AutoConfig 暴露的 {@code showPopupMessages} 等
     * 真实可读项由 {@link ClothAutoConfigToggleProvider} 处理。
     */
    private static void registerTargets() {

        // catchrate-display (Kotlin 单例, Gson 手写)
        // Companion 是静态字段, .get() 懒加载并在首次访问时读写配置文件。
        // 注意: 根对象必须每次重新解析 —— Companion.get() 会在 reload() 后替换实例。
        add(ReflectiveConfigTarget.builder("catchrate-display")
                .rootField("com.catchrate.config.CatchRateConfig", "Companion")
                .chain("get")
                .saver("save")
                .build());

        // cobblemongocatch (单例, properties 手写)
        add(ReflectiveConfigTarget.builder("cobblemongocatch")
                .rootMethod("com.zkev1.cobblemongocatch.config.GoCatchConfig", "getInstance")
                .saver("save")
                .build());

        // jo_battle_camera (静态 CONFIG 字段, JSON 原子写)
        // enabled 的写入必须走 JoBattleCameraClient.joSetEnabled(boolean): 它在关闭时
        // 还会释放相机并挂起导演, 直接 setEnabled + save 会留下悬挂的相机状态。
        // 同时 save() 在"原始配置载入失败"时会拒绝写入并返回 false, 该保护由它内部处理。
        add(ReflectiveConfigTarget.builder("jo_battle_camera")
                .rootField("com.jostar.jobattlecamera.client.JoBattleCameraClient", "CONFIG")
                .saver("save")
                .writer("enabled", (root, value) -> {
                    Class<?> owner = Class.forName(
                            "com.jostar.jobattlecamera.client.JoBattleCameraClient",
                            false,
                            ReflectiveConfigToggleProvider.class.getClassLoader());
                    Method setEnabled = ReflectUtil.findMethod(owner, "joSetEnabled", 1);
                    ReflectUtil.invoke(setEnabled, null, value);
                })
                .writer("diagnostics", (root, value) -> {
                    // 该方法名不是 setDiagnostics 而是 joSetDiagnostics, 因此必须显式声明;
                    // 写入后由 config.save() 落盘。
                    Method setDiagnostics = ReflectUtil.findMethod(root.getClass(), "joSetDiagnostics", 1);
                    ReflectUtil.invoke(setDiagnostics, root, value);
                    ReflectUtil.invoke(ReflectUtil.findMethod(root.getClass(), "save", 0), root);
                })
                // 内部状态标记, 不是用户可见开关
                .exclude("loadAttempted", "enabledWasSet")
                .build());

        // jo_trainer_intro (静态 CONTROLLER -> 不可变 record 配置)
        // record 的组件访问器就叫 enabled(); 写入是"先原子落盘、成功后再应用"的两步流程,
        // 因此必须整体交给 joSaveEnabled + joConfigure, 不能退化成 setter + save。
        add(ReflectiveConfigTarget.builder("jo_trainer_intro")
                .rootField("com.jostar.jotrainerintro.client.JoTrainerIntroClient", "CONTROLLER")
                .chain("joConfig")
                .writer("enabled", (root, value) -> {
                    ClassLoader loader = ReflectiveConfigToggleProvider.class.getClassLoader();
                    Class<?> configType = Class.forName(
                            "com.jostar.jotrainerintro.client.config.IntroClientConfig", false, loader);
                    Class<?> clientType = Class.forName(
                            "com.jostar.jotrainerintro.client.JoTrainerIntroClient", false, loader);

                    // 第一步: 先原子落盘。joSaveEnabled 会重新读盘校验, 保存失败时抛异常,
                    // 此时下面的 joConfigure 不会执行, 内存与磁盘保持一致。
                    Method saveEnabled = ReflectUtil.findMethod(configType, "joSaveEnabled", 2);
                    Object saved = ReflectUtil.invoke(saveEnabled, null, value, root);
                    if (saved == null) {
                        throw new IllegalStateException("joSaveEnabled did not return an updated config");
                    }

                    // 第二步: 落盘成功后才应用到控制器
                    Object controller = ReflectUtil.getField(
                            ReflectUtil.findField(clientType, "CONTROLLER"), null);
                    if (controller == null) {
                        throw new IllegalStateException("JoTrainerIntroClient.CONTROLLER is null");
                    }
                    Method configure = ReflectUtil.findMethod(controller.getClass(), "joConfigure", 1);
                    ReflectUtil.invoke(configure, controller, saved);
                })
                .build());
    }

    private static synchronized void add(ReflectiveConfigTarget target) {
        targets = put(targets, target);
    }

    /**
     * 供第三方模组注册自己的配置目标。
     * <p>
     * 与 {@code ToggleSourceRegistry.registerProvider} 不同, 这里注册的是"数据"而非"代码":
     * 调用方无需理解反射细节, 也不用自己实现 {@link ToggleSource}。
     * <p>
     * 由 {@code RadialApiEntrypoint.registerToggleSources()} 调用。
     */
    public static synchronized void registerTarget(ReflectiveConfigTarget target) {
        if (target == null || target.modId() == null) return;
        targets = put(targets, target);
        // 该模组的字段布局可能已变(尤其是替换了目标声明), 丢弃其解析缓存
        RESOLVED.remove(target.modId());
    }

    /**
     * 生成替换后的不可变快照, 保持插入顺序并覆盖同 ID 的旧目标。
     * <p>
     * 这里用 {@code unmodifiableMap} 而不是 {@code Map.copyOf}: 后者不保证迭代顺序,
     * 而声明顺序决定了选择界面里的模组排列, 需要稳定。
     */
    private static Map<String, ReflectiveConfigTarget> put(
            Map<String, ReflectiveConfigTarget> current, ReflectiveConfigTarget target) {
        Map<String, ReflectiveConfigTarget> next = new LinkedHashMap<>(current);
        next.put(target.modId(), target);
        return Collections.unmodifiableMap(next);
    }

    public static boolean hasTarget(String modId) {
        return targets.containsKey(modId);
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.reflective");
    }

    /**
     * 只要有一个登记模组在场即视为可用。
     * <p>
     * 本提供者的可用性取决于目标模组而非某个配置库, 因此逐个检查类是否存在;
     * 解析结果被缓存, 不会在每次轮询时重复触发类加载。
     */
    @Override
    public boolean isAvailable() {
        // 只读一次 volatile 字段, 保证本轮判断基于同一个快照
        Map<String, ReflectiveConfigTarget> snapshot = targets;
        for (ReflectiveConfigTarget target : snapshot.values()) {
            if (FabricLoader.getInstance().isModLoaded(target.modId()) && target.isClassPresent()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        // 同上: 发现期间即便有第三方注册新目标, 本轮也只用开头的快照
        Map<String, ReflectiveConfigTarget> snapshot = targets;
        for (Map.Entry<String, ReflectiveConfigTarget> entry : snapshot.entrySet()) {
            String modId = entry.getKey();
            ReflectiveConfigTarget target = entry.getValue();

            try {
                if (!FabricLoader.getInstance().isModLoaded(modId)) continue;
                if (!target.isClassPresent()) continue;

                for (ReflectiveToggleSource source : sourcesOf(modId, target)) {
                    sink.accept(source);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to discover reflective config toggles for mod '{}'", modId, t);
            }
        }
    }

    /**
     * 解析某个目标的全部布尔开关。
     * <p>
     * 结果按模组缓存: 字段布局与访问器在运行期不变, 重复反射没有意义;
     * 而每个 {@link ReflectiveToggleSource} 内部仍会重新解析根对象, 因此对
     * "配置实例被替换"是安全的。
     */
    private static List<ReflectiveToggleSource> sourcesOf(String modId, ReflectiveConfigTarget target) {
        List<ReflectiveToggleSource> cached = RESOLVED.get(modId);
        if (cached != null) return cached;

        List<ReflectiveToggleSource> discovered = new ArrayList<>();
        Object root = target.resolveRoot();
        if (root != null) {
            collect(target, root.getClass(), root, discovered);
        }

        // 只在解析成功时缓存: 根对象为 null 通常意味着该模组的配置尚未初始化,
        // 缓存空结果会让它在本次会话中永远无法被发现。
        if (root != null) {
            RESOLVED.put(modId, discovered);
        }
        return discovered;
    }

    /** 扫描配置对象的布尔字段并逐个适配成开关。 */
    private static void collect(
            ReflectiveConfigTarget target, Class<?> owner, Object root, List<ReflectiveToggleSource> out) {

        if (owner == null || owner == Object.class) return;

        Field[] fields;
        try {
            fields = owner.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }

        int accepted = 0;
        for (Field field : fields) {
            if (accepted >= MAX_FIELDS_PER_TARGET) break;

            try {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;
                // transient 字段是运行期缓存(如 pendingSave), 不属于用户配置
                if (Modifier.isTransient(field.getModifiers())) continue;
                if (field.getType() != boolean.class && field.getType() != Boolean.class) continue;

                String name = field.getName();
                if (target.isExcluded(name)) continue;

                field.setAccessible(true);

                ReflectiveConfigTarget.Writer writer = target.writerFor(name);
                Method getter = findGetter(owner, field, name);
                Method setter = findSetter(owner, field, name);
                Method saver = target.saver(owner);

                // 既没有 setter 也没有自定义写入方式: 只读开关无法切换, 不进候选。
                // 例外是 record 组件(无 setter 但有自定义 writer), 由 writer 覆盖写入。
                if (setter == null && writer == null) continue;

                String label = labelKeyFor(target.modId(), name);
                ResourceLocation id = ReflectUtil.safeId(target.modId(), name);
                if (id == null) continue;

                out.add(new ReflectiveToggleSource(target, name, field, getter, setter, saver, writer, id, label));
                accepted++;
            } catch (Throwable ignored) {
                // 单个字段失败不影响其它字段
            }
        }
    }

    /**
     * 按字段名推导读取方法。
     * <p>
     * 依次尝试 {@code getX} / {@code isX} / {@code areX}, 最后回退到裸名 {@code x()}
     * —— 后者是 record 组件与部分手写配置的风格(如 {@code showBattleLogMessages()})。
     * 全部未命中时返回 null, 由调用方直接读字段。
     * <p>
     * 前缀优先于裸名: {@code getHudEnabled()} 与 {@code hudEnabled()} 并存时取前者。
     * 每个候选都要求返回 boolean, 避免误命中同名的非布尔访问器。
     */
    private static Method findGetter(Class<?> owner, Field field, String fieldName) {
        String capitalized = capitalize(fieldName);
        for (String candidateName : new String[] {
                "get" + capitalized, "is" + capitalized, "are" + capitalized, fieldName}) {
            Method candidate = ReflectUtil.findMethod(owner, candidateName, 0);
            if (candidate == null) continue;

            Class<?> returnType = candidate.getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) {
                return candidate;
            }
        }
        // 未找到约定访问器: 由调用方直接读字段
        return null;
    }

    /** 按字段名推导写入方法(仅形如 {@code setX(boolean)} 的公共约定)。 */
    private static Method findSetter(Class<?> owner, Field field, String fieldName) {
        Method candidate = ReflectUtil.findMethod(owner, "set" + capitalize(fieldName), 1);
        if (candidate == null) return null;

        Class<?>[] params = candidate.getParameterTypes();
        if (params.length != 1) return null;
        if (params[0] != boolean.class && params[0] != Boolean.class) return null;

        return candidate;
    }

    /** 该开关的可翻译名键; 找不到翻译时 {@link ReflectiveToggleSource} 会退回字段名。 */
    private static String labelKeyFor(String modId, String fieldName) {
        return "radialmenu.toggle." + modId + "." + fieldName;
    }

    private static String capitalize(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (raw.length() == 1) return raw.toUpperCase(java.util.Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
