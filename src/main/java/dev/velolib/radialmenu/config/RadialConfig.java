package dev.velolib.radialmenu.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotMode;
import dev.velolib.radialmenu.api.SlotModeRegistry;
import dev.velolib.radialmenu.config.adapters.ColorTypeAdapter;
import dev.velolib.radialmenu.config.adapters.SlotModeTypeAdapter;
import dev.velolib.radialmenu.ui.screen.RadialScreen;
import dev.velolib.radialmenu.util.ConfigFiles;
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
    public Color backgroundColor = new Color(0x661A150D, true);
    public Color activationColor = new Color(0x66FFE7B0, true);
    public Color borderColor = new Color(0x99FFE7B0, true);
    public Color highlightBorderColor = new Color(0x99FFE7B0, true);
    public boolean showActivationZone = true;
    public boolean drawOuterBorders = true;
    public boolean drawSectorBorders = true;
    public float sectorBorderWidth = 1.0f;
    public float sectorGap = 8.5f;
    public boolean enableBackgroundBlur = false;

    // 文字配置 (screen.radial.config.group.text)
    public String titleText = "——     快捷轮盘     ——";
    public Color titleColor = new Color(DEFAULT_TITLE_COLOR, true);
    public Color bottomHintColor = new Color(DEFAULT_BOTTOM_HINT_COLOR, true);
    // 中心提示两行独立文本: 只有一行时居中显示, 两行时上下分布
    public String centerHintLine1 = "选择功能";
    public String centerHintLine2 = "";
    // 两行中心提示颜色分开设置
    public Color centerHintColorLine1 = new Color(DEFAULT_CENTER_HINT_COLOR, true);
    public Color centerHintColorLine2 = new Color(DEFAULT_CENTER_HINT_COLOR, true);

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
    public int hoverAnimationDurationMs = 120;
    public ActivationMode activationMode = ActivationMode.CLICK;
    public boolean resetCursorOnSubmenu = false;

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
            RadialConfig loaded = GSON.fromJson(root, RadialConfig.class);

            if (loaded != null) {
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

                INSTANCE = loaded;

                // 旧版中心提示为单字段(支持 \n), 迁移到两个独立行字段
                if (root.has("centerHintText") && root.get("centerHintText").isJsonPrimitive()) {
                    String[] oldLines = root.get("centerHintText").getAsString().split("\n", 2);
                    INSTANCE.centerHintLine1 = oldLines.length > 0 ? oldLines[0] : "";
                    INSTANCE.centerHintLine2 = oldLines.length > 1 ? oldLines[1] : "";
                }

                // 旧版中心提示仅一个颜色字段, 迁移为两行共用该颜色
                if (root.has("centerHintColor")) {
                    Color oldColor = GSON.fromJson(root.get("centerHintColor"), Color.class);
                    if (oldColor != null) {
                        INSTANCE.centerHintColorLine1 = oldColor;
                        INSTANCE.centerHintColorLine2 = oldColor;
                    }
                }

                if (INSTANCE.version < CONFIG_VERSION) {
                    handleMigration(INSTANCE);
                } else {
                    INSTANCE.validate();
                }
            }
        } catch (Exception e) {
            RadialClient.LOGGER.error("Failed to load config! Creating backup.", e);
            backupCorruptedConfig();
            INSTANCE = new RadialConfig();
            save();
        }
    }

    private static void handleMigration(RadialConfig loaded) {
        RadialClient.LOGGER.info("Migrating Radial Config from v{} to v{}", loaded.version, CONFIG_VERSION);

        // Map legacy values to new fields if they existed in the old config
        if (loaded.legacyRingRadius >= 0) {
            loaded.slotRadius = loaded.legacyRingRadius;
        }

        if (loaded.legacyInnerPadding >= 0 && loaded.legacyOuterReach >= 0) {
            loaded.radialThickness = loaded.legacyInnerPadding + loaded.legacyOuterReach;
        }

        if (loaded.legacyAnimationSpeedMs >= 0) {
            loaded.revealDurationMs = loaded.legacyAnimationSpeedMs;
            loaded.hoverAnimationDurationMs = loaded.legacyAnimationSpeedMs;
        }

        loaded.version = CONFIG_VERSION;
        loaded.validate();
        save();
    }

    public static void save() {
        try {
            ConfigFiles.writeJsonAtomically(CONFIG_FILE.toPath(), GSON, INSTANCE);
        } catch (Exception e) {
            RadialClient.LOGGER.error("Critical error during config save!", e);
        }
    }

    private static void backupCorruptedConfig() {
        if (!CONFIG_FILE.exists()) {
            return;
        }
        Path backup = CONFIG_FILE.toPath().resolveSibling(CONFIG_FILE.getName() + ".bak");
        try {
            Files.move(CONFIG_FILE.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            RadialClient.LOGGER.warn("Unreadable config was backed up to {}", backup);
        } catch (IOException e) {
            RadialClient.LOGGER.error("Failed to back up the unreadable config.", e);
        }
    }

    public void validate() {
        // Layout
        this.slotCount = Math.clamp(this.slotCount, 2, 12);
        this.slotRadius = Math.clamp(this.slotRadius, 30, 300);
        this.radialThickness = Math.clamp(this.radialThickness, 20, 300);
        this.innerDetectionBoundary = Math.clamp(this.innerDetectionBoundary, 0, 200);
        this.outerDetectionBoundary = Math.clamp(this.outerDetectionBoundary, 0, 300);
        this.radialSize = Math.clamp(this.radialSize, 0.5f, 3.0f);

        // Appearance
        if (this.backgroundColor == null) {
            this.backgroundColor = new Color(0x661A150D, true);
        }

        if (this.activationColor == null) {
            this.activationColor = new Color(0x66FFE7B0, true);
        }

        if (this.borderColor == null) {
            this.borderColor = new Color(0x99FFE7B0, true);
        }

        if (this.highlightBorderColor == null) {
            this.highlightBorderColor = new Color(0x99FFE7B0, true);
        }

        this.sectorBorderWidth = Math.clamp(this.sectorBorderWidth, 0.0f, 5.0f);
        this.sectorGap = Math.clamp(this.sectorGap, 0.0f, 20.0f);

        // 文字
        if (this.titleText == null) this.titleText = "——     快捷轮盘     ——";
        if (this.centerHintLine1 == null) this.centerHintLine1 = "选择功能";
        if (this.centerHintLine2 == null) this.centerHintLine2 = "";
        if (this.titleColor == null) this.titleColor = new Color(DEFAULT_TITLE_COLOR, true);
        if (this.bottomHintColor == null) this.bottomHintColor = new Color(DEFAULT_BOTTOM_HINT_COLOR, true);
        if (this.centerHintColorLine1 == null) this.centerHintColorLine1 = new Color(DEFAULT_CENTER_HINT_COLOR, true);
        if (this.centerHintColorLine2 == null) this.centerHintColorLine2 = new Color(DEFAULT_CENTER_HINT_COLOR, true);

        // Behavior
        if (this.revealAnimation == null) {
            this.revealAnimation = RevealAnimation.ZOOM;
        }

        this.revealDurationMs = Math.clamp(this.revealDurationMs, 0, 2000);
        this.hoverAnimationDurationMs = Math.clamp(this.hoverAnimationDurationMs, 0, 2000);

        if (this.activationMode == null) {
            this.activationMode = ActivationMode.CLICK;
        }

        if (this.slots == null) {
            this.slots = new ArrayList<>();
        }

        this.slots.removeIf(Objects::isNull);
        for (RadialSlot slot : this.slots) {
            slot.sanitize();
        }

        ensureSlotCapacity();
    }

    private void ensureSlotCapacity() {
        SlotMode defaultMode = SlotModeRegistry.getDefaultMode();
        while (slots.size() < 12) {
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
