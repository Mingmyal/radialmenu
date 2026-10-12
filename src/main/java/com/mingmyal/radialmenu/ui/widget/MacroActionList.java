package com.mingmyal.radialmenu.ui.widget;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotMode;
import com.mingmyal.radialmenu.api.SlotModeRegistry;
import com.mingmyal.radialmenu.mode.DelaySlotMode;
import com.mingmyal.radialmenu.mode.MacroSlotMode;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

public class MacroActionList extends ContainerObjectSelectionList<MacroActionList.Row> {

    public static final int ROW_HEIGHT = 24;

    private static final int HANDLE_WIDTH = 28;
    private static final int TYPE_WIDTH = 130;
    private static final int PICKER_WIDTH = 20;
    private static final int DELETE_WIDTH = 20;
    private static final int GAP = 4;
    private static final int WIDGET_HEIGHT = 20;

    private static final int DASH_LENGTH = 3;
    private static final int DASH_GAP = 2;

    private static final int AUTO_SCROLL_EDGE = 16;
    private static final double AUTO_SCROLL_SPEED = ROW_HEIGHT * 10.0; // pixels per second

    /**
     * The parent screen renders the open dropdown menu on top of the list and routes input to it.
     */
    public interface DropdownHost {
        void onDropdownOpened(DropdownButtonWidget<SlotMode> button);

        void onDropdownClosed(DropdownButtonWidget<SlotMode> button);

        void closeDropdown();
    }

    private final Screen screen;
    private final RadialSlot slot;
    private final List<SlotMode> actionModes;
    private final DropdownHost dropdownHost;
    private final AddEntry addRow;

    private ActionEntry draggedEntry;
    private double dragGrabOffset;
    private double dragMouseY;
    private long lastFrameNanos;

    public MacroActionList(
            Minecraft minecraft,
            int width,
            int height,
            int y,
            Screen screen,
            RadialSlot slot,
            DropdownHost dropdownHost) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        this.screen = screen;
        this.slot = slot;
        this.dropdownHost = dropdownHost;
        this.actionModes = SlotModeRegistry.getRegisteredModes().values().stream()
                .filter(SlotMode::isAvailable)
                .filter(SlotMode::isMacroAction)
                .toList();

        for (RadialSlot.Macro macro : slot.macros) {
            this.addEntry(new ActionEntry(macro));
        }

