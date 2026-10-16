package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.RadialClient;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.ui.screen.KeybindPickerScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
        // 从游戏"选项→控制"中已注册的按键(KeyMapping)里选择, 而非录制裸键盘按键
        Minecraft.getInstance().setScreen(new KeybindPickerScreen(parent, onSelect));
    }


    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen();
        if (slot.value == null || slot.value.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();

        // F3 调试快捷键(区块边界、高级提示等), 1.21.1 中不是 KeyMapping
        if (DebugKeyActions.isDebugAction(slot.value)) {
            String id = slot.value;
            // 排到下一 tick 执行, 此时轮盘界面已完全关闭, 与真实按键时机一致
            RadialClient.scheduleClientAction(
                    () -> DebugKeyActions.byId(id).ifPresent(entry -> DebugKeyActions.execute(entry, Minecraft.getInstance())));
            return;
        }

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

        // 排到下一 tick 执行: 此时轮盘界面已完全关闭, 与真实按键时机一致。
        // 必须走真实按键注入, 而不是只改 clickCount —— 事件驱动的模组(如 FTB Chunks
        // 的区块声明管理)订阅的是原始按键事件, 只改 clickCount 它们收不到,
        // 表现为"点了没反应"。
        RadialClient.scheduleClientAction(() -> {
            if (RadialClient.dispatchBoundKeys(keys)) {
                return;
            }
            // 鼠标绑定等无法作为键盘键注入的, 回退到游戏内状态模拟
            if (keys.size() == 1) {
                RadialClient.scheduleKeyPress(keys.get(0));
            } else {
                RadialClient.scheduleComboKeyPress(keys);
            }
        });
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
