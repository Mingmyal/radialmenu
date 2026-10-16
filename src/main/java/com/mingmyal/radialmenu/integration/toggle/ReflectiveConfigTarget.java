package com.mingmyal.radialmenu.integration.toggle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一个"模组自建配置"的通用读写目标。
 * <p>
 * 与 {@link MaLiLibToggleProvider}、{@link ClothAutoConfigToggleProvider} 这类针对
 * 某个配置库的实现不同, 这里处理的是<b>不使用任何配置库</b>的模组: 它们各自手写配置
 * 文件(Gson / properties / record + 原子写), 因而没有统一的"配置注册表"可供枚举,
 * 只能按模组逐个声明<b>从哪里取得配置根对象</b>。
 * <p>
 * 本类只负责"取根对象"与"写回后如何落盘"这两件无法约定的事; 布尔开关的枚举、
 * 读写与命名约定由 {@link ReflectiveConfigToggleProvider} 统一完成。
 * <p>
 * 根对象在每次读写时都会重新解析: 代价是几次静态字段读取与方法调用, 换来的是对
 * "根对象被整体替换"的免疫 —— 例如 jo_trainer_intro 的配置是不可变 record, 保存后
 * {@code joConfig()} 返回的是<b>新实例</b>, 缓存旧引用会让界面永远停在旧值。
 */
public final class ReflectiveConfigTarget {

    /**
     * 写入一个布尔开关并负责落盘。
     * <p>
     * 只在目标模组的写入方式不符合 {@code setX(boolean)} + {@code save()} 约定时才需要:
     * 例如写入方法是另一个类上的静态方法, 或需要"先落盘、成功后再应用"的两步流程。
     */
    @FunctionalInterface
    public interface Writer {
        void write(Object root, boolean value) throws Throwable;
    }

    private final String modId;
    private final String rootClassName;
    private final String rootStaticFieldName;
    private final String rootStaticMethodName;
    private final String chainMethodName;
    private final String defaultSaverName;
    private final Map<String, Writer> writers;
    private final Set<String> excluded;

    // 解析缓存。这些字段会同时被后台预热线程(发现阶段)与渲染线程(读取状态)访问,
    // 因此声明为 volatile: 写入按 "先填内容, 后置标记" 的顺序发布, 读取方据此获得
    // happens-before, 不会看到 "标记已置位但内容仍为 null" 的撕裂状态。
    private volatile boolean membersResolved;
    private volatile Class<?> rootClass;
    private volatile Field rootStaticField;
    private volatile Method rootStaticMethod;

    private volatile Method chainMethod;
    /**
     * 链式方法已按哪个运行时类型解析过。
     * <p>
     * 与 {@link #chainMethod} 分开记录的必要性: 方法不存在时 {@code chainMethod} 为 null,
     * 若只靠它判断就会在每帧读取时反复做一次注定失败的查找。记录"已解析过的类型"
     * 才能把否定结果也缓存下来。
     */
    private volatile Class<?> chainResolvedFor;

    private volatile Method saver;
    /** 落盘方法已按哪个运行时类型解析过; 记录类型以便把"确实不存在"也缓存下来。 */
    private volatile Class<?> saverResolvedFor;

    private ReflectiveConfigTarget(Builder builder) {
        this.modId = builder.modId;
        this.rootClassName = builder.rootClassName;
        this.rootStaticFieldName = builder.rootStaticFieldName;
        this.rootStaticMethodName = builder.rootStaticMethodName;
        this.chainMethodName = builder.chainMethodName;
        this.defaultSaverName = builder.defaultSaverName;
        this.writers = Map.copyOf(builder.writers);
        this.excluded = Set.copyOf(builder.excluded);
    }

    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    public String modId() {
        return modId;
    }

    /** 该字段是否被显式排除(内部状态字段, 不该暴露成开关)。 */
    public boolean isExcluded(String fieldName) {
        return excluded.contains(fieldName);
    }

    /** 该字段的自定义写入方式; 未提供时返回 null, 由调用方走命名约定。 */
    public Writer writerFor(String fieldName) {
        return writers.get(fieldName);
    }

    /** 目标类是否存在(不代表配置已就绪)。 */
    public boolean isClassPresent() {
        resolveMembers();
        return rootClass != null;
    }

