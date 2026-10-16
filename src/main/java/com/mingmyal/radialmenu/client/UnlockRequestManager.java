package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotCondition;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.network.ConsumeUnlockC2SPayload;
import com.mingmyal.radialmenu.network.ConsumeUnlockResultS2CPayload;
import com.mingmyal.radialmenu.network.ResetUnlocksC2SPayload;
import com.mingmyal.radialmenu.network.ServerUnlockSyncS2CPayload;
import com.mingmyal.radialmenu.util.SlotPath;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 客户端"消耗解锁"请求的发起与回执处理。
 * <p>
 * 消耗由<b>服务端</b>执行, 客户端只负责: 声明按自己菜单规则该付什么、等回执、
 * 拿到服务端算出的解锁截止时间后记录状态并继续执行槽位动作。
 * <p>
 * 之所以不能在本地扣: 本地扣只是改 {@code ItemStack}, 而 {@code Inventory#setChanged()}
 * 仅递增计数器、不发包, 服务端背包从未变化; 退出重进时服务端把权威背包同步回来,
 * 玩家花掉的物品就被"返还"了 —— 这正是本类要消灭的现象。
 * <p>
 * 同一时刻只保留一个待处理请求: 轮盘在等待回执期间会挡住输入, 玩家无法连点两个槽位。
 */
public final class UnlockRequestManager {

    private UnlockRequestManager() {}

    /** 等待回执时的回调。轮盘界面实现它, 以便在结果到达后继续播放音效/执行动作。 */
    public interface Listener {
        /**
         * @param granted   服务端是否已完成扣除并解锁
         * @param reasonKey 失败原因的翻译键; 成功时为空字符串
         */
        void onConsumeUnlockResult(boolean granted, String reasonKey);
    }

    /** 正在等待回执的请求。 */
    private record Pending(String slotKey, SlotCondition condition, Listener listener, long deadlineNanos) {}

    private static volatile Pending pending;

    /**
     * 等待回执的超时时间。
     * <p>
     * 正常情况下回执在一个往返内到达(几十毫秒)。若服务端版本较旧、不认识这个包,
     * 或者网络异常导致回执丢失, 就必须有个兜底: 否则轮盘会一直处于"挡住输入"的等待态,
     * 玩家既点不了别的槽位也关不掉界面, 只能强退游戏。
     */
    private static final long TIMEOUT_NANOS = 5_000_000_000L;

    /** 是否正在等待服务端回执(轮盘据此挡住输入)。 */
    public static boolean isPending() {
        Pending current = pending;
        if (current == null) {
            return false;
        }
        if (System.nanoTime() - current.deadlineNanos() >= 0L) {
            // 超时: 放弃等待。注意不回调 listener —— 动作不再执行, 但也不会误报失败
            pending = null;
            notifyRed("radialmenu.condition.consume.timeout");
            return false;
        }
        return true;
    }

    /** 服务端是否支持本模组的消耗解锁通道(未安装模组时为 false)。 */
    public static boolean serverSupportsConsume() {
        return ClientPlayNetworking.canSend(ConsumeUnlockC2SPayload.TYPE);
    }

    /**
     * 发起一次消耗解锁请求。
     *
     * @param slot      被激活的槽位(用于计算消耗内容与定位键)
     * @param condSlot  承载该条件约束的槽位(开关类型下是状态动作槽位)
     * @param key       槽位定位键, 由 {@code SlotPath} 生成
     * @return true 表示请求已发出; false 表示服务端不支持, 调用方应提示玩家
     */
    public static boolean request(RadialSlot slot, RadialSlot condSlot, String key, Listener listener) {
        SlotCondition condition = condSlot != null ? condSlot.condition : null;

        if (!serverSupportsConsume()) {
            // 服务器没装模组: 没有权威扣除的地方。此时绝不能退化成"本地假消耗" ——
            // 那正是物品被返还的根源。明确拒绝比假装成功更诚实。
            notifyRed("radialmenu.condition.consume.no_server_mod");
            return false;
        }

        if (condition == null || !condition.isConsumeType()) {
            return false;
        }

        ConsumeUnlockC2SPayload payload = buildPayload(condSlot, condition, key);
        if (payload == null) {
            return false;
        }

        pending = new Pending(key, condition, listener, System.nanoTime() + TIMEOUT_NANOS);
        ClientPlayNetworking.send(payload);
        return true;
    }

    /**
     * 取消等待(例如玩家关闭了轮盘), 不再回调。
     * <p>
     * 只解除回调: 若服务端其实已经扣过费, 回执到达时仍会按槽位键把解锁状态补记下来,
     * 玩家不会白花。见 {@link #onResult}。
     */
    public static void cancel() {
        pending = null;
    }

    /** 处理服务端回执。 */
    public static void onResult(ConsumeUnlockResultS2CPayload payload) {
        Pending current = pending;

        if (current == null) {
            // 请求已被取消(界面关闭等), 但服务端可能已经扣过费 —— 仍要把解锁状态记下来,
            // 否则玩家花了东西却什么都没得到
            applyUnlockIfGranted(payload);
            return;
        }

        if (!current.slotKey().equals(payload.slotKey())) {
            // 不是当前等待的那次请求(过期/串包), 忽略回调但仍记录已授权的解锁
            applyUnlockIfGranted(payload);
            return;
        }

        pending = null;

        if (payload.granted()) {
            applyUnlock(current.condition(), payload.unlockedUntil());
            persistRuntimeState();
        } else if (payload.reasonKey() != null && !payload.reasonKey().isEmpty()) {
            notifyRed(payload.reasonKey());
        }

        Listener listener = current.listener();
        if (listener != null) {
            listener.onConsumeUnlockResult(payload.granted(), payload.reasonKey());
        }
    }

    // --- 重置 ---

    /**
     * 请求服务端清空本玩家的解锁记录(重置按钮)。
     * <p>
     * 解锁权威副本在服务端, 因此只清本地是没用的 —— 下次进服会被重新下发回来。
     * 服务端处理完会回发一份空快照, 客户端据此清零并落盘。
     *
     * @return true 表示请求已发出
     */
    public static boolean requestReset() {
        if (!ClientPlayNetworking.canSend(ResetUnlocksC2SPayload.TYPE)) {
            // 服务器没装模组: 解锁本来就不在服务端, 本地清空即是全部
            return false;
        }
        ClientPlayNetworking.send(new ResetUnlocksC2SPayload());
        return true;
    }

    // --- 权威解锁状态 ---

    /**
     * 收到服务端下发的权威解锁状态: 清空本地记录后按服务端快照重建。
     * <p>
     * <b>必须先清后写</b>。只做逐键写入的话, 玩家在本地文件里多编造的解锁条目会原地留下 ——
     * 那些键不在服务端快照里, 却仍然生效, 等于白拿解锁。清空后重建保证"客户端有什么"
     * 完全由服务端决定。
     * <p>
     * 仅在采用服务端配置时套用: 玩家切回自己的全局配置时, 槽位树是另一份, 服务端按路径
     * 记下的键未必对得上, 强行套用会把不相关的槽位点亮。
     */
    public static void onServerUnlockSync(ServerUnlockSyncS2CPayload payload) {
        if (payload == null || !ServerConfigState.isUsingServerConfig()) {
            return;
        }

        RadialConfig config = ServerConfigState.getEffectiveConfig();
        if (config == null) {
            return;
        }

        // 1. 清空本地解锁: 编造的条目到此失效
        SlotRuntimeStateStore.clearUnlocks(config.slots);
        // 2. 写入服务端的权威记录
        SlotRuntimeStateStore.applyUnlocks(config.slots, payload.unlocks());

        // 3. 落盘: 覆盖运行期覆盖表的解锁段, 以及强制模式下那份只读副本之外的持久化途径
        String serverId = ServerConfigStore.currentServerId();
        SlotRuntimeStateStore.replaceUnlocks(serverId, payload.unlocks());

        RadialCommon.LOGGER.info(
                "Applied authoritative radial unlock state from server: {} entr(ies)", 
                payload.unlocks() != null ? payload.unlocks().size() : 0);
    }

    // --- 内部 ---

    /** 回执已授权但当前没有等待者时, 仍按定位键把状态写回生效配置。 */
    private static void applyUnlockIfGranted(ConsumeUnlockResultS2CPayload payload) {
        if (!payload.granted()) {
            return;
        }
        // 没有等待者就找不到条件对象引用, 只能按定位键回查一次
        RadialConfig config = ServerConfigState.getEffectiveConfig();
        if (config == null) {
            return;
        }
        RadialSlot slot = SlotPath.resolve(config.slots, payload.slotKey());
        SlotCondition condition = slot != null ? slot.condition : null;
        if (condition == null) {
            return;
        }
        applyUnlock(condition, payload.unlockedUntil());
        persistRuntimeState();
    }

    private static void applyUnlock(SlotCondition condition, long unlockedUntil) {
        if (condition != null && unlockedUntil > 0L) {
            condition.unlockedUntil = unlockedUntil;
        }
    }

    /** 采用服务端配置时, 解锁状态要单独写进按服覆盖表(强制模式下这是唯一的持久化途径)。 */
    private static void persistRuntimeState() {
        try {
            if (ServerConfigState.isUsingServerConfig()) {
                ServerConfigState.saveRuntimeStateNow();
            }
            RadialConfig.save();
        } catch (Throwable t) {
            RadialCommon.LOGGER.error("Failed to persist radial unlock state", t);
        }
    }

    /** 按条件的消耗内容构造请求报文; 条件不可用时返回 null。 */
    private static ConsumeUnlockC2SPayload buildPayload(
            RadialSlot condSlot, SlotCondition condition, String key) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }

        ConsumeUnlockC2SPayload.ConditionKind kind = condition.type
                        == SlotCondition.ConditionType.XP_CONSUME
                ? ConsumeUnlockC2SPayload.ConditionKind.XP_CONSUME
                : ConsumeUnlockC2SPayload.ConditionKind.ITEM_CONSUME;

        List<ConsumeUnlockC2SPayload.ItemCost> costs = new ArrayList<>();
        if (kind == ConsumeUnlockC2SPayload.ConditionKind.ITEM_CONSUME) {
            for (var req : condition.items) {
                if (req == null) continue;
                // 集合按"背包里实际会被扣掉的那一件"上报, 与确认对话框显示的内容保持一致;
                // 服务端只被授权扣这一件, 不会顺带扣走其它候选
                var picked = ConditionDisplay.pickConsumable(req, player, true);
                if (picked == null) {
                    // 资源不足: 本地就不必发了, 服务端也会拒绝
                    notifyRed("radialmenu.condition.consume.insufficient");
                    return null;
                }
                costs.add(new ConsumeUnlockC2SPayload.ItemCost(
                        picked.itemId, Math.max(1, req.count), Math.max(0, req.durabilityCost)));
            }
            if (costs.isEmpty()) {
                return null;
            }
        }

        return new ConsumeUnlockC2SPayload(
                key,
                kind,
                costs,
                Math.max(1, condition.xpAmount),
                condition.xpLevels,
                condition.timedUnlock,
                Math.max(1L, condition.unlockDurationSeconds));
    }

    private static void notifyRed(String translationKey) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.displayClientMessage(
                Component.translatable(translationKey)
                        .withStyle(net.minecraft.ChatFormatting.RED),
                true);
    }
}
