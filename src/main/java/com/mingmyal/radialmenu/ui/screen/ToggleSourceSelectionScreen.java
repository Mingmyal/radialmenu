package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 外部模组开关选择界面。
 * <p>
 * 采用"先选模组, 再选开关"的两级结构:
 * <ol>
 *   <li><b>模组列表</b> — 搜索并点进要改的模组;</li>
 *   <li><b>开关列表</b> — 该模组下的开关, 点击即绑定。</li>
 * </ol>
 * 不按配置库(提供者)分组: 玩家找的是"某个模组", 而不是"某个配置库";
 * 且不少模组的开关没有中文翻译, 按模组定位比按库定位更容易找到目标。
 * <p>
 * 两级都是可滚动的列表控件(而非顶部的按钮条), 模组多时不会挤在一起点不到。
 */
public class ToggleSourceSelectionScreen extends Screen {

    private static final int MOD_ENTRY_HEIGHT = 24;
    private static final int SWITCH_ENTRY_HEIGHT = 26;
    private static final int ROW_HEIGHT = 20;
    private static final int BTN_GAP = 8;

    /** 触发全量扫描的安全上限, 避免异常模组拖慢界面。 */
    private static final int MAX_ENTRIES = 4000;

    private final Screen parent;
    private final Consumer<String> onSelect;

    /** 当前进入的模组 ID; null 表示处于模组列表层。 */
    private String selectedModId;

    /** 当前模组的展示名(进入时解析一次, 供标题栏每帧使用)。 */
    private String selectedModLabel = "";

    private EditBox searchField;
    private ModList modList;
    private SwitchList switchList;

    /** 两级各自独立的搜索词。 */
    private String modQuery = "";
    private String switchQuery = "";

    private List<String> visibleModIds = List.of();
    private List<ToggleSource> visibleSwitches = List.of();

