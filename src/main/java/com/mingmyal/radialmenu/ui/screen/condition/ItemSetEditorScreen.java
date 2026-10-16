package com.mingmyal.radialmenu.ui.screen.condition;

import com.mingmyal.radialmenu.api.ItemRequirement;
import com.mingmyal.radialmenu.ui.screen.iconpicker.IconPickerScreen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 物品集合成员编辑器。
 * <p>
 * 集合的成员可能非常多(同一 mod 物品的各种颜色/款式), 因此成员列表放进独立的可滚动界面,
 * 不再铺在条件编辑器里撑爆屏幕。列表由 {@link ContainerObjectSelectionList} 提供滚动条。
 * <p>
 * 直接编辑传入的 {@link ItemRequirement}(条件编辑器的工作副本), "取消"时从快照还原。
 * 集合的数量/耐久由集合统一提供, 因此本界面只编辑成员物品 ID。
 */
public class ItemSetEditorScreen extends Screen {

    private static final int HEADER_HEIGHT = 46;
    private static final int FOOTER_HEIGHT = 34;
    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_HEIGHT = 20;
    private static final int PICKER_WIDTH = 46;
    private static final int DELETE_WIDTH = 20;
    private static final int GAP = 4;
    private static final int CONTENT_PADDING = 2;
    private static final int BUTTON_WIDTH = 120;

    private final Screen parent;
    private final ItemRequirement set;

    // 取消时还原
    private final List<ItemRequirement> oldMembers;
    private boolean isSaved = false;

    private MemberList list;

    public ItemSetEditorScreen(Screen parent, ItemRequirement set) {
        super(Component.translatable("screen.radialmenu.condition.set.editor_title"));
        this.parent = parent;
        this.set = set;

        if (this.set.members == null) {
            this.set.members = new ArrayList<>();
        }
        this.oldMembers = new ArrayList<>();
        for (ItemRequirement member : this.set.members) {
            this.oldMembers.add(member != null ? member.copy() : null);
        }
    }

