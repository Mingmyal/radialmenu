package com.mingmyal.radialmenu;

import com.mingmyal.radialmenu.api.ShortcutRegistry;
import com.mingmyal.radialmenu.api.SlotModeRegistry;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceRegistry;
import com.mingmyal.radialmenu.client.ClientNetworkHandlers;
import com.mingmyal.radialmenu.client.ServerConfigState;
import com.mingmyal.radialmenu.command.RadialMenuCommand;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.config.RadialConfigScreen;
import com.mingmyal.radialmenu.integration.MalilibIntegration;
import com.mingmyal.radialmenu.mixin.KeyMappingAccessor;
import com.mingmyal.radialmenu.network.RadialNetworking;
import com.mingmyal.radialmenu.ui.screen.RadialScreen;
import com.mingmyal.radialmenu.util.MacroExecutor;
import com.mingmyal.radialmenu.util.NativeKeyboard;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

public class RadialClient implements ClientModInitializer {

    /** 与 {@link RadialCommon} 共用同一模组 ID 与 logger。 */
    public static final String MOD_ID = RadialCommon.MOD_ID;
    public static final Logger LOGGER = RadialCommon.LOGGER;

    // 1.21.1 key categories are plain translation-key strings.
    private static final String CATEGORY = "key.categories.radialmenu";

