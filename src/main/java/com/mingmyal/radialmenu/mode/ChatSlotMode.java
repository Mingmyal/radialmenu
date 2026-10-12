package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;

import net.minecraft.client.Minecraft;
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