    public ToggleSourceSelectionScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.translatable("screen.radialmenu.toggle_picker.title"));
        this.parent = parent;
        this.onSelect = onSelect;

        // 只建立一次缓存(进世界时已后台预热)。需要重扫时用底部按钮。
        ToggleSourceRegistry.ensureDiscovered();
    }

    private boolean inSwitchView() {
        return selectedModId != null;
    }

    private int getListWidth() {
        return Math.min(420, (int) (width * 0.95));
    }

    private int getListLeft() {
        return width / 2 - getListWidth() / 2;
    }

    private int getSearchY() {
        return inSwitchView() ? 32 : 10;
    }

    private int getListStartY() {
        return getSearchY() + ROW_HEIGHT + 4;
    }

    private int getListBottom() {
        return height - 34;
    }

    private int getListHeight() {
        return Math.max(1, getListBottom() - getListStartY());
    }

    @Override
    protected void init() {
        int listWidth = getListWidth();

        // --- 开关层的顶部: 返回 + 打开该模组设置 ---
        if (inSwitchView()) {
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.back"),
                            b -> {
                                selectedModId = null;
                                selectedModLabel = "";
                                switchQuery = "";
                                rebuildWidgets();
                            })
                    .bounds(getListLeft(), 8, 60, ROW_HEIGHT)
                    .build());

            // 没有中文翻译的开关难以辨认, 提供直达该模组自身设置界面的入口便于对照
            String modId = selectedModId;
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.radialmenu.toggle_picker.open_mod_config"),
                            b -> {
                                ToggleSourceRegistry.openConfigScreen(modId, this);
                            })
                    .bounds(getListLeft() + 64, 8, listWidth - 64, ROW_HEIGHT)
                    .build());
        }

        // --- 搜索框 ---
        searchField = new EditBox(
                font,
                getListLeft(),
                getSearchY(),
                listWidth,
                ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.search"));
        searchField.setHint(Component.translatable(inSwitchView()
                ? "screen.radialmenu.toggle_picker.search_switch"
                : "screen.radialmenu.toggle_picker.search_mod"));
        searchField.setValue(inSwitchView() ? switchQuery : modQuery);
        searchField.setResponder(v -> {
            if (inSwitchView()) {
                switchQuery = v;
            } else {
                modQuery = v;
            }
            refreshEntries();
        });
        addRenderableWidget(searchField);
        setInitialFocus(searchField);

        // --- 主列表 ---
        if (inSwitchView()) {
            switchList = new SwitchList(
                    Minecraft.getInstance(), listWidth, getListHeight(), getListStartY(), SWITCH_ENTRY_HEIGHT);
            switchList.updateSizeAndPosition(listWidth, getListHeight(), getListStartY());
            switchList.setX(getListLeft());
            addRenderableWidget(switchList);
        } else {
            modList = new ModList(
                    Minecraft.getInstance(), listWidth, getListHeight(), getListStartY(), MOD_ENTRY_HEIGHT);
            modList.updateSizeAndPosition(listWidth, getListHeight(), getListStartY());
            modList.setX(getListLeft());
            addRenderableWidget(modList);
        }

        // --- 底部按钮 ---
        buildBottomButtons();

        refreshEntries();
    }

    private void buildBottomButtons() {
        boolean hasSources = !ToggleSourceRegistry.isEmpty();

        int btnCount = hasSources ? 3 : 2;
        int btnW = 120;
        int totalW = btnW * btnCount + BTN_GAP * (btnCount - 1);
        int btnX = width / 2 - totalW / 2;
        int btnY = height - 26;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.radialmenu.toggle_picker.rescan"),
                        b -> {
                            // 显式重扫: 连同负缓存一起清空, 之前判定为"不存在"的绑定会重新尝试解析
                            ToggleSourceRegistry.refresh();
                            ToggleSourceRegistry.clearNegativeCache();
                            selectedModId = null;
                            selectedModLabel = "";
                            modQuery = "";
                            switchQuery = "";
                            rebuildWidgets();
                        })
                .bounds(btnX, btnY, btnW, ROW_HEIGHT)
                .build());
        btnX += btnW + BTN_GAP;

        if (hasSources) {
            // 解绑: 退回本地手动开关状态
            addRenderableWidget(Button.builder(
                            Component.translatable("screen.radialmenu.toggle_picker.clear"),
                            b -> {
                                onSelect.accept(null);
                                onClose();
                            })
                    .bounds(btnX, btnY, btnW, ROW_HEIGHT)
                    .build());
            btnX += btnW + BTN_GAP;
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(btnX, btnY, btnW, ROW_HEIGHT)
                .build());
    }

    // --- 数据刷新 ---

    private void refreshEntries() {
        if (inSwitchView()) {
            refreshSwitches();
        } else {
            refreshMods();
        }
    }

    private void refreshMods() {
        if (modList == null) return;

        String q = modQuery == null ? "" : modQuery.toLowerCase(Locale.ROOT);

        // 一次性取出分组结果, 供过滤与行构造共用(避免重复构建映射)
        var grouped = ToggleSourceRegistry.groupByMod();

        // 标签只解析一次: modLabel 需要遍历该模组的开关, 在排序比较器里反复调用会明显变慢
        Map<String, String> labels = new HashMap<>();
        for (String modId : grouped.keySet()) {
            labels.put(modId, ToggleSourceRegistry.modLabel(modId));
        }

        visibleModIds = grouped.keySet().stream()
                .filter(modId -> {
                    if (q.isBlank()) return true;
                    // 同时匹配展示名与模组 ID, 便于用习惯的名字搜索
                    String label = labels.get(modId).toLowerCase(Locale.ROOT);
                    return label.contains(q) || modId.toLowerCase(Locale.ROOT).contains(q);
                })
                .sorted((a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)))
                .limit(MAX_ENTRIES)
                .toList();

        modList.setEntries(visibleModIds.stream()
                .map(modId -> new ModEntry(
                        modId,
                        labels.get(modId),
                        grouped.getOrDefault(modId, List.of()).size(),
                        this::enterMod))
                .toList());
        modList.setScrollAmount(0.0);
    }

    private void refreshSwitches() {
        if (switchList == null) return;

        String q = switchQuery == null ? "" : switchQuery.toLowerCase(Locale.ROOT);

        visibleSwitches = ToggleSourceRegistry.sourcesOfMod(selectedModId).stream()
                .filter(s -> {
                    if (q.isBlank()) return true;
                    return s.displayName().getString().toLowerCase(Locale.ROOT).contains(q)
                            || s.id().toString().toLowerCase(Locale.ROOT).contains(q);
                })
                .limit(MAX_ENTRIES)
                .toList();

        switchList.setEntries(visibleSwitches.stream()
                .map(s -> new SwitchEntry(s, onSelect, this::onClose))
                .toList());
        switchList.setScrollAmount(0.0);
    }

    private void enterMod(String modId) {
        selectedModId = modId;
        selectedModLabel = ToggleSourceRegistry.modLabel(modId);
        switchQuery = "";
        rebuildWidgets();
    }

    // --- 渲染 ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        if (ToggleSourceRegistry.isEmpty()) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.radialmenu.toggle_picker.empty"),
                    width / 2,
                    height / 2,
                    0xFFAAAAAA);
        } else if (inSwitchView()) {
            // 当前模组名与开关数量, 明确"我在哪一层"
            graphics.drawCenteredString(
                    font,
                    Component.literal(selectedModLabel + "  (" + visibleSwitches.size() + ")"),
                    width / 2,
                    height - 38,
                    0xFF808080);
        } else if (visibleModIds.isEmpty()) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("screen.radialmenu.toggle_picker.no_match"),
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

    // --- 列表控件 ---

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

    private static class SwitchList extends ObjectSelectionList<SwitchEntry> {

        private SwitchList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, getWidth() - 20);
        }

        public void setEntries(Collection<SwitchEntry> entries) {
            replaceEntries(entries);
        }
    }

    /** 模组列表的一行。标签与数量在刷新阶段算好, 避免每帧重建分组映射。 */
    private static class ModEntry extends ObjectSelectionList.Entry<ModEntry> {

        private final String modId;
        private final String label;
        private final int switchCount;
        private final Consumer<String> onEnter;

        private ModEntry(String modId, String label, int switchCount, Consumer<String> onEnter) {
            this.modId = modId;
            this.label = label;
            this.switchCount = switchCount;
            this.onEnter = onEnter;
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
            int bottom = rowTop + MOD_ENTRY_HEIGHT;

            graphics.fill(left, rowTop + 1, right, bottom - 1, hovered ? 0x80FFFFFF : 0x40000000);

            int textY = rowTop + (MOD_ENTRY_HEIGHT - client.font.lineHeight) / 2;

            graphics.drawString(client.font, label, left + 8, textY, 0xFFFFFFFF);

            // 右侧: 开关数量, 方便判断该模组是否值得点进去
            int count = switchCount;
            Component countText = Component.translatable("screen.radialmenu.toggle_picker.count", count);
            graphics.drawString(
                    client.font, countText, right - client.font.width(countText) - 16, textY, 0xFFAAAAAA);

            // 进入箭头
            graphics.drawString(client.font, ">", right - 10, textY, 0xFF808080);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onEnter.accept(modId);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(label);
        }
    }

    /** 开关列表的一行。 */
    private static class SwitchEntry extends ObjectSelectionList.Entry<SwitchEntry> {

        private final ToggleSource source;
        private final Consumer<String> onSelect;
        private final Runnable onClose;

        private SwitchEntry(ToggleSource source, Consumer<String> onSelect, Runnable onClose) {
            this.source = source;
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
            int bottom = rowTop + SWITCH_ENTRY_HEIGHT;

            graphics.fill(left, rowTop + 1, right, bottom - 1, hovered ? 0x80FFFFFF : 0x40000000);

            // 状态指示灯: 绿=开 红=关
            boolean state = readState();
            int dotColor = state ? 0xFF55FF55 : 0xFFFF5555;
            graphics.fill(left + 4, rowTop + 10, left + 10, rowTop + 16, dotColor);

            int textX = left + 16;
            int textY = rowTop + (SWITCH_ENTRY_HEIGHT - client.font.lineHeight) / 2;

            String name = source.displayName().getString();
            graphics.drawString(client.font, name, textX, textY, state ? 0xFFB0FFB0 : 0xFFFFB0B0);

            // 右侧显示开关 ID, 便于对照模组自身的设置界面
            String idText = source.id().toString();
            int idWidth = client.font.width(idText);
            int idX = right - idWidth - 8;
            if (idX > textX + client.font.width(name) + 8) {
                graphics.drawString(client.font, idText, idX, textY, 0xFF707070);
            }
        }

        private boolean readState() {
            try {
                return source.getState();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }

            onSelect.accept(source.id().toString());
            onClose.run();
            return true;
        }

        @Override
        public Component getNarration() {
            return source.displayName();
        }
    }
}
