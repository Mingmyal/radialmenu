package com.mingmyal.radialmenu.api;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 槽位条件约束。
 * <ul>
 *   <li>{@link ConditionType#ITEM_CONSUME} 物品/耐久消耗解锁(永久/限期)</li>
 *   <li>{@link ConditionType#ITEM_CHECK} 背包物品持有检测</li>
 *   <li>{@link ConditionType#XP_CONSUME} 经验点数/等级消耗解锁(永久/限期)</li>
 * </ul>
 * 解锁进度 {@code unlockedUntil} 随配置 JSON 持久化, 游戏重启后保留。
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

    /** 剩余时间的紧凑文本, 自动换算单位(秒/分/小时/天)。 */
    public String remainingTimeText() {
        long total = remainingSeconds();
        if (total <= 0L) return "";
        if (total < 60L) {
            return total + unit("s");
        }
        if (total < 3600L) {
            long m = total / 60L, s = total % 60L;
            return s == 0L ? m + unit("m") : m + unit("m") + s + unit("s");
        }
        if (total < 86400L) {
            long h = total / 3600L, m = (total % 3600L) / 60L;
            return m == 0L ? h + unit("h") : h + unit("h") + m + unit("m");
        }
        long d = total / 86400L, h = (total % 86400L) / 3600L;
        return h == 0L ? d + unit("d") : d + unit("d") + h + unit("h");
    }

    private static String unit(String key) {
        return Component.translatable("radialmenu.condition.unit." + key).getString();
    }

    /** 记录解锁状态(随配置保存)。 */
    public void unlock() {
        long now = System.currentTimeMillis();
        unlockedUntil = timedUnlock ? now + Math.max(1L, unlockDurationSeconds) * 1000L : Long.MAX_VALUE;
    }

    /** 消耗解锁类(类型 1/3)在未解锁时点击需要先弹出确认对话框。 */
    public boolean needsUnlockConfirm() {
        return (type == ConditionType.ITEM_CONSUME || type == ConditionType.XP_CONSUME) && !isUnlocked();
    }

    private Player player() {
        return Minecraft.getInstance().player;
    }

    /** 条件是否满足(未解锁但资源足够也算满足, 可点击后确认消耗)。 */
    public boolean isSatisfied() {
        Player player = player();
        if (player == null) return false;

        return switch (type) {
            case ITEM_CONSUME -> isUnlocked() || checkItems(player);
            case ITEM_CHECK -> checkItems(player);
            case XP_CONSUME -> isUnlocked() || hasXp(player);
        };
    }

    private boolean checkItems(Player player) {
        boolean any = false;
        for (ItemRequirement req : items) {
            if (req == null) continue;
            any = true;
            boolean durabilityMode = type == ConditionType.ITEM_CONSUME && req.durabilityCost > 0;
            int durabilityAmount = req.durabilityCost;
            if (!req.isSatisfied(player, durabilityMode, durabilityAmount)) {
                return false;
            }
        }
        return any;
    }

    private boolean hasXp(Player player) {
        return xpLevels
                ? player.experienceLevel >= Math.max(1, xpAmount)
                : player.totalExperience >= Math.max(1, xpAmount);
    }

    /** 确认对话框后执行: 消耗资源并记录解锁状态。失败返回 false。 */
    public boolean consumeForUnlock() {
        Player player = player();
        if (player == null) return false;

        boolean consumed = switch (type) {
            case ITEM_CONSUME -> {
                for (ItemRequirement req : items) {
                    if (req == null) continue;
                    boolean durabilityMode = req.durabilityCost > 0;
                    if (!req.consumeForUnlock(player, durabilityMode)) yield false;
                }
                yield true;
            }
            case XP_CONSUME -> {
                if (!hasXp(player)) yield false;
                if (xpLevels) {
                    player.experienceLevel -= Math.max(1, xpAmount);
                    player.experienceProgress = 0.0f;
                } else {
                    player.giveExperiencePoints(-Math.max(1, xpAmount));
                }
                yield true;
            }
            default -> false;
        };

        if (consumed) {
            unlock();
        }
        return consumed;
    }

    /** 生成选中提示文本(悬停槽位时显示)。 */
    public Component describe() {
        List<String> parts = new ArrayList<>();
        for (ItemRequirement req : items) {
            if (req == null) continue;
            parts.add(describeItem(req));
        }
        String itemList = String.join(" + ", parts);
        String unlock = timedUnlock
                ? Component.translatable("radialmenu.condition.tag.timed", unlockDurationSeconds).getString()
                : Component.translatable("radialmenu.condition.tag.permanent").getString();

        return switch (type) {
            case ITEM_CONSUME -> Component.translatable("radialmenu.condition.desc.item_consume", itemList, unlock);
            case ITEM_CHECK -> Component.translatable("radialmenu.condition.desc.item_check", itemList);
            case XP_CONSUME -> Component.translatable(
                    "radialmenu.condition.desc.xp_consume",
                    xpLevels
                            ? Component.translatable("radialmenu.condition.tag.levels", xpAmount).getString()
                            : Component.translatable("radialmenu.condition.tag.points", xpAmount).getString(),
                    unlock);
        };
    }

    private String describeItem(ItemRequirement req) {
        ItemStack sample = req.sampleStack();
        String name = sample != null
                ? sample.getHoverName().getString()
                : Component.translatable("radialmenu.condition.invalid_item").getString();

        String text;
        if (type == ConditionType.ITEM_CONSUME && req.durabilityCost > 0) {
            text = Component.translatable("radialmenu.condition.tag.durability", name, req.durabilityCost).getString();
        } else {
            text = Math.max(1, req.count) + "×" + name;
        }

        return text;
    }

    public void sanitize() {
        if (type == null) type = ConditionType.ITEM_CHECK;
        if (items == null) items = new ArrayList<>();
        items.removeIf(req -> req == null);
        for (ItemRequirement req : items) {
            req.count = Math.max(1, req.count);
            req.durabilityCost = Math.max(0, req.durabilityCost);
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
