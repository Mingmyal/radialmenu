package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 从 YACL (Yet Another Config Lib) v3 的 config v2 API 读取功能开关。
 * <p>
 * <b>关键约束:</b> YACL 没有任何 handler 注册表
 * ({@code ConfigClassHandlerImpl} 一个静态字段都没有, {@code build()} 也不做全局登记),
 * 因此无法"向 YACL 要一张配置表"。这里的做法是:
 * <ol>
 *   <li>只处理元数据里声明依赖 {@code yacl} 的模组, 避免全量扫描;</li>
 *   <li>在这些模组的类文件名中筛选疑似配置类(config/settings 等关键词);</li>
 *   <li>以 {@code initialize=false} 方式加载, 查找类型为 {@code ConfigClassHandler}
 *       的静态字段 —— 这是各模组持有配置的既有约定;</li>
 *   <li>通过公开的 {@code fields()} / {@code access()} API 读写布尔项,
 *       再调用 {@code save()} 落盘。</li>
 * </ol>
 * 全部通过反射完成, 未安装 YACL 时不会产生类加载错误。
 */
public class YaclToggleProvider implements ToggleSourceProvider {

    public static final String ID = "yacl";

    private static final String CONFIG_HANDLER = "dev.isxander.yacl3.config.v2.api.ConfigClassHandler";

    /**
     * YACL 的 Fabric 模组 ID 是 {@code yet_another_config_lib_v3}
     * (maven 坐标写作 {@code yacl}, 但那是仓库 artifact 名而非模组 ID)。
     */
    private static final String YACL_MOD_ID = "yet_another_config_lib_v3";

    /** 疑似配置类的文件名关键词(小写匹配)。 */
    private static final List<String> CLASS_HINTS = List.of("config", "settings", "options");

    /** 单次发现中最多检查的类数量, 防止异常模组拖慢界面。 */
    private static final int MAX_CANDIDATE_CLASSES = 4000;

    /**
     * 已经扫描过静态字段的类, 避免重复加载。
     * <p>
     * 它只用于去重, <b>不</b>用作配额计数: 配额是"每次发现"的上限,
     * 若拿这个集合的累计大小当配额, 扫够一定数量后所有后续模组都会被静默跳过。
     */
    private static final Set<String> SCANNED_CLASSES = new LinkedHashSet<>();

