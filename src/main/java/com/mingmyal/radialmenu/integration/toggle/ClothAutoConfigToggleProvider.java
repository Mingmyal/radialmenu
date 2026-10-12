package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 从 Cloth Config 的 AutoConfig 读取功能开关。
 * <p>
 * AutoConfig 把注册表放在私有静态字段
 * {@code AutoConfig.holders: Map<Class<? extends ConfigData>, ConfigHolder<?>>}, 没有公开的
 * 遍历入口, 因此只能反射读取该字段。
 * <p>
 * 配置类本身是普通 POJO: 布尔选项就是 {@code public boolean foo} 这样的字段。
 * {@code @ConfigEntry.Gui.CollapsibleObject} / {@code TransitiveObject} 标注的嵌套对象
 * 需要递归进去才能拿到里面的开关, 这里按注解名(字符串)判断, 不引入编译期依赖。
 * 持久化调用 {@code ConfigHolder#save()}: 直接改字段不会自动落盘。
 */
public class ClothAutoConfigToggleProvider implements ToggleSourceProvider {

    public static final String ID = "clothconfig";

    private static final String AUTO_CONFIG = "me.shedaniel.autoconfig.AutoConfig";

    /** 嵌套配置对象的最大递归深度, 防御异常结构。 */
    private static final int MAX_DEPTH = 3;
    private static final int MAX_OPTIONS_PER_CONFIG = 512;

    private static final Set<String> PRIMITIVE_SKIP = Set.of(
            "java.lang.String", "java.lang.Character", "java.lang.Integer", "java.lang.Long",
            "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte",
            "java.lang.Boolean");

    /**
     * configClass -> 所属模组 ID 的缓存(解析较贵, 只做一次)。
     * <p>
     * 用 {@link ClassValue} 而不是普通 Map: 发现流程会同时从后台预热线程与渲染线程
     * (选择界面/能力探测)进入, ClassValue 自带按类的线程安全缓存, 无需额外加锁。
     */
    private static final ClassValue<String> MOD_ID_CACHE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            String resolved = lookupModId(type);
            return (resolved == null || resolved.isBlank()) ? sanitizeModId(type) : resolved;
        }
    };

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.clothconfig");
    }

    @Override
    public boolean isAvailable() {
        // AutoConfig 随 cloth-config 分发, 但并非所有 cloth-config 使用方都用 AutoConfig
        return FabricLoader.getInstance().isModLoaded("cloth-config") && ReflectUtil.loadClass(AUTO_CONFIG) != null;
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        for (Map.Entry<Class<?>, Object> entry : collectHolders().entrySet()) {
            Class<?> configClass = entry.getKey();
            Object holder = entry.getValue();

            try {
                Object config = ReflectUtil.invoke(ReflectUtil.findMethod(holder.getClass(), "getConfig", 0), holder);
                if (config == null) continue;

                String modId = resolveModId(configClass);
                String configName = configName(configClass);
                List<Field> booleans = new ArrayList<>();

                collectBooleanFields(config.getClass(), config, booleans, new IdentityHashMap<>(), 0);

                for (Field field : booleans) {
                    ToggleSource source = adapt(modId, configName, config, holder, field);
                    if (source != null) sink.accept(source);
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to scan Cloth AutoConfig '{}'", configClass.getName(), t);
            }
        }
    }

    /** 反射读取 {@code AutoConfig.holders}。 */
    private Map<Class<?>, Object> collectHolders() {
        Map<Class<?>, Object> result = new LinkedHashMap<>();

        Class<?> autoConfig = ReflectUtil.loadClass(AUTO_CONFIG);
        if (autoConfig == null) return result;

        // 静态初始化会建立空表; 各模组在自己的初始化阶段 fill 它
        try {
            Class.forName(AUTO_CONFIG, true, ClothAutoConfigToggleProvider.class.getClassLoader());
        } catch (Throwable ignored) {
            // 初始化失败时下面的字段读取会返回 null, 走空结果
        }

        Field field = ReflectUtil.findField(autoConfig, "holders");
        Object value = ReflectUtil.getField(field, null);

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof Class<?> cls && e.getValue() != null) {
                    result.put(cls, e.getValue());
                }
            }
        }
        return result;
    }

    /**
     * 递归收集布尔字段。会被要求递归的对象仅限: 标注了
     * {@code CollapsibleObject}/{@code TransitiveObject} 的字段, 以及普通自定义类型字段。
     * 这里选择"只跳过 JDK 类型"的策略, 以兼容未加注解的嵌套配置。
     */
    private void collectBooleanFields(
            Class<?> owner, Object instance, List<Field> out, Map<Object, Boolean> visited, int depth) {
        if (owner == null || owner == Object.class || depth > MAX_DEPTH) return;
        if (out.size() > MAX_OPTIONS_PER_CONFIG) return;
        if (visited.put(instance, Boolean.TRUE) != null) return;

        Field[] fields;
        try {
            fields = owner.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }

        for (Field field : fields) {
            try {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (isExcluded(field)) continue;

                Class<?> type = field.getType();
                if (type == boolean.class || type == Boolean.class) {
                    field.setAccessible(true);
                    out.add(field);
                    continue;
                }

                // 递归嵌套配置对象
                if (type.isPrimitive() || type.isEnum() || type.isArray() || PRIMITIVE_SKIP.contains(type.getName())) {
                    continue;
                }
                if (type.getName().startsWith("java.") || type.getName().startsWith("javax.")) {
                    continue;
                }
                if (!isNestedConfigCandidate(field)) {
                    continue;
                }

                field.setAccessible(true);
                Object nested = field.get(instance);
                if (nested != null) {
                    collectBooleanFields(nested.getClass(), nested, out, visited, depth + 1);
                }
            } catch (Throwable ignored) {
                // 单个字段不可读时跳过
            }
        }
    }

    /** 只递归被注解标记为嵌套配置的字段, 避免误入无关的对象图。 */
    private boolean isNestedConfigCandidate(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.endsWith("CollapsibleObject") || name.endsWith("TransitiveObject")) {
                return true;
            }
        }
        return false;
    }

    /** {@code @ConfigEntry.Gui.Excluded} 标记的字段不出现在界面里, 也不应作为开关。 */
    private boolean isExcluded(Field field) {
        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.endsWith("ConfigEntry$Gui$Excluded") || name.endsWith("Gui$Excluded")) {
                return true;
            }
        }
        return false;
    }

    private ToggleSource adapt(String modId, String configName, Object config, Object holder, Field field) {
        String fieldName = field.getName();

        ResourceLocation id = ReflectUtil.safeId("cloth_" + modId, fieldName);
        if (id == null) return null;

        return new ClothToggle(modId, configName, fieldName, field, config, holder, id);
    }

    /** 读取配置类上的 {@code @Config(name = "...")}。 */
    private String configName(Class<?> configClass) {
        try {
            for (Annotation annotation : configClass.getAnnotations()) {
                if (!annotation.annotationType().getName().equals("me.shedaniel.autoconfig.annotation.Config")) {
                    continue;
                }
                Object name = ReflectUtil.invoke(annotation.annotationType().getMethod("name"), annotation);
                if (name instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) {
            // 无注解或用默认名
        }
        return configClass.getSimpleName();
    }

    /** 通过类的加载来源定位所属模组 ID; 失败时退回配置名。 */
    static String resolveModId(Class<?> clazz) {
        return MOD_ID_CACHE.get(clazz);
    }

    private static String lookupModId(Class<?> clazz) {
        try {
            var source = clazz.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;

            Path classPath = Paths.get(source.getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();

            for (var mod : FabricLoader.getInstance().getAllMods()) {
                for (Path root : mod.getRootPaths()) {
                    Path normalized = root.toAbsolutePath().normalize();
                    // 生产环境: 类在模组 jar/目录内; 开发环境: 两者可能互为前缀
                    if (classPath.equals(normalized) || classPath.startsWith(normalized)) {
                        return mod.getMetadata().getId();
                    }
                    if (normalized.startsWith(classPath)) {
                        return mod.getMetadata().getId();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无法定位时退回包名推断
        }
        return null;
    }

    /** 从包名推断模组 ID, 作为无法定位加载来源时的兜底。 */
    private static String sanitizeModId(Class<?> clazz) {
        String pkg = clazz.getPackageName();
        if (pkg == null || pkg.isBlank()) return "unknown";

        String[] parts = pkg.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "_" + parts[1];
        }
        return parts[0];
    }

    /** Cloth AutoConfig 布尔开关。 */
    private static final class ClothToggle implements ToggleSource {

        private final String modId;
        private final String configName;
        private final String fieldName;
        private final Field field;
        private final Object config;
        private final Object holder;
        private final ResourceLocation id;

        /** ConfigHolder#save(): 解析一次即可; 为 null 表示该配置不支持手动落盘。 */
        private final Method saveMethod;

        private ClothToggle(
                String modId, String configName, String fieldName, Field field, Object config, Object holder,
                ResourceLocation id) {
            this.modId = modId;
            this.configName = configName;
            this.fieldName = fieldName;
            this.field = field;
            this.config = config;
            this.holder = holder;
            this.id = id;
            this.saveMethod = ReflectUtil.findMethod(holder.getClass(), "save", 0);
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component displayName() {
            // Cloth 的界面名翻译键约定: text.autoconfig.<name>.option.<field>
            String key = "text.autoconfig." + configName + ".option." + fieldName;
            try {
                Component translated = Component.translatable(key);
                String text = translated.getString();
                if (text != null && !text.isBlank() && !text.equals(key)) {
                    return translated;
                }
            } catch (Throwable ignored) {
                // 语言未就绪时退回字段名
            }
            return Component.literal(fieldName);
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
        public Component description() {
            return Component.literal(configName + "." + fieldName);
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
            Object value = ReflectUtil.getField(field, config);
            return value instanceof Boolean b && b;
        }

        @Override
        public void setState(boolean value) {
            if (!ReflectUtil.setField(field, config, value)) return;

            // 改内存不会自动落盘。save() 返回 void, 所以只能用"方法是否存在"判断能否保存,
            // 不能拿 invoke 的返回值判断成败(void 方法恒返回 null)。
            if (saveMethod == null) {
                RadialCommon.LOGGER.warn("Cloth toggle '{}' changed for this session but may not have been saved", id);
                return;
            }
            ReflectUtil.invoke(saveMethod, holder);
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }

    // --- 配置界面 ---

    /**
     * 已知可打开配置界面的模组: 从 AutoConfig 注册表推断,
     * 同时提供用 {@code AutoConfig.getConfigScreen(Class, Screen)} 直接建界面的能力。
     */
    @Override
    public Collection<String> knownConfigScreenModIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Class<?> configClass : collectHolders().keySet()) {
            ids.add(resolveModId(configClass));
        }
        return ids;
    }

    @Override
    public boolean canOpenConfigScreens() {
        return !collectHolders().isEmpty();
    }

    @Override
    public Screen createConfigScreen(String modId, Screen parent) {
        for (Class<?> configClass : collectHolders().keySet()) {
            if (!resolveModId(configClass).equals(modId)) continue;

            try {
                Class<?> autoConfig = ReflectUtil.loadClass(AUTO_CONFIG);
                if (autoConfig == null) return null;

                // public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T>, Screen)
                java.lang.reflect.Method method = null;
                for (java.lang.reflect.Method candidate : autoConfig.getMethods()) {
                    if (candidate.getName().equals("getConfigScreen") && candidate.getParameterCount() == 2) {
                        method = candidate;
                        break;
                    }
                }
                if (method == null) return null;

                Object supplier = ReflectUtil.invoke(method, null, configClass, parent);
                if (supplier instanceof Supplier<?> s) {
                    Object screen = s.get();
                    return screen instanceof Screen sc ? sc : null;
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to build Cloth config screen for '{}'", modId, t);
            }
            return null;
        }
        return null;
    }
}
