package com.mingmyal.radialmenu.api;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;

/**
 * 槽位条件约束。
 * <ul>
 *   <li>{@link ConditionType#ITEM_CONSUME} 物品/耐久消耗解锁(永久/限期)</li>
 *   <li>{@link ConditionType#ITEM_CHECK} 背包物品持有检测</li>
 *   <li>{@link ConditionType#XP_CONSUME} 经验点数/等级消耗解锁(永久/限期)</li>
 * </ul>
 * 解锁进度 {@code unlockedUntil} 随配置 JSON 持久化, 游戏重启后保留。
 * <p>
 * <b>本类必须保持双端通用</b>: 判定与消耗都以传入的 {@link Player} 为准,
 * 因此服务端可以用同一套代码权威地完成"扣物品/扣经验", 客户端只负责发起请求与展示。
 * 这一点是必须的 —— 客户端 {@code Inventory#setChanged()} 只递增本地计数器、不发包,
 * 服务端背包从未变化, 于是本地扣掉的东西会在下一次服务端同步时被原样还原,
 * 表现为"退出重进后解锁道具被返还"。任何依赖客户端 {@code Minecraft} 实例的展示逻辑
 * 都放在 {@code client.ConditionDisplay}, 不要加回这里。
 */
public class SlotCondition {

    public enum ConditionType {
        ITEM_CONSUME, ITEM_CHECK, XP_CONSUME
    }

    public ConditionType type = ConditionType.ITEM_CHECK;

    /** 约束物品列表(类型 1/2 使用), 可多选 */
    public List<ItemRequirement> items = new ArrayList<>();

    // --- 经验约束(类型 3) ---
    /** true=按等级, false=按经验点数 */
    public boolean xpLevels = false;
    public int xpAmount = 1;

    // --- 解锁方式(类型 1/3) ---
    /** true=限期解锁, false=永久解锁 */
    public boolean timedUnlock = false;
    public long unlockDurationSeconds = 60;

    // --- 解锁状态(持久化) ---
    /** epoch 毫秒; Long.MAX_VALUE 表示永久; 0 表示未解锁 */
    public long unlockedUntil = 0L;

    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;

    public boolean isUnlocked() {
        return unlockedUntil == Long.MAX_VALUE
                || (unlockedUntil > 0L && System.currentTimeMillis() < unlockedUntil);
    }

    /** 是否为永久解锁。 */
    public boolean isUnlockedForever() {
        return unlockedUntil == Long.MAX_VALUE;
    }

    /** 限期解锁剩余秒数; 未解锁/永久/已过期返回 0。 */
    public long remainingSeconds() {
        if (unlockedUntil <= 0L || unlockedUntil == Long.MAX_VALUE) return 0L;
        long remainMs = unlockedUntil - System.currentTimeMillis();
        return remainMs <= 0L ? 0L : (remainMs + 999L) / 1000L;
    }

    /** 记录解锁状态(随配置保存)。服务端在权威扣除成功后调用。 */
    public void unlock() {
        long now = System.currentTimeMillis();
        unlockedUntil = timedUnlock ? now + Math.max(1L, unlockDurationSeconds) * 1000L : Long.MAX_VALUE;
    }

    /** 消耗解锁类(类型 1/3)在未解锁时点击需要先弹出确认对话框。 */
    public boolean needsUnlockConfirm() {
        return isConsumeType() && !isUnlocked();
    }

    /** 条件是否满足(未解锁但资源足够也算满足, 可点击后确认消耗)。 */
    public boolean isSatisfied(Player player) {
        if (player == null) return false;

        return switch (type) {
            case ITEM_CONSUME -> isUnlocked() || checkItems(player);
            case ITEM_CHECK -> checkItems(player);
            case XP_CONSUME -> isUnlocked() || hasXp(player);
        };
    }

    /** 是否为"消耗解锁"类(物品/耐久 或 经验), 这类条件在资源足够时点击即消耗解锁。 */
    public boolean isConsumeType() {
        return type == ConditionType.ITEM_CONSUME || type == ConditionType.XP_CONSUME;
    }

    /** 该条件当前是否具备完成一次"消耗解锁"的资源。 */
    public boolean canAfford(Player player) {
        if (player == null) return false;
        return switch (type) {
            case ITEM_CONSUME -> checkItems(player);
            case XP_CONSUME -> hasXp(player);
            default -> false;
        };
    }

    private boolean checkItems(Player player) {
        boolean any = false;
        boolean consumeMode = type == ConditionType.ITEM_CONSUME;
        for (ItemRequirement req : items) {
            if (req == null) continue;
            any = true;
            if (!req.isSatisfied(player, consumeMode)) {
                return false;
            }
        }
        return any;
    }

    /** 经验是否足够。服务端用玩家自己的权威数值判定。 */
    public boolean hasXp(Player player) {
        return player != null && (xpLevels
                ? player.experienceLevel >= Math.max(1, xpAmount)
                : player.totalExperience >= Math.max(1, xpAmount));
    }

    /**
     * 注意: 这里<b>刻意没有</b>"在本地扣掉物品/经验"的方法。
     * <p>
     * 曾经有过 {@code consumeForUnlock()}, 它直接在客户端玩家的 {@code ItemStack} 上扣除 ——
     * 而 {@code Inventory#setChanged()} 只递增本地计数器、<b>不发包</b>, 服务端背包从未变化,
     * 于是退出重进时服务端把权威背包同步下来, 玩家花掉的物品就被"返还"了。
     * 现在消耗统一由服务端执行(见 {@code server.ServerUnlockHandler}),
     * 客户端只通过 {@code client.UnlockRequestManager} 发起请求并等待回执。
     * 因此这个类只保留"判定是否拿得出"的能力, 不再提供任何扣除入口。
     */

    public void sanitize() {
        if (type == null) type = ConditionType.ITEM_CHECK;
        if (items == null) items = new ArrayList<>();
        items.removeIf(req -> req == null);
        for (ItemRequirement req : items) {
            req.sanitize();
        }
        xpAmount = Math.max(1, xpAmount);
        unlockDurationSeconds = Math.max(1L, unlockDurationSeconds);
        if (unlockedUntil < 0L) unlockedUntil = 0L;
    }

    public SlotCondition copy() {
        SlotCondition copy = new SlotCondition();
        copy.type = type;
        for (ItemRequirement req : items) {
            copy.items.add(req != null ? req.copy() : null);
        }
        copy.xpLevels = xpLevels;
        copy.xpAmount = xpAmount;
        copy.timedUnlock = timedUnlock;
        copy.unlockDurationSeconds = unlockDurationSeconds;
        copy.unlockedUntil = unlockedUntil;
        return copy;
    }
}