        this.addRow = new AddEntry();
        this.addEntry(this.addRow);
    }

    @Override
    public int getRowWidth() {
        return Math.min(360, this.getWidth() - 40);
    }

    public int getTypeColumnX() {
        return this.getRowLeft() + Row.CONTENT_PADDING + HANDLE_WIDTH + GAP;
    }

    public int getValueColumnX() {
        return this.getTypeColumnX() + TYPE_WIDTH + GAP;
    }

    public boolean canAddAction() {
        return this.slot.macros.size() < MacroSlotMode.MAX_ACTIONS;
    }

    public int getActionCount() {
        return this.slot.macros.size();
    }

    public void addAction() {
        if (!this.canAddAction()) return;

        SlotMode mode = this.actionModes.stream()
                .filter(m -> !m.isMacroOnly())
                .findFirst()
                .orElse(this.actionModes.getFirst());
        RadialSlot.Macro macro = new RadialSlot.Macro(mode, defaultValue(mode));

        this.slot.macros.add(macro);

        // Insert above the "Add Action" row so it stays last
        this.removeEntry(this.addRow);
        this.addEntry(new ActionEntry(macro));
        this.addEntry(this.addRow);
        this.setScrollAmount(this.getMaxScroll());
    }

    private void removeAction(ActionEntry entry) {
        int index = this.children().indexOf(entry);
        if (index < 0) return;

        this.slot.macros.remove(index);
        this.removeEntry(entry);
        this.clampScrollAmount();
    }

    private void moveAction(int from, int to) {
        Collections.swap(this.slot.macros, from, to);
        java.util.List<Row> entries = this.children();
        Row temp = entries.get(from);
        entries.set(from, entries.get(to));
        entries.set(to, temp);
    }

    private static String defaultValue(SlotMode mode) {
        return mode instanceof DelaySlotMode ? DelaySlotMode.DEFAULT_SECONDS : "";
    }

    // --- Dragging ---

    private void startDrag(ActionEntry entry, double mouseY) {
        this.dropdownHost.closeDropdown();
        this.setFocused(null);

        this.draggedEntry = entry;
        this.dragGrabOffset = mouseY - entry.getY();
        this.dragMouseY = mouseY;
        this.lastFrameNanos = System.nanoTime();
    }

    /**
     * The Y position of the floating dragged row, kept inside the visible list area.
     */
    private int getDraggedRowY() {
        return (int) Mth.clamp(this.dragMouseY - this.dragGrabOffset, this.getY(), this.getBottom() - ROW_HEIGHT);
    }

    /**
     * Moves the dragged entry to the row underneath the center of the floating row.
     */
    private void updateDragTarget() {
        int from = this.children().indexOf(this.draggedEntry);
        if (from < 0) return;

        double center = this.getDraggedRowY() + ROW_HEIGHT / 2.0;
        int target = Mth.clamp(
                (int) Math.floor((center - this.getRowTop(0)) / ROW_HEIGHT),
                0,
                this.getActionCount() - 1); // Never below the "Add Action" row

        while (from < target) {
            this.moveAction(from, from + 1);
            from++;
        }
        while (from > target) {
            this.moveAction(from, from - 1);
            from--;
        }
    }

    private void autoScrollWhileDragging() {
        long now = System.nanoTime();
        double dt = Math.min((now - this.lastFrameNanos) / 1.0e9, 0.1);
        this.lastFrameNanos = now;

        double step = AUTO_SCROLL_SPEED * dt;
        if (this.dragMouseY < this.getY() + AUTO_SCROLL_EDGE) {
            this.setScrollAmount(this.getScrollAmount() - step);
        } else if (this.dragMouseY > this.getBottom() - AUTO_SCROLL_EDGE) {
            this.setScrollAmount(this.getScrollAmount() + step);
        }

        // Also covers mouse wheel scrolling mid-drag
        this.updateDragTarget();
    }

    private void endDrag() {
        this.draggedEntry = null;
        this.clampScrollAmount();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && this.isMouseOver(mouseX, mouseY)) {
            if (this.getEntryAtPosition(mouseX, mouseY) instanceof ActionEntry entry
                    && entry.isOverHandle(mouseX, mouseY)) {
                this.startDrag(entry, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggedEntry != null) {
            this.dragMouseY = mouseY;
            this.updateDragTarget();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggedEntry != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
            this.endDrag();
            super.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // --- Rendering ---

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.draggedEntry != null) {
            // Safety net in case the release event never reached the list (e.g. window lost focus).
            // 1.21.1: MouseHandler#isLeftPressed is only updated while no screen is open, so query GLFW directly.
            // FIXME(1.21.1): SDL mouse state replaced with GLFW per-button query
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                this.endDrag();
            } else {
                this.autoScrollWhileDragging();
            }
        }

        super.renderWidget(graphics, mouseX, mouseY, delta);

        // 1.21.1 没有 graphics.requestCursor(CursorTypes.RESIZE_NS) 的等价 API,
        // 因此拖动时不再切换鼠标指针样式。
    }

    @Override
    protected void renderListItems(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.draggedEntry == null) {
            super.renderListItems(graphics, mouseX, mouseY, delta);
            return;
        }

        int left = this.getRowLeft();
        int rowWidth = this.getRowWidth();
        int right = left + rowWidth;

        // Suppress hover effects on the other rows while dragging
        for (int i = 0; i < this.children().size(); i++) {
            Row row = this.children().get(i);
            int rowTop = this.getRowTop(i);

            if (row == this.draggedEntry) {
                this.draggedEntry.extractPlaceholder(graphics, left, rowTop, right, rowTop + ROW_HEIGHT);
            } else if (rowTop + row.getHeight() >= this.getY() && rowTop <= this.getBottom()) {
                row.render(graphics, i, rowTop, left, rowWidth, ROW_HEIGHT, -1, -1, false, delta);
            }
        }

        // Draw the dragged row floating under the cursor, above the other rows.
        // graphics.nextStratum() dropped (no direct 1.21.1 equivalent)
        int dragIndex = this.children().indexOf(this.draggedEntry);
        this.draggedEntry.render(graphics, dragIndex, this.getDraggedRowY(), left, rowWidth, ROW_HEIGHT, -1, -1, true, delta);
    }

    // --- Rows ---

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        private static final int CONTENT_PADDING = 2;

        // 1.21.1 entries are not widgets; cache the row bounds from render() for hit-testing and drag rendering.
        protected int cachedLeft;
        protected int cachedTop;

        public int getY() {
            return cachedTop;
        }

        public int getHeight() {
            return MacroActionList.ROW_HEIGHT;
        }
    }

    /**
     * The last row of the list. A full-width dashed button that appends a new action.
     */
    public class AddEntry extends Row {

        private final AddActionButton button = new AddActionButton();

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int rowTop,
                int left,
                int rowWidth,
                int rowHeight,
                int mouseX,
                int mouseY,
                boolean hovered,
                float delta) {
            int right = left + rowWidth;
            this.cachedLeft = left;
            this.cachedTop = rowTop;

            this.button.active = MacroActionList.this.canAddAction();
            this.button.setPosition(left + Row.CONTENT_PADDING, rowTop);
            this.button.setSize(right - left - Row.CONTENT_PADDING * 2, MacroActionList.ROW_HEIGHT);
            this.button.render(graphics, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.button);
        }
    }

    private class AddActionButton extends AbstractButton {

        private AddActionButton() {
            super(0, 0, 0, WIDGET_HEIGHT, Component.translatable("screen.radialmenu.macro.add"));
        }

        @Override
        public void onPress() {
            MacroActionList.this.addAction();
        }

        // FIXME(1.21.1): 26.3 AbstractButton.extractContents -> renderWidget (verify name)
        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            Font font = MacroActionList.this.minecraft.font;
            boolean enabled = this.active;
            boolean highlighted = enabled && this.isHoveredOrFocused();

            int left = this.getX();
            int top = this.getY();
            int right = this.getRight();
            int bottom = this.getBottom();

            graphics.fill(left, top, right, bottom, highlighted ? 0x30FFFFFF : 0x10FFFFFF);
            drawDashedBorder(
                    graphics, left, top, right, bottom, !enabled ? 0x30FFFFFF : highlighted ? 0xFFFFFFFF : 0x70FFFFFF);

            Component label = enabled
                    ? Component.literal("+  ").append(this.getMessage())
                    : Component.translatable("screen.radialmenu.macro.limit", MacroSlotMode.MAX_ACTIONS);
            int textColor = !enabled ? 0xFF707070 : highlighted ? 0xFFFFFFFF : 0xFFA0A0A0;
            graphics.drawCenteredString(font, label, (left + right) / 2, top + (this.getHeight() - 8) / 2, textColor);

            if (!enabled && this.isHovered()) {
                // FIXME(1.21.1): graphics.requestCursor(CursorTypes.NOT_ALLOWED) — no direct 1.21.1 equivalent
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    private static void drawDashedBorder(
            GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        for (int x = left; x < right; x += DASH_LENGTH + DASH_GAP) {
            int end = Math.min(x + DASH_LENGTH, right);
            graphics.fill(x, top, end, top + 1, color);
            graphics.fill(x, bottom - 1, end, bottom, color);
        }
        for (int y = top; y < bottom; y += DASH_LENGTH + DASH_GAP) {
            int end = Math.min(y + DASH_LENGTH, bottom);
            graphics.fill(left, y, left + 1, end, color);
            graphics.fill(right - 1, y, right, end, color);
        }
    }

    public class ActionEntry extends Row {

        private final DropdownButtonWidget<SlotMode> typeDropdown;
        private final EditBox valueBox;
        private final Button pickerButton;
        private final Button deleteButton;

        private SlotMode mode;

        private ActionEntry(RadialSlot.Macro macro) {
            Font font = MacroActionList.this.minecraft.font;
            this.mode = macro.mode();

            this.valueBox =
                    new EditBox(
                            font,
                            0,
                            0,
                            100,
                            WIDGET_HEIGHT,
                            Component.translatable("screen.radialmenu.macro.column.value")) {
                        @Override
                        public void insertText(String input) {
                            if (!ActionEntry.this.isDelay()) {
                                super.insertText(input);
                                return;
                            }

                            // Delays only accept seconds with up to two decimals. Invalid results are
                            // reverted by writeValue; keep the cursor where it was when that happens.
                            String previousValue = this.getValue();
                            int previousCursor = this.getCursorPosition();

                            super.insertText(DelaySlotMode.filterChars(input));

                            if (this.getValue().equals(previousValue)) {
                                this.setCursorPosition(previousCursor);
                                this.setHighlightPos(previousCursor);
                            }
                        }
                    };
            this.applyValueConstraints();
            this.valueBox.setValue(macro.value() != null ? macro.value() : "");
            this.valueBox.setResponder(this::writeValue);

            this.typeDropdown =
                    new DropdownButtonWidget<>(
                            0,
                            0,
                            TYPE_WIDTH,
                            WIDGET_HEIGHT,
                            MacroActionList.this.actionModes,
                            this.mode,
                            option -> fitToWidth(font, option.getTranslatedName(), TYPE_WIDTH - 18),
                            this::changeMode,
                            b -> MacroActionList.this.dropdownHost.onDropdownOpened(this.getTypeDropdown())) {
                        @Override
                        public void closeMenu() {
                            boolean wasOpen = this.isMenuOpen();
                            super.closeMenu();
                            if (wasOpen) {
                                MacroActionList.this.dropdownHost.onDropdownClosed(this);
                            }
                        }
                    };

            this.pickerButton = Button.builder(Component.literal("..."), b -> this.openPicker())
                    .size(PICKER_WIDTH, WIDGET_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("screen.radialmenu.editor.select")))
                    .build();

            this.deleteButton = Button.builder(Component.literal("✕"), b -> removeAction(this))
                    .size(DELETE_WIDTH, WIDGET_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("screen.radialmenu.macro.remove")))
                    .build();
        }

        private DropdownButtonWidget<SlotMode> getTypeDropdown() {
            return this.typeDropdown;
        }

        private boolean isDelay() {
            return this.mode instanceof DelaySlotMode;
        }

        private boolean hasPicker() {
            return this.mode != null && this.mode.hasValuePicker();
        }

        private void openPicker() {
            if (!this.hasPicker()) return;

            MacroActionList.this.dropdownHost.closeDropdown();
            // The picker returns to the macro editor, which rebuilds its list from the updated slot data
            this.mode.openValuePicker(MacroActionList.this.screen, this.valueBox::setValue);
        }

        private void applyValueConstraints() {
            this.valueBox.setMaxLength(this.isDelay() ? DelaySlotMode.MAX_LENGTH : Integer.MAX_VALUE);
            this.valueBox.setHint(
                    this.mode != null
                            ? this.mode.getValueHint()
                            : Component.translatable("screen.radialmenu.editor.value"));
        }

        private void changeMode(SlotMode newMode) {
            boolean wasDelay = this.isDelay();
            String value = this.valueBox.getValue();

            this.mode = newMode;
            if (this.isDelay()) {
                value = DelaySlotMode.normalize(value);
            } else if (wasDelay) {
                value = ""; // A number of seconds is meaningless for other action types
            }

            this.applyValueConstraints();
            this.valueBox.setValue(value); // Triggers writeValue
        }

        private void writeValue(String value) {
            int index = MacroActionList.this.children().indexOf(this);

            // Deleting characters bypasses insertText (e.g. removing the '.' from "1234.56"), so restore the last valid
            // delay
            if (this.isDelay() && !DelaySlotMode.isValid(value) && index >= 0) {
                String lastValid = MacroActionList.this.slot.macros.get(index).value();
                this.valueBox.setValue(DelaySlotMode.isValid(lastValid) ? lastValid : DelaySlotMode.DEFAULT_SECONDS);
                return;
            }

            if (index >= 0) {
                MacroActionList.this.slot.macros.set(index, new RadialSlot.Macro(this.mode, value));
            }
        }

        private boolean isOverHandle(double mouseX, double mouseY) {
            return this.isMouseOver(mouseX, mouseY)
                    && mouseX >= this.cachedLeft
                    && mouseX < this.cachedLeft + HANDLE_WIDTH;
        }

        private void extractPlaceholder(GuiGraphics graphics, int left, int top, int right, int bottom) {
            graphics.fill(left, top, right, bottom, 0x40FFFFFF);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0x60000000);
        }

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int rowTop,
                int left,
                int rowWidth,
                int rowHeight,
                int mouseX,
                int mouseY,
                boolean hovered,
                float delta) {
            int right = left + rowWidth;
            Font font = MacroActionList.this.minecraft.font;
            boolean dragged = this == MacroActionList.this.draggedEntry;

            this.cachedLeft = left;
            this.cachedTop = rowTop;

            int top = rowTop;
            int bottom = rowTop + MacroActionList.ROW_HEIGHT;

            // Row background
            int background = dragged ? 0xE0202020 : (hovered ? 0x30FFFFFF : 0x18FFFFFF);
            graphics.fill(left, top, right, bottom, background);
            if (dragged) {
                graphics.fill(left, top, right, top + 1, 0xFFFFFFFF);
                graphics.fill(left, bottom - 1, right, bottom, 0xFFFFFFFF);
            }

            // Drag handle: grip lines + step number
            boolean handleActive = dragged || this.isOverHandle(mouseX, mouseY);
            int gripColor = handleActive ? 0xFFFFFFFF : 0xFF808080;
            int centerY = top + (bottom - top) / 2;
            for (int line = -3; line <= 3; line += 3) {
                graphics.fill(left + 3, centerY + line, left + 11, centerY + line + 1, gripColor);
            }

            String number = String.valueOf(MacroActionList.this.children().indexOf(this) + 1);
            graphics.drawString(font, number, left + 14, centerY - 4, 0xFFA0A0A0);

            // Widgets
            boolean hasPicker = this.hasPicker();
            int typeX = left + HANDLE_WIDTH + GAP;
            int deleteX = right - DELETE_WIDTH;
            int pickerX = deleteX - GAP - PICKER_WIDTH;
            int valueX = typeX + TYPE_WIDTH + GAP;
            int valueRight = (hasPicker ? pickerX : deleteX) - GAP;

            this.typeDropdown.setPosition(typeX, top);
            this.valueBox.setPosition(valueX, top);
            this.valueBox.setWidth(Math.max(20, valueRight - valueX));
            this.pickerButton.setPosition(pickerX, top);
            this.deleteButton.setPosition(deleteX, top);

            this.typeDropdown.render(graphics, mouseX, mouseY, delta);
            this.valueBox.render(graphics, mouseX, mouseY, delta);
            if (hasPicker) {
                this.pickerButton.render(graphics, mouseX, mouseY, delta);
            }
            this.deleteButton.render(graphics, mouseX, mouseY, delta);

            // Unit suffix for delays
            if (this.isDelay() && !this.valueBox.getValue().isEmpty()) {
                Component suffix = Component.translatable("screen.radialmenu.macro.seconds");
                int suffixX = this.valueBox.getRight() - font.width(suffix) - 5;
                graphics.drawString(font, suffix, suffixX, centerY - 4, 0xFF808080);
            }
        }

        private List<AbstractWidget> widgets() {
            return this.hasPicker()
                    ? List.of(this.typeDropdown, this.valueBox, this.pickerButton, this.deleteButton)
                    : List.of(this.typeDropdown, this.valueBox, this.deleteButton);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets();
        }
    }

    private static Component fitToWidth(Font font, Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;

        String ellipsis = "...";
        return Component.literal(font.plainSubstrByWidth(text.getString(), maxWidth - font.width(ellipsis)) + ellipsis);
    }
}
