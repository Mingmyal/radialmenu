package com.mingmyal.radialmenu.api;

import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;
import com.mingmyal.radialmenu.mode.SubmenuSlotMode;
import com.mingmyal.radialmenu.render.SlotRenderHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
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

    // 条件约束: 启用后仅在满足条件时才能点击使用
    public boolean conditionEnabled = false;
    public SlotCondition condition;

    // Submenu configuration
    public List<RadialSlot> children = new ArrayList<>();
    public int childSlotCount = 8;

    // Macros
    public record Macro(SlotMode mode, String value) {}

    public List<Macro> macros = new ArrayList<>();

    // Optional "#RRGGBB" tint for Phosphor and glyph icons; null draws them white.
    // A string rather than an int because Gson skips the constructor, so a missing int would load as black.
    public String iconColor;

    // 图标自定义长宽(像素), null 或 <=0 表示用默认值 26。
    // iconWidth 是横向的"长", iconHeight 是纵向的"宽"(编辑界面按 长×宽 的顺序摆放)
    public Integer iconWidth;
    public Integer iconHeight;

    // 自定义长宽时图标的填充方式; null 视为 STRETCH(拉伸), 兼容旧配置
    public IconSizeMode iconSizeMode;

    // 点击该槽位时播放的音效 ID; 原版音效如 "minecraft:block.note_block.pling",
    // 导入音频格式为 "radialmenu:sound.<name>"
    public String clickSound = DEFAULT_CLICK_SOUND;

    // 点击音效开关: false 表示该槽位点击时静音(但保留已配置的音效 ID)。
    // 用包装类型: 旧配置缺失该字段时(Gson 跳过构造器, 字段为 null)视为默认开启
    public Boolean clickSoundEnabled;

    // 开关类型: 启用后激活槽位会翻转开/关状态, 并执行对应状态的动作配置
    public boolean toggleEnabled = false;

    // 当前开关状态(true=开启), 随配置持久化保存
    public boolean toggleState = false;

    // 图标状态色调: 开启后按状态将图标非透明像素染成红(关)/绿(开)色系。
    // 用包装类型: 旧配置缺失该字段时(Gson 跳过构造器, 字段为 null)视为默认开启
    public Boolean toggleTint;

    // 开/关状态下拼接在选中提示之后的状态文本, 留空表示不附加
    public String toggleTextOn = DEFAULT_TOGGLE_TEXT_ON;
    public String toggleTextOff = DEFAULT_TOGGLE_TEXT_OFF;

    // 两套状态动作配置(独立槽位对象, 激活时按翻转后的状态执行对应一组)
    public RadialSlot toggleActionOn;
    public RadialSlot toggleActionOff;

    // 外部模组开关来源 ID(形如 "malilib_litematica:genericXxx")。
    // 非空时: 开关状态改由目标模组提供, 激活槽位会写入目标模组而非翻转本地 toggleState。
    // null/空 表示保留原有的本地手动开关行为(向后兼容旧配置)。
    public String toggleSourceId;

    private transient ItemStack cachedStack;
    // 用包装类型: Gson 反序列化时不调用构造器, int 字段默认为 0 而非 -1,
    // 导致 getIconColor() 直接返回 0(黑色) 而不重新计算。Integer 默认为 null 表示未缓存。
    private transient Integer cachedIconColor = null;

    public RadialSlot(String name, SlotMode mode, String value, String itemId) {
        this.name = name;
        this.mode = mode;
        this.value = value;
        this.itemId = itemId;
    }

    public void clearCache() {
        cachedStack = null;
        cachedIconColor = null;
    }

    public static final int DEFAULT_ICON_SIZE = 26;

    /**
     * Returns the icon width (the horizontal "长"), defaulting to {@link #DEFAULT_ICON_SIZE} when unset.
     */
    public int getIconWidth() {
        return iconWidth != null && iconWidth > 0 ? iconWidth : DEFAULT_ICON_SIZE;
    }

    /**
     * Returns the icon height (the vertical "宽"), defaulting to {@link #DEFAULT_ICON_SIZE} when unset.
     */
    public int getIconHeight() {
        return iconHeight != null && iconHeight > 0 ? iconHeight : DEFAULT_ICON_SIZE;
    }

    /**
     * 是否手动填过自定义长/宽。
     * <p>
     * 只有填过才按 {@link IconSizeMode} 缩放图标; 未填写时所有图标沿用原版外观
     * (物品/效果图标保持原生像素居中, 导入图片铺满默认框), 这样旧配置的观感完全不变。
     */
    public boolean hasCustomIconSize() {
        return (iconWidth != null && iconWidth > 0) || (iconHeight != null && iconHeight > 0);
    }

    /** 自定义长宽时图标的填充方式; 未设置按"拉伸"处理, 兼容旧配置。 */
    public IconSizeMode getIconSizeMode() {
        return iconSizeMode != null ? iconSizeMode : IconSizeMode.STRETCH;
    }

    /** 自定义长宽时图标的填充方式。 */
    public enum IconSizeMode {
        /** 自适应: 等比缩放, 完整放进长×宽框内, 不变形。 */
        FIT("screen.radialmenu.editor.icon_size_mode.fit"),
        /** 拉伸: 铺满整个长×宽框, 允许变形。 */
        STRETCH("screen.radialmenu.editor.icon_size_mode.stretch");

        private final String translationKey;

        IconSizeMode(String translationKey) {
            this.translationKey = translationKey;
        }

        public Component getDisplayName() {
            return Component.translatable(translationKey);
        }
    }

    /**
     * Returns the icon tint as 0xRRGGBB, or white when no valid color is set.
     */
    public int getIconColor() {
        if (cachedIconColor == null) {
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
    /** 开关类型默认状态文本 */
    public static final String DEFAULT_TOGGLE_TEXT_ON = "[开]";
    public static final String DEFAULT_TOGGLE_TEXT_OFF = "[关]";
    /** 点击音效默认值: 原版音符盒"叮"声 */
    public static final String DEFAULT_CLICK_SOUND = "minecraft:block.note_block.pling";

    /** 图标状态色调 — 开启: 薄荷绿 */
    public static final int TOGGLE_TINT_ON = 0x87C9A7;
    /** 图标状态色调 — 关闭: 淡红 */
    public static final int TOGGLE_TINT_OFF = 0xD9958F;

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

    // --- 开关类型 (Toggle Type) ---

    /** 图标状态色调是否生效(开关类型启用且色调开关未关闭)。 */
    public boolean isToggleTintEnabled() {
        return toggleEnabled && (toggleTint == null || toggleTint);
    }

    /**
     * 当前状态下图标状态色调的 {@code #RRGGBB} 颜色; 未启用色调时返回 {@code null}。
     * <p>
     * 开启为薄荷绿 {@link #TOGGLE_TINT_ON}, 关闭为淡红 {@link #TOGGLE_TINT_OFF}。
     */
    public Integer getToggleTintColor() {
        if (!isToggleTintEnabled()) {
            return null;
        }
        return getEffectiveToggleState() ? TOGGLE_TINT_ON : TOGGLE_TINT_OFF;
    }

    // --- 点击音效 (Click Sound) ---

    /**
     * 点击该槽位时是否播放音效。
     * <p>
     * 用包装类型: 旧配置缺失该字段时(Gson 跳过构造器, 字段为 null)视为默认开启,
     * 这样升级后原有槽位的声音行为完全不变。
     */
    public boolean isClickSoundEnabled() {
        return clickSoundEnabled == null || clickSoundEnabled;
    }

    /**
     * 点击音效 ID 归一化: 空白一律回落到默认音效。
     * <p>
     * 编辑器里把音效输入框清空后保存时用它回填 —— 否则空值会被写进配置,
     * 表现为"这个槽位从此再也没有声音", 而玩家以为自己只是清了个输入框。
     */
    public static String normalizeClickSound(String soundId) {
        return soundId == null || soundId.isBlank() ? DEFAULT_CLICK_SOUND : soundId;
    }

    /** 当前状态下拼接在选中提示之后的状态文本(可为空字符串表示不附加)。 */
    public String getToggleStateText() {
        String text = getEffectiveToggleState() ? toggleTextOn : toggleTextOff;
        return text != null ? text : "";
    }

    // --- 外部开关来源 (External Toggle Source) ---

    /**
     * 解析绑定的外部开关来源; 未绑定或来源不可用时返回 null。
     * 每次调用都会查注册表缓存, 不做反射扫描。
     */
    public ToggleSource resolveToggleSource() {
        if (!toggleEnabled) return null;

        ToggleSource source =
                ToggleSourceRegistry.resolve(toggleSourceId);
        if (source == null) return null;

        return source.isAvailable() ? source : null;
    }

    /** 是否绑定了外部开关来源(不代表来源当前可用)。 */
    public boolean hasToggleSource() {
        return toggleSourceId != null && !toggleSourceId.isBlank();
    }

    /**
     * 是否为"已绑定的外部开关": 开关类型开启且来源当前可用。
     * <p>
     * 这种槽位激活时<b>只切换目标模组的开关</b>, 不执行任何动作模式,
     * 因此编辑器也不显示状态动作、直接执行时也跳过动作分发。
     */
    public boolean isBoundExternalToggle() {
        return resolveToggleSource() != null;
    }

    /**
     * 当前开关状态: 绑定外部来源时读取目标模组的真实状态, 否则用本地 toggleState。
     * 该方法是只读的, 可在渲染循环中安全调用。
     */
    public boolean getEffectiveToggleState() {
        ToggleSource source = resolveToggleSource();
        if (source != null) {
            try {
                return source.getState();
            } catch (Throwable ignored) {
                // 读取失败时退回本地状态, 不影响轮盘渲染
            }
        }
        return toggleState;
    }

    /**
     * 返回"此刻激活会执行"的动作槽位: 开关类型激活时状态先翻转,
     * 当前为关 → 翻为开 → 执行开启状态动作; 反之执行关闭状态动作。
     * 绑定外部开关来源时按目标模组的真实状态判断。
     * 非开关类型返回自身。
     */
    public RadialSlot resolveActionSlot() {
        if (!toggleEnabled) {
            return this;
        }
        RadialSlot action = getEffectiveToggleState() ? toggleActionOff : toggleActionOn;
        return action != null ? action : this;
    }

    /** 确保两套状态动作配置存在(开启开关类型时调用), 默认复制当前动作配置。 */
    public void ensureToggleActions() {
        if (toggleActionOn == null) {
            toggleActionOn = copyAsActionSlot();
        }
        if (toggleActionOff == null) {
            toggleActionOff = copyAsActionSlot();
        }
    }

    private RadialSlot copyAsActionSlot() {
        // 子菜单属于导航而非动作, 复制时降级为默认动作模式避免下拉框选择异常
        SlotMode actionMode = SlotModeRegistry.getDefaultMode();
        if (mode != null && !mode.isMacroOnly() && !(mode instanceof SubmenuSlotMode)) {
            actionMode = mode;
        }
        RadialSlot action = new RadialSlot(
                name != null ? name : "",
                actionMode,
                value != null ? value : "",
                itemId != null ? itemId : "minecraft:air");
        action.sanitize();
        return action;
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
        if (clickSound == null || clickSound.isBlank()) {
            clickSound = DEFAULT_CLICK_SOUND;
        }

        // 开关类型: 补齐状态文本; 启用时补齐色调默认值与两套状态动作
        if (toggleTextOn == null) toggleTextOn = DEFAULT_TOGGLE_TEXT_ON;
        if (toggleTextOff == null) toggleTextOff = DEFAULT_TOGGLE_TEXT_OFF;
        if (toggleEnabled) {
            if (toggleTint == null) toggleTint = Boolean.TRUE;
            ensureToggleActions();
            toggleActionOn.sanitize();
            toggleActionOff.sanitize();
        } else {
            // 未启用开关类型时不应残留外部来源绑定
            toggleSourceId = null;
        }
        if (toggleSourceId != null && toggleSourceId.isBlank()) {
            toggleSourceId = null;
        }

        // 条件约束: 关闭时清空条件, 开启时补齐字段
        if (conditionEnabled && condition == null) {
            condition = new SlotCondition();
        } else if (!conditionEnabled) {
            condition = null;
        }
        if (condition != null) {
            condition.sanitize();
        }

        Integer color = parseIconColor(iconColor);
        iconColor = color != null ? formatIconColor(color) : null;

        if (iconWidth != null) iconWidth = Math.clamp(iconWidth, 8, 64);
        if (iconHeight != null) iconHeight = Math.clamp(iconHeight, 8, 64);

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
