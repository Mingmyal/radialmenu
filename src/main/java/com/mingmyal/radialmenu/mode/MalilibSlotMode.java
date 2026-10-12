package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.integration.MalilibIntegration;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.ui.screen.MalilibSelectionScreen;

import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MalilibSlotMode extends IconEnabledSlotMode {
    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.malilib");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.malilib");
    }

    @Override
    public boolean hasValuePicker() {
        return true;
    }

    @Override
    public void openValuePicker(Screen parent, Consumer<String> onSelect) {
        Minecraft.getInstance()
                .setScreen(new MalilibSelectionScreen(parent, action -> onSelect.accept(action.id())));
    }

    @Override
    public boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("malilib");
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen();

        if (FabricLoader.getInstance().isModLoaded("malilib")) {
            MalilibIntegration.executeHotkey(slot.value);
        }
    }

}
