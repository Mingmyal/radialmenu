package dev.velolib.radialmenu.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 系统级键盘输入注入(SendInput)。
 *
 * <p>为什么需要它: 直接调用 Minecraft 的 keyboardHandler.keyPress 只是游戏进程内的事件,
 * 操作系统完全收不到, 因此 Alt+F4 这类由窗口层(WM_SYSKEYDOWN/WM_CLOSE)处理的组合键、
 * 以及任何带真实时序的组合键都无法生效。SendInput 注入的事件与真实硬件按键走同一条
 * 消息通道, GLFW 窗口状态(修饰键是否按住等)也会真实改变。
 *
 * <p>组合键序列严格遵循真实操作: 修饰键依次按下(带间隔) → 短暂保持 → 主键按下/抬起 →
 * 修饰键逆序抬起, 全程在独立守护线程执行, 任何异常都会补偿释放, 防止修饰键黏住。
 *
 * <p>非 Windows 平台(或 JNA 初始化失败)时 {@link #isReady()} 返回 false, 调用方回退到
 * 游戏内回调模拟。
 */
public final class NativeKeyboard {

    private static final Logger LOGGER = LoggerFactory.getLogger("radialmenu");

    private NativeKeyboard() {}

    // --- 时序参数(毫秒): 模拟真实按键节奏, 过短会被系统/目标窗口判定为孤立按键 ---
    /** 序列开始前等待轮盘屏幕关闭、焦点稳定 */
    private static final long START_DELAY_MS = 60L;
    /** 多个修饰键之间的按下间隔 */
    private static final long MOD_STAGGER_MS = 20L;
    /** 修饰键全部按住后到主键按下的保持时间 */
    private static final long PRE_MAIN_DELAY_MS = 35L;
    /** 主键按下后的保持时间 */
    private static final long MAIN_HOLD_MS = 45L;
    /** 主键抬起后到修饰键开始抬起的间隔 */
    private static final long POST_MAIN_DELAY_MS = 15L;
    /** 修饰键逆序抬起的间隔 */
    private static final long MOD_RELEASE_STAGGER_MS = 12L;
    /** 单键按下后的保持时间 */
    private static final long SINGLE_HOLD_MS = 45L;

    private static final int INPUT_KEYBOARD = 1;
    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int KEYEVENTF_SCANCODE = 0x0008;

    /** Windows 扩展键(E0 前缀)的 GLFW 键码集合, scancode 发送时必须附带 EXTENDEDKEY 标志 */
    private static final Set<Integer> EXTENDED_KEYS = Set.of(
            GLFW.GLFW_KEY_INSERT,        // 260
            GLFW.GLFW_KEY_DELETE,        // 261
            GLFW.GLFW_KEY_RIGHT,         // 262
            GLFW.GLFW_KEY_LEFT,          // 263
            GLFW.GLFW_KEY_DOWN,          // 264
            GLFW.GLFW_KEY_UP,            // 265
            GLFW.GLFW_KEY_PAGE_UP,       // 266
            GLFW.GLFW_KEY_PAGE_DOWN,     // 267
            GLFW.GLFW_KEY_HOME,          // 268
            GLFW.GLFW_KEY_END,           // 269
            GLFW.GLFW_KEY_PRINT_SCREEN,  // 283
            GLFW.GLFW_KEY_KP_DIVIDE,     // 331
            GLFW.GLFW_KEY_KP_ENTER,      // 335
            GLFW.GLFW_KEY_LEFT_SUPER,    // 343
            GLFW.GLFW_KEY_RIGHT_CONTROL, // 345
            GLFW.GLFW_KEY_RIGHT_ALT,     // 346
            GLFW.GLFW_KEY_RIGHT_SUPER);  // 347

    private static final AtomicBoolean INIT_TRIED = new AtomicBoolean(false);
    private static volatile boolean ready = false;
    private static volatile User32 user32;

    /** 串行执行所有注入序列, 保证多次触发不会互相打断或打乱修饰键状态 */
    private static final ExecutorService DISPATCHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radialmenu-native-keys");
            t.setDaemon(true);
            return t;
        });

    /** 平台是否支持(Windows 且 user32 加载成功)。 */
    public static boolean isReady() {
        if (!INIT_TRIED.get()) {
            synchronized (NativeKeyboard.class) {
                if (!INIT_TRIED.get()) {
                    try {
                        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
                        if (windows) {
                            // 继承 StdCallLibrary 的接口会自动使用 stdcall 调用约定
                            user32 = Native.load("user32", User32.class);
                            ready = true;
                            LOGGER.info("[radialmenu] 系统级键盘注入(SendInput)已就绪");
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("[radialmenu] 无法加载 user32, 组合键回退为游戏内模拟", t);
                        ready = false;
                        user32 = null;
                    }
                    INIT_TRIED.set(true);
                }
            }
        }
        return ready;
    }

    /**
     * 按真实时序注入一组按键。最后一个为主键, 其余为修饰键(可无)。
     * 在客户端线程调用(scancode 通过 GLFW 解析), 实际注入在后台线程执行。
     */
    public static void sendSequence(List<Integer> glfwCodes) {
        if (glfwCodes == null || glfwCodes.isEmpty() || !isReady()) return;

        List<KeyTarget> targets = new ArrayList<>(glfwCodes.size());
        for (int code : glfwCodes) {
            targets.add(new KeyTarget(code, GLFW.glfwGetKeyScancode(code), EXTENDED_KEYS.contains(code)));
        }

        DISPATCHER.submit(() -> runSequence(targets));
    }

    private static void runSequence(List<KeyTarget> targets) {
        try {
            Thread.sleep(START_DELAY_MS);

            if (targets.size() == 1) {
                KeyTarget k = targets.get(0);
                sendKey(k, false);
                Thread.sleep(SINGLE_HOLD_MS);
                sendKey(k, true);
                return;
            }

            int mainIndex = targets.size() - 1;
            // 1. 修饰键依次按下
            for (int i = 0; i < mainIndex; i++) {
                sendKey(targets.get(i), false);
                Thread.sleep(MOD_STAGGER_MS);
            }
            // 2. 保持修饰键, 再按主键
            Thread.sleep(PRE_MAIN_DELAY_MS);
            sendKey(targets.get(mainIndex), false);
            Thread.sleep(MAIN_HOLD_MS);
            // 3. 主键抬起
            sendKey(targets.get(mainIndex), true);
            Thread.sleep(POST_MAIN_DELAY_MS);
            // 4. 修饰键逆序抬起
            for (int i = mainIndex - 1; i >= 0; i--) {
                sendKey(targets.get(i), true);
                if (i > 0) Thread.sleep(MOD_RELEASE_STAGGER_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.warn("[radialmenu] 系统级按键注入异常", t);
        } finally {
            // 补偿释放: 无论中途发生什么, 确保所有键抬起, 杜绝修饰键黏住
            for (int i = targets.size() - 1; i >= 0; i--) {
                try {
                    sendKey(targets.get(i), true);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 发送单个键的 down/up。优先用硬件 scancode(系统自动映射, 对 Alt 等组合键语义最准确)。 */
    private static void sendKey(KeyTarget key, boolean keyUp) {
        INPUT input = new INPUT();
        input.type = INPUT_KEYBOARD;
        input.u.setType("ki");
        input.u.ki.time = 0;
        input.u.ki.dwExtraInfo = Pointer.nativeValue(user32.GetMessageExtraInfo());

        if (key.scanCode != 0) {
            input.u.ki.wVk = 0;
            input.u.ki.wScan = (short) key.scanCode;
            input.u.ki.dwFlags = KEYEVENTF_SCANCODE
                    | (key.extended ? KEYEVENTF_EXTENDEDKEY : 0)
                    | (keyUp ? KEYEVENTF_KEYUP : 0);
        } else {
            // 极少数键没有 scancode(如 Pause), 回退到虚拟键码
            input.u.ki.wVk = (short) glfwToVirtualKey(key.glfwCode);
            input.u.ki.wScan = 0;
            input.u.ki.dwFlags = (key.extended ? KEYEVENTF_EXTENDEDKEY : 0)
                    | (keyUp ? KEYEVENTF_KEYUP : 0);
        }

        input.u.write();
        input.write();

        INPUT[] arr = (INPUT[]) input.toArray(1);
        int sent = user32.SendInput(1, arr, input.size());
        if (sent != 1) {
            throw new IllegalStateException("SendInput 失败, 错误码=" + Native.getLastError());
        }
    }

    /** GLFW 键码 → Windows 虚拟键码(仅 scancode 不可用时兜底)。 */
    private static int glfwToVirtualKey(int glfw) {
        // 字母数字 GLFW 值与 VK 值一致(A-Z 0x41-0x5A, 0-9 0x30-0x39)
        if ((glfw >= 'A' && glfw <= 'Z') || (glfw >= '0' && glfw <= '9')) {
            return glfw;
        }
        return switch (glfw) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0xDE;   // '
            case GLFW.GLFW_KEY_COMMA -> 0xBC;        // ,
            case GLFW.GLFW_KEY_MINUS -> 0xBD;        // -
            case GLFW.GLFW_KEY_PERIOD -> 0xBE;       // .
            case GLFW.GLFW_KEY_SLASH -> 0xBF;        // /
            case GLFW.GLFW_KEY_SEMICOLON -> 0xBA;    // ;
            case GLFW.GLFW_KEY_EQUAL -> 0xBB;        // =
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0xDB; // [
            case GLFW.GLFW_KEY_BACKSLASH -> 0xDC;    // \
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0xDD;// ]
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0xC0; // `
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 0x91;
            case GLFW.GLFW_KEY_NUM_LOCK -> 0x90;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> 0x2C;
            case GLFW.GLFW_KEY_PAUSE -> 0x13;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xA0;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xA1;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xA2;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xA3;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xA4;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xA5;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0x5B;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0x5C;
            case GLFW.GLFW_KEY_MENU -> 0x5D;
            default -> {
                if (glfw >= GLFW.GLFW_KEY_F1 && glfw <= GLFW.GLFW_KEY_F25) {
                    yield 0x70 + (glfw - GLFW.GLFW_KEY_F1);
                }
                if (glfw >= GLFW.GLFW_KEY_KP_0 && glfw <= GLFW.GLFW_KEY_KP_9) {
                    yield 0x60 + (glfw - GLFW.GLFW_KEY_KP_0);
                }
                if (glfw == GLFW.GLFW_KEY_KP_DECIMAL) yield 0x6E;
                if (glfw == GLFW.GLFW_KEY_KP_DIVIDE) yield 0x6F;
                if (glfw == GLFW.GLFW_KEY_KP_MULTIPLY) yield 0x6A;
                if (glfw == GLFW.GLFW_KEY_KP_SUBTRACT) yield 0x6D;
                if (glfw == GLFW.GLFW_KEY_KP_ADD) yield 0x6B;
                yield 0;
            }
        };
    }

    // --- 目标键(在客户端线程解析好, 后台线程不再碰 GLFW) ---
    private record KeyTarget(int glfwCode, int scanCode, boolean extended) {}

    // --- JNA: Win32 结构体与接口(仅手写需要的部分, 避免引入整个 jna-platform) ---

    public interface User32 extends StdCallLibrary {
        int SendInput(int nInputs, INPUT[] pInputs, int cbSize);
        Pointer GetMessageExtraInfo();
    }

    // CHECKSTYLE:OFF —— JNA 结构体字段名必须与 Win32 布局一致
    public static class MOUSEINPUT extends Structure {
        public int dx;
        public int dy;
        public int mouseData;
        public int dwFlags;
        public int time;
        public long dwExtraInfo;

        public MOUSEINPUT() {
            super();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo");
        }
    }

    public static class KEYBDINPUT extends Structure {
        public short wVk;
        public short wScan;
        public int dwFlags;
        public int time;
        public long dwExtraInfo;

        public KEYBDINPUT() {
            super();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("wVk", "wScan", "dwFlags", "time", "dwExtraInfo");
        }
    }

    public static class HARDWAREINPUT extends Structure {
        public int uMsg;
        public short wParamL;
        public short wParamH;

        public HARDWAREINPUT() {
            super();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("uMsg", "wParamL", "wParamH");
        }
    }

    public static class INPUT_UNION extends Union {
        public MOUSEINPUT mi;
        public KEYBDINPUT ki;
        public HARDWAREINPUT hi;

        public INPUT_UNION() {
            super();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("mi", "ki", "hi");
        }
    }

    public static class INPUT extends Structure {
        public int type;
        public INPUT_UNION u;

        public INPUT() {
            super();
            this.u = new INPUT_UNION();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("type", "u");
        }
    }
    // CHECKSTYLE:ON
}
