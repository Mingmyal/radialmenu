package dev.velolib.radialmenu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.velolib.radialmenu.api.ShortcutRegistry;
import dev.velolib.radialmenu.api.SlotModeRegistry;
import dev.velolib.radialmenu.command.RadialMenuCommand;
import dev.velolib.radialmenu.config.RadialConfig;
import dev.velolib.radialmenu.integration.MalilibIntegration;
import dev.velolib.radialmenu.mixin.KeyMappingAccessor;
import dev.velolib.radialmenu.ui.screen.RadialScreen;
import dev.velolib.radialmenu.util.MacroExecutor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RadialClient implements ClientModInitializer {

    public static final String MOD_ID = "radialmenu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 1.21.1 key categories are plain translation-key strings.
    private static final String CATEGORY = "key.categories.radialmenu";

    public static final KeyMapping OPEN_RADIAL = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY));

    // How long a simulated key press is held, and how long it must be released before the next press of that key
    private static final int KEY_HOLD_TICKS = 2;
    private static final int KEY_RELEASE_TICKS = 1;

    private static final Map<KeyMapping, ScheduledKeyPress> keyPressQueue = new ConcurrentHashMap<>();
    private static boolean keyLocked = false;

    /**
     * Press state of a single key. Repeated presses are queued so each one gets a distinct
     * press and release, instead of merging into one long hold.
     */
    private static final class ScheduledKeyPress {
        private int holdTicksLeft;
        private int releaseTicksLeft;
        private int queuedPresses;
    }

    public static void lockKey() {
        keyLocked = true;
    }

    /**
     * Helper to blacklist our internal keys from the picker and slot modes.
     */
    public static boolean isRadialInternalKey(KeyMapping key) {
        return key == OPEN_RADIAL;
    }

    public static void scheduleKeyPress(KeyMapping key) {
        if (key == null) return;

        ScheduledKeyPress press = keyPressQueue.get(key);
        if (press == null) {
            press = new ScheduledKeyPress();
            keyPressQueue.put(key, press);
            startKeyPress(key, press);
        } else {
            // The key is still busy with an earlier press; run this one after it is released
            press.queuedPresses++;
        }
    }

    /**
     * 同时按下多个键(组合键),例如 Ctrl+A。
     * 所有修饰键先按下,主键最后按下并增加 click count,然后一起释放。
     */
    public static void scheduleComboKeyPress(java.util.List<KeyMapping> keys) {
        if (keys == null || keys.isEmpty()) return;

        // 所有键同时按下
        for (KeyMapping key : keys) {
            if (key == null) continue;
            key.setDown(true);
        }

        // 主键(最后一个)增加 click count
        KeyMapping mainKey = keys.get(keys.size() - 1);
        if (mainKey != null) {
            KeyMappingAccessor accessor = (KeyMappingAccessor) mainKey;
            accessor.setClickCount(accessor.getClickCount() + 1);
        }

        // 调度延迟释放
        comboReleaseQueue.add(new ComboRelease(keys, KEY_HOLD_TICKS));
    }

    private static final java.util.List<ComboRelease> comboReleaseQueue =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final class ComboRelease {
        final java.util.List<KeyMapping> keys;
        int holdTicksLeft;

        ComboRelease(java.util.List<KeyMapping> keys, int holdTicksLeft) {
            this.keys = keys;
            this.holdTicksLeft = holdTicksLeft;
        }
    }

    // --- 物理键盘按键模拟(用于自定义按键绑定, 不依赖 KeyMapping) ---

    private static final java.util.List<RawKeyRelease> rawReleaseQueue =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final class RawKeyRelease {
        final java.util.List<Integer> codes;
        int holdTicksLeft;

        RawKeyRelease(java.util.List<Integer> codes, int holdTicksLeft) {
            this.codes = codes;
            this.holdTicksLeft = holdTicksLeft;
        }
    }

    /** 模拟按下一个物理键并在短延迟后释放。 */
    public static void scheduleRawKeyPress(int glfwCode) {
        // Windows: 走系统级 SendInput(Alt+F4 等系统快捷键必须在 OS 层注入)
        if (dev.velolib.radialmenu.util.NativeKeyboard.isReady()) {
            dev.velolib.radialmenu.util.NativeKeyboard.sendSequence(java.util.List.of(glfwCode));
            return;
        }
        // 非 Windows 回退: 游戏内回调模拟
        sendKeyEvent(glfwCode, GLFW.GLFW_PRESS);
        rawReleaseQueue.add(new RawKeyRelease(java.util.List.of(glfwCode), KEY_HOLD_TICKS));
    }

    /**
     * 模拟按下一组物理键(组合键)。
     * Windows 上走系统级 SendInput, 按真实时序发送: 修饰键依次按住 → 主键按下/抬起 →
     * 修饰键逆序抬起, 与手工按键完全一致(可触发 Alt+F4、Ctrl+Shift+Esc 等任意组合)。
     * 非 Windows 回退: 直接派发 MC 键盘回调, 并用 VIRTUAL_HELD_KEYS 让修饰键判定放行。
     */
    public static void scheduleRawComboKeyPress(java.util.List<Integer> glfwCodes) {
        if (glfwCodes == null || glfwCodes.isEmpty()) return;
        if (dev.velolib.radialmenu.util.NativeKeyboard.isReady()) {
            dev.velolib.radialmenu.util.NativeKeyboard.sendSequence(glfwCodes);
            return;
        }

        java.util.List<Integer> codes = new java.util.ArrayList<>(glfwCodes);
        VIRTUAL_HELD_KEYS.addAll(codes);
        for (int code : codes) {
            sendKeyEvent(code, GLFW.GLFW_PRESS);
        }
        rawReleaseQueue.add(new RawKeyRelease(codes, KEY_HOLD_TICKS));
    }

    /** 当前处于"模拟按住"状态的键, 供 KeyboardHandlerMixin 兜底修饰键判定。 */
    private static final java.util.Set<Integer> VIRTUAL_HELD_KEYS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isVirtualHeld(int glfwCode) {
        return VIRTUAL_HELD_KEYS.contains(glfwCode);
    }

    /**
     * 直接调用 MC 的键盘回调派发按键事件。
     * 此前用 java.awt.Robot 发送 OS 级事件, 但 GLFW 窗口收不到 AWT 注入的事件导致按键完全失效;
     * 走 keyPress 回调与真实按键的处理路径完全一致。
     */
    private static void sendKeyEvent(int glfwCode, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        long window = mc.getWindow().getWindow();
        int scancode = GLFW.glfwGetKeyScancode(glfwCode);
        mc.keyboardHandler.keyPress(window, glfwCode, scancode, action, 0);
    }

    private static void startKeyPress(KeyMapping key, ScheduledKeyPress press) {
        KeyMappingAccessor accessor = (KeyMappingAccessor) key;
        accessor.setClickCount(accessor.getClickCount() + 1);
        press.holdTicksLeft = KEY_HOLD_TICKS;
        press.releaseTicksLeft = KEY_RELEASE_TICKS;
    }

    private static void tickKeyPresses() {
        var it = keyPressQueue.entrySet().iterator();

        while (it.hasNext()) {
            var entry = it.next();
            KeyMapping key = entry.getKey();
            ScheduledKeyPress press = entry.getValue();

            if (press.holdTicksLeft > 0) {
                key.setDown(true);
                press.holdTicksLeft--;
                continue;
            }

            key.setDown(false);

            if (press.queuedPresses <= 0) {
                it.remove();
            } else if (press.releaseTicksLeft > 0) {
                // Stay released for at least one tick so the game sees two separate presses
                press.releaseTicksLeft--;
            } else {
                press.queuedPresses--;
                startKeyPress(key, press);
                key.setDown(true);
                press.holdTicksLeft--;
            }
        }
    }

    /**
     * Whether the physical key or mouse button is currently held, regardless of which screen is open.
     * Unbound keys always count as released.
     */
    public static boolean isPhysicallyDown(InputConstants.Key key) {
        int code = key.getValue();
        if (code == InputConstants.UNKNOWN.getValue()) return false;

        return switch (key.getType()) {
            case MOUSE -> GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), code - 1) == GLFW.GLFW_PRESS;
            case KEYSYM -> InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);
            default -> false;
        };
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Radial Client...");

        // REGISTER CONFIG
        SlotModeRegistry.init();
        ShortcutRegistry.init();
        RadialConfig.load();
        MacroExecutor.init();

        if (FabricLoader.getInstance().isModLoaded("malilib")) {
            MalilibIntegration.init();
        }

        // 注册客户端命令 (/radialmenu size <值>)
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> RadialMenuCommand.register(dispatcher));

        // REGISTER EVENTS
        // Crosshair hiding while the radial screen is open is handled by GuiMixin
        // (26.3's HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, ...) does not exist for 1.21.1).

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (OPEN_RADIAL.isDown()) {
                if (!keyLocked && client.screen == null) {
                    client.setScreen(new RadialScreen());
                }
            } else {
                keyLocked = false;
            }

            //noinspection StatementWithEmptyBody
            while (OPEN_RADIAL.consumeClick()) {}

            if (!keyPressQueue.isEmpty()) {
                tickKeyPresses();
            }

            // 处理组合键释放
            if (!comboReleaseQueue.isEmpty()) {
                var it = comboReleaseQueue.iterator();
                while (it.hasNext()) {
                    ComboRelease combo = it.next();
                    combo.holdTicksLeft--;
                    if (combo.holdTicksLeft <= 0) {
                        for (KeyMapping key : combo.keys) {
                            if (key != null) key.setDown(false);
                        }
                        it.remove();
                    }
                }
            }

            // 处理物理按键释放(组合键逆序释放: 主键先释放, 修饰键最后释放)
            if (!rawReleaseQueue.isEmpty()) {
                var it = rawReleaseQueue.iterator();
                while (it.hasNext()) {
                    RawKeyRelease raw = it.next();
                    raw.holdTicksLeft--;
                    if (raw.holdTicksLeft <= 0) {
                        for (int i = raw.codes.size() - 1; i >= 0; i--) {
                            sendKeyEvent(raw.codes.get(i), GLFW.GLFW_RELEASE);
                        }
                        VIRTUAL_HELD_KEYS.removeAll(raw.codes);
                        it.remove();
                    }
                }
            }
        });
    }
}
