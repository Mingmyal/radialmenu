package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.integration.MalilibIntegration.MalilibAction;
import com.mingmyal.radialmenu.integration.MalilibIntegration;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public class MalilibSelectionScreen extends Screen {

    private static final int ENTRY_HEIGHT = 28;

    private final Screen parent;
    private final Consumer<MalilibAction> onSelect;
    private final Map<String, List<MalilibAction>> actionsByMod;

    private final List<Button> tabButtons = new ArrayList<>();

    private String currentTab;
    private List<MalilibAction> currentActions = new ArrayList<>();

    private EditBox searchField;
    private MalilibList malilibList;

    public MalilibSelectionScreen(Screen parent, Consumer<MalilibAction> onSelect) {
        super(Component.translatable("screen.radialmenu.malilib_picker.title"));

        this.parent = parent;
        this.onSelect = onSelect;

        List<MalilibAction> actions = MalilibIntegration.getAllActions();

        this.actionsByMod = actions.stream()
                .collect(Collectors.groupingBy(MalilibAction::modName, TreeMap::new, Collectors.toList()));

        if (!actionsByMod.isEmpty()) {
            currentTab = actionsByMod.keySet().iterator().next();
        }
    }

    private int getListStartY() {
        return 65;
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
        tabButtons.clear();

        if (actionsByMod.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                    .bounds(width / 2 - 100, height - 28, 200, 20)
                    .build());

            return;
        }

        int tabWidth = Math.min(80, width / actionsByMod.size());
        int xOffset = (width - tabWidth * actionsByMod.size()) / 2;

        for (String modName : actionsByMod.keySet()) {
            Button button = Button.builder(Component.literal(modName), b -> {
                        setTab(modName);
                        updateTabButtonStates();
                    })
                    .bounds(xOffset, 10, tabWidth, 20)
                    .build();

            button.active = !modName.equals(currentTab);

            tabButtons.add(button);
            addRenderableWidget(button);

            xOffset += tabWidth;
        }

        int listWidth = getListWidth();

        searchField = new EditBox(
                font,
                width / 2 - listWidth / 2,
                35,
                listWidth,
                20,
                Component.translatable("screen.radialmenu.editor.search"));
        searchField.setHint(Component.translatable("screen.radialmenu.editor.search"));
        searchField.setResponder(this::updateSearch);

        addRenderableWidget(searchField);

        malilibList =
                new MalilibList(Minecraft.getInstance(), listWidth, getListHeight(), getListStartY(), ENTRY_HEIGHT);
        malilibList.updateSizeAndPosition(listWidth, getListHeight(), getListStartY());
        malilibList.setX(getListLeft());

        addRenderableWidget(malilibList);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        setInitialFocus(searchField);

        setTab(currentTab);
    }

    private void setTab(String tabName) {
        currentTab = tabName;
        currentActions = actionsByMod.getOrDefault(tabName, new ArrayList<>());

        if (searchField != null) {
            searchField.setValue("");
        }

        updateSearch("");
    }

    private void updateSearch(String query) {
        if (malilibList == null) {
            return;
        }

        String q = query.toLowerCase();

        List<MalilibEntry> entries = currentActions.stream()
                .filter(action -> action.name().toLowerCase().contains(q)
                        || action.displayName().toLowerCase().contains(q)
                        || (action.description() != null && action.description().toLowerCase().contains(q))
                        || (action.subLabel() != null && action.subLabel().toLowerCase().contains(q)))
                .map(action -> new MalilibEntry(action, onSelect, this::onClose))
                .collect(Collectors.toList());

        malilibList.setEntries(entries);
        malilibList.setScrollAmount(0.0);
    }

    private void updateTabButtonStates() {
        for (Button button : tabButtons) {
            button.active = !button.getMessage().getString().equals(currentTab);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        if (actionsByMod.isEmpty()) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.radialmenu.malilib_picker.empty"),
                    width / 2,
                    height / 2,
                    0xFF555555);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static class MalilibList extends ObjectSelectionList<MalilibEntry> {

        private MalilibList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(330, getWidth() - 20);
        }

        public void setEntries(Collection<MalilibEntry> entries) {
            replaceEntries(entries);
        }
    }

    private static class MalilibEntry extends ObjectSelectionList.Entry<MalilibEntry> {

        private final MalilibAction action;
        private final Consumer<MalilibAction> onSelect;
        private final Runnable onClose;

        private MalilibEntry(MalilibAction action, Consumer<MalilibAction> onSelect, Runnable onClose) {
            this.action = action;
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

            String category = action.category();
            int categoryWidth = client.font.width(category);
            int textRight = right - categoryWidth - 12;

            int textX = left + 8;

            if (action.subLabel() != null) {
                // 双行: 本地化说明(主) + 英文原名(次)
                String line1 = action.displayName();
                int avail = textRight - textX;
                if (client.font.width(line1) > avail) {
                    line1 = client.font.plainSubstrByWidth(line1, avail) + "…";
                }
                graphics.drawString(client.font, line1, textX, top + 4, 0xFFFFFFFF);

                String line2 = action.subLabel();
                if (client.font.width(line2) > avail) {
                    line2 = client.font.plainSubstrByWidth(line2, avail) + "…";
                }
                graphics.drawString(client.font, line2, textX, top + 15, 0xFFAAAAAA);
            } else {
                String line1 = action.displayName();
                int avail = textRight - textX;
                if (client.font.width(line1) > avail) {
                    line1 = client.font.plainSubstrByWidth(line1, avail) + "…";
                }
                int textY = top + ((bottom - top) - client.font.lineHeight) / 2;
                graphics.drawString(client.font, line1, textX, textY, 0xFFFFFFFF);
            }

            graphics.drawString(client.font, category, right - categoryWidth - 8, top + 4, 0xFFAAAAAA);

            // 悬浮显示完整说明(可能多行或被截断)
            if (hovered && action.description() != null && !action.description().isBlank()) {
                List<FormattedCharSequence> tooltip = new ArrayList<>();
                action.description().lines().forEach(
                        line -> tooltip.addAll(client.font.split(Component.literal(line), 250)));
                graphics.renderTooltip(client.font, tooltip, mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onSelect.accept(action);
            onClose.run();

            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(action.displayName());
        }
    }
}
