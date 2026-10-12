package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.RadialClient;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.ui.screen.ModConfigSelectionScreen;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 动作模式: 打开指定模组的设置界面。
 * <p>
 * 动作值为模组 ID(如 {@code litematica}); 界面本身由
 * {@link ToggleSourceRegistry#openConfigScreen} 依次向各提供者询问
 * (目前是 ModMenu, 其次是 Cloth AutoConfig 自建界面)。
 * <p>
 * 因为 ModMenu 能否提供界面必须先构造界面才知道, 这里无法预先校验有效性,
 * 所以打开失败时会在聊天栏给出提示而不是静默失败。
 */
public class ModConfigSlotMode extends IconEnabledSlotMode {

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.modconfig");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.modconfig");
    }

    /**
     * 只要存在任一能打开模组配置界面的提供者就可用。
     * <p>
     * 早期版本这里硬编码了 modmenu / cloth-config, 结果只装 MaLiLib 或只装 YACL
     * 的玩家会完全看不到这个动作。改为向注册表询问能力, 与具体配置库解耦。
     */
    @Override
    public boolean isAvailable() {
        return ToggleSourceRegistry.hasConfigScreenCapability();
    }

    @Override
    public boolean hasValuePicker() {
        return true;
    }

    @Override
    public void openValuePicker(Screen parent, Consumer<String> onSelect) {
        Minecraft.getInstance().setScreen(new ModConfigSelectionScreen(parent, onSelect));
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        String modId = slot.value;

        // 未指定模组时, 先关闭轮盘再弹出选择界面, 并把选择结果写回该槽位,
        // 这样玩家只需操作一次即可完成配置
        if (modId == null || modId.isBlank()) {
            context.closeScreen();
            RadialClient.scheduleClientAction(() -> Minecraft.getInstance()
                    .setScreen(new ModConfigSelectionScreen(null, id -> {
                        slot.value = id;
                        // 槽位属于 RadialConfig.INSTANCE 的槽位树, 直接持久化
                        RadialConfig.save();
                    })));
            return;
        }

        // 先关闭轮盘(并锁定呼出键), 再于下一 tick 打开目标界面,
        // 这样目标界面成为一个干净的根界面, 关闭后直接回到游戏
        context.closeScreen();

        RadialClient.scheduleClientAction(() -> {
            boolean opened = ToggleSourceRegistry.openConfigScreen(modId, null);
            if (!opened && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("radialmenu.modconfig.unavailable", modId), true);
            }
        });
    }

}