    /**
     * 解析当前的配置根对象; 任何一步失败都返回 null。
     * <p>
     * 本方法必须在渲染热路径上足够便宜: 命中缓存后只剩静态字段读取(以及可选的
     * 一次链式方法调用), 不涉及磁盘 IO —— 各模组自己的单例已把 IO 挡在首次访问之后。
     */
    public Object resolveRoot() {
        if (!resolveMembers()) return null;

        Object current;
        try {
            if (rootStaticField != null) {
                current = rootStaticField.get(null);
            } else if (rootStaticMethod != null) {
                current = rootStaticMethod.invoke(null);
            } else {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }

        if (current == null) return null;
        if (chainMethodName == null) return current;

        // 链式方法按运行时类型解析: 静态字段的声明类型可能是接口或父类
        Class<?> currentType = current.getClass();
        if (chainResolvedFor != currentType) {
            chainMethod = ReflectUtil.findMethod(currentType, chainMethodName, 0);
            chainResolvedFor = currentType;
        }
        // 解析过后仍为 null 表示该方法确实不存在: 结果已按类型缓存, 不再重复查找
        if (chainMethod == null) return null;

        try {
            return chainMethod.invoke(current);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 取根对象自身的落盘方法(如 {@code save()}); 未声明或不存在时返回 null。
     * <p>
     * 按运行时类型解析并缓存: 配置实例的类往往与静态字段所在类不是同一个。
     */
    public Method saver(Class<?> rootType) {
        if (defaultSaverName == null || rootType == null) return null;
        if (saverResolvedFor == rootType) return saver;

        Method found = ReflectUtil.findMethod(rootType, defaultSaverName, 0);
        saver = found;
        saverResolvedFor = rootType;
        return found;
    }

    /** 解析静态根字段/方法并缓存; 类不存在时返回 false。 */
    private boolean resolveMembers() {
        if (membersResolved) return rootClass != null;
        membersResolved = true;

        try {
            rootClass = Class.forName(rootClassName, false, ReflectiveConfigTarget.class.getClassLoader());
        } catch (Throwable t) {
            rootClass = null;
            return false;
        }

        if (rootStaticFieldName != null) {
            rootStaticField = ReflectUtil.findField(rootClass, rootStaticFieldName);
        }
        if (rootStaticMethodName != null) {
            rootStaticMethod = ReflectUtil.findMethod(rootClass, rootStaticMethodName, 0);
        }
        if (rootStaticField == null && rootStaticMethod == null) {
            rootClass = null;
            return false;
        }
        return true;
    }

    /** 声明式构造器。 */
    public static final class Builder {

        private final String modId;
        private String rootClassName;
        private String rootStaticFieldName;
        private String rootStaticMethodName;
        private String chainMethodName;
        private String defaultSaverName;
        private final Map<String, Writer> writers = new LinkedHashMap<>();
        private final Set<String> excluded = new LinkedHashSet<>();

        private Builder(String modId) {
            this.modId = modId;
        }

        /** 根对象取自 {@code owner} 的静态字段(如 {@code CONFIG} / {@code CONTROLLER} / {@code Companion})。 */
        public Builder rootField(String owner, String fieldName) {
            this.rootClassName = owner;
            this.rootStaticFieldName = fieldName;
            return this;
        }

        /** 根对象取自 {@code owner} 的静态无参方法(如 {@code getInstance()})。 */
        public Builder rootMethod(String owner, String methodName) {
            this.rootClassName = owner;
            this.rootStaticMethodName = methodName;
            return this;
        }

        /** 在上一步得到的对象上再调用一次无参方法取得真正的配置(如 {@code joConfig()} / {@code get()})。 */
        public Builder chain(String methodName) {
            this.chainMethodName = methodName;
            return this;
        }

        /** 写入后调用的落盘方法名(如 {@code save}); 不声明时不做自动落盘。 */
        public Builder saver(String methodName) {
            this.defaultSaverName = methodName;
            return this;
        }

        /** 为指定字段提供自定义写入流程(绕过 setX + save 约定)。 */
        public Builder writer(String fieldName, Writer writer) {
            this.writers.put(fieldName, writer);
            return this;
        }

        /** 排除不该暴露成开关的内部布尔字段(如加载标记、写入标记)。 */
        public Builder exclude(String... fieldNames) {
            for (String name : fieldNames) {
                this.excluded.add(name);
            }
            return this;
        }

        public ReflectiveConfigTarget build() {
            return new ReflectiveConfigTarget(this);
        }
    }
}
