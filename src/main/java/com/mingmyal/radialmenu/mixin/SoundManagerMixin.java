package com.mingmyal.radialmenu.mixin;

import com.mingmyal.radialmenu.util.ImportedSoundStore;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 为导入的自定义音效提供 WeighedSoundEvents, 使 SoundManager 能解析
 * radialmenu:custom.* 这类未在原版 sounds.json 中注册的音效。
 */
@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @Inject(
            method = "getSoundEvent",
            at = @At("HEAD"),
            cancellable = true)
    private void radialmenu$getCustomSoundEvent(
            ResourceLocation location, CallbackInfoReturnable<WeighedSoundEvents> cir) {
        if (location == null) return;

        String id = location.toString();
        if (!ImportedSoundStore.isImportedSound(id)) {
            return;
        }

        // 构造一个 WeighedSoundEvents, 内含一个指向同 id 的 Sound
        WeighedSoundEvents event = new WeighedSoundEvents(location, null);
        Sound sound = new Sound(
                location,
                ConstantFloat.of(1.0F),  // volume
                ConstantFloat.of(1.0F),  // pitch
                1,                        // weight
                Sound.Type.FILE,
                false,                    // stream
                false,                    // preload
                16);                      // attenuation distance
        event.addSound(sound);
        cir.setReturnValue(event);
    }
}
