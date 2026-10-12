package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;

import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class EmptySlotMode extends IconEnabledSlotMode {
    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.empty");
    }

    @Override
    public boolean isMacroAction() {
        return false; // A "do nothing" step is pointless inside a macro
    }

    @Override
    public boolean activateOnRelease() {
        return false; // Don't trigger when releasing on empty slots
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        // Do absolutely nothing. Do not even call context.closeScreen()!
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildIconRow(screen, slot, width, container);
    }
}
