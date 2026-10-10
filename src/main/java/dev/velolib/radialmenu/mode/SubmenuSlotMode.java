package dev.velolib.radialmenu.mode;

import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.api.SlotModeRegistry;
import dev.velolib.radialmenu.mode.base.IconEnabledSlotMode;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SubmenuSlotMode extends IconEnabledSlotMode {
    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.submenu");
    }

    @Override
    public boolean isMacroAction() {
        return false; // Submenus need the radial menu to be open
    }

    @Override
    public boolean activateOnRelease() {
        return true; // 松开呼出键时也可进入子菜单, 且轮盘保持开启
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.openSubmenu(slot.children, slot.childSlotCount);
    }

    @Override
    public void onInitialize(RadialSlot slot) {
        if (slot.children == null) slot.children = new ArrayList<>();

        while (slot.children.size() < slot.childSlotCount) {
            slot.children.add(new RadialSlot(
                    "Sub Slot " + (slot.children.size() + 1), SlotModeRegistry.getDefaultMode(), "", "minecraft:air"));
        }
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        int ROW_HEIGHT = 20;

        LinearLayout subGroup = LinearLayout.vertical().spacing(2);

        StringWidget label =
                new StringWidget(Component.translatable("screen.radialmenu.editor.submenu"), Minecraft.getInstance().font);
        subGroup.addChild(label);

        // Pass 0, 0 for X and Y, the layout will override it automatically
        AbstractSliderButton subCountSlider =
                new AbstractSliderButton(
                        0,
                        0,
                        width,
                        ROW_HEIGHT,
                        Component.translatable("screen.radialmenu.editor.submenu.placeholder", slot.childSlotCount),
                        (slot.childSlotCount - 2) / 10.0) {
                    @Override
                    protected void updateMessage() {
                        int val = 2 + (int) Math.round(value * 10);
                        setMessage(Component.translatable("screen.radialmenu.editor.submenu.placeholder", val));
                    }

                    @Override
                    protected void applyValue() {
                        slot.childSlotCount = 2 + (int) Math.round(value * 10);
                        onInitialize(slot); // Trigger the array resize logic
                    }
                };
        subGroup.addChild(subCountSlider);

        container.addChild(subGroup);

        // Icon Row
        buildIconRow(screen, slot, width, container);
    }
}
