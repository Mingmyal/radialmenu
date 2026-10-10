package dev.velolib.radialmenu.mode;

import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.integration.MalilibIntegration;
import dev.velolib.radialmenu.mode.base.IconEnabledSlotMode;
import dev.velolib.radialmenu.ui.screen.MalilibSelectionScreen;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.layouts.LinearLayout;
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

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildValueRow(screen, slot, width, container);
        buildIconRow(screen, slot, width, container);
    }
}
