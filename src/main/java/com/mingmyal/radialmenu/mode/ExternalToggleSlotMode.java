package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.ui.screen.ToggleSourceSelectionScreen;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 动作模式: 触发一次外部模组的开关切换。
 * <p>
 * 与"开关类型"的区别在于本模式<b>不关心也不需要状态</b>:
 * <ul>
 *   <li>开关类型: 每帧读取目标状态, 按状态着色/显示文本, 激活时写入明确的开或关。
 *       它还额外占用图标色调与两套状态动作, 配置成本高。</li>
 *   <li>本模式: 点击就是"让那个模组的开关翻一下", 不读取、不显示任何状态。</li>
 * </ul>
 * 因此这是唯一能承载"状态不可读"来源的方式 —— 例如 Follow Me 的跟随开关: 状态保存在
 * 服务端的 {@code Set<UUID>} 里, 且该模组没有定义任何 S2C 包, 客户端无从得知当前是开还是关。
 * 用开关类型承载它只会显示一个恒定的假状态。
 * <p>
 * 绑定结果存放在 {@code slot.value}(而非 {@code slot.toggleSourceId}): 后者在
 * "开关类型"未启用时会被 {@link RadialSlot#sanitize()} 清空, 因此不能复用。
 */
public class ExternalToggleSlotMode extends IconEnabledSlotMode {

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.externaltoggle");
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.externaltoggle");
    }

    /**
     * 只有在确实存在可绑定的开关时才出现在动作模式列表里。
     * <p>
     * 与"打开模组设置界面"同样的取舍: 若一个支持的模组都没有, 这个动作毫无意义,
     * 列出来只会让玩家困惑。发现结果已被注册表缓存, 这里不会每帧重复扫描。
     */
    @Override
    public boolean isAvailable() {
        ToggleSourceRegistry.ensureDiscovered();
        return !ToggleSourceRegistry.isEmpty();
    }

    @Override
    public boolean hasValuePicker() {
        return true;
    }

    /**
     * 列出<b>全部</b>来源, 包含状态不可读的那些。
     * <p>
     * 与开关类型的来源选择器相反: 那边需要读状态, 因此过滤掉不可读来源;
     * 本模式只需要触发能力, 所以不该过滤 —— 否则 Follow Me 永远无法绑定。
     */
    @Override
    public void openValuePicker(Screen parent, Consumer<String> onSelect) {
        Minecraft.getInstance().setScreen(new ToggleSourceSelectionScreen(parent, onSelect, false));
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        context.closeScreen();

        if (slot.value == null || slot.value.isBlank()) return;

        ToggleSource source = ToggleSourceRegistry.resolve(slot.value);
        if (source == null || !source.isAvailable()) {
            message("radialmenu.externaltoggle.unavailable", slot.value);
            return;
        }

        // 纯触发: 不读状态, 也不据此显示任何结果
        try {
            source.toggle();
        } catch (Throwable t) {
            RadialCommon.LOGGER.error("External toggle '{}' failed to trigger", slot.value, t);
            message("radialmenu.externaltoggle.failed");
        }
    }

    /** 以 actionbar 形式反馈; 无玩家时静默跳过(不会发生在实际游戏中)。 */
    private static void message(String key, Object... args) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        player.displayClientMessage(
                args.length == 0 ? Component.translatable(key) : Component.translatable(key, args), true);
    }
}
