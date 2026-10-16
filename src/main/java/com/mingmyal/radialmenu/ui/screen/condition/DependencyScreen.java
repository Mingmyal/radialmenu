package com.mingmyal.radialmenu.ui.screen.condition;

import com.mingmyal.radialmenu.api.SlotCondition;
import com.mingmyal.radialmenu.client.ConditionDisplay;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 解锁依赖查看界面。
 * <p>
 * 依赖条目可能非常多(集合成员可展开到上百条), 因此放进独立的可滚动界面,
 * 而不是盖在轮盘上的固定面板 —— 后者条目一多就会溢出屏幕。
 * <p>
 * 本界面只读: 每行按当前是否满足着绿/红, 关闭后回到调用方指定的界面(通常是轮盘)。
 */
public class DependencyScreen extends Screen {

    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 34;
    private static final int ROW_HEIGHT = 14;
    private static final int BUTTON_WIDTH = 120;
    private static final int LINE_INDENT = 10;

    private final Screen parent;
    private final List<Component> lines;

    public DependencyScreen(Screen parent, SlotCondition condition) {
        super(Component.translatable("radialmenu.ui.dependency.title"));
        this.parent = parent;
        this.lines = new ArrayList<>(ConditionDisplay.describeDependencyLines(condition));
    }

    @Override
    protected void init() {
        DependencyList list = new DependencyList(
                this.minecraft,
                this.width,
                Math.max(ROW_HEIGHT, this.height - HEADER_HEIGHT - FOOTER_HEIGHT),
                HEADER_HEIGHT);
        this.addRenderableWidget(list);

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - BUTTON_WIDTH / 2, this.height - 28, BUTTON_WIDTH, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        super.render(graphics, mouseX, mouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // 图例: ✔ 已满足 / ✘ 未满足
        Component legend = Component.translatable("radialmenu.ui.dependency.legend");
        graphics.drawCenteredString(this.font, legend, this.width / 2, 21, 0xFF808080);
    }

    @Override
    public void onClose() {
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
     * 依赖行列表。第一行为条件总述(中性色), 其余行按满足与否着绿/红。
     */
    private class DependencyList extends ContainerObjectSelectionList<DependencyList.LineEntry> {

        private DependencyList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);

            for (int i = 0; i < DependencyScreen.this.lines.size(); i++) {
                this.addEntry(new LineEntry(DependencyScreen.this.lines.get(i), i == 0));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(460, this.getWidth() - 60);
        }

        private class LineEntry extends ContainerObjectSelectionList.Entry<LineEntry> {

            private final Component line;
            private final boolean summary;

            private LineEntry(Component line, boolean summary) {
                this.line = line;
                this.summary = summary;
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
                int color;
                if (this.summary) {
                    color = 0xFFCCCCCC;
                } else {
                    color = ConditionDisplay.isDependencySatisfied(this.line)
                            ? SlotCondition.GREEN
                            : SlotCondition.RED;
                }

                // 换行: 总述行顶格, 明细行缩进
                int x = this.summary ? left : left + LINE_INDENT;
                int maxWidth = rowWidth - (this.summary ? 0 : LINE_INDENT);
                graphics.drawString(
                        DependencyScreen.this.font,
                        fitToWidth(DependencyScreen.this.font, this.line, maxWidth),
                        x,
                        rowTop + (ROW_HEIGHT - 8) / 2,
                        color);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of();
            }
        }
    }

    /** 文本超出可用宽度时截断并追加省略号。 */
    private static Component fitToWidth(net.minecraft.client.gui.Font font, Component text, int maxWidth) {
        String value = text.getString();
        if (font.width(value) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int available = maxWidth - font.width(ellipsis);
        return Component.literal(font.plainSubstrByWidth(value, Math.max(0, available)) + ellipsis);
    }
}
