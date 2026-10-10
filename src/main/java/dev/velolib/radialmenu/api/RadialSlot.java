package dev.velolib.radialmenu.api;

import dev.velolib.radialmenu.render.SlotRenderHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

public class RadialSlot {

    public String name;
    public SlotMode mode;
    public String value;
    public String itemId;

    // 介绍: 显示在图标下方的文字, 单行
    public String description;

    // 文字颜色: #AARRGGBB(含透明度) 或 #RRGGBB, null 表示用默认色
    public String nameColor;
    public String descriptionColor;

    // 选中提示: 悬停该槽位时显示在底部提示上方的文字, 单行
    public String selectedHint;
    public String selectedHintColor;

    // Submenu configuration
    public List<RadialSlot> children = new ArrayList<>();
    public int childSlotCount = 8;

    // Macros
    public record Macro(SlotMode mode, String value) {}

    public List<Macro> macros = new ArrayList<>();

    // Optional "#RRGGBB" tint for Phosphor and glyph icons; null draws them white.
    // A string rather than an int because Gson skips the constructor, so a missing int would load as black.
    public String iconColor;

    private transient ItemStack cachedStack;
    private transient int cachedIconColor = -1;

    public RadialSlot(String name, SlotMode mode, String value, String itemId) {
        this.name = name;
        this.mode = mode;
        this.value = value;
        this.itemId = itemId;
    }

    public void clearCache() {
        cachedStack = null;
        cachedIconColor = -1;
    }

    /**
     * Returns the icon tint as 0xRRGGBB, or white when no valid color is set.
     */
    public int getIconColor() {
        if (cachedIconColor < 0) {
            Integer parsed = parseIconColor(iconColor);
            cachedIconColor = parsed != null ? parsed : 0xFFFFFF;
        }

        return cachedIconColor;
    }

    /**
     * Parses "#RRGGBB" (the leading # is optional), or returns null if the text isn't a valid color.
     */
    public static Integer parseIconColor(String text) {
        if (text == null) return null;

        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6) return null;

        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatIconColor(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** 槽位名称默认色(不透明白) */
    public static final int DEFAULT_NAME_COLOR = 0xFFFFFFFF;
    /** 介绍默认色(半透明灰) */
    public static final int DEFAULT_DESCRIPTION_COLOR = 0xFFAAAAAA;
    /** 选中提示默认色(半透明灰) */
    public static final int DEFAULT_SELECTED_HINT_COLOR = 0xFFAAAAAA;

    /**
     * 解析 #RRGGBB 或 #AARRGGBB, 返回完整 ARGB; 无效返回 null。
     */
    public static Integer parseColorWithAlpha(String text) {
        if (text == null) return null;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            long v = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                return 0xFF000000 | (int) v;
            }
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatColorWithAlpha(int argb) {
        return String.format("#%08X", argb);
    }

    public int getNameColor() {
        Integer c = parseColorWithAlpha(nameColor);
        return c != null ? c : DEFAULT_NAME_COLOR;
    }

    public int getDescriptionColor() {
        Integer c = parseColorWithAlpha(descriptionColor);
        return c != null ? c : DEFAULT_DESCRIPTION_COLOR;
    }

    public int getSelectedHintColor() {
        Integer c = parseColorWithAlpha(selectedHintColor);
        return c != null ? c : DEFAULT_SELECTED_HINT_COLOR;
    }

    /**
     * Fills in missing fields and drops invalid entries, recursing into submenu children.
     */
    public void sanitize() {
        if (name == null) name = "";
        if (mode == null) mode = SlotModeRegistry.getDefaultMode();
        if (value == null) value = "";
        if (itemId == null) itemId = "minecraft:air";
        if (description == null) description = "";
        if (selectedHint == null) selectedHint = "";

        Integer color = parseIconColor(iconColor);
        iconColor = color != null ? formatIconColor(color) : null;

        // The radial menu renders at most 12 slots (plus the back button) per ring
        childSlotCount = Math.clamp(childSlotCount, 2, 12);

        if (macros == null) macros = new ArrayList<>();
        macros.removeIf(macro -> macro == null || macro.mode() == null);

        if (children == null) children = new ArrayList<>();
        children.removeIf(Objects::isNull);
        for (RadialSlot child : children) {
            child.sanitize();
        }

        mode.onInitialize(this);
    }

    public ItemStack getRenderStack() {
        if (cachedStack == null) {
            cachedStack = SlotRenderHelper.parseItemStack(itemId);
        }

        return cachedStack;
    }
}
