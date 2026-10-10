package dev.velolib.radialmenu.mode;

import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.mode.base.IconEnabledSlotMode;
import dev.velolib.radialmenu.ui.screen.MacroEditorScreen;
import dev.velolib.radialmenu.util.MacroExecutor;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MacroSlotMode extends IconEnabledSlotMode {

    public static final int MAX_ACTIONS = 100;

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.macro");
    }

    @Override
    public boolean isMacroAction() {
        return false; // Macros can't nest
    }

    @Override
    public void onInitialize(RadialSlot slot) {
        if (slot.macros == null) slot.macros = new ArrayList<>();
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen(); // Close the menu so the macro executes safely

        MacroExecutor.start(slot.macros);
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        onInitialize(slot);

        LinearLayout macroGroup = LinearLayout.vertical().spacing(2);

        StringWidget label =
                new StringWidget(Component.translatable("screen.radialmenu.editor.macro"), Minecraft.getInstance().font);
        macroGroup.addChild(label);

        Button editButton = Button.builder(
                        Component.translatable("screen.radialmenu.editor.macro.edit", slot.macros.size(), MAX_ACTIONS),
                        b -> Minecraft.getInstance().setScreen(new MacroEditorScreen(screen, slot)))
                .bounds(0, 0, width, 20)
                .build();
        macroGroup.addChild(editButton);

        container.addChild(macroGroup);

        buildIconRow(screen, slot, width, container);
    }
}
