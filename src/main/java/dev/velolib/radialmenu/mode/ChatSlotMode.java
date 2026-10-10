package dev.velolib.radialmenu.mode;

import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.mode.base.IconEnabledSlotMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

public class ChatSlotMode extends IconEnabledSlotMode {

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.chat");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.chat");
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildValueRow(screen, slot, width, container);
        buildIconRow(screen, slot, width, container);
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen(); // Close the radial menu first

        if (slot.value == null || slot.value.isEmpty()) return;

        // Execute the chat message or command
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            if (slot.value.startsWith("/")) {
                connection.sendCommand(slot.value.substring(1)); // Remove the slash for commands
            } else {
                connection.sendChat(slot.value); // Send as normal chat
            }
        }
    }
}
