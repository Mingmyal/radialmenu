package com.mingmyal.radialmenu.ui.screen.condition;

import com.mingmyal.radialmenu.api.ItemRequirement;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotCondition;
import com.mingmyal.radialmenu.ui.screen.SlotEditorScreen;
import com.mingmyal.radialmenu.ui.screen.iconpicker.IconPickerScreen;
import com.mingmyal.radialmenu.ui.widget.DropdownButtonWidget;
import com.mingmyal.radialmenu.ui.widget.DropdownMenuWidget;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 槽位条件约束编辑器: 选择条件类型并配置参数。
 * 编辑工作副本, 点击"保存"写回槽位; "取消"丢弃修改。
 */
public class ConditionEditorScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int HORIZ_GAP = 5;
    private static final int MAX_ITEMS = 6;
    /** 集合成员的缩进宽度 */
    private static final int MEMBER_INDENT = 12;

    private final SlotEditorScreen parent;
    private final RadialSlot slot;

    private final SlotCondition condition;

    private DropdownButtonWidget<SlotCondition.ConditionType> typeDropdown;
    private DropdownButtonWidget<?> openDropdown;

    // 滚动支持: 物品约束最多 6 条, 内容可能超出屏幕高度
    private static final int SCROLL_TOP_OFFSET = 20;
    private static final int SCROLL_BOTTOM_MARGIN = 12;
    private final List<AbstractWidget> scrollWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public ConditionEditorScreen(SlotEditorScreen parent, RadialSlot slot) {
        super(Component.translatable("screen.radialmenu.condition.title"));
        this.parent = parent;
        this.slot = slot;

        SlotCondition existing = slot.condition;
        this.condition = existing != null ? existing.copy() : new SlotCondition();

        // 物品类条件保证至少一条约束物品, 便于直接编辑
        if (needsItems() && condition.items.isEmpty()) {
            condition.items.add(new ItemRequirement());
        }
    }

    private boolean needsItems() {
        return condition.type == SlotCondition.ConditionType.ITEM_CONSUME
                || condition.type == SlotCondition.ConditionType.ITEM_CHECK;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(320, (int) (width * 0.9));

        LinearLayout mainLayout = LinearLayout.vertical().spacing(6);

        // --- 条件类型 ---
        mainLayout.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.type"), font));

        typeDropdown = makeDropdown(
                contentWidth,
                List.of(SlotCondition.ConditionType.values()),
                condition.type,
                t -> Component.translatable("screen.radialmenu.condition.type." + t.name().toLowerCase()),
                t -> {
                    if (condition.type != t) {
                        condition.type = t;
                        if (needsItems() && condition.items.isEmpty()) {
                            condition.items.add(new ItemRequirement());
                        }
                        this.rebuildWidgets();
                    }
                });
        mainLayout.addChild(typeDropdown);

        // --- 类型相关配置区 ---
        switch (condition.type) {
            case ITEM_CONSUME -> {
                addItemsSection(mainLayout, contentWidth);
                addUnlockSection(mainLayout);
            }
            case ITEM_CHECK -> addItemsSection(mainLayout, contentWidth);
            case XP_CONSUME -> {
                addXpSection(mainLayout);
                addUnlockSection(mainLayout);
            }
        }

        // --- 底部按钮 ---
        LinearLayout actionGroup = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int btnCount = needsItems() ? 4 : 2;
        int btnWidth = (contentWidth - HORIZ_GAP * (btnCount - 1)) / btnCount;

        if (needsItems()) {
            Button addItemBtn = Button.builder(
                            Component.translatable("screen.radialmenu.condition.add_item"),
                            b -> {
                                if (condition.items.size() < MAX_ITEMS) {
                                    condition.items.add(new ItemRequirement());
                                    this.rebuildWidgets();
                                }
                            })
                    .bounds(0, 0, btnWidth, ROW_HEIGHT)
                    .build();
            addItemBtn.active = condition.items.size() < MAX_ITEMS;
            actionGroup.addChild(addItemBtn);

            // 直接添加一个空集合(集合内任意一件满足即通过)
            Button addSetBtn = Button.builder(
                            Component.translatable("screen.radialmenu.condition.add_set"),
                            b -> {
                                if (condition.items.size() < MAX_ITEMS) {
                                    ItemRequirement set = ItemRequirement.newSet();
                                    set.members.add(new ItemRequirement());
                                    condition.items.add(set);
                                    this.rebuildWidgets();
                                }
                            })
                    .bounds(0, 0, btnWidth, ROW_HEIGHT)
                    .build();
            addSetBtn.active = condition.items.size() < MAX_ITEMS;
            actionGroup.addChild(addSetBtn);
        }

        Button saveBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.save"), b -> saveCondition())
                .bounds(0, 0, btnWidth, ROW_HEIGHT)
                .build();
        actionGroup.addChild(saveBtn);

        Button cancelBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.cancel"), b -> returnToParent())
                .bounds(0, 0, btnWidth, ROW_HEIGHT)
                .build();
        actionGroup.addChild(cancelBtn);

        mainLayout.addChild(actionGroup);

        // --- FINAL ASSEMBLY ---
        FrameLayout rootLayout = new FrameLayout();
        rootLayout.addChild(mainLayout);
        rootLayout.arrangeElements();

        // 内容高度超出可用区域时启用滚动: 顶部对齐
        int contentHeight = rootLayout.getHeight();
        int availableHeight = height - SCROLL_TOP_OFFSET - SCROLL_BOTTOM_MARGIN;

        scrollWidgets.clear();
        baseY.clear();
        scrollOffset = 0;

        if (contentHeight > availableHeight) {
            // 在高度恰为内容高度的矩形中居中, 等价于顶部对齐
            FrameLayout.centerInRectangle(rootLayout, 0, SCROLL_TOP_OFFSET, width, contentHeight);
            maxScroll = contentHeight - availableHeight;
        } else {
            FrameLayout.centerInRectangle(rootLayout, 0, 0, width, height);
            maxScroll = 0;
        }

        rootLayout.visitWidgets(widget -> {
            scrollWidgets.add(widget);
            baseY.put(widget, widget.getY());
            this.addRenderableWidget(widget);
        });
    }

    private void applyScroll() {
        for (AbstractWidget widget : scrollWidgets) {
            Integer y = baseY.get(widget);
            if (y != null) {
                widget.setY(y - scrollOffset);
            }
        }
    }

    // --- 物品类条件(类型 1/2) ---

    private void addItemsSection(LinearLayout mainLayout, int contentWidth) {
        LinearLayout section = LinearLayout.vertical().spacing(4);
        section.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.item_list"), font));
        section.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.item_list.desc"), font));

        for (int i = 0; i < condition.items.size(); i++) {
            addRequirementBlock(section, contentWidth, condition.items.get(i));
        }

        mainLayout.addChild(section);
    }

    /**
     * 渲染一条约束物品(单件或集合)。
     * <p>
     * 集合条目会额外渲染"集合成员"子块: 每行一个成员, 集合内任意一件满足即通过。
     */
    private void addRequirementBlock(LinearLayout section, int contentWidth, ItemRequirement req) {
        LinearLayout block = LinearLayout.vertical().spacing(2);

        if (req.isSet()) {
            addSetBlock(block, contentWidth, req);
            section.addChild(block);
            return;
        }

        // 行1: 物品 ID + 物品栏 + 转集合 + 删除
        LinearLayout line1 = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int invBtnW = 50;
        int setBtnW = 50;
        int delBtnW = 36;
        int idFieldW = contentWidth - invBtnW - setBtnW - delBtnW - HORIZ_GAP * 3;

        EditBox idField = new EditBox(
                font, 0, 0, idFieldW, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.condition.item_id"));
        idField.setMaxLength(220);
        idField.setValue(req.itemId != null ? req.itemId : "");
        idField.setHint(Component.literal("minecraft:diamond"));
        idField.setResponder(v -> req.itemId = v.trim());
        line1.addChild(idField);

        Button invBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.icon_picker.inventory"),
                        b -> this.minecraft.setScreen(
                                IconPickerScreen.createItemPicker(this, req.itemId, id -> req.itemId = id)))
                .bounds(0, 0, invBtnW, ROW_HEIGHT)
                .build();
        line1.addChild(invBtn);

        // 转为集合: 当前物品作为集合的第一个成员保留
        Button setBtn = Button.builder(
                        Component.translatable("screen.radialmenu.condition.to_set"),
                        b -> {
                            ItemRequirement set = ItemRequirement.newSet();
                            set.members.add(req.copy());
                            int index = condition.items.indexOf(req);
                            if (index >= 0) {
                                condition.items.set(index, set);
                            }
                            this.rebuildWidgets();
                        })
                .bounds(0, 0, setBtnW, ROW_HEIGHT)
                .build();
        line1.addChild(setBtn);

        Button delBtn = Button.builder(
                        Component.translatable("screen.radialmenu.condition.remove"),
                        b -> {
                            condition.items.remove(req);
                            this.rebuildWidgets();
                        })
                .bounds(0, 0, delBtnW, ROW_HEIGHT)
                .build();
        line1.addChild(delBtn);

        block.addChild(line1);

        // 行2: 数量 + 类型专属字段
        LinearLayout line2 = LinearLayout.horizontal().spacing(HORIZ_GAP);
        line2.addChild(labeled("screen.radialmenu.condition.count", 34));
        line2.addChild(numberField(38, req.count, 1, 999999, v -> req.count = v));

        if (condition.type == SlotCondition.ConditionType.ITEM_CONSUME) {
            // 解锁消耗: 数量 或 消耗耐久值(0=整件)
            line2.addChild(labeled("screen.radialmenu.condition.durability_cost", 90));
            line2.addChild(numberField(38, req.durabilityCost, 0, 999999, v -> req.durabilityCost = v));
        }

        block.addChild(line2);
        section.addChild(block);
    }

    /** 集合条目: 标题行(含转回单件/删除) + 统一数量/耐久 + 成员编辑入口。 */
    private void addSetBlock(LinearLayout block, int contentWidth, ItemRequirement set) {
        int delBtnW = 36;
        int backBtnW = 50;

        // 标题行: "集合 (任意一件满足)" + 转回单件 + 删除
        LinearLayout header = LinearLayout.horizontal().spacing(HORIZ_GAP);
        StringWidget setLabel = new StringWidget(
                Component.translatable("screen.radialmenu.condition.set_title", set.members.size()), font);
        setLabel.setWidth(contentWidth - backBtnW - delBtnW - HORIZ_GAP * 2);
        header.addChild(setLabel);

        Button backBtn = Button.builder(
                        Component.translatable("screen.radialmenu.condition.to_single"),
                        b -> {
                            // 转回单件: 保留第一个成员(没有成员则回到默认单件)
                            ItemRequirement single = set.members.isEmpty()
                                    ? new ItemRequirement()
                                    : set.members.get(0).copy();
                            int index = condition.items.indexOf(set);
                            if (index >= 0) {
                                condition.items.set(index, single);
                            }
                            this.rebuildWidgets();
                        })
                .bounds(0, 0, backBtnW, ROW_HEIGHT)
                .build();
        header.addChild(backBtn);

        Button delBtn = Button.builder(
                        Component.translatable("screen.radialmenu.condition.remove"),
                        b -> {
                            condition.items.remove(set);
                            this.rebuildWidgets();
                        })
                .bounds(0, 0, delBtnW, ROW_HEIGHT)
                .build();
        header.addChild(delBtn);

        block.addChild(header);

        // 集合统一的数量 / 耐久
        LinearLayout line2 = LinearLayout.horizontal().spacing(HORIZ_GAP);
        line2.addChild(labeled("screen.radialmenu.condition.count", 34));
        line2.addChild(numberField(38, set.count, 1, 999999, v -> set.count = v));

        if (condition.type == SlotCondition.ConditionType.ITEM_CONSUME) {
            line2.addChild(labeled("screen.radialmenu.condition.durability_cost", 90));
            line2.addChild(numberField(38, set.durabilityCost, 0, 999999, v -> set.durabilityCost = v));
        }

        block.addChild(line2);
        block.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.set.shared_values"), font));

        // 成员列表放进独立的可滚动界面, 避免成员多时撑爆本界面
        Button editMembersBtn = Button.builder(
                        Component.translatable(
                                "screen.radialmenu.condition.set.edit_members",
                                set.members.size(),
                                ItemRequirement.MAX_SET_MEMBERS),
                        b -> this.minecraft.setScreen(new ItemSetEditorScreen(this, set)))
                .bounds(0, 0, contentWidth - MEMBER_INDENT, ROW_HEIGHT)
                .build();

        LinearLayout memberRow = LinearLayout.horizontal().spacing(0);
        StringWidget indent = new StringWidget(Component.empty(), font);
        indent.setWidth(MEMBER_INDENT);
        memberRow.addChild(indent);
        memberRow.addChild(editMembersBtn);
        block.addChild(memberRow);
    }

    // --- 经验类条件(类型 3) ---

    private void addXpSection(LinearLayout mainLayout) {
        LinearLayout section = LinearLayout.vertical().spacing(4);
        section.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.xp_mode"), font));

        LinearLayout row = LinearLayout.horizontal().spacing(HORIZ_GAP);
        row.addChild(makeDropdown(
                110,
                List.of(false, true),
                condition.xpLevels,
                levels -> Component.translatable(
                        "screen.radialmenu.condition." + (levels ? "xp_levels" : "xp_points")),
                levels -> {
                    condition.xpLevels = levels;
                    this.rebuildWidgets();
                }));
        row.addChild(numberField(50, condition.xpAmount, 1, 999999, v -> condition.xpAmount = v));

        section.addChild(row);
        mainLayout.addChild(section);
    }

    // --- 解锁方式(类型 1/3) ---

    private void addUnlockSection(LinearLayout mainLayout) {
        LinearLayout section = LinearLayout.vertical().spacing(4);
        section.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.condition.unlock_mode"), font));

        LinearLayout row = LinearLayout.horizontal().spacing(HORIZ_GAP);
        row.addChild(makeDropdown(
                110,
                List.of(false, true),
                condition.timedUnlock,
                timed -> Component.translatable(
                        "screen.radialmenu.condition."
                                + (timed ? "unlock_timed" : "unlock_permanent")),
                timed -> {
                    condition.timedUnlock = timed;
                    this.rebuildWidgets();
                }));
        row.addChild(numberField(
                60,
                (int) Math.min(Integer.MAX_VALUE, condition.unlockDurationSeconds),
                1,
                Integer.MAX_VALUE,
                v -> condition.unlockDurationSeconds = v));

        section.addChild(row);
        mainLayout.addChild(section);
    }

    // --- 保存 / 取消 ---

    private void saveCondition() {
        // 空约束列表保持原状(不满足条件), 但至少保留一条便于编辑
        if (needsItems() && condition.items.isEmpty()) {
            condition.items.add(new ItemRequirement());
        }
        condition.sanitize();
        this.slot.condition = this.condition;
        // 由槽位编辑器的"保存"统一持久化, 保持撤回语义一致
        returnToParent();
    }

    private void returnToParent() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        // 取消时丢弃工作副本, slot.condition 保持原值
        returnToParent();
    }

    // --- 通用控件构建 ---

    private <T> DropdownButtonWidget<T> makeDropdown(
            int w,
            List<T> options,
            T selected,
            Function<T, Component> fmt,
            Consumer<T> onSelect) {
        final DropdownButtonWidget<T>[] holder = new DropdownButtonWidget[1];
        holder[0] = new DropdownButtonWidget<>(0, 0, w, ROW_HEIGHT, options, selected, fmt, onSelect,
                menu -> {
                    this.openDropdown = holder[0];
                    this.addRenderableWidget(menu);
                }) {
            @Override
            public void closeMenu() {
                if (this.isMenuOpen()) {
                    ConditionEditorScreen.this.removeWidget(this.getActiveMenu());
                }
                super.closeMenu();
                if (ConditionEditorScreen.this.openDropdown == this) {
                    ConditionEditorScreen.this.openDropdown = null;
                }
            }
        };
        return holder[0];
    }

    private StringWidget labeled(String key, int w) {
        StringWidget widget = new StringWidget(Component.translatable(key), font);
        widget.setWidth(w);
        return widget;
    }

    private EditBox numberField(int w, int value, int min, int max, Consumer<Integer> setter) {
        EditBox box = new EditBox(font, 0, 0, w, ROW_HEIGHT, Component.empty());
        box.setMaxLength(9);
        box.setValue(String.valueOf(value));
        box.setFilter(s -> s.matches("\\d*"));
        box.setResponder(v -> {
            if (v.isBlank()) return;
            try {
                int n = Integer.parseInt(v);
                if (n >= min && n <= max) {
                    setter.accept(n);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        return box;
    }

    // --- 下拉菜单渲染与交互(多下拉跟踪) ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        boolean hoveringMenu = openDropdown != null
                && openDropdown.isMenuOpen()
                && openDropdown.getActiveMenu().isMouseOver(mouseX, mouseY);

        int passMouseX = hoveringMenu ? -999 : mouseX;
        int passMouseY = hoveringMenu ? -999 : mouseY;

        super.render(graphics, passMouseX, passMouseY, delta);

        if (hoveringMenu) {
            this.openDropdown.getActiveMenu().render(graphics, mouseX, mouseY, delta);
        }

        // 滚动时绘制上下遮罩, 盖住滚出可视区域的内容
        if (maxScroll > 0) {
            graphics.fill(0, 0, width, SCROLL_TOP_OFFSET, 0xF0101010);
            graphics.fill(0, height - SCROLL_BOTTOM_MARGIN, width, height, 0xF0101010);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 浮出菜单打开时: 菜单内滚动交给菜单, 菜单外滚动先收起菜单再滚动内容
        if (openDropdown != null && openDropdown.isMenuOpen()) {
            DropdownMenuWidget<?> floatingMenu = openDropdown.getActiveMenu();
            if (floatingMenu.isMouseOver(mouseX, mouseY)) {
                return floatingMenu.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
            openDropdown.closeMenu();
        }

        if (maxScroll > 0) {
            int newOffset = (int) (scrollOffset - scrollY * 18);
            scrollOffset = Math.clamp(newOffset, 0, maxScroll);
            applyScroll();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (openDropdown != null && openDropdown.isMenuOpen()) {
            DropdownMenuWidget<?> floatingMenu = openDropdown.getActiveMenu();

            if (floatingMenu.isMouseOver(mouseX, mouseY)) {
                floatingMenu.mouseClicked(mouseX, mouseY, button);
                return true;
            } else //noinspection StatementWithEmptyBody
            if (openDropdown.isMouseOver(mouseX, mouseY)) {
                // 让点击穿透到按钮本身, 由其自行收起菜单
            } else {
                openDropdown.closeMenu();
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
