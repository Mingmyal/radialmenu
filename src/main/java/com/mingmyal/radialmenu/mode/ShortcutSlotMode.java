package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.ShortcutEntry;
import com.mingmyal.radialmenu.api.ShortcutRegistry;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.ui.screen.ShortcutSelectionScreen;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class ShortcutSlotMode extends IconEnabledSlotMode {

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.shortcut");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.shortcut");
    }

    @Override
    public boolean hasValuePicker() {
        return true;
    }

    @Override
    public void openValuePicker(Screen parent, Consumer<String> onSelect) {
        Minecraft.getInstance()
                .setScreen(new ShortcutSelectionScreen(parent, (ResourceLocation id) -> onSelect.accept(id.toString())));
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        if (slot.value == null || slot.value.isBlank()) {
            return;
        }

        ResourceLocation menuId = ResourceLocation.tryParse(slot.value);
        if (menuId == null) {
            return;
        }

        ShortcutEntry entry = ShortcutRegistry.getRegisteredShortcuts().get(menuId);

        if (entry != null && entry.openAction() != null) {
            entry.openAction().accept(null);
        }
    }

}
