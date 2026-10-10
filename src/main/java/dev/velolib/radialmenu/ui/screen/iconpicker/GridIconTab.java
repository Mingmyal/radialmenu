package dev.velolib.radialmenu.ui.screen.iconpicker;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public abstract class GridIconTab<T> implements IconTab {
    private static final int SLOT_SIZE = 20;
    private static final int ROW_HEIGHT = 24;

    private static final int CURRENT_OUTLINE_COLOR = 0xFFFFAA00;
    private static final int CURSOR_OUTLINE_COLOR = 0xFFFFFFFF;

    protected final String currentId;
    protected final Consumer<String> onSelect;
    protected final Runnable onClose;

    private IconGridList listWidget;
    private List<T> currentResults = new ArrayList<>();
    private int columns = 1;
    private String query = "";

    // Keyboard cursor into currentResults, or -1 while focus is still on the search bar
    private int cursor = -1;

    public GridIconTab(String currentId, Consumer<String> onSelect, Runnable onClose) {
        this.currentId = currentId;
        this.onSelect = onSelect;
        this.onClose = onClose;
    }

    /**
     * Returns matching icons for a lowercase, trimmed query, best matches first.
     */
    protected abstract List<T> search(String query);

    protected abstract void renderIcon(
            GuiGraphics graphics, int x, int y, int mouseX, int mouseY, T item, boolean hovered);

    protected abstract String getIconId(T item);

    protected abstract Component getItemNarration(T item);

    protected Component getEmptyMessage() {
        return Component.translatable("screen.radialmenu.editor.icon_picker.no_results");
    }

    /**
     * Called after an icon is favorited or unfavorited from this tab.
     */
    protected void onFavoritesChanged() {}

    protected final int getSlotSize() {
        return SLOT_SIZE;
    }

    private void selectIcon(T item) {
        onSelect.accept(getIconId(item));
        onClose.run();
    }

    // FIXME(1.21.1): GuiGraphics.outline replaced with manual border fills
    private static void drawOutline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void setup(int width, int height, Consumer<Renderable> addRenderable, Consumer<GuiEventListener> addWidget) {
        int listWidth = Math.min(350, (int) (width * 0.9));
        int top = 65;
        int bottom = height - 40;
        int left = width / 2 - listWidth / 2;

        listWidget = new IconGridList(Minecraft.getInstance(), listWidth, Math.max(1, bottom - top), top, ROW_HEIGHT);

        listWidget.updateSizeAndPosition(listWidth, Math.max(1, bottom - top), top);
        listWidget.setX(left);

        addRenderable.accept(listWidget);
        addWidget.accept(listWidget);

        updateSearch("");
        scrollToCurrent();
    }

    private void scrollToCurrent() {
        if (currentId == null) return;

        for (int i = 0; i < currentResults.size(); i++) {
            if (currentId.equals(getIconId(currentResults.get(i)))) {
                listWidget.centerOnRow(i / columns);
                return;
            }
        }
    }

    @Override
    public void updateSearch(String query) {
        this.query = query.trim().toLowerCase();
        currentResults = search(this.query);
        cursor = -1;
        rebuildRows();
    }

    protected final String getQuery() {
        return query;
    }

    /**
     * Re-runs the current search while keeping the scroll position, e.g. after the underlying list changed.
     */
    protected final void refreshResults() {
        double scroll = listWidget != null ? listWidget.getScrollAmount() : 0.0;
        updateSearch(query);
        if (listWidget != null) listWidget.setScrollAmount(scroll);
    }

    private void rebuildRows() {
        if (listWidget == null) return;

        int usableWidth = listWidget.getRowWidth();
        columns = Math.max(1, usableWidth / SLOT_SIZE);

        List<IconGridEntry> rows = new ArrayList<>();

        for (int start = 0; start < currentResults.size(); start += columns) {
            int end = Math.min(start + columns, currentResults.size());

            rows.add(new IconGridEntry(start, new ArrayList<>(currentResults.subList(start, end))));
        }

        listWidget.setEntries(rows);
        listWidget.setScrollAmount(0.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        /* Managed by listWidget */
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false; /* Managed by listWidget */
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (currentResults.isEmpty() || listWidget == null) {
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            // Without a query or cursor, Enter would just grab whatever happens to be listed first
            if (cursor < 0 && query.isEmpty()) {
                return false;
            }

            selectIcon(currentResults.get(Math.max(cursor, 0)));
            return true;
        }

        int last = currentResults.size() - 1;
        int next;

        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            next = cursor < 0 ? 0 : Math.min(cursor + columns, last);
        } else if (keyCode == GLFW.GLFW_KEY_UP && cursor >= 0) {
            // Moving up past the first row hands focus back to the search bar
            next = cursor - columns;
        } else if (keyCode == GLFW.GLFW_KEY_RIGHT && cursor >= 0) {
            next = Math.min(cursor + 1, last);
        } else if (keyCode == GLFW.GLFW_KEY_LEFT && cursor >= 0) {
            next = Math.max(cursor - 1, 0);
        } else {
            return false;
        }

        cursor = Math.max(next, -1);
        if (cursor >= 0) {
            listWidget.scrollToRow(cursor / columns);
        }
        return true;
    }

    @Override
    public boolean showSearchBar() {
        return true;
    }

    private class IconGridList extends ObjectSelectionList<IconGridEntry> {

        public IconGridList(Minecraft mc, int w, int h, int y, int rowHeight) {
            super(mc, w, h, y, rowHeight);
        }

        @Override
        public int getRowWidth() {
            int availableWidth = Math.min(330, getWidth() - 20);
            int columns = Math.max(1, availableWidth / SLOT_SIZE);

            return columns * SLOT_SIZE;
        }

        public void setEntries(java.util.Collection<IconGridEntry> entries) {
            replaceEntries(entries);
        }

        private void centerOnRow(int row) {
            if (row >= 0 && row < children().size()) {
                centerScrollOn(children().get(row));
            }
        }

        private void scrollToRow(int row) {
            if (row >= 0 && row < children().size()) {
                centerScrollOn(children().get(row));
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.renderWidget(graphics, mouseX, mouseY, delta);

            Font font = Minecraft.getInstance().font;

            if (children().isEmpty()) {
                Component empty = getEmptyMessage();
                graphics.drawString(font, empty, getX() + (getWidth() - font.width(empty)) / 2, getY() + 12, 0xFFAAAAAA);
            }
        }
    }

    private class IconGridEntry extends ObjectSelectionList.Entry<IconGridEntry> {

        private final int startIndex;
        private final List<T> items;

        // Cached row position from render(); used by mouseClicked which 1.21.1 calls without row bounds.
        private int cachedLeft;
        private int cachedTop;

        private IconGridEntry(int startIndex, List<T> items) {
            this.startIndex = startIndex;
            this.items = items;
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
            int top = rowTop;

            cachedLeft = left;
            cachedTop = rowTop;

            int verticalOffset = Math.max(0, (ROW_HEIGHT - SLOT_SIZE) / 2);

            for (int i = 0; i < items.size(); i++) {
                int x = left + i * SLOT_SIZE;
                int y = top + verticalOffset;
                T item = items.get(i);
                String iconId = getIconId(item);

                boolean slotHovered = mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE;
                boolean atCursor = startIndex + i == cursor;

                if (slotHovered || atCursor) {
                    graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0x40FFFFFF);
                }

                renderIcon(graphics, x, y, mouseX, mouseY, item, slotHovered);

                if (iconId.equals(currentId)) {
                    drawOutline(graphics, x, y, SLOT_SIZE, SLOT_SIZE, CURRENT_OUTLINE_COLOR);
                } else if (atCursor) {
                    drawOutline(graphics, x, y, SLOT_SIZE, SLOT_SIZE, CURSOR_OUTLINE_COLOR);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            int verticalOffset = Math.max(0, (ROW_HEIGHT - SLOT_SIZE) / 2);

            for (int i = 0; i < items.size(); i++) {
                int x = cachedLeft + i * SLOT_SIZE;
                int y = cachedTop + verticalOffset;

                if (mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE) {
                    selectIcon(items.get(i));
                    return true;
                }
            }

            return false;
        }

        @Override
        public Component getNarration() {
            return items.isEmpty()
                    ? Component.translatable("screen.radialmenu.editor.icon_picker.empty_row")
                    : getItemNarration(items.getFirst());
        }
    }
}
