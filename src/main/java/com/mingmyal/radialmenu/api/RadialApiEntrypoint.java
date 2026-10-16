package com.mingmyal.radialmenu.api;

/**
 * 第三方模组接入轮盘的入口点。
 * <p>
 * 在 {@code fabric.mod.json} 中声明:
 * <pre>
 * "entrypoints": { "radialmenu": ["your.mod.YourEntrypoint"] }
 * </pre>
 * 各方法均为 default, 只需覆写自己关心的方法。
 */
public interface RadialApiEntrypoint {

    /** 注册自定义槽位动作模式({@link SlotModeRegistry#register})。 */
    default void registerSlotModes() {}

    /** 注册界面快捷方式({@link ShortcutRegistry#register})。 */
    default void registerShortcuts() {}

    /**
     * 注册本模组的功能开关, 使其可以被轮盘的"开关类型"槽位绑定。
     * <p>
     * 两种用法:
     * <ul>
     *   <li>模组自己实现 {@code ToggleSourceProvider} 并调用
     *       {@code ToggleSourceRegistry.registerProvider(...)} —— 适合配置结构复杂、
     *       或开关不来自配置文件的场景(如绑定到运行时状态);</li>
     *   <li>模组配置是普通对象的布尔字段时, 直接用
     *       {@code ReflectiveConfigToggleProvider.registerTarget(...)} 声明配置根位置,
     *       由轮盘的通用反射引擎完成枚举与读写 —— 只需几行声明, 无需碰反射。</li>
     * </ul>
     * 本方法在客户端初始化阶段调用一次。
     */
    default void registerToggleSources() {}
}
