package dev.velolib.radialmenu.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.velolib.radialmenu.RadialClient;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class KeybindPickerScreen extends Screen {

    private static final int ENTRY_HEIGHT = 28;

    private final Screen parent;
    private final Consumer<String> onSelect;

    private KeybindList keybindList;

    public KeybindPickerScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.translatable("screen.radialmenu.keybind_picker.title"));

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

        keybindList = new KeybindList(Minecraft.getInstance(), listWidth, listHeight, listTop, ENTRY_HEIGHT);

        keybindList.updateSizeAndPosition(listWidth, listHeight, listTop);
        keybindList.setX(listLeft);

        addRenderableWidget(keybindList);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        setInitialFocus(searchField);

        updateSearch("");
    }

    private void updateSearch(String query) {
        if (keybindList == null) {
            return;
        }

        String q = query.toLowerCase();

        List<KeybindEntry> entries = Arrays.stream(minecraft.options.keyMappings)
                .filter(key -> {
                    // BLACKLIST CHECK: Skip our internal radial keys
                    if (RadialClient.isRadialInternalKey(key)) {
                        return false;
                    }

                    String actionName =
                            Component.translatable(key.getName()).getString().toLowerCase();
                    String category = Component.translatable(key.getCategory()).getString().toLowerCase();

                    return actionName.contains(q) || category.contains(q);
                })
                .map(key -> new KeybindEntry(key, onSelect, this::onClose))
                .collect(Collectors.toList());

        keybindList.setEntries(entries);
        keybindList.setScrollAmount(0.0);
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

    private static class KeybindList extends ObjectSelectionList<KeybindEntry> {

        private KeybindList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(330, getWidth() - 20);
        }

        public void setEntries(java.util.Collection<KeybindEntry> entries) {
            replaceEntries(entries);
        }
    }

    private static class KeybindEntry extends ObjectSelectionList.Entry<KeybindEntry> {

        private final KeyMapping key;
        private final Consumer<String> onSelect;
        private final Runnable onClose;

        private KeybindEntry(KeyMapping key, Consumer<String> onSelect, Runnable onClose) {
            this.key = key;
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

            Component display = Component.translatable(key.getName())
                    .append(" [")
                    .append(key.getTranslatedKeyMessage())
                    .append("]");
            int textY = top + ((bottom - top) - client.font.lineHeight) / 2;

            graphics.drawString(client.font, display, left + 8, textY, 0xFFFFFFFF);

            Component category = Component.translatable(key.getCategory());
            int categoryWidth = client.font.width(category);

            graphics.drawString(client.font, category, right - categoryWidth - 8, textY, 0xFFAAAAAA);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onSelect.accept(key.getName());
            onClose.run();

            return true;
        }

        @Override
        public Component getNarration() {
            return Component.translatable(key.getName());
        }
    }
}
