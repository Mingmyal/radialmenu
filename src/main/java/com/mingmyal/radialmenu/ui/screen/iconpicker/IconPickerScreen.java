package com.mingmyal.radialmenu.ui.screen.iconpicker;

import com.mingmyal.radialmenu.ui.screen.iconpicker.tabs.*;
import com.mingmyal.radialmenu.util.IconHistory;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class IconPickerScreen extends Screen {
    private final Screen parent;

    private final List<IconTab> tabs = new ArrayList<>();

    private IconTab currentTab;

    private EditBox searchField;

    private IconPickerScreen(Screen parent) {
        super(Component.translatable("screen.radialmenu.icon_picker.title"));
        this.parent = parent;
    }

    /**
     * @param currentId the slot's current icon id, used to open on its tab and highlight it; may be null
     */
    public IconPickerScreen(Screen parent, String currentId, Consumer<String> onSelect) {
        this(parent);

        Consumer<String> recordingSelect = id -> {
            IconHistory.recordUse(id);
            onSelect.accept(id);
        };

        // Register Tabs
        RecentIconTab recentTab = new RecentIconTab(currentId, recordingSelect, this::onClose);
        ItemIconTab itemTab = new ItemIconTab(currentId, recordingSelect, this::onClose);
        tabs.add(recentTab);
        tabs.add(itemTab);
        tabs.add(new InventoryIconTab(currentId, recordingSelect, this::onClose));
        tabs.add(new EffectIconTab(currentId, recordingSelect, this::onClose));
        tabs.add(new PhosphorIconTab(currentId, recordingSelect, this::onClose));
        tabs.add(new GlyphIconTab(currentId, recordingSelect, this::onClose));

        this.currentTab = getInitialTab(currentId, recentTab, itemTab);
    }

    /**
     * 条件约束用物品选择器: 只含"物品"搜索与"物品栏"两个标签,
     * 物品栏返回槽位内当前物品的具体 ID(give 格式), 默认打开物品栏标签。
     */
    public static IconPickerScreen createItemPicker(Screen parent, String currentId, Consumer<String> onSelect) {
        IconPickerScreen screen = new IconPickerScreen(parent);
        ItemIconTab itemTab = new ItemIconTab(currentId, onSelect, screen::onClose);
        InventoryIconTab inventoryTab = new InventoryIconTab(currentId, onSelect, screen::onClose, true);
        screen.tabs.add(itemTab);
        screen.tabs.add(inventoryTab);
        screen.currentTab = inventoryTab;
        return screen;
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 本界面自行绘制深色背景, 不使用原版背景模糊:
        // tab 内容(背包面板)先于 super.render 绘制, 模糊后处理会把已绘制的 GUI 一起糊掉
    }

    private IconTab getInitialTab(String currentId, IconTab recentTab, IconTab itemTab) {
        boolean hasIcon = currentId != null && !currentId.isBlank() && !currentId.equals("minecraft:air");

        if (hasIcon) {
            for (IconTab tab : tabs) {
                if (tab.accepts(currentId)) {
                    return tab;
                }
            }
        }

        return RecentIconTab.hasEntries() ? recentTab : itemTab;
    }

    @Override
    protected void init() {
        clearWidgets();

        int tabWidth = Math.min(80, width / Math.max(1, tabs.size()));
        int xOffset = (width - tabWidth * tabs.size()) / 2;

        for (IconTab tab : tabs) {
            Button button = Button.builder(tab.getTitle(), b -> setTab(tab))
                    .bounds(xOffset, 10, tabWidth, 20)
                    .build();

            button.active = (tab != currentTab);
            addRenderableWidget(button);

            xOffset += tabWidth;
        }

        int listWidth = Math.min(350, (int) (width * 0.9));
        searchField = new EditBox(
                font,
                width / 2 - listWidth / 2,
                35,
                listWidth,
                20,
                Component.translatable("screen.radialmenu.editor.search"));
        searchField.setHint(Component.translatable("screen.radialmenu.editor.search"));
        searchField.setResponder(query -> {
            if (currentTab != null) currentTab.updateSearch(query);
        });

        addRenderableWidget(searchField);
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build());

        currentTab.setup(width, height, renderable -> addRenderableWidget((AbstractWidget) renderable), listener -> {
            if (!this.children().contains(listener)) {
                addWidget((AbstractWidget) listener);
            }
        });

        // The field is recreated on every init, so the query already starts empty; calling setValue here would
        // re-run the search and throw away the tab's scroll to the current icon
        searchField.visible = currentTab.showSearchBar();
        setInitialFocus(searchField);
    }

    private void setTab(IconTab tab) {
        this.currentTab = tab;
        this.rebuildWidgets(); // Triggers init() again to cleanly swap widgets
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        // Let the tab render its background/custom UI
        currentTab.render(graphics, mouseX, mouseY, delta);

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+Tab / Ctrl+Shift+Tab cycle through the tabs
        if (keyCode == GLFW.GLFW_KEY_TAB && Screen.hasControlDown()) {
            int step = Screen.hasShiftDown() ? -1 : 1;
            setTab(tabs.get(Math.floorMod(tabs.indexOf(currentTab) + step, tabs.size())));
            return true;
        }

        // The tab gets first pick so arrows and Enter drive the icon grid even while typing a search
        if (currentTab.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentTab.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);

        // Right-clicking to favorite focuses the icon list; hand focus back so typing keeps searching
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && searchField != null && searchField.visible) {
            setFocused(searchField);
        }

        return handled;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
