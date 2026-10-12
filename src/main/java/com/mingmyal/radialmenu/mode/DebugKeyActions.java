package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.mixin.KeyboardHandlerAccessor;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 1.21.1 的 F3 调试快捷键不是可绑定的 KeyMapping（不出现在 options.keyMappings 里），
 * 而是硬编码在 {@link net.minecraft.client.KeyboardHandler#handleDebugKeys(int)} 中。
 * 这里把这些动作显式列出，供快捷键选择器展示，并通过 mixin 访问器按原版逻辑执行。
 *
 * 标签优先复用原版语言键 debug.xxx.help（官方中文已翻译，且自带 F3 组合提示）。
 *
 * 另外补充 keyPress 中硬编码的非 F3/特殊分支：F3+1/2/3 图表（在 handleDebugKeys 的
 * switch 中）、F3+Esc 无菜单暂停（Minecraft#pauseGame）、F1 隐藏界面（options.hideGui）。
 * F2 截图与 F11 全屏本身是 KeyMapping，不在此表。
 */
public final class DebugKeyActions {

    public static final String PREFIX = "radialmenu:debug.";

    public record Entry(String id, String labelKey, int glfwKey) {}

    public static final List<Entry> ENTRIES = List.of(
            // F3 单独按下：开关调试信息屏幕（走 DebugScreenOverlay#toggleOverlay）
            new Entry(PREFIX + "overlay", "radialmenu.debug.overlay", GLFW.GLFW_KEY_F3),
            new Entry(PREFIX + "reload_chunks", "debug.reload_chunks.help", GLFW.GLFW_KEY_A),
            new Entry(PREFIX + "show_hitboxes", "debug.show_hitboxes.help", GLFW.GLFW_KEY_B),
            new Entry(PREFIX + "copy_location", "debug.copy_location.help", GLFW.GLFW_KEY_C),
            new Entry(PREFIX + "clear_chat", "debug.clear_chat.help", GLFW.GLFW_KEY_D),
            new Entry(PREFIX + "chunk_borders", "debug.chunk_boundaries.help", GLFW.GLFW_KEY_G),
            new Entry(PREFIX + "advanced_tooltips", "debug.advanced_tooltips.help", GLFW.GLFW_KEY_H),
            new Entry(PREFIX + "inspect", "debug.inspect.help", GLFW.GLFW_KEY_I),
            new Entry(PREFIX + "profiling", "debug.profiling.help", GLFW.GLFW_KEY_L),
            new Entry(PREFIX + "creative_spectator", "debug.creative_spectator.help", GLFW.GLFW_KEY_N),
            new Entry(PREFIX + "pause_focus", "debug.pause_focus.help", GLFW.GLFW_KEY_P),
            new Entry(PREFIX + "help", "debug.help.help", GLFW.GLFW_KEY_Q),
            new Entry(PREFIX + "dump_textures", "debug.dump_dynamic_textures.help", GLFW.GLFW_KEY_S),
            new Entry(PREFIX + "reload_resourcepacks", "debug.reload_resourcepacks.help", GLFW.GLFW_KEY_T),
            new Entry(PREFIX + "gamemode_switcher", "debug.gamemodes.help", GLFW.GLFW_KEY_F4),
            new Entry(PREFIX + "profiler_chart", "radialmenu.debug.profiler_chart", GLFW.GLFW_KEY_1),
            new Entry(PREFIX + "fps_chart", "radialmenu.debug.fps_chart", GLFW.GLFW_KEY_2),
            new Entry(PREFIX + "network_chart", "radialmenu.debug.network_chart", GLFW.GLFW_KEY_3),
            new Entry(PREFIX + "pause_no_menu", "debug.pause.help", GLFW.GLFW_KEY_ESCAPE),
            new Entry(PREFIX + "hide_gui", "radialmenu.debug.hide_gui", GLFW.GLFW_KEY_F1));

    private DebugKeyActions() {}

    public static boolean isDebugAction(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static Optional<Entry> byId(String id) {
        return ENTRIES.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** 必须在游戏内（玩家与世界已加载）执行，与原版 F3 组合键的触发前提一致。 */
    public static void execute(Entry entry, Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gui == null) {
            return;
        }

        switch (entry.glfwKey()) {
            // F3 单独按下：开关调试信息屏幕
            case GLFW.GLFW_KEY_F3 -> client.gui.getDebugOverlay().toggleOverlay();
            // F3 + Esc：暂停但不弹暂停菜单（pauseGame(true)）
            case GLFW.GLFW_KEY_ESCAPE -> client.pauseGame(true);
            // F1：隐藏界面（HUD）
            case GLFW.GLFW_KEY_F1 -> client.options.hideGui = !client.options.hideGui;
            // 其余均走原版 handleDebugKeys（含 F3+1/2/3 图表）
            default -> ((KeyboardHandlerAccessor) client.keyboardHandler).radialmenu$handleDebugKeys(entry.glfwKey());
        }
    }
}
