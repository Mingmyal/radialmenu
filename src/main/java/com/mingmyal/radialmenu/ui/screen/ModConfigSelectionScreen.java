package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 模组配置界面选择器。
 * <p>
 * 列出所有已加载且"可能带配置界面"的模组, 选中后由
 * {@link ToggleSourceRegistry#openConfigScreen} 交给各提供者尝试打开
 * (ModMenu / Cloth AutoConfig)。
 * <p>
 * 由于 ModMenu 没有无副作用的探测方式, 这里不预先判断某模组是否真的有配置界面;
 * 打开失败时会在界面上给出提示。
 */
public class ModConfigSelectionScreen extends Screen {

    private static final int ENTRY_HEIGHT = 24;

    private final Screen parent;
    private final Consumer<String> onSelect;

    private EditBox searchField;
    private ModList modList;
    private List<ModEntryData> allMods = new ArrayList<>();
    private List<ModEntryData> visibleMods = new ArrayList<>();

    public ModConfigSelectionScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.translatable("screen.radialmenu.modconfig_picker.title"));
        this.parent = parent;
        this.onSelect = onSelect;

        loadMods();
    }

    private void loadMods() {
        List<ModEntryData> list = new ArrayList<>();

        // 已知有配置界面的模组优先(来自各提供者的声明)
        var known = ToggleSourceRegistry.modIdsWithConfigScreens();

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            try {
                String id = mod.getMetadata().getId();
                String name = mod.getMetadata().getName();
                if (id == null || name == null) continue;
                // 排除自身与纯依赖库之外的都列出, 由玩家判断
                list.add(new ModEntryData(id, name, known.contains(id)));
            } catch (Throwable ignored) {
                // 单个模组元数据异常时跳过
            }
        }

        list.sort((a, b) -> {
            if (a.known() != b.known()) return a.known() ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });

        allMods = list;
        visibleMods = list;
    }

    private int getListStartY() {
        return 45;
    }

    private int getListBottom() {
        return height - 40;
    }

    private int getListWidth() {
        return Math.min(420, (int) (width * 0.95));
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

        searchField = new EditBox(font, getListLeft(), 15, listWidth, 20,
                Component.translatable("screen.radialmenu.editor.search"));
        searchField.setHint(Component.translatable("screen.radialmenu.modconfig_picker.search"));
        searchField.setResponder(this::updateSearch);
        addRenderableWidget(searchField);

        modList = new ModList(Minecraft.getInstance(), listWidth, getListHeight(), getListStartY(), ENTRY_HEIGHT);
        modList.updateSizeAndPosition(listWidth, getListHeight(), getListStartY());
        modList.setX(getListLeft());
        addRenderableWidget(modList);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        setInitialFocus(searchField);
        updateSearch("");
    }

    private void updateSearch(String query) {
        if (modList == null) return;

        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);

        visibleMods = allMods.stream()
                .filter(m -> q.isBlank()
                        || m.name().toLowerCase(Locale.ROOT).contains(q)
                        || m.id().toLowerCase(Locale.ROOT).contains(q))
                .toList();

        modList.setEntries(visibleMods.stream()
                .map(m -> new ModEntry(m, onSelect, this::onClose))
                .toList());
        modList.setScrollAmount(0.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        graphics.drawCenteredString(
                font, Component.translatable("screen.radialmenu.modconfig_picker.hint"), width / 2, height - 44, 0xFF808080);

        if (visibleMods.isEmpty()) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.radialmenu.modconfig_picker.empty"),
                    width / 2,
                    height / 2,
                    0xFFAAAAAA);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private record ModEntryData(String id, String name, boolean known) {}

    private static class ModList extends ObjectSelectionList<ModEntry> {

        private ModList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, getWidth() - 20);
        }

        public void setEntries(Collection<ModEntry> entries) {
            replaceEntries(entries);
        }
    }

    private static class ModEntry extends ObjectSelectionList.Entry<ModEntry> {

        private final ModEntryData data;
        private final Consumer<String> onSelect;
        private final Runnable onClose;

        private ModEntry(ModEntryData data, Consumer<String> onSelect, Runnable onClose) {
            this.data = data;
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
            int bottom = rowTop + ENTRY_HEIGHT;

            graphics.fill(left, rowTop + 1, right, bottom - 1, hovered ? 0x80FFFFFF : 0x40000000);

            int textY = rowTop + (ENTRY_HEIGHT - client.font.lineHeight) / 2;

            // 已知有配置界面的模组用绿色圆点标记
            if (data.known()) {
                graphics.fill(left + 4, rowTop + 9, left + 10, rowTop + 15, 0xFF55FF55);
            }

            graphics.drawString(client.font, data.name(), left + 16, textY, 0xFFFFFFFF);

            String idText = data.id();
            int idWidth = client.font.width(idText);
            int idX = right - idWidth - 8;
            if (idX > left + 16 + client.font.width(data.name()) + 8) {
                graphics.drawString(client.font, idText, idX, textY, 0xFF808080);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onSelect.accept(data.id());
            onClose.run();
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(data.name());
        }
    }
}