    /** modId -> 已发现 handler 实例; 发现一次后缓存。 */
    private static final Map<String, List<Object>> HANDLERS_BY_MOD = new LinkedHashMap<>();

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.yacl");
    }

    @Override
    public boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded(YACL_MOD_ID) && ReflectUtil.loadClass(CONFIG_HANDLER) != null;
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        Class<?> handlerType = ReflectUtil.loadClass(CONFIG_HANDLER);
        if (handlerType == null) return;

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            try {
                if (!usesYacl(mod)) continue;

                List<Object> handlers = HANDLERS_BY_MOD.computeIfAbsent(modId, id -> findHandlers(mod, handlerType));

                for (Object handler : handlers) {
                    discoverInHandler(modId, handler, sink);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to scan YACL configs for mod '{}'", modId, t);
            }
        }
    }

    /** 判断模组是否声明依赖 YACL。 */
    private boolean usesYacl(ModContainer mod) {
        try {
            return mod.getMetadata().getDependencies().stream()
                    .anyMatch(dep -> YACL_MOD_ID.equals(dep.getModId()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在模组类路径中查找静态的 ConfigClassHandler 字段。 */
    private List<Object> findHandlers(ModContainer mod, Class<?> handlerType) {
        List<Object> found = new ArrayList<>();
        // 配额按"本次调用"计数, 而不是按 SCANNED_CLASSES 的累计大小
        int[] budget = {MAX_CANDIDATE_CLASSES};

        for (Path root : mod.getRootPaths()) {
            try {
                for (String className : candidateClassNames(root)) {
                    if (budget[0] <= 0) return found;
                    budget[0]--;
                    if (!SCANNED_CLASSES.add(className)) continue;

                    scanStaticFields(className, handlerType, found);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.debug("YACL scan skipped for path {}", root, t);
            }
        }
        return found;
    }

    /** 列出类路径下疑似配置类的全限定名。 */
    private List<String> candidateClassNames(Path root) {
        List<String> names = new ArrayList<>();

        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String relative = root.relativize(p).toString();
                    addIfCandidate(relative, names);
                });
            } catch (Throwable ignored) {
                // 目录不可读时返回已收集的结果
            }
            return names;
        }

        if (Files.isRegularFile(root) && root.toString().endsWith(".jar")) {
            try (ZipFile zip = new ZipFile(root.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        addIfCandidate(entry.getName(), names);
                    }
                }
            } catch (Throwable ignored) {
                // 非 zip 或损坏时忽略
            }
        }
        return names;
    }

    private void addIfCandidate(String relativePath, List<String> out) {
        String path = relativePath.replace('\\', '/');
        if (path.contains("META-INF/")) return;

        String lower = path.toLowerCase(Locale.ROOT);
        boolean looksLikeConfig = CLASS_HINTS.stream().anyMatch(lower::contains);
        if (!looksLikeConfig) return;

        String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        // 跳过内部类与包信息
        if (className.endsWith("package-info") || className.contains("$")) return;

        out.add(className);
    }

    /** 加载类(不初始化)并收集其静态 ConfigClassHandler 字段值。 */
    private void scanStaticFields(String className, Class<?> handlerType, List<Object> found) {
        try {
            Class<?> clazz = Class.forName(className, false, YaclToggleProvider.class.getClassLoader());
            if (clazz.getClassLoader() == null) return;

            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!handlerType.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) found.add(value);
            }
        } catch (Throwable ignored) {
            // 类加载失败(缺依赖、版本不符等)属于正常情况
        }
    }

    /** 遍历 handler 的字段, 把布尔项适配成开关。 */
    private void discoverInHandler(String modId, Object handler, Consumer<ToggleSource> sink) {
        Object fieldsObj = ReflectUtil.invoke(ReflectUtil.findMethod(handler.getClass(), "fields", 0), handler);
        if (!(fieldsObj instanceof Object[] fields)) return;

        ResourceLocation handlerId = null;
        Object idObj = ReflectUtil.invoke(ReflectUtil.findMethod(handler.getClass(), "id", 0), handler);
        if (idObj instanceof ResourceLocation rl) {
            handlerId = rl;
        }

        for (Object field : fields) {
            try {
                ToggleSource source = adapt(modId, handler, handlerId, field);
                if (source != null) sink.accept(source);
            } catch (Throwable t) {
                RadialCommon.LOGGER.debug("Skipped a YACL config field of '{}'", modId, t);
            }
        }
    }

    /** 把单个 YACL 配置字段适配成开关; 非布尔返回 null。 */
    private ToggleSource adapt(String modId, Object handler, ResourceLocation handlerId, Object field) {
        if (field == null) return null;

        Object access = ReflectUtil.invoke(ReflectUtil.findMethod(field.getClass(), "access", 0), field);
        if (access == null) return null;

        // 用 typeClass()/type() 判断布尔, 比读值更安全(值为 null 时也能识别)
        Object typeClass = ReflectUtil.invoke(ReflectUtil.findMethod(access.getClass(), "typeClass", 0), access);
        boolean isBoolean = (typeClass == boolean.class) || (typeClass == Boolean.class);
        if (!isBoolean) return null;

        Object nameObj = ReflectUtil.invoke(ReflectUtil.findMethod(access.getClass(), "name", 0), access);
        String fieldName = nameObj instanceof String s ? s : null;
        if (fieldName == null || fieldName.isBlank()) return null;

        java.lang.reflect.Method getter = ReflectUtil.findMethod(access.getClass(), "get", 0);
        java.lang.reflect.Method setter = ReflectUtil.findMethod(access.getClass(), "set", 1);
        if (getter == null || setter == null) return null;

        java.lang.reflect.Method save = ReflectUtil.findMethod(handler.getClass(), "save", 0);

        String namespace = handlerId != null ? handlerId.getNamespace() : modId;
        ResourceLocation id = ReflectUtil.safeId("yacl_" + namespace, fieldName);
        if (id == null) return null;

        return new YaclToggle(modId, namespace, fieldName, access, getter, setter, handler, save, id);
    }

    /** YACL 布尔开关。直接改字段不会落盘, 因此写后调用 handler.save()。 */
    private static final class YaclToggle implements ToggleSource {

        private final String modId;
        private final String namespace;
        private final String fieldName;
        private final Object access;
        private final java.lang.reflect.Method getter;
        private final java.lang.reflect.Method setter;
        private final Object handler;
        private final java.lang.reflect.Method save;
        private final ResourceLocation id;

        private YaclToggle(
                String modId, String namespace, String fieldName, Object access,
                java.lang.reflect.Method getter, java.lang.reflect.Method setter,
                Object handler, java.lang.reflect.Method save, ResourceLocation id) {
            this.modId = modId;
            this.namespace = namespace;
            this.fieldName = fieldName;
            this.access = access;
            this.getter = getter;
            this.setter = setter;
            this.handler = handler;
            this.save = save;
            this.id = id;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component displayName() {
            // YACL 自动生成的翻译键约定: yacl3.config.<namespace>.<field>
            String key = "yacl3.config." + namespace + "." + fieldName;
            try {
                Component translated = Component.translatable(key);
                String text = translated.getString();
                if (text != null && !text.isBlank() && !text.equals(key)) {
                    return translated;
                }
            } catch (Throwable ignored) {
                // 语言未就绪
            }
            return Component.literal(fieldName);
        }

        @Override
        public String modId() {
            // 用 handler 的命名空间(即配置所属模组), 而非外层容器 ID
            return namespace;
        }

        @Override
        public Component modName() {
            return Component.literal(ReflectUtil.modDisplayName(namespace));
        }

        @Override
        public Component description() {
            return Component.literal(namespace + "." + fieldName);
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
            Object value = ReflectUtil.invoke(getter, access);
            return value instanceof Boolean b && b;
        }

        @Override
        public void setState(boolean value) {
            ReflectUtil.invoke(setter, access, value);

            if (save != null) {
                ReflectUtil.invoke(save, handler);
            }
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }

    // --- 配置界面 ---

    /**
     * YACL 配置界面由 {@code handler.generateGui().generateScreen(parent)} 生成,
     * 但 {@code generateGui()} 在字段未使用 {@code @AutoGen} 时会抛异常,
     * 且需要处在客户端环境, 因此不主动提供(沿用接口默认实现),
     * 统一交给 ModMenu 提供者处理。
     */
}
