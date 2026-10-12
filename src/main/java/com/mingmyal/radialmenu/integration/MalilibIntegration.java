package com.mingmyal.radialmenu.integration;

import com.mingmyal.radialmenu.RadialCommon;

import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindCategory;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class MalilibIntegration {

    public static void init() {
        RadialCommon.LOGGER.info("Radial Malilib logic active.");
    }

    public static List<MalilibAction> getAllActions() {
        List<MalilibAction> list = new ArrayList<>();
        try {
            for (KeybindCategory cat : InputEventHandler.getKeybindManager().getKeybindCategories()) {
                String mod = cat.getModName();
                String category = cat.getCategory();
                for (IHotkey hk : cat.getHotkeys()) {
                    String id = mod + ":" + category + ":" + hk.getName();
                    Display display = resolveDisplay(hk);
                    list.add(new MalilibAction(
                            mod, category, hk.getName(), id,
                            display.label(), display.description(), display.subLabel()));
                }
            }
        } catch (Throwable e) {
            RadialCommon.LOGGER.error("Failed to fetch Malilib hotkeys", e);
        }
        return list;
    }

    public static void executeHotkey(String hotkeyId) {
        if (hotkeyId == null || hotkeyId.isEmpty()) return;

        try {
            for (KeybindCategory cat : InputEventHandler.getKeybindManager().getKeybindCategories()) {
                String mod = cat.getModName();
                String category = cat.getCategory();

                for (IHotkey hk : cat.getHotkeys()) {
                    // Reconstruct the ID instead of splitting the input string
                    String currentId = mod + ":" + category + ":" + hk.getName();

                    if (currentId.equals(hotkeyId)) {
                        // Cast is required: IKeybind doesn't store the callback, KeybindMulti does
                        if (hk.getKeybind() instanceof KeybindMulti multi) {
                            IHotkeyCallback callback = multi.getCallback();
                            if (callback != null) {
                                callback.onKeyAction(KeyAction.PRESS, multi);
                            }
                        }
                        return; // Found and executed, exit early
                    }
                }
            }
        } catch (Throwable e) {
            RadialCommon.LOGGER.error("Failed to execute Malilib hotkey", e);
        }
    }

    /**
     * 解析热键的本地化显示信息。
     *
     * masa 系 Mod（如 Litematica）的热键在 {@code apply(prefix)} 后拥有三套翻译键：
     * {@code <prefix>.name.<id>}（正式名）、{@code <prefix>.prettyName.<id>}、
     * {@code <prefix>.comment.<id>}（说明）。实测 Litematica 等 Mod 只提供 comment 翻译
     * （zh_cn 里为中文说明），名称无翻译，所以不能只依赖 getPrettyName()（回退后是英文
     * 驼峰拆词 "Open Config Gui"，任何汉化包都改不了）。
     *
     * 优先级：已翻译的正式名 → 已翻译的说明首行 → 英文名 → 原始内部名。
     */
    private static Display resolveDisplay(IHotkey hk) {
        String rawName = safe(hk::getName);
        if (rawName == null || rawName.isBlank()) {
            return new Display("?", null, null);
        }

        // 1) 正式名翻译存在时（部分 Mod 提供 <prefix>.name.<id>）
        String guiName = safe(hk::getConfigGuiDisplayName);
        if (guiName != null && !guiName.isBlank() && !guiName.equals(rawName)) {
            return new Display(guiName.trim(), guiName.trim(), null);
        }

        String englishFallback = safe(hk::getPrettyName);
        if (englishFallback == null || englishFallback.isBlank() || englishFallback.equals(rawName)) {
            englishFallback = splitCamelCase(rawName);
        }

        // 2) 说明翻译（Litematica zh_cn 提供的中文说明就在这里）
        String comment = safe(hk::getComment);
        if (hasRealTranslation(comment)) {
            String description = comment.trim();
            String firstLine = description.split("\\R", 2)[0].trim();
            String subLabel = englishFallback != null && !englishFallback.equalsIgnoreCase(firstLine)
                    ? englishFallback
                    : null;
            return new Display(firstLine, description, subLabel);
        }

        // 3) 英文回退名 / 4) 原始名
        return new Display(englishFallback != null ? englishFallback : rawName, null, null);
    }

    /**
     * getComment() 在翻译缺失时会回退为"原始翻译键"或拼接占位串，而不是抛错，
     * 典型形态：{@code xxx.comment.yyy}、{@code config.comment.yyy}、{@code Xxx Comment?}。
     * 这些都不是给用户看的文本，必须识别后回退。
     */
    private static boolean hasRealTranslation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.contains(".comment.")) {
            return false;
        }
        return !text.endsWith(" Comment?");
    }

    private static String splitCamelCase(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(name.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.substring(0, 1).toUpperCase() + sb.substring(1);
    }

    private static String safe(/* @Nullable */ Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private record Display(String label, String description, String subLabel) {}

    /**
     * @param displayName 列表主显示名（已本地化的最佳文本）
     * @param description 完整说明（可能含多行），用于悬浮提示；null 表示无
     * @param subLabel    次要名称（如英文原名），显示在第二行；null 表示无
     */
    public record MalilibAction(
            String modName,
            String category,
            String name,
            String id,
            String displayName,
            String description,
            String subLabel) {}
}
