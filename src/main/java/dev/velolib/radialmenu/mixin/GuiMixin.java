package dev.velolib.radialmenu.mixin;

import dev.velolib.radialmenu.ui.screen.RadialScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla crosshair while the radial menu screen is open, replacing the
 * 26.3 HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, ...) approach
 * which does not exist for 1.21.1.
 *
 * NOTE: the target method name "renderCrosshair" must match the 1.21.1 Gui method;
 * it is verified against loom's decompiled 1.21.1 sources after the first build setup.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void radialmenu$hideCrosshairDuringRadial(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof RadialScreen) {
            ci.cancel();
        }
    }
}
