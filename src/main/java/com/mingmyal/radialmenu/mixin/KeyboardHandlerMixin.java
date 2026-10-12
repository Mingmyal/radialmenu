package com.mingmyal.radialmenu.mixin;

import com.mingmyal.radialmenu.RadialClient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    /**
     * MC 在 keyPress 中用 InputConstants.isKeyDown 查询 GLFW 物理状态,
     * 判断 F3 等修饰键是否被按住(F3+G 区块调试、F3+F4 等)。
     * 我们直接派发 keyPress 回调时 GLFW 状态不会变化, 因此对处于
     * "模拟按住"状态的键返回 true, 组合键即可像真实按键一样触发。
     */
    @Redirect(
            method = "keyPress",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/InputConstants;isKeyDown(JI)Z"))
    private boolean radialmenu$virtualHeldKeys(long window, int key) {
        if (RadialClient.isVirtualHeld(key)) {
            return true;
        }
        return InputConstants.isKeyDown(window, key);
    }
}
