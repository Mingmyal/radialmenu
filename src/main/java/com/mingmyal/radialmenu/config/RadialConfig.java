package com.mingmyal.radialmenu.config;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotMode;
import com.mingmyal.radialmenu.api.SlotModeRegistry;
import com.mingmyal.radialmenu.client.ServerConfigState;
import com.mingmyal.radialmenu.config.adapters.ColorTypeAdapter;
import com.mingmyal.radialmenu.config.adapters.SlotModeTypeAdapter;
import com.mingmyal.radialmenu.ui.screen.RadialScreen;
import com.mingmyal.radialmenu.util.ConfigFiles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

public class RadialConfig {

    public static final int CONFIG_VERSION = 3;

    /** 单个轮盘的扇环数量下限/上限(与设置界面的滑块范围一致)。 */
    public static final int MIN_SLOTS = 2;
    public static final int MAX_SLOTS = 12;

    private static final File CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("radialmenu.json").toFile();

    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Color.class, new ColorTypeAdapter())
            .registerTypeAdapter(SlotMode.class, new SlotModeTypeAdapter())
            .create();

    public static RadialConfig INSTANCE;

    public int version = CONFIG_VERSION;

    // Layout (screen.radial.config.group.layout)
    public int slotCount = 5;
    public int slotRadius = 105;
    public int radialThickness = 100;
    public int innerDetectionBoundary = 0;
    public int outerDetectionBoundary = 0;
    public float radialSize = 1.2f;

    // Appearance (screen.radial.config.group.appearance)
    public Color backgroundColor = new Color(DEFAULT_BACKGROUND_COLOR, true);
    public Color activationColor = new Color(DEFAULT_ACTIVATION_COLOR, true);
    public Color borderColor = new Color(DEFAULT_BORDER_COLOR, true);
    public Color highlightBorderColor = new Color(DEFAULT_HIGHLIGHT_BORDER_COLOR, true);
    public boolean showActivationZone = true;
    public boolean drawOuterBorders = true;
    public float sectorBorderWidth = 1.0f;
    public float sectorGap = 8.5f;
    public boolean enableBackgroundBlur = false;

    // 条件约束 (screen.radial.config.group.condition): 未满足条件时的扇环配色与标识
    public Color unmetBackgroundColor = new Color(DEFAULT_UNMET_BACKGROUND_COLOR, true);
    public Color unmetHighlightBackgroundColor = new Color(DEFAULT_UNMET_HIGHLIGHT_BACKGROUND_COLOR, true);
    public Color unmetBorderColor = new Color(DEFAULT_UNMET_BORDER_COLOR, true);
    public Color unmetHighlightBorderColor = new Color(DEFAULT_UNMET_HIGHLIGHT_BORDER_COLOR, true);
    public boolean drawUnmetSlash = true;
    public Color unmetSlashColor = new Color(DEFAULT_UNMET_SLASH_COLOR, true);
    public boolean dimUnmetIcon = true;

    // 背景模糊程度(0-20, 与 enableBackgroundBlur 共同生效; 0 等同关闭)
    public int backgroundBlurRadius = 5;
    // 两类消耗解锁条件的确认弹窗开关: 关闭时直接消耗不提示
    public boolean confirmItemConsumeUnlock = true;
    public boolean confirmXpConsumeUnlock = true;

    public static final int DEFAULT_BACKGROUND_COLOR = 0x661A150D;
    public static final int DEFAULT_ACTIVATION_COLOR = 0x66FFE7B0;
    public static final int DEFAULT_BORDER_COLOR = 0x99FFE7B0;
    public static final int DEFAULT_HIGHLIGHT_BORDER_COLOR = 0x99FFE7B0;

    public static final int DEFAULT_UNMET_BACKGROUND_COLOR = 0x66A0522D;            // 浅红褐色
    public static final int DEFAULT_UNMET_HIGHLIGHT_BACKGROUND_COLOR = 0x66C8763C;  // 亮红褐色
    public static final int DEFAULT_UNMET_BORDER_COLOR = 0x99FF6B6B;                // 浅红色
    public static final int DEFAULT_UNMET_HIGHLIGHT_BORDER_COLOR = 0x99FF2020;      // 亮红色
    public static final int DEFAULT_UNMET_SLASH_COLOR = 0xFFFF6B6B;                 // 浅红色

    // 文字配置 (screen.radial.config.group.text)
    public String titleText = DEFAULT_TITLE_TEXT;
    public Color titleColor = new Color(DEFAULT_TITLE_COLOR, true);
    public Color bottomHintColor = new Color(DEFAULT_BOTTOM_HINT_COLOR, true);
    // 中心提示两行独立文本: 只有一行时居中显示, 两行时上下分布
    public String centerHintLine1 = DEFAULT_CENTER_HINT_LINE1;
    public String centerHintLine2 = "";
    // 两行中心提示颜色分开设置
    public Color centerHintColorLine1 = new Color(DEFAULT_CENTER_HINT_COLOR, true);
    public Color centerHintColorLine2 = new Color(DEFAULT_CENTER_HINT_COLOR, true);

    public static final String DEFAULT_TITLE_TEXT = "——     快捷轮盘     ——";
    public static final String DEFAULT_CENTER_HINT_LINE1 = "选择功能";
    public static final int DEFAULT_TITLE_COLOR = 0xFFFFFFFF;
    public static final int DEFAULT_BOTTOM_HINT_COLOR = 0xFF808080;
    public static final int DEFAULT_CENTER_HINT_COLOR = 0xFFAAAAAA;

    public int getTitleColor() {
        return titleColor != null ? titleColor.getRGB() : DEFAULT_TITLE_COLOR;
    }

    public int getBottomHintColor() {
        return bottomHintColor != null ? bottomHintColor.getRGB() : DEFAULT_BOTTOM_HINT_COLOR;
    }

    public int getCenterHintColorLine1() {
        return centerHintColorLine1 != null ? centerHintColorLine1.getRGB() : DEFAULT_CENTER_HINT_COLOR;
    }

    public int getCenterHintColorLine2() {
        return centerHintColorLine2 != null ? centerHintColorLine2.getRGB() : DEFAULT_CENTER_HINT_COLOR;
    }

    // Behavior (screen.radial.config.group.behavior)
    public RevealAnimation revealAnimation = RevealAnimation.ZOOM;
    public int revealDurationMs = 180;
    public boolean enableHoverAnimation = true;
    public ActivationMode activationMode = ActivationMode.CLICK;
    public boolean resetCursorOnSubmenu = false;
    // 编辑模式为常驻全局状态: 不打开轮盘也可用快捷键切换, 重启游戏后保留
    public boolean editMode = false;

    /** 本次解析是否发生了版本迁移(迁移后需要落盘)。非持久化字段。 */
    private transient boolean migrated = false;

    /** 该实例是否来自服务端下发。 */
    /**
     * 该实例的保存目标(绝对路径)。
     * <p>
     * 默认 {@code null} 表示写入客户端全局配置 {@code radialmenu.json}。
     * 按服务器隔离的自定义副本会设置成 {@code config/radialmenu-servers/<id>.json},
     * 这样同一份 {@link #save()} 逻辑就能服务于多个配置文件。
     */
    private transient Path savePath;

    /**
     * 该实例是否为只读(强制模式下的"纯净"服务端配置)。
     * <p>
     * 只读实例只存在于内存, 任何保存都会被忽略 —— 即使玩家本地改了它, 下次进服
     * 仍会重新从服务端拉取全新副本, 从而保证"强制模式下永远是服主那套"。
     */
    private transient boolean readOnly;

    public List<RadialSlot> slots = new ArrayList<>();

    // Only keeping legacy transients for fields that actually changed names/meanings
    private transient int legacyRingRadius = -1;
    private transient int legacyInnerPadding = -1;
    private transient int legacyOuterReach = -1;
    private transient int legacyAnimationSpeedMs = -1;

    public RadialConfig() {
        ensureSlotCapacity();
    }

    public static void load() {
        if (INSTANCE == null) {
            INSTANCE = new RadialConfig();
        }

        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_FILE.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            RadialConfig loaded = fromRoot(root);

            if (loaded != null) {
                INSTANCE = loaded;
                // 迁移后立即落盘, 保留既有行为
                if (loaded.migrated) {
                    save();
                }
            }
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to load config! Creating backup.", e);
            backupCorruptedConfig();
            INSTANCE = new RadialConfig();
            save();
        }
    }

    /**
     * 解析一份配置 JSON 文本, 返回独立实例, 不影响 {@link #INSTANCE} 也不写盘。
     * <p>
     * 供服务端配置下发使用: 服务端配置必须与本地配置完全隔离。
     * 默认标记为只读与"来自服务端", 调用方可按需改写
     * (按服务器隔离的自定义副本会改用 {@link #setSavePath} 指向自己的文件)。
     */
    public static RadialConfig parseFromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RadialConfig parsed = fromRoot(root);
        if (parsed == null) {
            throw new IllegalStateException("server config JSON produced no config object");
        }
        // 服务端配置不进本地文件, 迁移只在内存中完成
        parsed.migrated = false;
        // 默认只读: 纯净服务端配置仅存在于内存。
        // 需要落盘的"按服务器自定义副本"会显式 setReadOnly(false) + setSavePath(...)。
        parsed.readOnly = true;
        return parsed;
    }

    /** 深拷贝一份可写的副本(基于当前实例的内容)。 */
    public RadialConfig copyForEditing() {
        RadialConfig copy = GSON.fromJson(GSON.toJson(this), RadialConfig.class);
        if (copy == null) {
            copy = new RadialConfig();
        }
        copy.migrated = false;
        copy.readOnly = false;
        copy.savePath = null;
        copy.validate();
        return copy;
    }

    /**
     * 从 JSON 根对象构建配置对象(含旧字段提取、迁移与校验), 不写盘。
     */
    private static RadialConfig fromRoot(JsonObject root) {
        RadialConfig loaded = GSON.fromJson(root, RadialConfig.class);
        if (loaded == null) {
            return null;
        }

        // Manually extract legacy fields that no longer map directly to variables
        if (root.has("ringRadius") && root.get("ringRadius").isJsonPrimitive()) {
            loaded.legacyRingRadius = root.get("ringRadius").getAsInt();
        }
        if (root.has("innerPadding") && root.get("innerPadding").isJsonPrimitive()) {
            loaded.legacyInnerPadding = root.get("innerPadding").getAsInt();
        }
        if (root.has("outerReach") && root.get("outerReach").isJsonPrimitive()) {
            loaded.legacyOuterReach = root.get("outerReach").getAsInt();
        }
        if (root.has("animationSpeedMs") && root.get("animationSpeedMs").isJsonPrimitive()) {
            loaded.legacyAnimationSpeedMs = root.get("animationSpeedMs").getAsInt();
        }

        // 旧版中心提示为单字段(支持 \n), 迁移到两个独立行字段
        if (root.has("centerHintText") && root.get("centerHintText").isJsonPrimitive()) {
            String[] oldLines = root.get("centerHintText").getAsString().split("\n", 2);
            loaded.centerHintLine1 = oldLines.length > 0 ? oldLines[0] : "";
            loaded.centerHintLine2 = oldLines.length > 1 ? oldLines[1] : "";
        }

        // 旧版中心提示仅一个颜色字段, 迁移为两行共用该颜色
        if (root.has("centerHintColor")) {
            Color oldColor = GSON.fromJson(root.get("centerHintColor"), Color.class);
            if (oldColor != null) {
                loaded.centerHintColorLine1 = oldColor;
                loaded.centerHintColorLine2 = oldColor;
            }
        }

        if (loaded.version < CONFIG_VERSION) {
            handleMigration(loaded);
        } else {
            loaded.validate();
        }

        return loaded;
    }

    private static void handleMigration(RadialConfig loaded) {
        RadialCommon.LOGGER.info("Migrating Radial Config from v{} to v{}", loaded.version, CONFIG_VERSION);

        // Map legacy values to new fields if they existed in the old config
        if (loaded.legacyRingRadius >= 0) {
            loaded.slotRadius = loaded.legacyRingRadius;
        }

        if (loaded.legacyInnerPadding >= 0 && loaded.legacyOuterReach >= 0) {
            loaded.radialThickness = loaded.legacyInnerPadding + loaded.legacyOuterReach;
        }

        if (loaded.legacyAnimationSpeedMs >= 0) {
            loaded.revealDurationMs = loaded.legacyAnimationSpeedMs;
        }

        loaded.version = CONFIG_VERSION;
        loaded.validate();
        loaded.migrated = true;
    }

    /** 设置该实例的保存目标路径(按服务器隔离的副本使用)。 */
    public void setSavePath(Path path) {
        this.savePath = path;
    }

    /** 标记为只读(强制模式下的纯净服务端配置, 不落盘)。 */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * 保存"当前生效的配置"。
     * <p>
     * 统一入口, 由 {@link com.mingmyal.radialmenu.client.ServerConfigState} 决定实际落点:
     * 采用服务端配置时按服副本需要合并写入(运行期状态变更很频繁), 否则直接写客户端全局配置。
     */
    public static void save() {
        if (ServerConfigState.isUsingServerConfig()) {
            // 运行期状态变更频繁, 标记后由客户端 tick 合并提交一次
            ServerConfigState.markServerConfigDirty();
            return;
        }
        RadialConfig target = INSTANCE;
        if (target != null) {
            target.saveSelf();
        }
    }

    /**
     * 把本实例写回它自己的目标文件。
     * <p>
     * 只读实例(强制模式下的纯净服务端配置)直接忽略, 保证"本地怎么改都没用"。
     */
    public void saveSelf() {
        if (readOnly) {
            return;
        }
        Path target = savePath != null ? savePath : CONFIG_FILE.toPath();
        try {
            ConfigFiles.writeJsonAtomically(target, GSON, this);
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Critical error during config save to {}!", target, e);
        }
    }

    private static void backupCorruptedConfig() {
        if (!CONFIG_FILE.exists()) {
            return;
        }
        Path backup = CONFIG_FILE.toPath().resolveSibling(CONFIG_FILE.getName() + ".bak");
        try {
            Files.move(CONFIG_FILE.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            RadialCommon.LOGGER.warn("Unreadable config was backed up to {}", backup);
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to back up the unreadable config.", e);
        }
    }

    public void validate() {
        // Layout
        this.slotCount = Math.clamp(this.slotCount, MIN_SLOTS, MAX_SLOTS);
        this.slotRadius = Math.clamp(this.slotRadius, 30, 300);
        this.radialThickness = Math.clamp(this.radialThickness, 20, 300);
        this.innerDetectionBoundary = Math.clamp(this.innerDetectionBoundary, 0, 200);
        this.outerDetectionBoundary = Math.clamp(this.outerDetectionBoundary, 0, 300);
        this.radialSize = Math.clamp(this.radialSize, 0.5f, 3.0f);

        // Appearance —— 颜色字段缺失时(旧配置/手改文件)回落到默认色
        this.backgroundColor = defaultColor(this.backgroundColor, DEFAULT_BACKGROUND_COLOR);
        this.activationColor = defaultColor(this.activationColor, DEFAULT_ACTIVATION_COLOR);
        this.borderColor = defaultColor(this.borderColor, DEFAULT_BORDER_COLOR);
        this.highlightBorderColor = defaultColor(this.highlightBorderColor, DEFAULT_HIGHLIGHT_BORDER_COLOR);

        this.sectorBorderWidth = Math.clamp(this.sectorBorderWidth, 0.0f, 5.0f);
        this.sectorGap = Math.clamp(this.sectorGap, 0.0f, 20.0f);

        // 条件约束
        this.unmetBackgroundColor = defaultColor(this.unmetBackgroundColor, DEFAULT_UNMET_BACKGROUND_COLOR);
        this.unmetHighlightBackgroundColor =
                defaultColor(this.unmetHighlightBackgroundColor, DEFAULT_UNMET_HIGHLIGHT_BACKGROUND_COLOR);
        this.unmetBorderColor = defaultColor(this.unmetBorderColor, DEFAULT_UNMET_BORDER_COLOR);
        this.unmetHighlightBorderColor =
                defaultColor(this.unmetHighlightBorderColor, DEFAULT_UNMET_HIGHLIGHT_BORDER_COLOR);
        this.unmetSlashColor = defaultColor(this.unmetSlashColor, DEFAULT_UNMET_SLASH_COLOR);
        this.backgroundBlurRadius = Math.clamp(this.backgroundBlurRadius, 0, 20);

        // 文字
        if (this.titleText == null) this.titleText = DEFAULT_TITLE_TEXT;
        if (this.centerHintLine1 == null) this.centerHintLine1 = DEFAULT_CENTER_HINT_LINE1;
        if (this.centerHintLine2 == null) this.centerHintLine2 = "";
        this.titleColor = defaultColor(this.titleColor, DEFAULT_TITLE_COLOR);
        this.bottomHintColor = defaultColor(this.bottomHintColor, DEFAULT_BOTTOM_HINT_COLOR);
        this.centerHintColorLine1 = defaultColor(this.centerHintColorLine1, DEFAULT_CENTER_HINT_COLOR);
        this.centerHintColorLine2 = defaultColor(this.centerHintColorLine2, DEFAULT_CENTER_HINT_COLOR);

        // Behavior
        if (this.revealAnimation == null) {
            this.revealAnimation = RevealAnimation.ZOOM;
        }

        this.revealDurationMs = Math.clamp(this.revealDurationMs, 0, 2000);

        if (this.activationMode == null) {
            this.activationMode = ActivationMode.CLICK;
        }

        if (this.slots == null) {
            this.slots = new ArrayList<>();
        }

        this.slots.removeIf(Objects::isNull);
        for (RadialSlot slot : this.slots) {
            sanitizeRecursive(slot);
        }

        ensureSlotCapacity();
    }

    /** 颜色字段为空时回落到默认色; 否则原样保留。 */
    private static Color defaultColor(Color current, int argb) {
        return current != null ? current : new Color(argb, true);
    }

    /** 递归清洗槽位(含子菜单子槽位), 确保条件约束等新增字段在旧配置中也被规范化。 */
    private static void sanitizeRecursive(RadialSlot slot) {
        slot.sanitize();
        if (slot.children != null) {
            slot.children.removeIf(Objects::isNull);
            for (RadialSlot child : slot.children) {
                sanitizeRecursive(child);
            }
        }
    }

    /**
     * 重置所有槽位(含子菜单)的条件约束解锁状态, 并立即持久化。
     * <p>
     * 作用于<b>当前生效的配置</b>: 采用服务端配置时重置服务端配置的解锁状态并回传,
     * 否则重置本地配置。
     */
    public static void resetAllUnlockStates() {
        RadialConfig target = ServerConfigState.getEffectiveConfig();
        if (target == null) {
            return;
        }
        for (RadialSlot slot : target.slots) {
            resetUnlockRecursive(slot);
        }
        save();
    }

    private static void resetUnlockRecursive(RadialSlot slot) {
        if (slot == null) return;
        if (slot.condition != null) {
            slot.condition.unlockedUntil = 0L;
        }
        if (slot.children != null) {
            for (RadialSlot child : slot.children) {
                resetUnlockRecursive(child);
            }
        }
    }

    private void ensureSlotCapacity() {
        SlotMode defaultMode = SlotModeRegistry.getDefaultMode();
        while (slots.size() < MAX_SLOTS) {
            slots.add(new RadialSlot("Empty Slot " + (slots.size() + 1), defaultMode, "", "minecraft:air"));
        }
    }

    // --- Geometry ---

    public float getVisibleInnerRadius() {
        return Math.max(0.0F, slotRadius - radialThickness / 2.0F);
    }

    public float getVisibleOuterRadius() {
        return slotRadius + radialThickness / 2.0F;
    }

    public float getDetectionInnerRadius() {
        return Math.max(0.0F, getVisibleInnerRadius() - innerDetectionBoundary);
    }

    public float getDetectionOuterRadius() {
        float radius = getVisibleOuterRadius() + outerDetectionBoundary;
        return enableHoverAnimation ? radius + RadialScreen.SLOT_PUSH : radius;
    }

    public enum RevealAnimation {
        ZOOM("screen.radialmenu.config.reveal_animation.zoom"),
        STAGGERED_CLOCKWISE("screen.radialmenu.config.reveal_animation.staggered_clockwise"),
        STAGGERED_COUNTERCLOCKWISE("screen.radialmenu.config.reveal_animation.staggered_counterclockwise"),
        STAGGERED_BOTH("screen.radialmenu.config.reveal_animation.staggered_both");

        private final Component displayName;

        RevealAnimation(String translationKey) {
            this.displayName = Component.translatable(translationKey);
        }

        public Component getDisplayName() {
            return displayName;
        }
    }

    public enum ActivationMode {
        CLICK("screen.radialmenu.config.activation_mode.click"),
        RELEASE("screen.radialmenu.config.activation_mode.release"),
        SCROLL_CLICK("screen.radialmenu.config.activation_mode.scroll_click"),
        SCROLL_RELEASE("screen.radialmenu.config.activation_mode.scroll_release");

        private final Component displayName;

        ActivationMode(String translationKey) {
            this.displayName = Component.translatable(translationKey);
        }

        public Component getDisplayName() {
            return displayName;
        }
    }
}
