package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.ItemRequirement;
import com.mingmyal.radialmenu.network.ConsumeUnlockC2SPayload;
import com.mingmyal.radialmenu.network.ConsumeUnlockResultS2CPayload;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端权威的"消耗解锁"执行器。
 * <p>
 * <b>为什么必须在服务端做</b>: 客户端扣物品只是改本地 {@code ItemStack},
 * {@code Inventory#setChanged()} 只递增计数器、<b>不发包</b>, 服务端背包从未变化;
 * 退出重进时服务端把权威背包同步下来, 玩家花掉的物品就被"返还"了。
 * 因此消耗只能由服务端执行 —— 客户端只声明"我想解锁哪个槽位、按我的菜单规则该付什么",
 * 服务端的职责是<b>校验他真的拿得出, 然后在他身上真正扣掉</b>。
 * <p>
 * 可信边界:
 * <ul>
 *   <li><b>不信任</b>报文结构 —— {@link ConsumeUnlockC2SPayload#isStructurallyValid()} 先过滤
 *       数量为负 / 物品 ID 为空 / 清单为空之类的报文。</li>
 *   <li><b>不信任</b>资源声明 —— 够不够扣完全由服务端读自己的背包与经验判定。</li>
 *   <li><b>不信任</b>解锁结果 —— 截止时间戳由服务端按报文里的解锁方式自行计算后回传,
 *       客户端只负责记录, 因此"限期"不会被客户端改写成"永久"。</li>
 * </ul>
 * "解锁需要付出什么"本质上属于玩家自己的菜单规则(服务端只以原始 JSON 文本转发配置,
 * 刻意不做反序列化以免依赖客户端渲染类), 所以扣多少来自客户端声明; 但<b>扣没扣、够不够扣,
 * 完全由服务端说了算</b>, 玩家无法凭空拿到解锁。
 */
public final class ServerUnlockHandler {

    private ServerUnlockHandler() {}

    /** 处理一次消耗解锁请求: 校验 → 权威扣除 → 回执。 */
    public static void handle(ConsumeUnlockC2SPayload payload, ServerPlayer player) {
        if (player == null || payload == null) {
            return;
        }

        String slotKey = payload.slotKey();

        if (!payload.isStructurallyValid()) {
            RadialCommon.LOGGER.warn(
                    "Rejected malformed radial consume-unlock request from {} (slot '{}')",
                    player.getGameProfile().getName(), slotKey);
            reply(player, ConsumeUnlockResultS2CPayload.denied(
                    slotKey, ConsumeUnlockResultS2CPayload.REASON_INVALID_SLOT));
            return;
        }

        // 记不下就别扣: 扣了费却没能留下解锁记录, 等于玩家白花
        if (!ServerUnlockStore.canRecord(player.getUUID(), slotKey)) {
            RadialCommon.LOGGER.warn(
                    "Radial unlock store for {} is full; rejecting unlock of '{}' without charging",
                    player.getGameProfile().getName(), slotKey);
            reply(player, ConsumeUnlockResultS2CPayload.denied(
                    slotKey, ConsumeUnlockResultS2CPayload.REASON_INVALID_SLOT));
            return;
        }

        String failure = switch (payload.conditionKind()) {
            case ITEM_CONSUME -> chargeItems(payload, player);
            case XP_CONSUME -> chargeXp(payload, player);
        };

        if (failure != null) {
            reply(player, ConsumeUnlockResultS2CPayload.denied(slotKey, failure));
            return;
        }

        // 扣除成功: 立刻把背包/经验的改动推给客户端, 消除"数字还停在旧值"的观感延迟
        syncToClient(player);

        long unlockedUntil = deadline(payload);

        // 记入服务端权威副本: 这份记录才是玩家资产的凭据, 进服时会重新下发给客户端
        ServerUnlockStore.record(player.getUUID(), slotKey, unlockedUntil);

        RadialCommon.LOGGER.info(
                "Radial consume-unlock granted for {} (slot '{}', {}, until {})",
                player.getGameProfile().getName(), slotKey, payload.conditionKind(), unlockedUntil);

        reply(player, ConsumeUnlockResultS2CPayload.granted(slotKey, unlockedUntil));
    }

    // --- 物品消耗 ---

    /**
     * 按客户端声明的清单扣除物品/耐久。
     *
     * @return null 表示已扣除; 否则为失败原因的翻译键(此时未扣除任何东西)
     */
    private static String chargeItems(ConsumeUnlockC2SPayload payload, ServerPlayer player) {
        // 先整体校验、再统一扣除: 逐条扣到一半才发现不够, 会留下"扣了前几项却什么都没解锁"的烂摊子。
        // 校验必须带"本轮已计划占用"的记账, 否则两条要求同一件物品的需求各自看着都够、
        // 实际扣到第二条就会失败(同一堆被重复计入)。
        Map<ItemStack, Integer> plannedDurability = new IdentityHashMap<>();
        Map<String, Integer> plannedCounts = new HashMap<>();

        for (ConsumeUnlockC2SPayload.ItemCost cost : payload.itemCosts()) {
            if (cost.durabilityCost() > 0) {
                // 用已计划的耐久占用去找, 保证两轮不会选中同一堆的同一段耐久
                ItemStack target = ItemRequirement.findDurabilityTarget(
                        player, cost.itemId(), cost.durabilityCost(), plannedDurability);
                if (target == null) {
                    return ConsumeUnlockResultS2CPayload.REASON_INSUFFICIENT;
                }
                plannedDurability.merge(target, cost.durabilityCost(), Integer::sum);
            } else {
                int need = Math.max(1, cost.count());
                int available = ItemRequirement.countHeld(player, cost.itemId())
                        - plannedCounts.getOrDefault(cost.itemId(), 0);
                if (available < need) {
                    return ConsumeUnlockResultS2CPayload.REASON_INSUFFICIENT;
                }
                plannedCounts.merge(cost.itemId(), need, Integer::sum);
            }
        }

        for (ConsumeUnlockC2SPayload.ItemCost cost : payload.itemCosts()) {
            if (!ItemRequirement.consume(player, cost.itemId(), cost.count(), cost.durabilityCost())) {
                // 上面刚校验过, 走到这里说明存在并发或异常。已扣的部分无法安全回滚,
                // 因此明确记 error 而不是静默继续, 便于排查
                RadialCommon.LOGGER.error(
                        "Radial consume-unlock failed midway for {} on item '{}'; partial charge applied",
                        player.getGameProfile().getName(), cost.itemId());
                return ConsumeUnlockResultS2CPayload.REASON_INSUFFICIENT;
            }
        }

        return null;
    }

    // --- 经验消耗 ---

    /** @return null 表示已扣除; 否则为失败原因 */
    private static String chargeXp(ConsumeUnlockC2SPayload payload, ServerPlayer player) {
        int amount = Math.max(1, payload.xpAmount());

        if (payload.xpLevels()) {
            if (player.experienceLevel < amount) {
                return ConsumeUnlockResultS2CPayload.REASON_INSUFFICIENT;
            }
            // 走原版路径: 一并处理进度归零与服务端经验重发
            player.giveExperienceLevels(-amount);
        } else {
            if (player.totalExperience < amount) {
                return ConsumeUnlockResultS2CPayload.REASON_INSUFFICIENT;
            }
            player.giveExperiencePoints(-amount);
        }

        return null;
    }

    // --- 工具 ---

    /** 由报文里的解锁方式算出截止时间戳(永久为 {@link Long#MAX_VALUE})。 */
    private static long deadline(ConsumeUnlockC2SPayload payload) {
        if (!payload.timedUnlock()) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() + Math.max(1L, payload.unlockDurationSeconds()) * 1000L;
    }

    /**
     * 把背包与经验的改动立刻同步给该玩家。
     * <p>
     * 原版下一次 tick 在 {@code ServerPlayer#tick} / {@code doTick} 里本来就会
     * {@code broadcastChanges()} 并重发经验包, 这里主动推一次只是为了消除观感延迟,
     * 并不替代原版同步 —— 因此失败也只记警告, 不让解锁因此失败。
     */
    private static void syncToClient(ServerPlayer player) {
        try {
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
        } catch (Throwable t) {
            RadialCommon.LOGGER.warn("Failed to broadcast inventory changes after radial unlock", t);
        }
    }

    /** 发送回执; 通道不可用时静默跳过(玩家客户端未装模组时不应打断服务端 tick)。 */
    private static void reply(ServerPlayer player, ConsumeUnlockResultS2CPayload payload) {
        if (ServerPlayNetworking.canSend(player, ConsumeUnlockResultS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
