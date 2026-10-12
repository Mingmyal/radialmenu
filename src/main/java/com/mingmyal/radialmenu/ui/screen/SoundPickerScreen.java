package com.mingmyal.radialmenu.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 原版音效选择器: 列出 BuiltInRegistries.SOUND_EVENT 中所有音效, 支持搜索与试听。
 * 选中后回调传入音效的 ResourceLocation 字符串。
 */
public class SoundPickerScreen extends Screen {

    private static final int ENTRY_HEIGHT = 24;

    private final Screen parent;
    private final Consumer<String> onSelect;

    private SoundList soundList;

    public SoundPickerScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.translatable("screen.radialmenu.sound_picker.title"));
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

        EditBox searchField = new EditBox(font, listLeft, 15, listWidth, 20,
                Component.translatable("screen.radialmenu.editor.search"));
        searchField.setHint(Component.translatable("screen.radialmenu.editor.search"));
        searchField.setResponder(this::updateSearch);
        addRenderableWidget(searchField);

        soundList = new SoundList(Minecraft.getInstance(), listWidth, getListHeight(), getListStartY(), ENTRY_HEIGHT);
        soundList.updateSizeAndPosition(listWidth, getListHeight(), getListStartY());
        soundList.setX(listLeft);
        addRenderableWidget(soundList);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        setInitialFocus(searchField);
        updateSearch("");
    }

    private static List<String> getAllSoundIds() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation key : BuiltInRegistries.SOUND_EVENT.keySet()) {
            ids.add(key.toString());
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    private void updateSearch(String query) {
        if (soundList == null) return;
        String q = query.toLowerCase();

        List<SoundEntry> entries = getAllSoundIds().stream()
                .filter(id -> id.toLowerCase().contains(q))
                .map(id -> new SoundEntry(id, onSelect, this::onClose))
                .collect(Collectors.toList());

        soundList.setEntries(entries);
        soundList.setScrollAmount(0.0);
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

    private static class SoundList extends ObjectSelectionList<SoundEntry> {
        private SoundList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(330, getWidth() - 20);
        }

        public void setEntries(Collection<SoundEntry> entries) {
            replaceEntries(entries);
        }
    }

    private static class SoundEntry extends ObjectSelectionList.Entry<SoundEntry> {
        private final String id;
        private final Consumer<String> onSelect;
        private final Runnable onClose;

        private SoundEntry(String id, Consumer<String> onSelect, Runnable onClose) {
            this.id = id;
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
            graphics.drawString(client.font, id, left + 8, textY, 0xFFFFFFFF);

            if (hovered) {
                graphics.drawString(client.font, "▶", right - 16, textY, 0xFFAAAAAA);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                onSelect.accept(id);
                onClose.run();
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(id);
        }
    }
}
