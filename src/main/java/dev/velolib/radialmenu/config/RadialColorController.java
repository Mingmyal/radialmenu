package dev.velolib.radialmenu.config;

import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.ColorController;
import dev.isxander.yacl3.gui.controllers.ControllerWidget;
import java.awt.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 自定义颜色控件:
 * - 点击色块: 弹出 YACL 自带取色器;
 * - 点击色值文本: 进入内联十六进制编辑(编辑态完全由本类自控, 不依赖 YACL 的焦点逻辑,
 *   因此右侧 ↻ 重置按钮的点击不会误激活输入态, 重置后也不会留下光标)。
 */
public class RadialColorController extends ColorController {

    public RadialColorController(Option<Color> option, boolean allowAlpha) {
        super(option, allowAlpha);
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
        return new RadialColorWidget(this, screen, widgetDimension);
    }

    public static class RadialColorWidget extends ControllerWidget<ColorController> {

        /** 色块与色值文本的间距 */
        private static final int SWATCH_GAP = 4;

        /**
         * 取色器宿主元素(只作为取色器弹窗的锚点和状态持有者, 不加入行内组件树,
         * 因此它内部的行内输入框永远不会被渲染或直接点击到)。
         */
        private ColorControllerElement pickerHost;

        // --- 内联十六进制编辑状态(自控) ---
        private boolean editing = false;
        private String editText = "";
        private int caretPos = 0;
        private float caretTicks = 0.0F;

        public RadialColorWidget(ColorController control, YACLScreen screen, Dimension<Integer> dim) {
            super(control, screen, dim);
        }

        // --- 几何 ---

        /** 色块尺寸, 与 YACL 原生颜色控件的算法保持一致(便于取色器外部点击判定吻合)。 */
        private int swatchSize() {
            return (this.getDimension().height() - this.getYPadding() * 2) / 2;
        }

        private int swatchX() {
            return this.getDimension().xLimit() - this.getXPadding() - this.swatchSize();
        }

        private int swatchY() {
            return this.getDimension().centerY() - this.swatchSize() / 2;
        }

        private boolean isOverSwatch(double mouseX, double mouseY) {
            int size = this.swatchSize();
            int x0 = this.swatchX();
            int y0 = this.swatchY();
            return mouseX >= x0 && mouseX < x0 + size && mouseY >= y0 && mouseY < y0 + size;
        }

        // --- 编辑态 ---

        private String currentHex() {
            Color c = this.control.option().pendingValue();
            return this.control.allowAlpha()
                    ? String.format("#%02X%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha())
                    : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
        }

        /** 编辑文本最大长度(含 '#')。 */
        private int maxEditLen() {
            return this.control.allowAlpha() ? 9 : 7;
        }

        private void startEditing() {
            this.editing = true;
            this.editText = this.currentHex();
            this.caretPos = this.editText.length();
            this.caretTicks = 0.0F;
        }

        private void stopEditing() {
            this.editing = false;
        }

        /** 文本长度合法时立即应用(与 YACL 的即时生效一致)。 */
        private void applyEditText() {
            String hex = this.editText.startsWith("#") ? this.editText.substring(1) : this.editText;
            try {
                if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);
                    Color old = this.control.option().pendingValue();
                    int alpha = this.control.allowAlpha() ? old.getAlpha() : 255;
                    this.control.option().requestSet(new Color(
                            (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, alpha));
                } else if (hex.length() == 8 && this.control.allowAlpha()) {
                    long v = Long.parseLong(hex, 16);
                    this.control.option().requestSet(new Color(
                            (int) (v >> 24) & 0xFF, (int) (v >> 16) & 0xFF, (int) (v >> 8) & 0xFF, (int) (v & 0xFF)));
                }
            } catch (NumberFormatException ignored) {
                // 中间态文本无法解析时保持原值
            }
        }

        private void moveCaretTo(double mouseX) {
            int textX = this.swatchX() - SWATCH_GAP - this.textRenderer.width(this.editText);
            int rel = (int) mouseX - textX;
            int pos = this.editText.length();
            int w = 0;
            for (int i = 0; i < this.editText.length(); i++) {
                int cw = this.textRenderer.width(String.valueOf(this.editText.charAt(i)));
                if (w + cw / 2 > rel) {
                    pos = i;
                    break;
                }
                w += cw;
            }
            // 光标不允许移到 '#' 之前
            this.caretPos = Math.max(1, Math.min(pos, this.editText.length()));
            this.caretTicks = 0.0F;
        }

        // --- 渲染 ---

