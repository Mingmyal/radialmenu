package dev.velolib.radialmenu.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 自定义键盘按键录制界面:
 * 点击输入框获得焦点后, 按键盘按键会追加到键值列表(显示为 "Alt+4"),
 * 点击"确定"按钮保存, "清除"按钮清空, "取消"返回。
 */
public class KeyCaptureScreen extends Screen {

    private static final String VALUE_PREFIX = "glfw:";

    private final Screen parent;
    private final Consumer<String> onSelect;

    // 已录制的 GLFW 键码序列
    private final List<Integer> capturedCodes = new ArrayList<>();

    private EditBox keyDisplay;
    private boolean capturing = false;

    public KeyCaptureScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.translatable("screen.radialmenu.key_capture.title"));
        this.parent = parent;
        this.onSelect = onSelect;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int boxWidth = 200;
        int boxHeight = 20;
        int boxX = centerX - boxWidth / 2 - 40;
        int boxY = height / 2 - 30;

        // 显示框 (只读, 点击后进入捕获模式)
        keyDisplay = new EditBox(font, boxX, boxY, boxWidth, boxHeight,
                Component.translatable("screen.radialmenu.key_capture.display")) {
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.isMouseOver(mouseX, mouseY)) {
                    setFocused(true);
                    capturing = true;
                    updateDisplay();
                    return true;
                }
                return false;
            }

            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (!capturing) return false;

                // ESC 取消捕获 (不关闭界面)
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    capturing = false;
                    updateDisplay();
                    return true;
                }

                // 退格键删除最后一个键
                if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!capturedCodes.isEmpty()) {
                        capturedCodes.remove(capturedCodes.size() - 1);
                        updateDisplay();
                    }
                    return true;
                }

                // 忽略单独按下的修饰键, 等待修饰键 + 主键
                if (isModifierKey(keyCode) && modifiers == 0) {
                    return true;
                }

                // 记录组合键: 修饰键 + 主键
                if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && !capturedCodes.contains(GLFW.GLFW_KEY_LEFT_CONTROL)) {
                    capturedCodes.add(GLFW.GLFW_KEY_LEFT_CONTROL);
                }
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 && !capturedCodes.contains(GLFW.GLFW_KEY_LEFT_SHIFT)) {
                    capturedCodes.add(GLFW.GLFW_KEY_LEFT_SHIFT);
                }
                if ((modifiers & GLFW.GLFW_MOD_ALT) != 0 && !capturedCodes.contains(GLFW.GLFW_KEY_LEFT_ALT)) {
                    capturedCodes.add(GLFW.GLFW_KEY_LEFT_ALT);
                }
                if ((modifiers & GLFW.GLFW_MOD_SUPER) != 0 && !capturedCodes.contains(GLFW.GLFW_KEY_LEFT_SUPER)) {
                    capturedCodes.add(GLFW.GLFW_KEY_LEFT_SUPER);
                }

                if (!capturedCodes.contains(keyCode)) {
                    capturedCodes.add(keyCode);
                }

                updateDisplay();
                return true;
            }

            @Override
            public void setFocused(boolean focused) {
                super.setFocused(focused);
                capturing = focused;
                updateDisplay();
            }
        };
        keyDisplay.setEditable(false);
        keyDisplay.setMaxLength(100);
        keyDisplay.setValue("");
        addRenderableWidget(keyDisplay);

        // 确定按钮
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.radialmenu.key_capture.confirm"),
                        b -> confirm())
                .bounds(boxX + boxWidth + 8, boxY, 72, boxHeight)
                .build());

        // 清除按钮
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.radialmenu.key_capture.clear"),
                        b -> {
                            capturedCodes.clear();
                            updateDisplay();
                        })
                .bounds(centerX - 80, boxY + 35, 72, 20)
                .build());

        // 取消按钮
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.cancel"),
                        b -> onClose())
                .bounds(centerX + 8, boxY + 35, 72, 20)
                .build());

        updateDisplay();
    }

    private void confirm() {
        if (capturedCodes.isEmpty()) {
            onClose();
            return;
        }
        StringBuilder sb = new StringBuilder(VALUE_PREFIX);
        for (int i = 0; i < capturedCodes.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(capturedCodes.get(i));
        }
        onSelect.accept(sb.toString());
        onClose();
    }

    private void updateDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < capturedCodes.size(); i++) {
            if (i > 0) sb.append('+');
            sb.append(getKeyDisplayName(capturedCodes.get(i)));
        }
        if (capturing) {
            if (sb.isEmpty()) {
                keyDisplay.setValue(Component.translatable("screen.radialmenu.key_capture.listening").getString());
            } else {
                keyDisplay.setValue(sb.toString());
            }
        } else {
            keyDisplay.setValue(sb.toString());
        }
    }

    private static String getKeyDisplayName(int glfwCode) {
        return switch (glfwCode) {
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "Ctrl";
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "Shift";
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "Alt";
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> "Super";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_UP -> "Up";
            case GLFW.GLFW_KEY_DOWN -> "Down";
            case GLFW.GLFW_KEY_LEFT -> "Left";
            case GLFW.GLFW_KEY_RIGHT -> "Right";
            default -> {
                String name = GLFW.glfwGetKeyName(glfwCode, 0);
                if (name != null && !name.isEmpty()) {
                    yield name.toUpperCase();
                }
                yield "Key" + glfwCode;
            }
        };
    }

    private static boolean isModifierKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL
                || keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT
                || keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT
                || keyCode == GLFW.GLFW_KEY_LEFT_SUPER || keyCode == GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, width, height, 0xC0101010, 0xD0101010);
        super.render(graphics, mouseX, mouseY, delta);

        Component title = getTitle();
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFFFF);

        Component prompt = Component.translatable("screen.radialmenu.key_capture.prompt2");
        graphics.drawCenteredString(font, prompt, width / 2, height / 2 - 8, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
