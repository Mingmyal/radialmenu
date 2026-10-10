package dev.velolib.radialmenu.mode;

import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.mode.base.IconEnabledSlotMode;
import dev.velolib.radialmenu.ui.screen.KeyCaptureScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class KeybindSlotMode extends IconEnabledSlotMode {

    /** 自定义按键录制的值前缀, 形如 "glfw:341+65" (Ctrl+A)。 */
    public static final String RAW_PREFIX = "glfw:";

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.keybind");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.keybind");
    }

    @Override
    public boolean hasValuePicker() {
        return true;
    }

    @Override
    public void openValuePicker(Screen parent, Consumer<String> onSelect) {
        Minecraft.getInstance().setScreen(new KeyCaptureScreen(parent, onSelect));
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildValueRow(screen, slot, width, container);
        buildIconRow(screen, slot, width, container);
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen();
        if (slot.value == null || slot.value.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();

        if (slot.value.startsWith(RAW_PREFIX)) {
            // 自定义键盘按键(可含组合键): 直接用 GLFW 模拟物理按键, 不依赖 KeyMapping
            List<Integer> codes = parseGlfwCodes(slot.value.substring(RAW_PREFIX.length()));
            if (codes.isEmpty()) return;
            if (codes.size() == 1) {
                RadialClient.scheduleRawKeyPress(codes.get(0));
            } else {
                RadialClient.scheduleRawComboKeyPress(codes);
            }
            return;
        }

        // 兼容旧的按键名称格式: "key.sneak+key.forward"
        List<KeyMapping> keys = resolveNamedKeys(slot.value, client);
        if (keys.isEmpty()) return;

        if (keys.size() == 1) {
            RadialClient.scheduleKeyPress(keys.get(0));
        } else {
            RadialClient.scheduleComboKeyPress(keys);
        }
    }

    private List<Integer> parseGlfwCodes(String body) {
        List<Integer> codes = new ArrayList<>();
        String[] parts = body.split("\\+");
        for (String part : parts) {
            try {
                codes.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return codes;
    }

    private List<KeyMapping> resolveNamedKeys(String value, Minecraft client) {
        List<KeyMapping> keys = new ArrayList<>();
        String[] parts = value.split("\\+");
        for (String part : parts) {
            String name = part.trim();
            for (KeyMapping key : client.options.keyMappings) {
                if (key.getName().equals(name)) {
                    if (RadialClient.isRadialInternalKey(key)) return List.of();
                    keys.add(key);
                    break;
                }
            }
        }
        return keys;
    }
}
