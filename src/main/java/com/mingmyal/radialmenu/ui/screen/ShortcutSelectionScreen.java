package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.api.ShortcutEntry;
import com.mingmyal.radialmenu.api.ShortcutRegistry;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class ShortcutSelectionScreen extends Screen {

    private static final int ENTRY_HEIGHT = 28;

    private final Screen parent;
    private final Consumer<ResourceLocation> onSelect;

    private ShortcutList shortcutList;

    public ShortcutSelectionScreen(Screen parent, Consumer<ResourceLocation> onSelect) {
        super(Component.translatable("screen.radialmenu.shortcut_picker.title"));

        this.parent = parent;
        this.onSelect = onSelect;
    }

    private int getListStartY() {
        return 45;
    }

    private int getListBottom() {
        return height - 40;
    }

    private int getListWidth() {
        return Math.min(350, (int) (width * 0.9));
    }

    private int getListHeight() {
        return Math.max(1, getListBottom() - getListStartY());
    }

    private int getListLeft() {
        return width / 2 - getListWidth() / 2;
    }

    @Override
    protected void init() {
        int listWidth = getListWidth();
        int listLeft = getListLeft();
        int listTop = getListStartY();
        int listHeight = getListHeight();

        EditBox searchField =
                new EditBox(font, listLeft, 15, listWidth, 20, Component.translatable("screen.radialmenu.editor.search"));

        searchField.setHint(Component.translatable("screen.radialmenu.editor.search"));
        searchField.setResponder(this::updateSearch);

        addRenderableWidget(searchField);

        shortcutList = new ShortcutList(Minecraft.getInstance(), listWidth, listHeight, listTop, ENTRY_HEIGHT);
        shortcutList.updateSizeAndPosition(listWidth, listHeight, listTop);
        shortcutList.setX(listLeft);

        addRenderableWidget(shortcutList);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        setInitialFocus(searchField);

        updateSearch("");
    }

    private void updateSearch(String query) {
        if (shortcutList == null) {
            return;
        }

        String q = query.toLowerCase();

        List<ShortcutEntryItem> entries = ShortcutRegistry.getRegisteredShortcuts().entrySet().stream()
                .filter(entry -> {
                    String name = entry.getValue().name().getString().toLowerCase();
                    String id = entry.getKey().toString().toLowerCase();

                    return name.contains(q) || id.contains(q);
                })
                .map(entry -> new ShortcutEntryItem(entry, onSelect, this::onClose))
                .collect(Collectors.toList());

        shortcutList.setEntries(entries);
        shortcutList.setScrollAmount(0.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static class ShortcutList extends ObjectSelectionList<ShortcutEntryItem> {

        private ShortcutList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(330, getWidth() - 20);
        }

        public void setEntries(Collection<ShortcutEntryItem> entries) {
            replaceEntries(entries);
        }
    }

    private static class ShortcutEntryItem extends ObjectSelectionList.Entry<ShortcutEntryItem> {

        private final ResourceLocation id;
        private final ShortcutEntry entry;
        private final Consumer<ResourceLocation> onSelect;
        private final Runnable onClose;

        private ShortcutEntryItem(
                Map.Entry<ResourceLocation, ShortcutEntry> mapEntry,
                Consumer<ResourceLocation> onSelect,
                Runnable onClose) {
            this.id = mapEntry.getKey();
            this.entry = mapEntry.getValue();
            this.onSelect = onSelect;
            this.onClose = onClose;
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
            Minecraft client = Minecraft.getInstance();

            int right = left + rowWidth;
            int top = rowTop;
            int bottom = rowTop + ENTRY_HEIGHT;

            graphics.fill(left, top + 1, right, bottom - 1, hovered ? 0x80FFFFFF : 0x40000000);

            int textY = top + ((bottom - top) - client.font.lineHeight) / 2;

            graphics.drawString(client.font, entry.name(), left + 8, textY, 0xFFFFFFFF);

            String idString = id.toString();
            int idWidth = client.font.width(idString);

            graphics.drawString(client.font, idString, right - idWidth - 8, textY, 0xFFAAAAAA);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onSelect.accept(id);
            onClose.run();

            return true;
        }

        @Override
        public Component getNarration() {
            return entry.name();
        }
    }
}
