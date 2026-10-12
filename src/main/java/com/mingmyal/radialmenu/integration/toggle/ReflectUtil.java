package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

/**
 * 可选依赖模组的反射工具集。
 * <p>
 * 对第三方配置库的访问一律走反射而非编译期引用, 原因有三:
 * <ul>
 *   <li>这些库的"枚举全部配置"能力都只存在于私有字段里, 无论如何都要反射;</li>
 *   <li>避免为可选依赖增加编译期耦合, 未安装时不会产生 NoClassDefFoundError;</li>
 *   <li>跨版本差异(如 MaLiLib 0.21.1 与 0.21.10)集中在这里降级处理。</li>
 * </ul>
 * 所有方法都不抛异常: 失败返回 null / false / 空集合, 由调用方决定降级策略。
 */
public final class ReflectUtil {

    private ReflectUtil() {}

    // --- 类加载 ---

    /** 加载类; 不存在或加载失败返回 null。 */
    public static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, ReflectUtil.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    // --- 字段 ---

    /** 查找字段(含父类); 找不到返回 null。 */
    public static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null) return null;

        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 读取字段值; 失败返回 null。 */
    public static Object getField(Field field, Object target) {
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写入字段值; 成功返回 true。 */
    public static boolean setField(Field field, Object target, Object value) {
        if (field == null) return false;
        try {
            field.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // --- 方法 ---

    /** 按名称与参数个数查找方法(含父类与接口); 找不到或不可访问时返回 null。 */
    public static Method findMethod(Class<?> owner, String name, int paramCount) {
        if (owner == null || name == null) return null;

        // getDeclaredMethods/getMethods 在类含无法解析的可选成员时会抛 NoClassDefFoundError,
        // setAccessible 也可能被模块系统拒绝; 与本类其它方法一致, 失败一律返回 null。
        try {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
            // 接口默认方法(如 IConfigBoolean#toggleBooleanValue)不在此类的 declaredMethods 中
            for (Method method : owner.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** 调用方法; 失败返回 null。 */
    public static Object invoke(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用方法并预期 boolean 结果; 失败返回 false。 */
    public static boolean invokeBoolean(Method method, Object target, Object... args) {
        Object result = invoke(method, target, args);
        return result instanceof Boolean b && b;
    }

    // --- 遍历 ---

    /**
     * 把一个字段值展开成候选对象列表: 集合/数组会被逐个展开, 其余作为单元素。
     * 用于同时支持 {@code OPTIONS} 列表与单个配置字段两种声明风格。
     */
    public static List<Object> expand(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) return out;

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element != null) out.add(element);
            }
        } else if (value instanceof Object[] array) {
            for (Object element : array) {
                if (element != null) out.add(element);
            }
        } else {
            out.add(value);
        }
        return out;
    }

    // --- 标识符 ---

    /**
     * 构造安全的 {@link ResourceLocation}: 原版要求命名空间/路径均为小写且只含有限字符,
     * 而配置项名字多为 camelCase, 因此统一小写并替换非法字符。
     */
    public static ResourceLocation safeId(String namespace, String path) {
        try {
            return ResourceLocation.fromNamespaceAndPath(sanitizeNamespace(namespace), sanitizePath(path));
        } catch (Throwable t) {
            RadialCommon.LOGGER.debug("Could not build toggle id from {}/{}", namespace, path, t);
            return null;
        }
    }

    public static String sanitizeNamespace(String raw) {
        String cleaned = sanitize(raw, false);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    public static String sanitizePath(String raw) {
        String cleaned = sanitize(raw, true);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static String sanitize(String raw, boolean allowSlash) {
        if (raw == null) return "";

        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok && allowSlash && c == '/') ok = true;
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** 取模组展示名; 拿不到时退回模组 ID。 */
    public static String modDisplayName(String modId) {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(modId)
                    .map(container -> container.getMetadata().getName())
                    .orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }
}
