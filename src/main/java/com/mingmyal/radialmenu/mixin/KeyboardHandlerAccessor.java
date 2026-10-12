package com.mingmyal.radialmenu.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 KeyboardHandler 私有的 F3 调试组合键处理方法。
 * 1.21.1 中区块边界(F3+G)、高级提示(F3+H)等调试快捷键不是可绑定的 KeyMapping,
 * 而是硬编码在 handleDebugKeys(int) 的 switch 中, 只能通过此访问器直接调用,
 * 行为与真实按下 F3 组合键完全一致(含聊天栏反馈、权限判断)。
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {

    @Invoker("handleDebugKeys")
    boolean radialmenu$handleDebugKeys(int key);
}
