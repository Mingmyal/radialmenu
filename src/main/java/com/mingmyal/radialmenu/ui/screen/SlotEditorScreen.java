package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotCondition;
import com.mingmyal.radialmenu.api.SlotMode;
import com.mingmyal.radialmenu.api.SlotModeRegistry;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.mode.EmptySlotMode;
import com.mingmyal.radialmenu.mode.MacroSlotMode;
import com.mingmyal.radialmenu.mode.SubmenuSlotMode;
import com.mingmyal.radialmenu.mode.base.IconEnabledSlotMode;
import com.mingmyal.radialmenu.render.SlotRenderHelper;
import com.mingmyal.radialmenu.ui.screen.ToggleSourceSelectionScreen;
import com.mingmyal.radialmenu.ui.screen.condition.ConditionEditorScreen;
import com.mingmyal.radialmenu.ui.widget.DropdownButtonWidget;
import com.mingmyal.radialmenu.ui.widget.DropdownMenuWidget;
import com.mingmyal.radialmenu.util.ImportedSoundStore;

import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public class SlotEditorScreen extends Screen {

    private static final int SLOT_SIZE = 26;

    // LAYOUT CONSTANTS
    private static final int ROW_HEIGHT = 20;
    private static final int HORIZ_GAP = 5;

    // 滚动布局(Bug7): 顶部为钉住图标预留的高度与底部边距
    private static final int SCROLL_TOP_OFFSET = 46;
    private static final int SCROLL_BOTTOM_MARGIN = 12;

    private final RadialSlot slot;

    // State for reverting changes on cancel
    private final String oldName, oldValue, oldId, oldIconColor, oldDescription;
    private final String oldNameColor, oldSelectedHint, oldSelectedHintColor;
    private final SlotMode oldMode;
    private final int oldChildCount;
    private final List<RadialSlot> oldChildren;
    private final List<RadialSlot.Macro> oldMacros;
    private final boolean oldConditionEnabled;
    private final SlotCondition oldCondition;
    private final String oldClickSound;

    // 开关类型快照(取消时还原; 状态动作为独立对象, 需深拷贝)
    private final boolean oldToggleEnabled;
    private final boolean oldToggleState;
    private final Boolean oldToggleTint;
    private final String oldToggleTextOn, oldToggleTextOff;
    private final RadialSlot oldToggleActionOn, oldToggleActionOff;
    private final String oldToggleSourceId;

    private boolean isSaved = false;

    // Universal Widgets
    private EditBox nameField;
    private DropdownButtonWidget<SlotMode> modeDropdown;

    // 全部动作模式下拉(主模式 + 开关类型的两组状态动作), 用于统一的菜单悬浮/点击/滚动处理
    private final List<DropdownButtonWidget<SlotMode>> modeDropdowns = new ArrayList<>();

    // 滚动支持(Bug7): 内容高度超出可用区域时, 顶部对齐并允许鼠标滚轮滚动
    private final List<AbstractWidget> scrollWidgets = new ArrayList<>();
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public SlotEditorScreen(RadialSlot slot) {
        super(Component.translatable("screen.radialmenu.editor.title"));
        this.slot = slot;

        this.oldName = slot.name;
        this.oldValue = slot.value;
        this.oldId = slot.itemId;
        this.oldIconColor = slot.iconColor;
        this.oldDescription = slot.description;
        this.oldNameColor = slot.nameColor;
        this.oldSelectedHint = slot.selectedHint;
        this.oldSelectedHintColor = slot.selectedHintColor;
        this.oldMode = slot.mode;
        this.oldChildCount = slot.childSlotCount;
        this.oldChildren = slot.children != null ? new java.util.ArrayList<>(slot.children) : null;
        this.oldMacros = slot.macros != null ? new java.util.ArrayList<>(slot.macros) : null;
        this.oldConditionEnabled = slot.conditionEnabled;
        this.oldCondition = slot.condition != null ? slot.condition.copy() : null;
        this.oldClickSound = slot.clickSound;
        this.oldToggleEnabled = slot.toggleEnabled;
        this.oldToggleState = slot.toggleState;
        this.oldToggleTint = slot.toggleTint;
        this.oldToggleTextOn = slot.toggleTextOn;
        this.oldToggleTextOff = slot.toggleTextOff;
        this.oldToggleActionOn = copySlot(slot.toggleActionOn);
        this.oldToggleActionOff = copySlot(slot.toggleActionOff);
        this.oldToggleSourceId = slot.toggleSourceId;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(300, (int) (width * 0.9));
        modeDropdowns.clear();

        LinearLayout mainLayout = LinearLayout.vertical().spacing(8);

        // --- ROW 1: Name + Name Color ---
        LinearLayout nameGroup = LinearLayout.vertical().spacing(2);
        StringWidget nameLabel = new StringWidget(Component.translatable("screen.radialmenu.editor.name"), font);
        nameGroup.addChild(nameLabel);

        LinearLayout nameRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int nameFieldWidth = contentWidth - 110 - HORIZ_GAP;

        nameField =
                new EditBox(font, 0, 0, nameFieldWidth, ROW_HEIGHT, Component.translatable("screen.radialmenu.editor.name"));
        nameField.setMaxLength(Integer.MAX_VALUE);
        nameField.setValue(slot.name);
        nameField.setResponder(v -> slot.name = v);
        nameRow.addChild(nameField);

        nameRow.addChild(IconEnabledSlotMode.buildArgbColorField(
                Component.translatable("screen.radialmenu.editor.name_color"),
                slot.nameColor,
                v -> slot.nameColor = v));

        nameGroup.addChild(nameRow);

        mainLayout.addChild(nameGroup);

        // --- ROW 2: 动作模式 (开关类型开启时隐藏, 改由两组状态动作替代) ---
        if (!slot.toggleEnabled) {
            LinearLayout modeGroup = LinearLayout.vertical().spacing(2);
            StringWidget modeLabel = new StringWidget(Component.translatable("screen.radialmenu.editor.mode"), font);
            modeGroup.addChild(modeLabel);

            List<SlotMode> availableModes = SlotModeRegistry.getRegisteredModes().values().stream()
                    .filter(SlotMode::isAvailable)
                    .filter(mode -> !mode.isMacroOnly())
                    .toList();

            modeDropdown = buildModeDropdown(slot, contentWidth, availableModes);
            modeGroup.addChild(modeDropdown);
            mainLayout.addChild(modeGroup);
        }

        // --- ROW 3: 动作值 + 图标设置 (开关类型开启时仅显示图标设置, 不含动作模式/动作值) ---
        LinearLayout dynamicLayoutContainer = LinearLayout.vertical().spacing(8);
        if (slot.toggleEnabled) {
            slot.mode.buildIconRowOnly(this, slot, contentWidth, dynamicLayoutContainer);
        } else {
            slot.mode.buildEditorWidgets(this, slot, contentWidth, dynamicLayoutContainer);
        }
        mainLayout.addChild(dynamicLayoutContainer);

        // --- ROW 4: Selected Hint (悬停时底部提示上方显示的文字) ---
        LinearLayout hintGroup = LinearLayout.vertical().spacing(2);
        hintGroup.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.selected_hint"), font));

        LinearLayout hintRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int hintFieldWidth = contentWidth - 110 - HORIZ_GAP;

        EditBox hintField = new IconEnabledSlotMode.GrayHintEditBox(
                font, 0, 0, hintFieldWidth, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.selected_hint"));
        hintField.setMaxLength(100);
        hintField.setValue(slot.selectedHint != null ? slot.selectedHint : "");
        hintField.setHint(Component.translatable("screen.radialmenu.editor.selected_hint.hint"));
        hintField.setResponder(v -> slot.selectedHint = v);
        hintRow.addChild(hintField);

        hintRow.addChild(IconEnabledSlotMode.buildArgbColorField(
                Component.translatable("screen.radialmenu.editor.selected_hint_color"),
                slot.selectedHintColor,
                v -> slot.selectedHintColor = v));

        hintGroup.addChild(hintRow);
        mainLayout.addChild(hintGroup);

        // --- ROW 4.5: 条件约束 (开关类型开启时移至两组状态动作下方, 此处仅非开关时显示) ---
        if (!slot.toggleEnabled) {
            mainLayout.addChild(buildConditionGroup(contentWidth));
        }

        // --- ROW 4.8: 点击音效 ---
        LinearLayout soundGroup = LinearLayout.vertical().spacing(2);
        soundGroup.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.click_sound"), font));

        LinearLayout soundRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int soundFieldWidth = contentWidth - 60 - 60 - HORIZ_GAP * 2;

        EditBox soundField = new EditBox(
                font, 0, 0, soundFieldWidth, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.click_sound"));
        soundField.setMaxLength(120);
        soundField.setValue(slot.clickSound != null ? slot.clickSound : "");
        soundField.setResponder(v -> slot.clickSound = v);
        soundRow.addChild(soundField);

        Button soundPickBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.sound.pick"),
                        b -> this.minecraft.setScreen(new SoundPickerScreen(this, id -> {
                            slot.clickSound = id;
                            soundField.setValue(id);
                        })))
                .bounds(0, 0, 60, ROW_HEIGHT)
                .build();
        soundRow.addChild(soundPickBtn);

        Button soundImportBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.sound.import"),
                        b -> {
                            String selected;
                            try (MemoryStack stack = MemoryStack.stackPush()) {
                                PointerBuffer filters = stack.pointers(stack.UTF8("*.mp3"));
                                selected = TinyFileDialogs.tinyfd_openFileDialog(
                                        "导入音效 (.mp3)", null, filters, "MP3 Audio", false);
                            }
                            if (selected != null) {
                                String id = ImportedSoundStore.importFromFile(
                                        new File(selected));
                                if (id != null) {
                                    slot.clickSound = id;
                                    soundField.setValue(id);
                                }
                            }
                        })
                .bounds(0, 0, 60, ROW_HEIGHT)
                .build();
        soundRow.addChild(soundImportBtn);

        soundGroup.addChild(soundRow);
        mainLayout.addChild(soundGroup);

        // --- ROW 4.9: 开关类型 ---
        LinearLayout toggleGroup = LinearLayout.vertical().spacing(2);
        toggleGroup.addChild(new StringWidget(Component.translatable("screen.radialmenu.editor.toggle_type"), font));

        LinearLayout toggleTypeRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        Button toggleTypeBtn = Button.builder(getToggleTypeLabel(), b -> {
                    slot.toggleEnabled = !slot.toggleEnabled;
                    if (slot.toggleEnabled) {
                        // 首次开启: 默认以当前动作配置作为两套状态动作的初始值
                        slot.ensureToggleActions();
                    }
                    b.setMessage(getToggleTypeLabel());
                    this.rebuildWidgets();
                })
                .bounds(0, 0, 60, ROW_HEIGHT)
                .build();
        toggleTypeRow.addChild(toggleTypeBtn);
        toggleTypeRow.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.toggle_type.desc"), font));
        toggleGroup.addChild(toggleTypeRow);

        // 外部开关来源: 只要开启了开关类型就显示。
        // 注意不要把它放进下面那个"两套状态动作均非空"的判断里 ——
        // 那样一旦动作为空(例如旧配置未补齐、粘贴异常), 这一行会跟着一起消失,
        // 而它本身并不依赖具体动作内容。
        if (slot.toggleEnabled) {
            toggleGroup.addChild(buildToggleSourceGroup(contentWidth));
        }

        if (slot.toggleEnabled && slot.toggleActionOn != null && slot.toggleActionOff != null) {
            // 图标状态色调: 关=红色系 / 开=绿色系, 可独立关闭
            LinearLayout tintGroup = LinearLayout.vertical().spacing(2);
            tintGroup.addChild(new StringWidget(
                    Component.translatable("screen.radialmenu.editor.toggle.tint"), font));
            LinearLayout tintRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
            Button tintBtn = Button.builder(getToggleTintLabel(), b -> {
                        slot.toggleTint = !slot.isToggleTintEnabled();
                        b.setMessage(getToggleTintLabel());
                    })
                    .bounds(0, 0, 60, ROW_HEIGHT)
                    .build();
            tintRow.addChild(tintBtn);
            tintRow.addChild(new StringWidget(
                    Component.translatable("screen.radialmenu.editor.toggle.tint.desc"), font));
            tintGroup.addChild(tintRow);
            toggleGroup.addChild(tintGroup);

            // 已成功绑定外部开关时: 点击槽位直接切换那个开关, 不再执行任何动作模式,
            // 因此隐藏"开启/关闭状态动作"的配置入口, 只保留两个状态各自的条件约束。
            boolean bound = slot.resolveToggleSource() != null;
            if (bound) {
                toggleGroup.addChild(buildConditionGroup(
                        Component.translatable("screen.radialmenu.editor.toggle.condition_on"),
                        slot.toggleActionOn, contentWidth));
                toggleGroup.addChild(buildConditionGroup(
                        Component.translatable("screen.radialmenu.editor.toggle.condition_off"),
                        slot.toggleActionOff, contentWidth));
            } else {
                // 本地手动开关: 保留原有的两套状态动作(各自带条件约束)。
                // 子菜单属于导航而非动作, 不作为开关状态动作
                List<SlotMode> stateModes = SlotModeRegistry.getRegisteredModes().values().stream()
                        .filter(SlotMode::isAvailable)
                        .filter(mode -> !mode.isMacroOnly())
                        .filter(mode -> !(mode instanceof SubmenuSlotMode))
                        .toList();

                toggleGroup.addChild(buildStateActionGroup(
                        Component.translatable("screen.radialmenu.editor.toggle.action_on"),
                        slot.toggleActionOn, contentWidth, stateModes));
                toggleGroup.addChild(buildStateActionGroup(
                        Component.translatable("screen.radialmenu.editor.toggle.action_off"),
                        slot.toggleActionOff, contentWidth, stateModes));
            }
        }

        mainLayout.addChild(toggleGroup);

        // --- ROW 5: Action Buttons ---
        LinearLayout actionGroup = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int actionBtnWidth = (contentWidth - HORIZ_GAP) / 2;

        Button saveButton = Button.builder(Component.translatable("screen.radialmenu.editor.save"), b -> {
                    this.isSaved = true;
                    RadialConfig.save();
                    onClose();
                })
                .bounds(0, 0, actionBtnWidth, ROW_HEIGHT)
                .build();
        actionGroup.addChild(saveButton);

        Button cancelButton = Button.builder(Component.translatable("screen.radialmenu.editor.cancel"), b -> onClose())
                .bounds(0, 0, actionBtnWidth, ROW_HEIGHT)
                .build();
        actionGroup.addChild(cancelButton);

        mainLayout.addChild(actionGroup);

        // --- ROW 5: Slot Management ---
        LinearLayout managementGroup = LinearLayout.horizontal().spacing(HORIZ_GAP);
        int mgmtBtnWidth = (contentWidth - HORIZ_GAP * 2) / 3;

        Button copyBtn = Button.builder(Component.translatable("screen.radialmenu.editor.copy"), b -> copyToClipboard())
                .bounds(0, 0, mgmtBtnWidth, ROW_HEIGHT)
                .build();
        managementGroup.addChild(copyBtn);

        Button pasteBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.paste"), b -> pasteFromClipboard())
                .bounds(0, 0, mgmtBtnWidth, ROW_HEIGHT)
                .build();
        managementGroup.addChild(pasteBtn);

        Button resetBtn = Button.builder(Component.translatable("screen.radialmenu.editor.reset"), b -> resetSlot())
                .bounds(0, 0, mgmtBtnWidth, ROW_HEIGHT)
                .build();
        managementGroup.addChild(resetBtn);

        mainLayout.addChild(managementGroup);

        // --- FINAL ASSEMBLY ---
        FrameLayout rootLayout = new FrameLayout();
        rootLayout.addChild(mainLayout);
        rootLayout.arrangeElements();

        // 内容高度超出可用区域时启用滚动(Bug7): 顶部对齐, 为钉在顶部的图标预留空间
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
            // Offset the Y position down by 20 to ensure room at the top of the screen for the icon
            FrameLayout.centerInRectangle(rootLayout, 0, 20, width, height);
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

    /** 构建条件约束组(开关/配置按钮); 开关类型开启时移至两组状态动作下方, 关闭时保留原位。 */
    private LinearLayout buildConditionGroup(int contentWidth) {
        return buildConditionGroup(slot, contentWidth);
    }

    /** 为指定槽位构建条件约束组; 开关类型的状态动作各自独立配置条件。 */
    private LinearLayout buildConditionGroup(RadialSlot target, int contentWidth) {
        return buildConditionGroup(Component.translatable("screen.radialmenu.editor.condition"),
                target, contentWidth);
    }

    /**
     * 带自定义标题的条件约束组。
     * <p>
     * 绑定外部开关时不显示状态动作, 两个状态的条件约束需要各自的标题
     * ("开启状态条件约束"/"关闭状态条件约束")来区分。
     */
    private LinearLayout buildConditionGroup(Component title, RadialSlot target, int contentWidth) {
        LinearLayout conditionGroup = LinearLayout.vertical().spacing(2);
        conditionGroup.addChild(new StringWidget(title, font));

        LinearLayout conditionRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        Button conditionConfigBtn = Button.builder(
                        Component.translatable("screen.radialmenu.editor.condition.configure"),
                        b -> this.minecraft.setScreen(new ConditionEditorScreen(this, target)))
                .bounds(0, 0, contentWidth - 60 - HORIZ_GAP, ROW_HEIGHT)
                .build();
        conditionConfigBtn.active = target.conditionEnabled;

        Button conditionToggle = Button.builder(
                        onOffLabel(target.conditionEnabled),
                        b -> {
                    target.conditionEnabled = !target.conditionEnabled;
                    if (target.conditionEnabled && target.condition == null) {
                        target.condition = new SlotCondition();
                    }
                    conditionConfigBtn.active = target.conditionEnabled;
                    b.setMessage(onOffLabel(target.conditionEnabled));
                })
                .bounds(0, 0, 60, ROW_HEIGHT)
                .build();
        conditionRow.addChild(conditionToggle);
        conditionRow.addChild(conditionConfigBtn);

        conditionGroup.addChild(conditionRow);
        return conditionGroup;
    }

    private Component getToggleTypeLabel() {
        return onOffLabel(slot.toggleEnabled);
    }

    private Component getToggleTintLabel() {
        return onOffLabel(slot.isToggleTintEnabled());
    }

    /** 开关类控件的开/关标签。 */
    private static Component onOffLabel(boolean on) {
        return Component.translatable(on
                ? "screen.radialmenu.editor.toggle.on"
                : "screen.radialmenu.editor.toggle.off");
    }

    /** 构建动作模式下拉(注册进 modeDropdowns 统一处理悬浮菜单), 选中后写回目标槽位并重建界面。 */
    private DropdownButtonWidget<SlotMode> buildModeDropdown(RadialSlot targetSlot, int width, List<SlotMode> availableModes) {
        DropdownButtonWidget<SlotMode> dropdown = new DropdownButtonWidget<>(
                0,
                0,
                width,
                ROW_HEIGHT,
                availableModes,
                targetSlot.mode,
                SlotMode::getTranslatedName,
                selectedMode -> {
                    targetSlot.mode = selectedMode;
                    selectedMode.onInitialize(targetSlot);
                    this.rebuildWidgets();
                },
                this::addRenderableWidget) {
            @Override
            public void closeMenu() {
                if (this.isMenuOpen()) {
                    SlotEditorScreen.this.removeWidget(this.getActiveMenu());
                }
                super.closeMenu();
            }
        };
        modeDropdowns.add(dropdown);
        return dropdown;
    }

    /**
     * 构建"外部开关来源"编辑行。
     * <p>
     * 绑定后开关状态直接来自目标模组(每帧只读查询), 激活槽位会写入目标模组;
     * 未绑定时沿用本地手动开关(向后兼容)。
     */
    private LinearLayout buildToggleSourceGroup(int contentWidth) {
        LinearLayout group = LinearLayout.vertical().spacing(2);
        group.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.toggle.source"), font));

        LinearLayout row = LinearLayout.horizontal().spacing(HORIZ_GAP);

        // 显示当前解析到的来源名; 找不到时显示未绑定即退回本地状态
        Component label;
        ToggleSource source = slot.resolveToggleSource();
        if (source != null) {
            String mod = source.modName().getString();
            label = mod == null || mod.isBlank()
                    ? source.displayName()
                    : Component.literal(mod + " · ").append(source.displayName());
        } else if (slot.hasToggleSource()) {
            // 已绑定但来源当前不可用
            label = Component.translatable("screen.radialmenu.editor.toggle.source.missing", slot.toggleSourceId);
        } else {
            label = Component.translatable("screen.radialmenu.editor.toggle.source.local");
        }

        Button pick = Button.builder(label, b -> Minecraft.getInstance()
                        .setScreen(new ToggleSourceSelectionScreen(this, id -> {
                            slot.toggleSourceId = id;
                            this.rebuildWidgets();
                        })))
                .bounds(0, 0, contentWidth - 65 - HORIZ_GAP, ROW_HEIGHT)
                .build();
        row.addChild(pick);

        Button clear = Button.builder(
                        Component.translatable("screen.radialmenu.editor.toggle.source.clear"),
                        b -> {
                            slot.toggleSourceId = null;
                            this.rebuildWidgets();
                        })
                .bounds(0, 0, 65, ROW_HEIGHT)
                .build();
        clear.active = slot.hasToggleSource();
        row.addChild(clear);

        group.addChild(row);
        group.addChild(new StringWidget(
                Component.translatable("screen.radialmenu.editor.toggle.source.desc"), font));

        return group;
    }

    /** 构建一组状态动作编辑入口: 标题 + 动作模式下拉 + 动作值编辑行 + 条件约束。 */
    private LinearLayout buildStateActionGroup(Component title, RadialSlot actionSlot, int width, List<SlotMode> availableModes) {
        LinearLayout group = LinearLayout.vertical().spacing(2);
        if (actionSlot == null) {
            return group;
        }

        group.addChild(new StringWidget(title, font));
        group.addChild(buildModeDropdown(actionSlot, width, availableModes));

        LinearLayout valueContainer = LinearLayout.vertical().spacing(8);
        buildStateValueRow(actionSlot, width, valueContainer);
        group.addChild(valueContainer);

        // 每个状态动作各自独立配置条件约束
        group.addChild(buildConditionGroup(actionSlot, width));
        return group;
    }

    /** 按状态动作的模式构建动作值编辑行: 宏模式走宏编辑器, 空操作无值, 其余为输入框+选择按钮。 */
    private void buildStateValueRow(RadialSlot actionSlot, int width, LinearLayout container) {
        SlotMode mode = actionSlot.mode;

        if (mode instanceof MacroSlotMode) {
            int macroCount = actionSlot.macros != null ? actionSlot.macros.size() : 0;
            Button editButton = Button.builder(
                            Component.translatable("screen.radialmenu.editor.macro.edit", macroCount, MacroSlotMode.MAX_ACTIONS),
                            b -> this.minecraft.setScreen(new MacroEditorScreen(this, actionSlot)))
                    .bounds(0, 0, width, ROW_HEIGHT)
                    .build();
            container.addChild(editButton);
            return;
        }

        if (mode instanceof EmptySlotMode) {
            return; // 无动作值可编辑
        }

        boolean hasPicker = mode.hasValuePicker();
        int valueFieldWidth = hasPicker ? width - 55 - HORIZ_GAP : width;

        LinearLayout valueGroup = LinearLayout.vertical().spacing(2);
        valueGroup.addChild(new StringWidget(Component.translatable("screen.radialmenu.editor.value"), font));

        LinearLayout inputRow = LinearLayout.horizontal().spacing(HORIZ_GAP);
        EditBox valueField = new EditBox(
                font, 0, 0, valueFieldWidth, ROW_HEIGHT,
                Component.translatable("screen.radialmenu.editor.value"));
        valueField.setMaxLength(Integer.MAX_VALUE);
        valueField.setValue(actionSlot.value != null ? actionSlot.value : "");
        valueField.setHint(mode.getValueHint());
        valueField.setResponder(v -> actionSlot.value = v);
        inputRow.addChild(valueField);

        if (hasPicker) {
            Button pickButton = Button.builder(
                            Component.translatable("screen.radialmenu.editor.select"),
                            b -> mode.openValuePicker(this, id -> {
                                valueField.setValue(id);
                                actionSlot.value = id;
                            }))
                    .bounds(0, 0, 55, ROW_HEIGHT)
                    .build();
            inputRow.addChild(pickButton);
        }

        valueGroup.addChild(inputRow);
        container.addChild(valueGroup);
    }

    /** 槽位深拷贝(Gson 往返), 用于开关状态动作的快照与粘贴隔离。 */
    private static RadialSlot copySlot(RadialSlot source) {
        if (source == null) {
            return null;
        }
        RadialSlot copy = RadialConfig.GSON.fromJson(RadialConfig.GSON.toJson(source), RadialSlot.class);
        if (copy != null) {
            copy.sanitize();
        }
        return copy;
    }

    private void copyToClipboard() {
        try {
            String json = RadialConfig.GSON.toJson(this.slot);
            this.minecraft.keyboardHandler.setClipboard(json);
            SystemToast.add(
                    this.minecraft.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("screen.radialmenu.editor.toast.copied"),
                    Component.translatable("screen.radialmenu.editor.toast.copied.desc"));
        } catch (Exception e) {
            SystemToast.add(
                    this.minecraft.getToasts(),
                    SystemToast.SystemToastId.PACK_COPY_FAILURE,
                    Component.translatable("screen.radialmenu.editor.toast.copy_failed"),
                    Component.literal(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : Component.translatable("screen.radialmenu.editor.error.unknown")
                                            .getString()));
        }
    }

    private void pasteFromClipboard() {
        try {
            String json = this.minecraft.keyboardHandler.getClipboard();
            if (json.trim().isEmpty()) {
                throw new IllegalArgumentException(Component.translatable("screen.radialmenu.editor.error.empty_clipboard")
                        .getString());
            }

            RadialSlot pasted = RadialConfig.GSON.fromJson(json, RadialSlot.class);
            if (pasted == null) {
                throw new IllegalArgumentException(Component.translatable("screen.radialmenu.editor.error.invalid_json")
                        .getString());
            }

            pasted.sanitize();

            // Apply directly onto current slot reference
            this.slot.name = pasted.name;
            this.slot.mode = pasted.mode;
            this.slot.value = pasted.value;
            this.slot.itemId = pasted.itemId;
            this.slot.iconColor = pasted.iconColor;
            this.slot.description = pasted.description;
            this.slot.nameColor = pasted.nameColor;
            this.slot.selectedHint = pasted.selectedHint;
            this.slot.selectedHintColor = pasted.selectedHintColor;
            this.slot.childSlotCount = pasted.childSlotCount;
            this.slot.children = pasted.children;
            this.slot.macros = pasted.macros;
            this.slot.conditionEnabled = pasted.conditionEnabled;
            this.slot.condition = pasted.condition != null ? pasted.condition.copy() : null;
            this.slot.clickSound = pasted.clickSound;
            this.slot.toggleEnabled = pasted.toggleEnabled;
            this.slot.toggleState = pasted.toggleState;
            this.slot.toggleTint = pasted.toggleTint;
            this.slot.toggleTextOn = pasted.toggleTextOn;
            this.slot.toggleTextOff = pasted.toggleTextOff;
            this.slot.toggleActionOn = copySlot(pasted.toggleActionOn);
            this.slot.toggleActionOff = copySlot(pasted.toggleActionOff);
            this.slot.toggleSourceId = pasted.toggleSourceId;
            this.slot.clearCache();

            // Screen native rebuild function refreshes UI with new values
            this.rebuildWidgets();

            SystemToast.add(
                    this.minecraft.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("screen.radialmenu.editor.toast.pasted"),
                    Component.translatable("screen.radialmenu.editor.toast.pasted.desc"));
        } catch (Exception e) {
            SystemToast.add(
                    this.minecraft.getToasts(),
                    SystemToast.SystemToastId.PACK_COPY_FAILURE,
                    Component.translatable("screen.radialmenu.editor.toast.paste_failed"),
                    Component.literal(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : Component.translatable("screen.radialmenu.editor.error.unknown")
                                            .getString()));
        }
    }

    private void resetSlot() {
        this.slot.name = "";
        this.slot.mode = SlotModeRegistry.getDefaultMode();
        this.slot.value = "";
        this.slot.itemId = "minecraft:air";
        this.slot.iconColor = null;
        this.slot.description = "";
        this.slot.nameColor = null;
        this.slot.selectedHint = "";
        this.slot.selectedHintColor = null;
        this.slot.childSlotCount = 8;
        this.slot.children = null;
        this.slot.macros = null;
        this.slot.conditionEnabled = false;
        this.slot.condition = null;
        this.slot.clickSound = "minecraft:block.note_block.pling";
        this.slot.toggleEnabled = false;
        this.slot.toggleState = false;
        this.slot.toggleTint = null;
        this.slot.toggleTextOn = RadialSlot.DEFAULT_TOGGLE_TEXT_ON;
        this.slot.toggleTextOff = RadialSlot.DEFAULT_TOGGLE_TEXT_OFF;
        this.slot.toggleActionOn = null;
        this.slot.toggleActionOff = null;
        this.slot.toggleSourceId = null;
        this.slot.clearCache();

        this.rebuildWidgets();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // 本界面自行绘制 fillGradient 背景, 不使用原版背景模糊:
        // 原版 Screen.renderBackground 会调 renderBlurredBackground + renderMenuBackground,
        // 模糊后处理会把已绘制的图标预览一起糊掉
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);

        // 图标预览在 super.render 之前画 (无背景模糊, 不会被糊掉)
        int centerX = width / 2;
        boolean scrolling = maxScroll > 0;
        int iconY = scrolling
                ? 10
                : ((nameField != null) ? nameField.getY() - SLOT_SIZE - 20 : height / 2 - 110);

        graphics.blitSprite(SlotRenderHelper.SLOT_TEXTURE, centerX - 13, iconY, SLOT_SIZE, SLOT_SIZE);
        SlotRenderHelper.renderSlotIcon(graphics, slot, centerX - 13, iconY, 255);

        // --- THE MOUSE SPOOFING TRICK ---
        // 任意动作模式下拉(主模式/状态动作)的悬浮菜单打开时, 遮挡鼠标避免触发下方控件
        DropdownMenuWidget<SlotMode> openMenu = null;
        for (DropdownButtonWidget<SlotMode> dropdown : modeDropdowns) {
            if (dropdown != null && dropdown.isMenuOpen()) {
                openMenu = dropdown.getActiveMenu();
                break;
            }
        }

        boolean hoveringMenu = openMenu != null && openMenu.isMouseOver(mouseX, mouseY);

        int passMouseX = hoveringMenu ? -999 : mouseX;
        int passMouseY = hoveringMenu ? -999 : mouseY;

        super.render(graphics, passMouseX, passMouseY, delta);

        if (hoveringMenu) {
            openMenu.render(graphics, mouseX, mouseY, delta);
        }
    }

    @Override
    public void onClose() {
        if (!this.isSaved) {
            slot.name = oldName;
            slot.value = oldValue;
            slot.itemId = oldId;
            slot.iconColor = oldIconColor;
            slot.description = oldDescription;
            slot.nameColor = oldNameColor;
            slot.selectedHint = oldSelectedHint;
            slot.selectedHintColor = oldSelectedHintColor;
            slot.mode = oldMode;
            slot.childSlotCount = oldChildCount;
            slot.conditionEnabled = oldConditionEnabled;
            slot.condition = oldCondition != null ? oldCondition.copy() : null;
            slot.clickSound = oldClickSound;

            if (oldChildren != null) {
                slot.children = new java.util.ArrayList<>(oldChildren);
            } else {
                slot.children = null;
            }

            if (oldMacros != null) {
                slot.macros = new java.util.ArrayList<>(oldMacros);
            } else {
                slot.macros = null;
            }

            slot.toggleEnabled = oldToggleEnabled;
            slot.toggleState = oldToggleState;
            slot.toggleTint = oldToggleTint;
            slot.toggleTextOn = oldToggleTextOn;
            slot.toggleTextOff = oldToggleTextOff;
            slot.toggleActionOn = oldToggleActionOn;
            slot.toggleActionOff = oldToggleActionOff;
            slot.toggleSourceId = oldToggleSourceId;

            slot.clearCache();
        }

        this.minecraft.setScreen(null);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (DropdownButtonWidget<SlotMode> dropdown : modeDropdowns) {
            if (dropdown != null && dropdown.isMenuOpen()) {
                DropdownMenuWidget<SlotMode> floatingMenu = dropdown.getActiveMenu();

                if (floatingMenu.isMouseOver(mouseX, mouseY)) {
                    floatingMenu.mouseClicked(mouseX, mouseY, button);
                    return true;
                } else //noinspection StatementWithEmptyBody
                if (dropdown.isMouseOver(mouseX, mouseY)) {
                    // Let the click fall through so the button can close itself
                } else {
                    dropdown.closeMenu();
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The floating menu overlaps other widgets, which would otherwise receive the scroll first
        for (DropdownButtonWidget<SlotMode> dropdown : modeDropdowns) {
            if (dropdown != null && dropdown.isMenuOpen()) {
                DropdownMenuWidget<SlotMode> floatingMenu = dropdown.getActiveMenu();

                if (floatingMenu.isMouseOver(mouseX, mouseY)) {
                    return floatingMenu.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
                }

                // 菜单外滚动时先收起菜单, 避免菜单与滚动后的按钮错位
                dropdown.closeMenu();
            }
        }

        // Bug7: 滚轮滚动编辑器内容
        if (maxScroll > 0) {
            int newOffset = (int) (scrollOffset - scrollY * 18);
            scrollOffset = Math.clamp(newOffset, 0, maxScroll);
            applyScroll();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
