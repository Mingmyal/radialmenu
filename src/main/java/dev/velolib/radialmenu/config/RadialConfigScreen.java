package dev.velolib.radialmenu.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.mode.MacroSlotMode;
import dev.velolib.radialmenu.mode.SubmenuSlotMode;
import dev.velolib.radialmenu.render.DonutRenderer;
import dev.velolib.radialmenu.ui.screen.RadialScreen;
import java.awt.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RadialConfigScreen {

    private static final DonutRenderer PREVIEW_RENDERER = new DonutRenderer("preview");

    private static boolean showPreview = true;

    public static Screen create(Screen parent) {
        RadialConfig config = RadialConfig.INSTANCE;

        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("screen.radialmenu.config.title"))

                // SETTINGS
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("screen.radialmenu.config.category.settings"))
                        .tooltip(Component.translatable("screen.radialmenu.config.category.settings.tooltip"))
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.show_preview"))
                                .description(OptionDescription.of(
                                        Component.translatable("screen.radialmenu.config.show_preview.tooltip")))
                                .binding(true, () -> showPreview, v -> showPreview = v)
                                .controller(BooleanControllerBuilder::create)
                                .build())
                        .option(ButtonOption.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.optimize"))
                                .description(OptionDescription.of(
                                        Component.translatable("screen.radialmenu.config.optimize.tooltip")))
                                .action((a, b) -> {
                                    optimizeSlotTree(config.slots);
                                    RadialConfig.save();
                                })
                                .build())
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.empty())
                                .binding(false, () -> false, v -> {})
                                .customController(opt -> createPreviewController(opt, config))
                                .build())

                        // LAYOUT
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.layout"))
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.slot_count"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.slot_count.tooltip")))
                                        .binding(5, () -> config.slotCount, v -> config.slotCount = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(2, 12)
                                                .step(1))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.slot_radius"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.slot_radius.tooltip")))
                                        .binding(105, () -> config.slotRadius, v -> config.slotRadius = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(30, 300)
                                                .step(1))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.radial_thickness"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.radial_thickness.tooltip")))
                                        .binding(100, () -> config.radialThickness, v -> config.radialThickness = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(20, 300)
                                                .step(1))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.inner_detection_boundary"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.inner_detection_boundary.tooltip")))
                                        .binding(
                                                0,
                                                () -> config.innerDetectionBoundary,
                                                v -> config.innerDetectionBoundary = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(0, 200)
                                                .step(1))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.outer_detection_boundary"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.outer_detection_boundary.tooltip")))
                                        .binding(
                                                0,
                                                () -> config.outerDetectionBoundary,
                                                v -> config.outerDetectionBoundary = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(0, 300)
                                                .step(1))
                                        .build())
                                .option(Option.<Float>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.radial_size"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.radial_size.tooltip")))
                                        .binding(1.2f, () -> config.radialSize, v -> config.radialSize = v)
                                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                                                .range(0.5f, 3.0f)
                                                .step(0.1f))
                                        .build())
                                .build())

                        // APPEARANCE
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.appearance"))
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.background_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.background_color.tooltip")))
                                        .binding(
                                                new Color(0x661A150D, true),
                                                () -> config.backgroundColor,
                                                v -> config.backgroundColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.activation_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.activation_color.tooltip")))
                                        .binding(
                                                new Color(0x66FFE7B0, true),
                                                () -> config.activationColor,
                                                v -> config.activationColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.border_color"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.border_color.tooltip")))
                                        .binding(
                                                new Color(0x99FFE7B0, true),
                                                () -> config.borderColor,
                                                v -> config.borderColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.highlight_border_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.highlight_border_color.tooltip")))
                                        .binding(
                                                new Color(0x99FFE7B0, true),
                                                () -> config.highlightBorderColor,
                                                v -> config.highlightBorderColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.show_activation_zone"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.show_activation_zone.tooltip")))
                                        .binding(
                                                true,
                                                () -> config.showActivationZone,
                                                v -> config.showActivationZone = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.draw_outer_borders"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.draw_outer_borders.tooltip")))
                                        .binding(true, () -> config.drawOuterBorders, v -> config.drawOuterBorders = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Float>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.sector_border_width"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.sector_border_width.tooltip")))
                                        .binding(
                                                1.0f, () -> config.sectorBorderWidth, v -> config.sectorBorderWidth = v)
                                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                                                .range(0.0f, 5.0f)
                                                .step(0.1f))
                                        .build())
                                .option(Option.<Float>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.sector_gap"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.sector_gap.tooltip")))
                                        .binding(8.5f, () -> config.sectorGap, v -> config.sectorGap = v)
                                        .controller(opt -> FloatSliderControllerBuilder.create(opt)
                                                .range(0.0f, 20.0f)
                                                .step(0.5f))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.enable_background_blur"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.enable_background_blur.tooltip")))
                                        .binding(
                                                false,
                                                () -> config.enableBackgroundBlur,
                                                v -> config.enableBackgroundBlur = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .build())

                        // TEXT
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.text"))
                                .option(Option.<String>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.title_text"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.title_text.tooltip")))
                                        .binding("——     快捷轮盘     ——", () -> config.titleText, v -> config.titleText = v)
                                        .controller(StringControllerBuilder::create)
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.title_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.title_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_TITLE_COLOR, true),
                                                () -> config.titleColor,
                                                v -> config.titleColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<String>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.center_hint_line1"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.center_hint_line1.tooltip")))
                                        .binding("选择功能", () -> config.centerHintLine1, v -> config.centerHintLine1 = v)
                                        .controller(StringControllerBuilder::create)
                                        .build())
                                .option(Option.<String>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.center_hint_line2"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.center_hint_line2.tooltip")))
                                        .binding("", () -> config.centerHintLine2, v -> config.centerHintLine2 = v)
                                        .controller(StringControllerBuilder::create)
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.center_hint_line1_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.center_hint_line1_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_CENTER_HINT_COLOR, true),
                                                () -> config.centerHintColorLine1,
                                                v -> config.centerHintColorLine1 = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.center_hint_line2_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.center_hint_line2_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_CENTER_HINT_COLOR, true),
                                                () -> config.centerHintColorLine2,
                                                v -> config.centerHintColorLine2 = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.bottom_hint_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.bottom_hint_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_BOTTOM_HINT_COLOR, true),
                                                () -> config.bottomHintColor,
                                                v -> config.bottomHintColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .build())

                        // BEHAVIOR
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.behavior"))
                                .option(Option.<RadialConfig.RevealAnimation>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.reveal_animation"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.reveal_animation.tooltip")))
                                        .binding(
                                                RadialConfig.RevealAnimation.ZOOM,
                                                () -> config.revealAnimation,
                                                v -> config.revealAnimation = v)
                                        .controller(opt -> EnumControllerBuilder.create(opt)
                                                .enumClass(RadialConfig.RevealAnimation.class)
                                                .formatValue(RadialConfig.RevealAnimation::getDisplayName))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.reveal_duration_ms"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.reveal_duration_ms.tooltip")))
                                        .binding(180, () -> config.revealDurationMs, v -> config.revealDurationMs = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(0, 2000)
                                                .step(10))
                                        .build())
                                .option(Option.<RadialConfig.ActivationMode>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.activation_mode"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.activation_mode.tooltip")))
                                        .binding(
                                                RadialConfig.ActivationMode.CLICK,
                                                () -> config.activationMode,
                                                v -> config.activationMode = v)
                                        .controller(opt -> EnumControllerBuilder.create(opt)
                                                .enumClass(RadialConfig.ActivationMode.class)
                                                .formatValue(RadialConfig.ActivationMode::getDisplayName))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.reset_cursor_on_submenu"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.reset_cursor_on_submenu.tooltip")))
                                        .binding(
                                                false,
                                                () -> RadialConfig.INSTANCE.resetCursorOnSubmenu,
                                                v -> RadialConfig.INSTANCE.resetCursorOnSubmenu = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .build())
                        .build())

                // LIVE PREVIEW
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("screen.radialmenu.config.category.preview"))
                        .option(Option.<Boolean>createBuilder()
                                .name(Component.empty())
                                .binding(false, () -> false, v -> {})
                                .customController(opt -> createPreviewController(opt, config))
                                .build())
                        .build())
                .save(RadialConfig::save)
                .build()
                .generateScreen(parent);
    }

    private static void optimizeSlotTree(List<RadialSlot> slots) {
        if (slots == null || slots.isEmpty()) return;

        for (RadialSlot slot : slots) {
            if (!(slot.mode instanceof MacroSlotMode) && slot.macros != null) {
                slot.macros.clear();
            }

            if (!(slot.mode instanceof SubmenuSlotMode)) {
                if (slot.children != null) {
                    slot.children.clear();
                }
                slot.childSlotCount = 0;
            } else {
                optimizeSlotTree(slot.children);
            }
        }
    }

    private static Controller<Boolean> createPreviewController(Option<Boolean> opt, RadialConfig config) {
        return new Controller<>() {
            private AbstractWidget widget;

            @Override
            public Option<Boolean> option() {
                return opt;
            }

            @Override
            public Component formatValue() {
                return Component.empty();
            }

            @Override
            public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> dimension) {
                if (widget == null) {
                    widget = new AbstractWidget(dimension) {
                        @Override
                        public boolean isFocused() {
                            return false;
                        }

                        @Override
                        public void setFocused(boolean focused) {}

                        @Override
                        public void render(
                                GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                            if (showPreview) {
                                renderPreview(graphics, config);
                            }
                        }
                    };
                }
                return widget;
            }
        };
    }

    /*
     * Detection-zone preview
     */
    private static void drawAnnulus(
            GuiGraphics graphics, float cx, float cy, float innerRadius, float outerRadius) {
        if (outerRadius <= innerRadius) return;

        float outerSquared = outerRadius * outerRadius;
        float innerSquared = innerRadius * innerRadius;

        int minY = (int) Math.floor(cy - outerRadius);
        int maxY = (int) Math.ceil(cy + outerRadius);

        final int color = 0x3349A6FF;

        for (int y = minY; y <= maxY; y++) {
            float pixelY = y + 0.5F;
            float dy = pixelY - cy;
            float dySquared = dy * dy;
            float outerLimit = outerSquared - dySquared;

            if (outerLimit <= 0.0F) continue;

            float outerHalfWidth = (float) Math.sqrt(outerLimit);
            float innerHalfWidth = (dySquared >= innerSquared) ? 0.0F : (float) Math.sqrt(innerSquared - dySquared);

            int startLeft = (int) (cx - outerHalfWidth);
            int endRight = (int) (cx + outerHalfWidth);

            if (innerHalfWidth <= 0.0F) {
                // Completely solid row (above/below the inner hollow hole)
                graphics.fill(startLeft, y, endRight, y + 1, color);
            } else {
                // Hollow row (needs a left and right segment)
                int endLeft = (int) (cx - innerHalfWidth);
                int startRight = (int) (cx + innerHalfWidth);

                graphics.fill(startLeft, y, endLeft, y + 1, color);
                graphics.fill(startRight, y, endRight, y + 1, color);
            }
        }
    }

    /*
     * Preview rendering
     */
    private static void renderPreview(GuiGraphics graphics, RadialConfig config) {
        Minecraft client = Minecraft.getInstance();

        int cx = client.getWindow().getGuiScaledWidth() / 2;
        int cy = client.getWindow().getGuiScaledHeight() / 2;

        float visibleInner = config.getVisibleInnerRadius();
        float visibleOuter = config.getVisibleOuterRadius();
        float detectionInner = config.getDetectionInnerRadius();
        float detectionOuter = config.getDetectionOuterRadius();
        int count = config.slotCount;

        if (count <= 0) return;

        PREVIEW_RENDERER.prepare(count, visibleInner, visibleOuter, 2.0F);

        int hoveredSlot = 0;

        // Draw outer/inner bounds
        if (detectionInner < visibleInner) {
            drawAnnulus(graphics, cx, cy, detectionInner, visibleInner);
        }

        // When "Draw Menu Background" is off, the renderer never draws the visible ring, so
        // there'd otherwise be no indication of the true activation area (e.g. with both
        // detection boundaries at 0). Show it here instead.
        if (!config.showActivationZone) {
            drawAnnulus(graphics, cx, cy, visibleInner, visibleOuter);
        }

        if (detectionOuter > visibleOuter) {
            drawAnnulus(graphics, cx, cy, visibleOuter, detectionOuter);
        }

        if (config.showActivationZone) {
            for (int i = 0; i < count; i++) {
                boolean highlighted = (i == hoveredSlot);
                float slotAngle = (float) ((Math.PI * 2.0 / count) * i - Math.PI / 2.0);

                // 与主界面一致: 无选中位移, 高亮只强调扇环填充色+边框色
                PREVIEW_RENDERER.renderSector(graphics, cx, cy, slotAngle, visibleInner, visibleOuter, 0.0F, highlighted, 1.0F, count);
            }
        }
    }
}
