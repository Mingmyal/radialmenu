package com.mingmyal.radialmenu.ui.widget;

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public class DropdownMenuWidget<T> extends AbstractWidget {
    private static final int MAX_VISIBLE_ITEMS = 6;

    private static final ResourceLocation SPRITE_HIGHLIGHTED =
            ResourceLocation.fromNamespaceAndPath("minecraft", "widget/text_field_highlighted");

    private final List<T> options;
    private final T currentSelection;
    private final Function<T, Component> labelMapper;
    private final DropdownButtonWidget<T> parentButton;
    private final int itemHeight;

    private double scrollAmount = 0;

    public DropdownMenuWidget(
            int x,
            int y,
            int width,
            int itemHeight,
            List<T> options,
            T currentSelection,
            Function<T, Component> labelMapper,
            DropdownButtonWidget<T> parentButton) {

        super(x, y, width, Math.min(options.size(), MAX_VISIBLE_ITEMS) * itemHeight, Component.empty());

        this.options = options;
        this.currentSelection = currentSelection;
        this.labelMapper = labelMapper;
        this.parentButton = parentButton;
        this.itemHeight = itemHeight;
    }

    private int getMaxScroll() {
        return Math.max(0, (this.options.size() * this.itemHeight) - this.height);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= this.getX()
                && mouseX < this.getX() + this.getWidth()
                && mouseY >= this.getY()
                && mouseY < this.getY() + this.getHeight();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {

        /*
         * Only consume the event when the mouse is actually inside
         * the dropdown. This prevents the event from being passed
         * to unrelated widgets.
         */
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        }

        int maxScroll = getMaxScroll();

        if (maxScroll <= 0) {
            // Still consume the event because the dropdown is hovered.
            return true;
        }

        this.scrollAmount -= scrollY * this.itemHeight;

        this.scrollAmount = Mth.clamp(this.scrollAmount, 0, maxScroll);

        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {

        if (this.isMouseOver(mouseX, mouseY)) {
            this.onClick(mouseX, mouseY);
            return true;
        }

        /*
         * Clicking outside the dropdown closes it.
         */
        this.parentButton.closeMenu();

        return false;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {

        int index = (int) ((mouseY - this.getY() + this.scrollAmount) / this.itemHeight);

        if (index >= 0 && index < this.options.size()) {
            this.playDownSound(Minecraft.getInstance().getSoundManager());

            T selected = this.options.get(index);

            this.parentButton.updateSelection(selected);
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // 使用 z 偏移将下拉菜单置顶,并填充完全不透明背景挡住下方UI文字
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);

        graphics.fill(
                this.getX() - 2,
                this.getY() - 2,
                this.getX() + this.getWidth() + 2,
                this.getY() + this.getHeight() + 2,
                0xFF000000);
        graphics.fill(
                this.getX() - 1,
                this.getY() - 1,
                this.getX() + this.getWidth() + 1,
                this.getY() + this.getHeight() + 1,
                0xFF101010);

        /*
         * Dropdown background/border.
         */
        graphics.blitSprite(
                SPRITE_HIGHLIGHTED,
                this.getX(),
                this.getY(),
                this.getWidth(),
                this.getHeight());

        /*
         * Clip item rendering to the dropdown bounds.
         */
        graphics.enableScissor(
                this.getX() + 1,
                this.getY() + 1,
                this.getX() + this.getWidth() - 1,
                this.getY() + this.getHeight() - 1);

        for (int i = 0; i < this.options.size(); i++) {
            T option = this.options.get(i);

            int itemY = (int) (this.getY() + (i * this.itemHeight) - this.scrollAmount);

            /*
             * Skip items that are completely outside the visible area.
             */
            if (itemY + this.itemHeight < this.getY() || itemY > this.getY() + this.getHeight()) {
                continue;
            }

            boolean isItemHovered = mouseX >= this.getX()
                    && mouseX < this.getX() + this.getWidth()
                    && mouseY >= itemY
                    && mouseY < itemY + this.itemHeight
                    && mouseY >= this.getY()
                    && mouseY < this.getY() + this.getHeight();

            /*
             * Hover highlight.
             */
            if (isItemHovered) {
                graphics.blitSprite(
                        SPRITE_HIGHLIGHTED,
                        this.getX(),
                        itemY,
                        this.getWidth(),
                        this.itemHeight);
            }

            Component text = this.labelMapper.apply(option);

            int optionColor = option.equals(this.currentSelection) ? 0xFF55FF55 : 0xFFFFFFFF;

            graphics.drawString(font, text, this.getX() + 4, itemY + (this.itemHeight - 8) / 2, optionColor);
        }

        graphics.disableScissor();

        /*
         * Scrollbar.
         */
        int maxScroll = getMaxScroll();

        if (maxScroll > 0) {
            int scrollbarWidth = 2;

            int scrollbarHeight = Math.max(
                    8, (int) ((float) this.getHeight() * this.getHeight() / (this.options.size() * this.itemHeight)));

            int scrollbarX = this.getX() + this.getWidth() - scrollbarWidth - 2;

            int scrollbarTrackHeight = this.getHeight() - 2;

            int scrollbarY = this.getY()
                    + 1
                    + (int) ((this.scrollAmount / maxScroll) * (scrollbarTrackHeight - scrollbarHeight));

            graphics.fill(
                    scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, 0x80888888);
        }

        graphics.pose().popPose();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {}
}
