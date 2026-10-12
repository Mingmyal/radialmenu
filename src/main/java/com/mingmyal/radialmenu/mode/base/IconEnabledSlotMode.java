package com.mingmyal.radialmenu.mode.base;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotMode;
import com.mingmyal.radialmenu.render.SlotRenderHelper;
import com.mingmyal.radialmenu.ui.screen.iconpicker.IconPickerScreen;
import com.mingmyal.radialmenu.util.ImportedImageStore;

import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public abstract class IconEnabledSlotMode implements SlotMode {

    private static final int HORIZ_GAP = 5;
    private static final int BUTTON_WIDTH = 55;
    private static final int ROW_HEIGHT = 20;
    private static final int COLOR_FIELD_WIDTH = 110;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final int DESC_MAX_LENGTH = 200;

    @Override
    public void buildIconRowOnly(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildIconRow(screen, slot, width, container);
    }

    /**
     * 默认编辑控件: 动作值行 + 图标设置行。
     * <p>
     * 绝大多数动作模式都只需要这两行, 因此作为基类默认实现;
     * 需要额外控件(宏、子菜单)或只要图标(空槽位)的模式各自覆写。
     */
    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        buildValueRow(screen, slot, width, container);
        buildIconRow(screen, slot, width, container);
    }

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
     * Helper to build the icon widgets (Text Field, Browse Button, Import Image Button)
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

        // 导入图片按钮 (Feature 4: 导入图片, 黑色背景自动转透明)
        Button importButton = Button.builder(
                        Component.translatable("screen.radialmenu.editor.icon_picker.import_image"),
                        b -> {
                            // 使用 TinyFD 原生文件选择对话框 (Minecraft 为 headless, 不能用 AWT)
                            String selected;
                            try (MemoryStack stack = MemoryStack.stackPush()) {
                                PointerBuffer filters = stack.pointers(
                                        stack.UTF8("*.png"),
                                        stack.UTF8("*.jpg"),
                                        stack.UTF8("*.jpeg"));
                                selected = TinyFileDialogs.tinyfd_openFileDialog(
                                        "选择图片",
                                        null,
                                        filters,
                                        "Image Files (*.png, *.jpg, *.jpeg)",
                                        false);
                            }
                            if (selected != null) {
                                File file = new File(selected);
                                String itemId = ImportedImageStore.importFromFile(file);
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

        descRow.addChild(buildArgbColorField(
                Component.translatable("screen.radialmenu.editor.description_color"),
                slot.descriptionColor,
                v -> slot.descriptionColor = v));

        descGroup.addChild(descRow);
        container.addChild(descGroup);
    }

    /**
     * 构建 #AARRGGBB 颜色输入框(支持透明度)。
     * <p>
     * 输入非法时把文字标红, 并保留上一个有效值不变; 留空表示"使用默认色", 回调收到 null。
     *
     * @param label   输入框标签(同时用作无障碍描述)
     * @param value   当前颜色字符串, 可为 null
     * @param onValid 值合法时的回调, 收到规范化后的颜色字符串或 null
     */
    public static EditBox buildArgbColorField(Component label, String value, Consumer<String> onValid) {
        EditBox colorField = new EditBox(
                Minecraft.getInstance().font, 0, 0, COLOR_FIELD_WIDTH, ROW_HEIGHT, label);
        colorField.setMaxLength(9);
        colorField.setValue(value != null ? value : "");
        colorField.setHint(Component.translatable("screen.radialmenu.editor.color.hint"));
        colorField.setResponder(v -> {
            Integer color = RadialSlot.parseColorWithAlpha(v);
            boolean valid = v.isBlank() || color != null;
            colorField.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR);
            if (valid) {
                onValid.accept(color != null ? RadialSlot.formatColorWithAlpha(color) : null);
            }
        });
        return colorField;
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
