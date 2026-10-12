package com.mingmyal.radialmenu.config;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.client.ServerConfigState;
import com.mingmyal.radialmenu.mode.MacroSlotMode;
import com.mingmyal.radialmenu.mode.SubmenuSlotMode;
import com.mingmyal.radialmenu.render.DonutRenderer;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import java.awt.*;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RadialConfigScreen {

    private static boolean showPreview = true;

    /**
     * YACL 是否可用。
     * <p>
     * YACL 是可选依赖({@code modCompileOnly} + {@code suggests}), 未安装时本类不可加载,
     * 因此调用方必须先用本方法判断再调用 {@link #create}。
     */
    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3");
    }

    public static Screen create(Screen parent) {
        // 采用服务端配置时, 设置界面直接编辑服务端配置, 保存结果回传服务端
        RadialConfig config = ServerConfigState.getEffectiveConfig();

        // 服务端相关选项的可见性:
        // - 管理项(上传配置、强制开关): 仅多人模式的 OP 可见
        // - 来源切换: 仅在服务器确实分发配置且未强制时可见
        // - 本地可调设置(外观/布局/文字/行为): 强制模式下全部隐藏
        // 单人模式与普通玩家不该看到任何服务端选项, 因此这里直接不添加, 而不是置灰。
        boolean showServerManagement = ServerConfigState
                .shouldShowServerManagement();
        boolean showSourceSwitch = ServerConfigState
                .shouldShowSourceSwitch();
        // 强制模式下界面只保留"强制"开关本身: 其余设置改了也不会被保存, 显示出来会误导玩家
        boolean showLocal = ServerConfigState
                .shouldShowLocalSettings();

        var builder = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("screen.radialmenu.config.title"))

                // SETTINGS
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("screen.radialmenu.config.category.settings"))
                        .tooltip(Component.translatable("screen.radialmenu.config.category.settings.tooltip"))
                        .optionIf(showLocal, () -> Option.<Boolean>createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.show_preview"))
                                .description(OptionDescription.of(
                                        Component.translatable("screen.radialmenu.config.show_preview.tooltip")))
                                .binding(true, () -> showPreview, v -> showPreview = v)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        // --- 服务端设置(仅多人模式的 OP) ---
                        // 用原生 ButtonOption 而非自绘控件: 按钮点击由 YACL 处理, 可靠且观感统一
                        .optionIf(showServerManagement && showLocal, () -> ButtonOption.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.apply_server_config"))
                                .description(OptionDescription.of(Component.translatable(
                                        "screen.radialmenu.config.apply_server_config.tooltip")))
                                .text(Component.translatable(
                                        "screen.radialmenu.config.apply_server_config.button"))
                                .action((screen, opt) -> onApplyServerConfig())
                                .build())
                        // 强制开关在强制模式下<b>必须保留</b>, 否则 OP 无法关闭它
                        .optionIf(showServerManagement, () -> ButtonOption.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.force_server_config"))
                                .description(OptionDescription.of(Component.translatable(
                                        "screen.radialmenu.config.force_server_config.tooltip")))
                                .text(serverConfigForceButtonText())
                                .action((screen, opt) -> onToggleForce())
                                .build())
                        .optionIf(showSourceSwitch, () -> ButtonOption.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.switch_config_source"))
                                .description(OptionDescription.of(Component.translatable(
                                        "screen.radialmenu.config.switch_config_source.tooltip")))
                                .text(serverConfigSourceButtonText())
                                .action((screen, opt) -> onSwitchSource())
                                .build())
                        .optionIf(showLocal, () -> ButtonOption.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.optimize"))
                                .description(OptionDescription.of(
                                        Component.translatable("screen.radialmenu.config.optimize.tooltip")))
                                .action((a, b) -> {
                                    optimizeSlotTree(config.slots);
                                    RadialConfig.save();
                                })
                                .build())
                        .optionIf(showLocal, () -> previewOption(config))

                        // LAYOUT
                        .groupIf(showLocal, () -> OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.layout"))
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.slot_count"))
                                        .description(OptionDescription.of(
                                                Component.translatable("screen.radialmenu.config.slot_count.tooltip")))
                                        .binding(5, () -> config.slotCount, v -> config.slotCount = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(RadialConfig.MIN_SLOTS, RadialConfig.MAX_SLOTS)
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
                        .groupIf(showLocal, () -> OptionGroup.createBuilder()
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
                                .option(Option.<Integer>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.background_blur_radius"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.background_blur_radius.tooltip")))
                                        .binding(
                                                5,
                                                () -> config.backgroundBlurRadius,
                                                v -> config.backgroundBlurRadius = v)
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(0, 20)
                                                .step(1))
                                        .build())
                                .build())

                        // CONDITION CONSTRAINTS (条件约束)
                        .groupIf(showLocal, () -> OptionGroup.createBuilder()
                                .name(Component.translatable("screen.radialmenu.config.group.condition"))
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.unmet_background_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.unmet_background_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_UNMET_BACKGROUND_COLOR, true),
                                                () -> config.unmetBackgroundColor,
                                                v -> config.unmetBackgroundColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable(
                                                "screen.radialmenu.config.unmet_highlight_background_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.unmet_highlight_background_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_UNMET_HIGHLIGHT_BACKGROUND_COLOR, true),
                                                () -> config.unmetHighlightBackgroundColor,
                                                v -> config.unmetHighlightBackgroundColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.unmet_border_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.unmet_border_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_UNMET_BORDER_COLOR, true),
                                                () -> config.unmetBorderColor,
                                                v -> config.unmetBorderColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable(
                                                "screen.radialmenu.config.unmet_highlight_border_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.unmet_highlight_border_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_UNMET_HIGHLIGHT_BORDER_COLOR, true),
                                                () -> config.unmetHighlightBorderColor,
                                                v -> config.unmetHighlightBorderColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.draw_unmet_slash"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.draw_unmet_slash.tooltip")))
                                        .binding(true, () -> config.drawUnmetSlash, v -> config.drawUnmetSlash = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Color>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.unmet_slash_color"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.unmet_slash_color.tooltip")))
                                        .binding(
                                                new Color(RadialConfig.DEFAULT_UNMET_SLASH_COLOR, true),
                                                () -> config.unmetSlashColor,
                                                v -> config.unmetSlashColor = v)
                                        .customController(opt -> new RadialColorController(opt, true))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.dim_unmet_icon"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.dim_unmet_icon.tooltip")))
                                        .binding(true, () -> config.dimUnmetIcon, v -> config.dimUnmetIcon = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable(
                                                "screen.radialmenu.config.confirm_item_consume_unlock"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.confirm_item_consume_unlock.tooltip")))
                                        .binding(
                                                true,
                                                () -> config.confirmItemConsumeUnlock,
                                                v -> config.confirmItemConsumeUnlock = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.translatable(
                                                "screen.radialmenu.config.confirm_xp_consume_unlock"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.confirm_xp_consume_unlock.tooltip")))
                                        .binding(
                                                true,
                                                () -> config.confirmXpConsumeUnlock,
                                                v -> config.confirmXpConsumeUnlock = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(ButtonOption.createBuilder()
                                        .name(Component.translatable("screen.radialmenu.config.reset_unlocks"))
                                        .description(OptionDescription.of(Component.translatable(
                                                "screen.radialmenu.config.reset_unlocks.tooltip")))
                                        .action((yaclScreen, opt) -> Minecraft.getInstance()
                                                .setScreen(new ConfirmScreen(
                                                        result -> {
                                                            if (result) {
                                                                RadialConfig.resetAllUnlockStates();
                                                            }
                                                            Minecraft.getInstance().setScreen(yaclScreen);
                                                        },
                                                        Component.translatable(
                                                                "screen.radialmenu.config.reset_unlocks.confirm.title"),
                                                        Component.translatable(
                                                                "screen.radialmenu.config.reset_unlocks.confirm.message"))))
                                        .build())
                                .build())

                        // TEXT
                        .groupIf(showLocal, () -> OptionGroup.createBuilder()
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
                        .groupIf(showLocal, () -> OptionGroup.createBuilder()
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
                                                () -> config.resetCursorOnSubmenu,
                                                v -> config.resetCursorOnSubmenu = v)
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .build())
                        .build())

                // LIVE PREVIEW
                .categoryIf(showLocal, () -> ConfigCategory.createBuilder()
                        .name(Component.translatable("screen.radialmenu.config.category.preview"))
                        .option(previewOption(config))
                        .build())
                .save(() -> saveAndSync(config));

        var yacl = builder.build();

        // 无需再逐项禁用: 强制模式下 showLocal 为 false, 所有本地可调项都已不添加,
        // 界面上只剩"强制"开关本身(必须可点击, 否则 OP 无法关闭强制)。
        // 非强制模式下所有可见项都可编辑, 因此不需要额外处理。

        // 服务端状态变化后需要重建界面: 界面在创建时就捕获了"生效配置"的对象引用,
        // 一旦切换配置来源(服务器设置 ↔ 本地设置), 该引用就会指向不再生效的那份配置,
        // 此时继续编辑会改错对象。重建界面同时让按钮文本反映最新状态。
        ServerConfigState.setChangeListener(RadialConfigScreen::rebuildIfOpen);

        return yacl.generateScreen(parent);
    }

    /**
     * 若当前正停留在设置界面, 则重建它以反映最新的服务端状态。
     * <p>
     * 仅在确实处于设置界面时重建, 避免影响游戏内其它界面。
     */
    private static void rebuildIfOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof YACLScreen) {
            mc.setScreen(create(null));
        }
    }

    /**
     * 设置界面的保存。
     * <p>
     * 按当前生效的配置来源决定落点:
     * <ul>
     *   <li>强制模式 → 界面上所有可调项都已隐藏, 没有改动可保存, 直接忽略</li>
     *   <li>非强制的服务端配置 → 写入玩家自己那份按服副本(不影响他人)</li>
     *   <li>自己的客户端配置 → 写入客户端全局配置</li>
     * </ul>
     */
    private static void saveAndSync(RadialConfig config) {
        if (ServerConfigState.isForcedNow()) {
            // 强制模式下界面只保留"强制"开关, 没有任何可编辑项, 因此无改动可保存。
            // (要改全服配置, 需先关闭强制, 再由 OP 上传。)
            return;
        }

        if (ServerConfigState.isUsingServerConfig()) {
            // 非强制: 保存到玩家自己的按服副本, 只影响自己
            ServerConfigState.saveCurrentCopy();
            ServerConfigState.notifyInfo(
                    "radialmenu.server_config.custom_saved");
            return;
        }

        RadialConfig.save();
    }

    // --- 服务端设置按钮 ---

    /**
     * "应用为服务器设置":把服主当前的配置发布为服务端配置并开始全服分发。
     * <p>
     * 未开启时点击即开启;已开启时点击则用当前配置覆盖服务端配置(便于服主更新全服配置)。
     */
    private static void onApplyServerConfig() {
        if (!requireManagePermission()) {
            return;
        }
        // 发布"当前生效的配置": 服主在本地配置下点击时, 推的就是他自己那份
        RadialConfig toPublish = ServerConfigState.getEffectiveConfig();
        if (ServerConfigState.requestSetState(
                true, ServerConfigState.isForce(), toPublish)) {
            ServerConfigState.notifyInfo(
                    "radialmenu.server_config.applied_ok");
        }
    }

    /** "强制客户端应用服务器配置":切换全服强制开关。 */
    private static void onToggleForce() {
        if (!requireManagePermission()) {
            return;
        }
        if (!ServerConfigState.isApplied()) {
            // 尚未分发配置时强制没有意义
            ServerConfigState.notifyInfo(
                    "radialmenu.server_config.need_applied");
            return;
        }
        boolean target = !ServerConfigState.isForce();
        if (ServerConfigState.requestSetState(
                ServerConfigState.isApplied(), target, null)) {
            ServerConfigState.notifyInfo(
                    target ? "radialmenu.server_config.force_on" : "radialmenu.server_config.force_off");
        }
    }

    /**
     * "切换为客户端设置 / 切换为服务器设置":在服务端配置与自己的全局配置之间切换。
     * <p>
     * 服务器已强制使用服务端配置时无法切回本地(服务端会拒绝并回执原因)。
     * 该按钮对所有人可见可用 —— 服务器未装模组/未启用时点击会给出明确原因。
     */
    private static void onSwitchSource() {
        if (!ServerConfigState.isSynced()) {
            // 服务端未安装本模组: 明确告知, 避免"点了没反应"
            ServerConfigState.notifyServerMissing();
            return;
        }
        if (!ServerConfigState.isApplied()) {
            ServerConfigState.notifyInfo(
                    "radialmenu.server_config.not_applied");
            return;
        }
        if (ServerConfigState.isForce()) {
            // 服务器已强制, 不允许切回本地
            ServerConfigState.notifyRed(
                    Component.translatable("radialmenu.server_config.forced_locked"));
            return;
        }

        // 切回自己的全局配置是完全的个人选择, 不需要服务端权限。
        boolean toClient = !ServerConfigState.isUsingClientConfig();
        if (ServerConfigState.requestSetSource(toClient)) {
            ServerConfigState.notifyInfo(
                    toClient
                            ? "radialmenu.server_config.now_client"
                            : "radialmenu.server_config.now_server");
        }
    }

    /** 校验操作服务端设置的权限;无权限时给出明确原因并返回 false。 */
    private static boolean requireManagePermission() {
        if (!ServerConfigState.isSynced()) {
            // 服务端没装本模组(或版本不支持该通道)—— 与"无权限"是两回事, 必须分开提示
            ServerConfigState.notifyServerMissing();
            return false;
        }
        if (ServerConfigState.canManage()) {
            return true;
        }
        ServerConfigState.notifyNoPermission();
        return false;
    }

    /** "强制"按钮文本:显示当前状态。 */
    private static Component serverConfigForceButtonText() {
        if (!ServerConfigState.isSynced()) {
            return Component.translatable("radialmenu.server_config.no_server_mod.short");
        }
        return Component.translatable(ServerConfigState.isForce()
                ? "radialmenu.server_config.state_on"
                : "radialmenu.server_config.state_off");
    }

    /** "切换来源"按钮文本:显示当前正在使用的配置来源。 */
    private static Component serverConfigSourceButtonText() {
        if (!ServerConfigState.isSynced()) {
            return Component.translatable("radialmenu.server_config.no_server_mod.short");
        }
        if (ServerConfigState.isForce()) {
            return Component.translatable("radialmenu.server_config.state_forced");
        }
        return Component.translatable(
                ServerConfigState.isUsingServerConfig()
                        ? "radialmenu.server_config.using_server"
                        : "radialmenu.server_config.using_client");
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

    /**
     * 实时预览选项: 一个不可见的占位 Option, 由自定义控制器直接绘制预览。
     * <p>
     * 它在"设置"与"实时预览"两个分类中各出现一次 —— 两处都是有意保留的入口。
     */
    private static Option<Boolean> previewOption(RadialConfig config) {
        return Option.<Boolean>createBuilder()
                .name(Component.empty())
                .binding(false, () -> false, v -> {})
                .customController(opt -> createPreviewController(opt, config))
                .build();
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
                DonutRenderer.renderSector(cx, cy, slotAngle, visibleInner, visibleOuter, highlighted, false, 1.0F, count);
            }
        }
    }
}
