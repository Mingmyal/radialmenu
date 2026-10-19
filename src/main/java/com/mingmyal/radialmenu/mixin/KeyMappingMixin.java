package com.mingmyal.radialmenu.mixin;

import com.mingmyal.radialmenu.RadialClient;
import com.mingmyal.radialmenu.ui.screen.RadialScreen;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public class KeyMappingMixin {

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void allowRadialMovement(CallbackInfoReturnable<Boolean> cir) {
        if (Minecraft.getInstance().screen instanceof RadialScreen) {
            KeyMapping self = (KeyMapping) (Object) this;

            if (self.getCategory().equals("key.categories.movement")) {
                cir.setReturnValue(RadialClient.isPhysicallyDown(KeyBindingHelper.getBoundKeyOf(self)));
            }
        }
    }

    /**
     * 让"未绑定"的快捷键也能被合成按键事件命中。
     * <p>
     * 原版 {@code matches(keyCode, scanCode)} 只认两种绑定: 键码等于自身绑定的 KEYSYM,
     * 或(键码为 {@code InputConstants.UNKNOWN} 时)扫描码等于自身绑定的 SCANCODE。
     * 未绑定的 KeyMapping 绑定值是 {@code UNKNOWN}(键码 -1), 两条分支都对不上,
     * 于是永远返回 false —— 事件驱动的模组(如 FTB Chunks 的区块声明地图)收不到按键,
     * 表现就是"槽位点了没反应, 随便绑一个键就好了"。
     * <p>
     * 这里只在 {@link RadialClient#isVirtualPress} 明确认定"这次合成事件是给这个按键的"
     * 时才返回 true, 对真实按键与未涉及合成事件的调用完全透明。
     */
    @Inject(method = "matches", at = @At("HEAD"), cancellable = true)
    private void radialmenu$matchVirtualPress(
            int keyCode, int scanCode, CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (RadialClient.isVirtualPress(self, keyCode)) {
            cir.setReturnValue(true);
        }
    }
}
