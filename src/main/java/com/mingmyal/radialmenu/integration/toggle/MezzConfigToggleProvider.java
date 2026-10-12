package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 从 mezz_config(MezzConfig) 读取功能开关。
 * <p>
 * 这个库与其它三个不同: 它提供了<b>公开的枚举 API</b>, 不需要反射私有字段:
 * <pre>
 *   Configs.getSchemas()            -> Collection&lt;IConfigSchema&gt;
 *   schema.getModId() / getCategories()
 *   category.getConfigValues()      -> List&lt;IConfigValue&lt;?&gt;&gt;
 *   value.get() / value.set(v)      / value.getEditorInfo()
 * </pre>
 * 使用它的代表模组是 JEI(声明 {@code mezz_config} 为硬依赖)。
 * <p>
 * 布尔项的识别: 该库没有"布尔类型"枚举, 因此按取值类型({@code Boolean})
 * 结合序列化器类型名判断, 两者任一命中即可。
 */
public class MezzConfigToggleProvider implements ToggleSourceProvider {

    public static final String ID = "mezz_config";

    private static final String CONFIGS = "net.mezzdev.config.api.Configs";
    private static final String SCHEMA = "net.mezzdev.config.api.schema.IConfigSchema";

    /** 单个 schema 最多收集的开关数, 防御异常配置结构。 */
    private static final int MAX_VALUES_PER_SCHEMA = 2000;

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.mezz_config");
    }

    @Override
    public boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("mezz_config")
                && ReflectUtil.loadClass(CONFIGS) != null
                && ReflectUtil.loadClass(SCHEMA) != null;
    }

    @Override
    public void discover(Consumer<ToggleSource> sink) {
        for (Object schema : collectSchemas()) {
            try {
                discoverInSchema(schema, sink);
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to scan mezz_config schema", t);
            }
        }
    }

    /**
     * 取全部 schema。
     * <p>
     * {@code Configs.getSchemas()} 内部走 ServiceLoader, 只有在 Fabric 环境完全就绪后
     * 才能取到内容; 早期调用(或非客户端线程)可能抛错, 此时返回空集合,
     * 由注册表在后续刷新(如手动"重新扫描")时重试。
     */
    private Collection<?> collectSchemas() {
        try {
            Class<?> configsClass = ReflectUtil.loadClass(CONFIGS);
            if (configsClass == null) return List.of();

            Method getSchemas = null;
            for (Method m : configsClass.getMethods()) {
                if (m.getName().equals("getSchemas") && m.getParameterCount() == 0) {
                    getSchemas = m;
                    break;
                }
            }
            if (getSchemas == null) return List.of();

            Object result = getSchemas.invoke(null);
            if (result instanceof Collection<?> collection) {
                List<Object> out = new ArrayList<>();
                for (Object element : collection) {
                    if (element != null) out.add(element);
                }
                return out;
            }
        } catch (Throwable t) {
            // ServiceLoader 未就绪属于正常情况, 降级为"暂无"
            RadialCommon.LOGGER.debug("mezz_config schemas not available yet", t);
        }
        return List.of();
    }

    /** 遍历一个 schema 的全部分类与配置项。 */
    private void discoverInSchema(Object schema, Consumer<ToggleSource> sink) {
        String modId = stringOf(schema, "getModId");
        String schemaId = stringOf(schema, "getId");
        if (modId == null || modId.isBlank()) return;

        // 未激活的 schema(如仅服务端)不作为可绑定来源
        Object active = ReflectUtil.invoke(ReflectUtil.findMethod(schema.getClass(), "isActive", 0), schema);
        if (active instanceof Boolean b && !b) return;

        Object categoriesObj = ReflectUtil.invoke(
                ReflectUtil.findMethod(schema.getClass(), "getCategories", 0), schema);
        if (!(categoriesObj instanceof Iterable<?> categories)) return;

        int collected = 0;
        for (Object category : categories) {
            if (category == null || collected > MAX_VALUES_PER_SCHEMA) continue;

            String categoryName = stringOf(category, "getName");
            Object valuesObj = ReflectUtil.invoke(
                    ReflectUtil.findMethod(category.getClass(), "getConfigValues", 0), category);
            if (!(valuesObj instanceof Iterable<?> values)) continue;

            for (Object value : values) {
                if (value == null || collected > MAX_VALUES_PER_SCHEMA) continue;

                ToggleSource source = adapt(modId, schemaId, categoryName, value);
                if (source != null) {
                    sink.accept(source);
                    collected++;
                }
            }
        }
    }

    /** 把单个配置项适配成开关; 非布尔返回 null。 */
    private ToggleSource adapt(String modId, String schemaId, String categoryName, Object value) {
        Method getter = ReflectUtil.findMethod(value.getClass(), "get", 0);
        if (getter == null) return null;

        Object current = ReflectUtil.invoke(getter, value);
        if (!(current instanceof Boolean)) {
            // 取值不是布尔时, 再看序列化器类型(应对尚未初始化为 Boolean 的情况)
            if (!isBooleanSerializer(value)) return null;
        }

        Method setter = ReflectUtil.findMethod(value.getClass(), "set", 1);
        if (setter == null) return null;

        // 展示名与本地化键来自 editor info
        Object editorInfo = ReflectUtil.invoke(
                ReflectUtil.findMethod(value.getClass(), "getEditorInfo", 0), value);
        String name = editorInfo != null ? stringOf(editorInfo, "getName") : null;
        String localizationKey = editorInfo != null
                ? stringOf(editorInfo, "getLocalizationKey")
                : null;
        if (name == null || name.isBlank()) name = localizationKey;
        if (name == null || name.isBlank()) return null;

        ResourceLocation id = ReflectUtil.safeId("mezz_" + modId, schemaId + "_" + name);
        if (id == null) return null;

        return new MezzToggle(modId, name, localizationKey, categoryName, value, getter, setter, id);
    }

    /** 通过 editor info 的序列化器类型名判断是否为布尔项。 */
    private boolean isBooleanSerializer(Object value) {
        try {
            Object editorInfo = ReflectUtil.invoke(
                    ReflectUtil.findMethod(value.getClass(), "getEditorInfo", 0), value);
            if (editorInfo == null) return false;

            Object serializer = ReflectUtil.invoke(
                    ReflectUtil.findMethod(editorInfo.getClass(), "getSerializer", 0), editorInfo);
            if (serializer == null) return false;

            String typeName = serializer.getClass().getName();
            return typeName.contains("Boolean");
        } catch (Throwable t) {
            return false;
        }
    }

    private String stringOf(Object target, String method) {
        Object result = ReflectUtil.invoke(ReflectUtil.findMethod(target.getClass(), method, 0), target);
        return result instanceof String s ? s : null;
    }

    /**
     * mezz_config 的布尔开关。
     * <p>
     * {@code set()} 负责写入并持久化(返回是否成功), 因此不需要额外调用 save。
     */
    private static final class MezzToggle implements ToggleSource {

        private final String modId;
        private final String name;
        private final String localizationKey;
        private final String categoryName;
        private final Object value;
        private final Method getter;
        private final Method setter;
        private final ResourceLocation id;

        private MezzToggle(
                String modId, String name, String localizationKey, String categoryName,
                Object value, Method getter, Method setter, ResourceLocation id) {
            this.modId = modId;
            this.name = name;
            this.localizationKey = localizationKey;
            this.categoryName = categoryName;
            this.value = value;
            this.getter = getter;
            this.setter = setter;
            this.id = id;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public Component displayName() {
            // 优先用本地化键取翻译; 没有翻译时回退到内部名
            if (localizationKey != null && !localizationKey.isBlank()) {
                try {
                    Component translated = Component.translatable(localizationKey);
                    String text = translated.getString();
                    if (text != null && !text.isBlank() && !text.equals(localizationKey)) {
                        return translated;
                    }
                } catch (Throwable ignored) {
                    // 语言未就绪
                }
            }
            return Component.literal(name);
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
            return Component.literal(categoryName != null ? categoryName + "." + name : name);
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
            Object current = ReflectUtil.invoke(getter, value);
            return current instanceof Boolean b && b;
        }

        @Override
        public void setState(boolean newValue) {
            // set(T) 会写回并持久化; 返回 false 表示被拒绝
            Object ok = ReflectUtil.invoke(setter, value, newValue);
            if (ok instanceof Boolean b && !b) {
                RadialCommon.LOGGER.warn("mezz_config toggle '{}' rejected the new value", id);
            }
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }
}