    public static final KeyMapping OPEN_RADIAL = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY));

    // 切换编辑模式(原"编辑模式"按钮的快捷键替代)
    public static final KeyMapping TOGGLE_EDIT = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key." + MOD_ID + ".toggle_edit", InputConstants.Type.KEYSYM, InputConstants.KEY_K, CATEGORY));

    // 打开主轮盘设置菜单
    public static final KeyMapping OPEN_CONFIG = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key." + MOD_ID + ".open_config", InputConstants.Type.KEYSYM, InputConstants.KEY_N, CATEGORY));

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
        return key == OPEN_RADIAL || key == TOGGLE_EDIT || key == OPEN_CONFIG;
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
    public static void scheduleComboKeyPress(List<KeyMapping> keys) {
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

    /**
     * 触发"选项→控制"里已注册的按键(KeyMapping), 走与真实按键完全相同的输入通道。
     *
     * <p><b>为什么不能只改 {@code clickCount} / {@code setDown}</b>:
     * {@link #scheduleKeyPress} 只是把"这个键被点了一次"写进游戏内部状态,
     * 不会产生任何键盘事件。只轮询 {@code consumeClick()} 的模组能读到它, 但
     * <b>事件驱动</b>的模组读不到。FTB Chunks 的"区块声明管理"正是后者:
     * 它不轮询, 而是订阅 Architectury 的 {@code ClientRawInputEvent.KEY_PRESSED}
     * (该事件由 mixin 挂在 Minecraft 的 {@code keyPress} 上)。因此用旧写法时,
     * 轮盘会正常关闭、音效也会响, 目标界面却永远不弹 —— 表现为"点了没反应"。
     *
     * <p>这里改为先取出该按键绑定的物理键码, 再复用"自定义按键(glfw:...)"那条
     * 已经验证过的注入路径: Windows 上系统级 {@code SendInput} 注入, 与真实硬件
     * 按键走同一条消息通道, 因此 GLFW、{@code keyPress}、Architectury 事件、
     * 以及轮询 {@code consumeClick()} 的模组全部都能看到这次按键; 其它平台回退到
     * 游戏内 {@code keyPress} 事件派发。
     *
     * @return true 表示已派发; false 表示该绑定不是键盘键(鼠标绑定或未绑定),
     *         调用方应回退到 {@link #scheduleKeyPress} 的状态模拟
     */
    public static boolean dispatchBoundKeys(List<KeyMapping> keys) {
        if (keys == null || keys.isEmpty()) return false;

        List<Integer> codes = new ArrayList<>(keys.size());
        for (KeyMapping key : keys) {
            if (key == null) return false;

            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
            // 只有键盘键能这样派发; 鼠标绑定/未绑定键交由调用方回退处理
            if (bound == null || bound.getType() != InputConstants.Type.KEYSYM) return false;

            int code = bound.getValue();
            if (code == InputConstants.UNKNOWN.getValue()) return false;

            codes.add(code);
        }

        // 复用既有注入路径: 组合键按"修饰键依次按下 → 主键最后按下 → 逆序释放"处理
        if (codes.size() == 1) {
            scheduleRawKeyPress(codes.get(0));
        } else {
            scheduleRawComboKeyPress(codes);
        }
        return true;
    }

    private static final Queue<ComboRelease> comboReleaseQueue = new ConcurrentLinkedQueue<>();

    private static final class ComboRelease {
        final List<KeyMapping> keys;
        int holdTicksLeft;

        ComboRelease(List<KeyMapping> keys, int holdTicksLeft) {
            this.keys = keys;
            this.holdTicksLeft = holdTicksLeft;
        }
    }

    // --- 客户端 tick 任务(轮盘动作需要在界面关闭后的下一 tick 执行, 如 F3 调试快捷键) ---

    private static final Queue<Runnable> clientActionQueue =
            new ConcurrentLinkedQueue<>();

    /** 将动作排到下一个 END_CLIENT_TICK 执行（渲染线程，此时轮盘界面已关闭）。 */
    public static void scheduleClientAction(Runnable action) {
        if (action != null) {
            clientActionQueue.add(action);
        }
    }

    private static void runScheduledClientActions() {
        Runnable action;
        while ((action = clientActionQueue.poll()) != null) {
            try {
                action.run();
            } catch (Throwable t) {
                LOGGER.error("Scheduled client action failed", t);
            }
        }
    }

    // --- 物理键盘按键模拟(用于自定义按键绑定, 不依赖 KeyMapping) ---

    private static final Queue<RawKeyRelease> rawReleaseQueue = new ConcurrentLinkedQueue<>();

    private static final class RawKeyRelease {
        final List<Integer> codes;
        int holdTicksLeft;

        RawKeyRelease(List<Integer> codes, int holdTicksLeft) {
            this.codes = codes;
            this.holdTicksLeft = holdTicksLeft;
        }
    }

    /** 模拟按下一个物理键并在短延迟后释放。 */
    public static void scheduleRawKeyPress(int glfwCode) {
        // Windows: 走系统级 SendInput(Alt+F4 等系统快捷键必须在 OS 层注入)
        if (NativeKeyboard.isReady()) {
            NativeKeyboard.sendSequence(List.of(glfwCode));
            return;
        }
        // 非 Windows 回退: 游戏内回调模拟
        sendKeyEvent(glfwCode, GLFW.GLFW_PRESS);
        rawReleaseQueue.add(new RawKeyRelease(List.of(glfwCode), KEY_HOLD_TICKS));
    }

    /**
     * 模拟按下一组物理键(组合键)。
     * Windows 上走系统级 SendInput, 按真实时序发送: 修饰键依次按住 → 主键按下/抬起 →
     * 修饰键逆序抬起, 与手工按键完全一致(可触发 Alt+F4、Ctrl+Shift+Esc 等任意组合)。
     * 非 Windows 回退: 直接派发 MC 键盘回调, 并用 VIRTUAL_HELD_KEYS 让修饰键判定放行。
     */
    public static void scheduleRawComboKeyPress(List<Integer> glfwCodes) {
        if (glfwCodes == null || glfwCodes.isEmpty()) return;
        if (NativeKeyboard.isReady()) {
            NativeKeyboard.sendSequence(glfwCodes);
            return;
        }

        List<Integer> codes = new ArrayList<>(glfwCodes);
        VIRTUAL_HELD_KEYS.addAll(codes);
        for (int code : codes) {
            sendKeyEvent(code, GLFW.GLFW_PRESS);
        }
        rawReleaseQueue.add(new RawKeyRelease(codes, KEY_HOLD_TICKS));
    }

    /** 当前处于"模拟按住"状态的键, 供 KeyboardHandlerMixin 兜底修饰键判定。 */
    private static final Set<Integer> VIRTUAL_HELD_KEYS =
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
        if (key == null) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;

        int code = key.getValue();
        if (code == InputConstants.UNKNOWN.getValue()) return false;

        long window = mc.getWindow().getWindow();
        return switch (key.getType()) {
            case MOUSE -> GLFW.glfwGetMouseButton(window, code - 1) == GLFW.GLFW_PRESS;
            case KEYSYM -> InputConstants.isKeyDown(window, code);
            default -> false;
        };
    }

    /**
     * 后台预热首次打开主轮盘设置(YACL 界面)时才会加载的类。
     * YACL 及其控制器、Mixin 转换后的类数量很多, 首次点击中心圆盘时在渲染线程同步
     * 加载+校验会造成约 2 秒卡顿; 放到守护线程提前完成, 首次打开即瞬时。
     * initialize=false 只触发加载/校验, 不运行静态初始化, 避免跨线程触碰 GL 相关状态。
     */
    private static void preloadConfigClasses() {
        if (!FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
            return;
        }

        Thread thread = new Thread(() -> {
            String[] classes = {
                    "dev.isxander.yacl3.api.YetAnotherConfigLib",
                    "dev.isxander.yacl3.api.ConfigCategory",
                    "dev.isxander.yacl3.api.Option",
                    "dev.isxander.yacl3.api.OptionDescription",
                    "dev.isxander.yacl3.api.OptionGroup",
                    "dev.isxander.yacl3.api.ButtonOption",
                    "dev.isxander.yacl3.api.Controller",
                    "dev.isxander.yacl3.api.controller.BooleanControllerBuilder",
                    "dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder",
                    "dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder",
                    "dev.isxander.yacl3.api.controller.EnumControllerBuilder",
                    "dev.isxander.yacl3.api.controller.StringControllerBuilder",
                    "dev.isxander.yacl3.gui.YACLScreen",
                    "dev.isxander.yacl3.gui.AbstractWidget",
                    "dev.isxander.yacl3.gui.controllers.ControllerWidget",
                    "dev.isxander.yacl3.gui.controllers.ColorController",
                    "dev.isxander.yacl3.gui.controllers.Controller",
                    "dev.isxander.yacl3.api.utils.Dimension",
                    "com.mingmyal.radialmenu.config.RadialConfigScreen",
                    "com.mingmyal.radialmenu.config.RadialColorController",
            };

            ClassLoader loader = RadialClient.class.getClassLoader();
            for (String name : classes) {
                try {
                    Class.forName(name, false, loader);
                } catch (Throwable t) {
                    // 预热失败无副作用, 真正打开界面时仍会正常加载
                    LOGGER.debug("Config class preload skipped: {}", name, t);
                }
            }
        }, "radialmenu-config-preload");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Radial Client...");

        // REGISTER CONFIG
        SlotModeRegistry.init();
        ShortcutRegistry.init();
        // 外部模组开关来源(供"开关类型"绑定真实模组功能状态)
        ToggleSourceRegistry.init();
        RadialConfig.load();
        MacroExecutor.init();

        // 网络通道类型注册(客户端与服务端各自注册一份) + 接收服务端配置
        RadialNetworking.registerPayloadTypes();
        ClientNetworkHandlers.register();

        // 后台预热设置界面类, 消除首次打开主轮盘设置时的卡顿
        preloadConfigClasses();

        if (FabricLoader.getInstance().isModLoaded("malilib")) {
            MalilibIntegration.init();
        }

        // 注册客户端命令 (/radialmenu size <值>)
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> RadialMenuCommand.register(dispatcher));

        // 进入世界后后台预热外部开关发现。
        // 放在此时的原因: MaLiLib 系模组要到游戏初始化完成才注册配置处理器,
        // 在 onInitializeClient 里扫描会漏掉它们。
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) ->
                        ToggleSourceRegistry.warmUpAsync());

        // 断开连接时释放所有模拟按键状态: 排队的按下动作不会再被 tick 处理,
        // 不主动松开会让移动键永久卡住, 并让虚拟按住集合持续残留。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetKeyState());

        // REGISTER EVENTS
        // Crosshair hiding while the radial screen is open is handled by GuiMixin
        // (26.3's HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, ...) does not exist for 1.21.1).

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!clientActionQueue.isEmpty()) {
                runScheduledClientActions();
            }

            // 合并后的服务端配置变更提交(运行期状态变更不逐次上传)
            ServerConfigState.flushDirty();

            if (OPEN_RADIAL.isDown()) {
                if (!keyLocked && client.screen == null) {
                    client.setScreen(new RadialScreen());
                }
            } else {
                keyLocked = false;
            }

            // 打开主轮盘设置菜单(N 键), 仅在无界面时响应
            while (OPEN_CONFIG.consumeClick()) {
                if (client.screen == null) {
                    // 强制应用服务端配置且无权限时不进入设置界面, 提示后直接返回;
                    // 仅"应用"时无权限玩家仍可打开界面只读查看
                    if (!ServerConfigState.canOpenSettings()) {
                        ServerConfigState.notifyNoPermission();
                    } else if (!RadialConfigScreen.isAvailable()) {
                        // YACL 是可选依赖: 未安装时给出明确原因, 而不是抛 NoClassDefFoundError
                        ServerConfigState.notifyInfo("radialmenu.ui.yacl_missing");
                    } else {
                        client.setScreen(RadialConfigScreen.create(null));
                    }
                }
            }

            // K 键切换编辑模式(常驻全局状态, 不打开轮盘也可切换), 仅在无界面时响应
            // 轮盘界面打开时由 RadialScreen.keyPressed 处理
            // 采用服务端配置且无权限时由 ServerConfigState 拒绝并提示红色文本
            while (TOGGLE_EDIT.consumeClick()) {
                if (client.screen == null) {
                    ServerConfigState.toggleEditMode();
                }
            }

            //noinspection StatementWithEmptyBody
            while (OPEN_RADIAL.consumeClick()) {}

            if (!keyPressQueue.isEmpty()) {
                tickKeyPresses();
            }

            // 处理组合键释放
            for (ComboRelease combo : comboReleaseQueue) {
                combo.holdTicksLeft--;
                if (combo.holdTicksLeft <= 0) {
                    releaseKeys(combo.keys);
                    comboReleaseQueue.remove(combo);
                }
            }

            // 处理物理按键释放(组合键逆序释放: 主键先释放, 修饰键最后释放)
            for (RawKeyRelease raw : rawReleaseQueue) {
                raw.holdTicksLeft--;
                if (raw.holdTicksLeft <= 0) {
                    for (int i = raw.codes.size() - 1; i >= 0; i--) {
                        sendKeyEvent(raw.codes.get(i), GLFW.GLFW_RELEASE);
                    }
                    VIRTUAL_HELD_KEYS.removeAll(raw.codes);
                    rawReleaseQueue.remove(raw);
                }
            }
        });
    }

    /** 松开一组按键(跳过 null, 与按下时一一对应)。 */
    private static void releaseKeys(List<KeyMapping> keys) {
        for (KeyMapping key : keys) {
            if (key != null) key.setDown(false);
        }
    }

    /**
     * 释放所有"模拟按键"状态。
     * <p>
     * 退出世界/断开连接时必须调用: 排队的按下动作不会再被 tick 处理, 若不主动松开,
     * {@code KeyMapping.setDown(true)} 会永久残留(表现为移动键卡住), 而
     * {@link #VIRTUAL_HELD_KEYS} 也会一直保留条目, 让 {@code KeyboardHandlerMixin}
     * 持续把它们判定为按住。
     */
    private static void resetKeyState() {
        for (ComboRelease combo : comboReleaseQueue) {
            releaseKeys(combo.keys);
        }
        comboReleaseQueue.clear();

        for (RawKeyRelease raw : rawReleaseQueue) {
            for (int code : raw.codes) {
                sendKeyEvent(code, GLFW.GLFW_RELEASE);
            }
        }
        rawReleaseQueue.clear();
        VIRTUAL_HELD_KEYS.clear();

        keyPressQueue.values().forEach(press -> press.holdTicksLeft = 0);
        keyPressQueue.keySet().forEach(key -> key.setDown(false));
        keyPressQueue.clear();

        keyLocked = false;
    }
}
