package dev.velolib.radialmenu.mixin;

import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.ui.screen.RadialScreen;
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
}
