package com.mingmyal.radialmenu.api.toggle;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个外部模组功能开关的只读/可写视图。
 * <p>
 * 开关类型槽位可以绑定到一个 {@code ToggleSource}, 此时轮盘不再维护自己的
 * {@code toggleState}, 而是直接从目标模组读取真实状态、并在激活时写回目标模组。
 * 未绑定任何来源时退回原有的本地手动开关行为。
 * <p>
 * 实现必须是"无副作用读取"的: {@link #getState()} 会被轮盘的渲染循环每帧调用,
 * 因此不能触发磁盘 IO、界面创建或网络包。
 */
public interface ToggleSource {

    /** 全局唯一标识, 形如 {@code clothconfig:modid.fieldName}。 */
    ResourceLocation id();

    /** 展示名(通常是开关在目标模组界面里的名字)。 */
    Component displayName();

    /**
     * 所属模组的 ID(如 {@code litematica})。
     * 用于按模组分组浏览, 以及定位该模组的配置界面。
     */
    String modId();

    /** 所属模组的展示名, 用于选择界面分组; 可为空。 */
    default Component modName() {
        return Component.empty();
    }

    /** 附加说明(可选), 显示在悬停提示中。 */
    default Component description() {
        return null;
    }

    /** 提供者标识, 用于选择界面分组与排错。 */
    String providerId();

    /** 来源当前是否可用(模组已加载且配置已就绪)。不可用时轮盘退回本地手动状态。 */
    boolean isAvailable();

    /** 读取目标模组的当前开关状态。不得有副作用。 */
    boolean getState();

    /**
     * 该来源是否能读到真实状态。
     * <p>
     * 返回 {@code false} 表示这是"只能触发、状态未知"的来源: 例如跟随开关的状态保存在
     * 服务端集合里, 且模组没有定义任何 S2C 包, 客户端无从得知当前是开还是关。
     * <p>
     * 这类来源<b>不会</b>出现在"开关类型"的来源选择列表里 —— 开关类型需要读状态才能
     * 显示开/关并写入明确的目标值, 而这里既显示不了也写不准, 只会给出一个恒定的假值。
     * 它应当通过"外部模组开关"动作模式使用, 该模式下点击即触发切换, 不显示任何状态。
     * <p>
     * 默认 {@code true}, 保持既有实现的语义不变。
     */
    default boolean hasReadableState() {
        return true;
    }

    /**
     * 写入目标模组并持久化。
     * 实现应尽量让 {@link #getState()} 在调用后立刻反映新值。
     */
    void setState(boolean value);

    /** 翻转状态; 默认实现为读后取反写回。 */
    default void toggle() {
        setState(!getState());
    }
}