        @Override
        protected void drawValueText(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int size = this.swatchSize();
            int x0 = this.swatchX();
            int y0 = this.swatchY();
            int textY = this.getTextY();

            if (this.editing) {
                int textW = this.textRenderer.width(this.editText);
                int textX = x0 - SWATCH_GAP - textW;
                graphics.drawString(this.textRenderer, this.editText, textX, textY, this.getValueColor(), true);
                // 下划线
                graphics.fill(textX, textY + 9, x0 - SWATCH_GAP, textY + 10, 0xFFFFFFFF);
                // 闪烁光标
                this.caretTicks += delta;
                if ((this.caretTicks % 20.0F) <= 10.0F) {
                    int caretX = textX + this.textRenderer.width(this.editText.substring(0, this.caretPos));
                    graphics.fill(caretX, textY - 1, caretX + 1, textY + 8, 0xFFFFFFFF);
                }
            } else {
                Component value = this.control.formatValue();
                int textX = x0 - SWATCH_GAP - this.textRenderer.width(value);
                graphics.drawString(this.textRenderer, value, textX, textY, this.getValueColor(), true);
            }

            // 色块(含半透明 alpha 直接填充) + 黑色描边, 与 YACL 原色块观感一致
            graphics.fill(x0, y0, x0 + size, y0 + size, this.control.option().pendingValue().getRGB());
            this.drawOutline(graphics, x0, y0, x0 + size, y0 + size, 1, Color.black.getRGB());
        }

        @Override
        protected int getHoveredControlWidth() {
            int textWidth = this.editing
                    ? this.textRenderer.width(this.editText)
                    : this.textRenderer.width(this.control.formatValue());
            return textWidth + SWATCH_GAP + this.swatchSize();
        }

        @Override
        protected int getUnhoveredControlWidth() {
            return this.getHoveredControlWidth();
        }

        // --- 输入 ---

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // 维度已被 YACL 为重置按钮缩窄, 天然不含右侧 20px 的 ↻ 区域
            boolean inside = this.getDimension().isPointInside((int) mouseX, (int) mouseY);
            if (button != 0 || !this.isAvailable() || !inside) {
                if (!inside) {
                    this.stopEditing();
                }
                return false;
            }
            this.playDownSound();
            if (this.isOverSwatch(mouseX, mouseY)) {
                // 色块: 打开取色器
                this.stopEditing();
                this.openColorPicker();
            } else if (!this.editing) {
                // 色值文本/行内其它区域: 进入手动输入
                this.startEditing();
            } else {
                this.moveCaretTo(mouseX);
            }
            return true;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            if (!this.editing) {
                return false;
            }
            char c = Character.toLowerCase(chr);
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (isHex && this.editText.length() < this.maxEditLen()) {
                this.editText = this.editText.substring(0, this.caretPos) + c + this.editText.substring(this.caretPos);
                this.caretPos++;
                this.applyEditText();
            }
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!this.editing) {
                return false;
            }
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE:
                case GLFW.GLFW_KEY_ENTER:
                case GLFW.GLFW_KEY_KP_ENTER:
                    // 编辑中吞掉 ESC/回车, 仅结束编辑(值已即时生效), 避免误关配置界面
                    this.stopEditing();
                    return true;
                case GLFW.GLFW_KEY_BACKSPACE:
                    if (this.caretPos > 1) {
                        this.editText =
                                this.editText.substring(0, this.caretPos - 1) + this.editText.substring(this.caretPos);
                        this.caretPos--;
                        this.applyEditText();
                    }
                    return true;
                case GLFW.GLFW_KEY_DELETE:
                    if (this.caretPos < this.editText.length()) {
                        this.editText =
                                this.editText.substring(0, this.caretPos) + this.editText.substring(this.caretPos + 1);
                        this.applyEditText();
                    }
                    return true;
                case GLFW.GLFW_KEY_LEFT:
                    if (this.caretPos > 1) {
                        this.caretPos--;
                    }
                    return true;
                case GLFW.GLFW_KEY_RIGHT:
                    if (this.caretPos < this.editText.length()) {
                        this.caretPos++;
                    }
                    return true;
                default:
                    // 编辑中吞掉其它按键, 避免触发屏幕级快捷键
                    return true;
            }
        }

        @Override
        public void unfocus() {
            super.unfocus();
            this.stopEditing();
        }

        // --- 取色器 ---

        private void openColorPicker() {
            if (this.pickerHost == null) {
                this.pickerHost =
                        new ColorController.ColorControllerElement(this.control, this.screen, this.getDimension());
            }
            // 让取色器打开期间内联十六进制输入可用(按键事件由取色器弹窗委托给宿主)
            this.pickerHost.setFocused(true);
            this.pickerHost.createOrRemoveColorPicker();
        }

        @Override
        public void setDimension(Dimension<Integer> dim) {
            super.setDimension(dim);
            // 滚动行/窗口尺寸变化时同步取色器锚点(宿主内部还会在滚出可视区时自动关闭弹窗)
            if (this.pickerHost != null) {
                this.pickerHost.setDimension(dim);
            }
        }
    }
}
