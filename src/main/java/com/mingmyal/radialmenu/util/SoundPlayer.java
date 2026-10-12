package com.mingmyal.radialmenu.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 槽位点击音效播放。
 * 支持原版音效 (如 minecraft:block.note_block.pling) 与导入音频 (radialmenu:custom.*)。
 * 导入音频通过 SoundManagerMixin + SoundBufferLibraryMixin 从 config 目录加载。
 */
public final class SoundPlayer {

    private SoundPlayer() {}

    public static void play(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        // 导入音频: 直接用 ResourceLocation 构造 SoundInstance (SoundManagerMixin 解析)
        if (ImportedSoundStore.isImportedSound(soundId)) {
            ResourceLocation rl = ResourceLocation.tryParse(soundId);
            if (rl != null) {
                mc.getSoundManager().play(
                        new SimpleSoundInstance(
                                rl,
                                SoundSource.MASTER,
                                1.0F, 1.0F,
                                mc.player != null ? mc.player.getRandom() : RandomSource.create(),
                                false, 0,
                                SoundInstance.Attenuation.LINEAR,
                                0.0, 0.0, 0.0,
                                true));
            }
            return;
        }

        // 原版音效: 从注册表查找 SoundEvent
        ResourceLocation rl = ResourceLocation.tryParse(soundId);
        if (rl == null) {
            return;
        }

        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(rl);
        if (event != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F));
        }
    }
}