    @Override
    protected void init() {
        double previousScroll = this.list != null ? this.list.getScrollAmount() : 0.0;

        this.list = new MemberList(
                this.minecraft,
                this.width,
                Math.max(ROW_HEIGHT, this.height - HEADER_HEIGHT - FOOTER_HEIGHT),
                HEADER_HEIGHT);
        this.list.setScrollAmount(previousScroll);
        this.addRenderableWidget(this.list);

        int centerX = this.width / 2;
        int totalWidth = BUTTON_WIDTH * 2 + GAP;
        int left = centerX - totalWidth / 2;

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.radialmenu.editor.save"), b -> {
                            this.isSaved = true;
                            this.onClose();
                        })
                .bounds(left, this.height - 28, BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.radialmenu.editor.cancel"), b -> this.onClose())
                .bounds(left + BUTTON_WIDTH + GAP, this.height - 28, BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // 成员计数, 达到上限时转为红色
        int count = this.set.members.size();
        Component counter = Component.translatable(
                "screen.radialmenu.condition.set.member_count",
                count,
                ItemRequirement.MAX_SET_MEMBERS);
        graphics.drawCenteredString(
                this.font,
                counter,
                this.width / 2,
                24,
                count >= ItemRequirement.MAX_SET_MEMBERS ? 0xFFFF5555 : 0xFFA0A0A0);

        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.radialmenu.condition.set.shared_values"),
                this.width / 2,
                35,
                0xFF808080);
    }

    @Override
    public void onClose() {
        if (!this.isSaved) {
            this.set.members = new ArrayList<>(this.oldMembers);
        }

        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 成员列表。最后一行是"添加成员"按钮, 始终留在末尾。
     */
    private class MemberList extends ContainerObjectSelectionList<MemberList.Row> {

        private final AddEntry addRow;

        private MemberList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);

            for (ItemRequirement member : ItemSetEditorScreen.this.set.members) {
                this.addEntry(new MemberEntry(member));
            }

            this.addRow = new AddEntry();
            this.addEntry(this.addRow);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, this.getWidth() - 60);
        }

        private boolean canAddMember() {
            return ItemSetEditorScreen.this.set.members.size() < ItemRequirement.MAX_SET_MEMBERS;
        }

        private void addMember() {
            if (!this.canAddMember()) return;

            ItemRequirement member = new ItemRequirement();
            ItemSetEditorScreen.this.set.members.add(member);

            // 插在"添加成员"行之前, 保持它在末尾
            this.removeEntry(this.addRow);
            this.addEntry(new MemberEntry(member));
            this.addEntry(this.addRow);
            this.setScrollAmount(this.getMaxScroll());
        }

        private void removeMember(MemberEntry entry) {
            int index = this.children().indexOf(entry);
            if (index < 0) return;

            ItemSetEditorScreen.this.set.members.remove(index);
            this.removeEntry(entry);
            this.clampScrollAmount();
        }

        private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}

        private class AddEntry extends Row {

            private final Button button = Button.builder(
                            Component.translatable("screen.radialmenu.condition.set.add_member"),
                            b -> MemberList.this.addMember())
                    .build();

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
                this.button.active = MemberList.this.canAddMember();
                this.button.setPosition(left + CONTENT_PADDING, rowTop);
                this.button.setSize(rowWidth - CONTENT_PADDING * 2, WIDGET_HEIGHT);
                this.button.setMessage(MemberList.this.canAddMember()
                        ? Component.translatable("screen.radialmenu.condition.set.add_member")
                        : Component.translatable(
                                "screen.radialmenu.condition.set.member_limit",
                                ItemRequirement.MAX_SET_MEMBERS));
                this.button.render(graphics, mouseX, mouseY, delta);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(this.button);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(this.button);
            }
        }

        private class MemberEntry extends Row {

            private final ItemRequirement member;
            private final EditBox idField;
            private final Button pickerButton;
            private final Button deleteButton;

            private MemberEntry(ItemRequirement member) {
                this.member = member;
                Font font = MemberList.this.minecraft.font;

                this.idField = new EditBox(
                        font,
                        0,
                        0,
                        100,
                        WIDGET_HEIGHT,
                        Component.translatable("screen.radialmenu.condition.item_id"));
                this.idField.setMaxLength(220);
                this.idField.setValue(member.itemId != null ? member.itemId : "");
                this.idField.setHint(Component.literal("minecraft:diamond"));
                this.idField.setResponder(value -> this.member.itemId = value.trim());

                this.pickerButton = Button.builder(
                                Component.translatable("screen.radialmenu.editor.icon_picker.inventory"),
                                b -> MemberList.this.minecraft.setScreen(IconPickerScreen.createItemPicker(
                                        ItemSetEditorScreen.this,
                                        this.member.itemId,
                                        id -> {
                                            this.member.itemId = id;
                                            this.idField.setValue(id);
                                        })))
                        .size(PICKER_WIDTH, WIDGET_HEIGHT)
                        .build();

                this.deleteButton = Button.builder(Component.literal("✕"), b -> MemberList.this.removeMember(this))
                        .size(DELETE_WIDTH, WIDGET_HEIGHT)
                        .tooltip(Tooltip.create(Component.translatable("screen.radialmenu.condition.remove")))
                        .build();
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
                int bottom = rowTop + ROW_HEIGHT;

                graphics.fill(left, top, right, bottom, hovered ? 0x30FFFFFF : 0x18FFFFFF);

                // 序号
                graphics.drawString(
                        MemberList.this.minecraft.font,
                        String.valueOf(index + 1),
                        left + CONTENT_PADDING + 2,
                        top + (ROW_HEIGHT - 8) / 2,
                        0xFF808080);

                int deleteX = right - DELETE_WIDTH;
                int pickerX = deleteX - GAP - PICKER_WIDTH;
                int idX = left + CONTENT_PADDING + 16;
                int idRight = pickerX - GAP;

                this.idField.setPosition(idX, top);
                this.idField.setWidth(Math.max(20, idRight - idX));
                this.pickerButton.setPosition(pickerX, top);
                this.deleteButton.setPosition(deleteX, top);

                this.idField.render(graphics, mouseX, mouseY, delta);
                this.pickerButton.render(graphics, mouseX, mouseY, delta);
                this.deleteButton.render(graphics, mouseX, mouseY, delta);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(this.idField, this.pickerButton, this.deleteButton);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(this.idField, this.pickerButton, this.deleteButton);
            }
        }
    }
}
