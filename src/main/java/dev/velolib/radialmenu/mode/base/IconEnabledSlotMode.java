package dev.velolib.radialmenu.mode.base;

import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotMode;
import dev.velolib.radialmenu.render.SlotRenderHelper;
import dev.velolib.radialmenu.ui.screen.iconpicker.IconPickerScreen;
import dev.velolib.radialmenu.util.EncoderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public abstract class IconEnabledSlotMode implements SlotMode {

    private static final int HORIZ_GAP = 5;
    private static final int BUTTON_WIDTH = 55;
    private static final int ROW_HEIGHT = 20;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final int DESC_MAX_LENGTH = 200;

    /**
     * Helper to build the "Action Value" label and text field, plus a Select button
     * when this mode {@link #hasValuePicker() has a value picker}.
     */
    protected void buildValueRow(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        boolean hasPicker = this.hasValuePicker();
        int valueFieldWidth = hasPicker ? width - BUTTON_WIDTH - HORIZ_GAP : width;

        // Group the label and row together vertically
        LinearLayout valueGroup = LinearLayout.vertical().spacing(2);

        StringWidget label =
                new StringWidget(Component.translatable("screen.radialmenu.editor.value"), Minecraft.getInstance().font);
        valueGroup.addChild(label);

        // Horizontal row for the field + picker button
        LinearLayout inputRow = LinearLayout.horizontal().spacing(HORIZ_GAP);

        EditBox valueField = new EditBox(
                Minecraft.getInstance().font,
                0,
                0,
                valueFieldWidth,
                ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.value"));
        valueField.setMaxLength(Integer.MAX_VALUE);
        valueField.setValue(slot.value != null ? slot.value : "");
        valueField.setHint(this.getValueHint());
        valueField.setResponder(v -> slot.value = v);
        inputRow.addChild(valueField);

        if (hasPicker) {
            Button valueBrowseButton = Button.builder(
                            Component.translatable("screen.radialmenu.editor.select"),
                            b -> openValuePicker(screen, id -> {
                                valueField.setValue(id);
                                slot.value = id;
                            }))
                    .bounds(0, 0, BUTTON_WIDTH, ROW_HEIGHT)
                    .build();
            inputRow.addChild(valueBrowseButton);
        }

        valueGroup.addChild(inputRow);
        container.addChild(valueGroup);
    }

    /**
     * Helper to build the 3 icon widgets (Text Field, Browse Button, Hand Button)
     * using modern layout managers.
     */
    protected void buildIconRow(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        int iconFieldWidth = width - (BUTTON_WIDTH * 2) - (HORIZ_GAP * 2);

        // Group the label and the row of inputs together
        LinearLayout iconGroup = LinearLayout.vertical().spacing(2);

        // 1. Label
        StringWidget label =
                new StringWidget(Component.translatable("screen.radialmenu.editor.icon"), Minecraft.getInstance().font);
        iconGroup.addChild(label);

        // 2. Horizontal row for Field + Buttons
        LinearLayout inputRow = LinearLayout.horizontal().spacing(HORIZ_GAP);

        // Built first so the icon field's responder can enable it for tintable icons
        EditBox colorField = buildIconColorField(slot, width);

        // Icon EditBox
        EditBox iconField = new EditBox(
                Minecraft.getInstance().font,
                0,
                0,
                iconFieldWidth,
                ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.icon"));
        iconField.setMaxLength(Integer.MAX_VALUE);
        iconField.setValue(slot.itemId != null ? slot.itemId : "minecraft:air");
        iconField.setResponder(v -> {
            slot.itemId = v;
            slot.clearCache();
            updateIconFieldState(iconField, colorField, v);
        });
        updateIconFieldState(iconField, colorField, iconField.getValue());
        inputRow.addChild(iconField);

        // Browse Button (setValue runs the responder, which updates the slot)
        Button browseIconButton = Button.builder(
                        Component.translatable("screen.radialmenu.editor.browse"), b -> Minecraft.getInstance()
                                .setScreen(new IconPickerScreen(screen, slot.itemId, iconField::setValue)))
                .bounds(0, 0, BUTTON_WIDTH, ROW_HEIGHT)
                .build();
        inputRow.addChild(browseIconButton);

        // Hand Button
        Button handButton = Button.builder(Component.translatable("screen.radialmenu.editor.hand"), b -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null && client.level != null) {
                        iconField.setValue(getHandItemId(client.player.getMainHandItem(), client));
                    }
                })
                .bounds(0, 0, BUTTON_WIDTH, ROW_HEIGHT)
                .build();
        inputRow.addChild(handButton);

        // 导入图片按钮 (Feature 4: 导入图片, 黑色背景自动转透明)
        Button importButton = Button.builder(
                        Component.translatable("screen.radialmenu.editor.icon_picker.import_image"),
                        b -> {
                            // 使用 TinyFD 原生文件选择对话框 (Minecraft 为 headless, 不能用 AWT)
                            String selected;
                            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                                org.lwjgl.PointerBuffer filters = stack.pointers(
                                        stack.UTF8("*.png"),
                                        stack.UTF8("*.jpg"),
                                        stack.UTF8("*.jpeg"));
                                selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                                        "选择图片",
                                        null,
                                        filters,
                                        "Image Files (*.png, *.jpg, *.jpeg)",
                                        false);
                            }
                            if (selected != null) {
                                java.io.File file = new java.io.File(selected);
                                String itemId = dev.velolib.radialmenu.util.ImportedImageStore.importFromFile(file);
                                if (itemId != null) {
                                    iconField.setValue(itemId);
                                } else {
                                    Minecraft.getInstance().player.displayClientMessage(
                                            Component.translatable("screen.radialmenu.editor.icon_picker.import_image.failed"),
                                            true);
                                }
                            }
                        })
                .bounds(0, 0, BUTTON_WIDTH, ROW_HEIGHT)
                .build();
        inputRow.addChild(importButton);

        // Add the horizontal row into the vertical group, then add the group to the main container
        iconGroup.addChild(inputRow);
        container.addChild(iconGroup);

        // 3. Tint color for text-based icons
        LinearLayout colorGroup = LinearLayout.vertical().spacing(2);
        colorGroup.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.icon_color"), Minecraft.getInstance().font));
        colorGroup.addChild(colorField);
        container.addChild(colorGroup);

        // 4. 介绍 (description) — 单行, 显示在图标下方
        buildDescriptionRow(slot, width, container);
    }

    /**
     * 构建介绍文本输入行 (单行输入框 + 同行颜色框)。
     */
    protected void buildDescriptionRow(RadialSlot slot, int width, LinearLayout container) {
        LinearLayout descGroup = LinearLayout.vertical().spacing(2);
        descGroup.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.description"), Minecraft.getInstance().font));

        LinearLayout descRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int descFieldWidth = width - 110 - HORIZ_GAP;

        EditBox descField = new GrayHintEditBox(
                Minecraft.getInstance().font,
                0, 0, descFieldWidth, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.description"));
        descField.setMaxLength(DESC_MAX_LENGTH);
        descField.setValue(slot.description != null ? slot.description : "");
        descField.setHint(Component.translatable("screen.radialmenu.editor.description.hint"));
        descField.setResponder(v -> slot.description = v);
        descRow.addChild(descField);

        EditBox descColorField = new EditBox(
                Minecraft.getInstance().font,
                0, 0, 110, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.description_color"));
        descColorField.setMaxLength(9);
        descColorField.setValue(slot.descriptionColor != null ? slot.descriptionColor : "");
        descColorField.setHint(Component.translatable("screen.radialmenu.editor.color.hint"));
        descColorField.setResponder(v -> {
            Integer color = RadialSlot.parseColorWithAlpha(v);
            boolean valid = v.isBlank() || color != null;
            descColorField.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR);
            if (valid) {
                slot.descriptionColor = color != null ? RadialSlot.formatColorWithAlpha(color) : null;
            }
        });
        descRow.addChild(descColorField);

        descGroup.addChild(descRow);
        container.addChild(descGroup);
    }

    /**
     * 构建一个通用颜色输入行 (#AARRGGBB, 支持透明度)。
     *
     * @param label     行标签
     * @param value     当前颜色字符串
     * @param responder 值变更回调
     */
    protected LinearLayout buildColorFieldRow(Component label, String value, java.util.function.Consumer<String> responder) {
        LinearLayout colorGroup = LinearLayout.vertical().spacing(2);
        colorGroup.addChild(new StringWidget(label, Minecraft.getInstance().font));

        EditBox colorField = new EditBox(
                Minecraft.getInstance().font,
                0, 0, 110, ROW_HEIGHT,
                label);
        colorField.setMaxLength(9);
        colorField.setValue(value != null ? value : "");
        colorField.setHint(Component.translatable("screen.radialmenu.editor.color.hint"));
        colorField.setResponder(v -> {
            Integer color = RadialSlot.parseColorWithAlpha(v);
            boolean valid = v.isBlank() || color != null;
            colorField.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR);
            if (valid) {
                responder.accept(color != null ? RadialSlot.formatColorWithAlpha(color) : null);
            }
        });
        colorGroup.addChild(colorField);

        return colorGroup;
    }

    private static EditBox buildIconColorField(RadialSlot slot, int width) {
        EditBox colorField = new EditBox(
                Minecraft.getInstance().font,
                0,
                0,
                width,
                ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.icon_color"));
        colorField.setMaxLength(7);
        colorField.setValue(slot.iconColor != null ? slot.iconColor : "");
        colorField.setHint(Component.translatable("screen.radialmenu.editor.icon_color.hint"));
        colorField.setResponder(v -> {
            Integer color = RadialSlot.parseIconColor(v);
            boolean valid = v.isBlank() || color != null;
            colorField.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR);

            // Invalid input leaves the last valid color in place until it's fixed
            if (valid) {
                slot.iconColor = color != null ? RadialSlot.formatIconColor(color) : null;
                slot.clearCache();
            }
        });

        return colorField;
    }

    private static void updateIconFieldState(EditBox iconField, EditBox colorField, String iconId) {
        boolean valid = SlotRenderHelper.isValidIconId(iconId);
        iconField.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR);
        iconField.setTooltip(
                valid ? null : Tooltip.create(Component.translatable("screen.radialmenu.editor.icon.invalid")));

        boolean tintable = SlotRenderHelper.isTintable(iconId);
        colorField.active = tintable;
        colorField.setEditable(tintable);
        colorField.setTooltip(
                tintable ? null : Tooltip.create(Component.translatable("screen.radialmenu.editor.icon_color.disabled")));
    }

    private static String getHandItemId(ItemStack stack, Minecraft client) {
        if (stack.isEmpty()) {
            return "minecraft:air";
        }

        try {
            return EncoderUtils.toGiveCommandString(stack, client.level.registryAccess());
        } catch (Exception e) {
            // Some component data can't be written back out; the plain item is still a usable icon
            RadialClient.LOGGER.warn("Failed to encode held item components, using the plain item id", e);
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
    }

    /**
     * 空内容且未聚焦时把文字色临时切换为灰色, 使 hint 提示文字显示为灰色。
     * 1.21.1 的 EditBox 画 hint 复用 textColor(默认 0xE0E0E0 接近白色), 且没有独立 hint 颜色 API。
     */
    public static class GrayHintEditBox extends EditBox {
        private static final int HINT_COLOR = 0xFFAAAAAA;

        public GrayHintEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean showHint = getValue().isEmpty() && !isFocused();
            if (showHint) setTextColor(HINT_COLOR);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            if (showHint) setTextColor(EditBox.DEFAULT_TEXT_COLOR);
        }
    }
}
