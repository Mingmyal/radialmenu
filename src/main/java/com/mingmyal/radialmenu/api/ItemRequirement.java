package com.mingmyal.radialmenu.api;

import com.mojang.brigadier.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 单条物品约束。
 * <p>
 * 有两种形态:
 * <ul>
 *   <li><b>单件</b>({@link #members} 为 null): 约束物品(give 格式 ID) + 所需数量。</li>
 *   <li><b>集合</b>({@link #members} 非空): 集合内<b>任意一件</b>满足即通过。</li>
 * </ul>
 * 同一件 mod 物品常有多种颜色/款式且 ID 不同, 用集合即可覆盖全部款式。
 * <p>
 * <b>集合的数量与耐久由集合自身统一提供</b>, 所有成员共用同一套数值 ——
 * 因为判定是"任意一件满足", 若每个成员各带一套数值, 就会出现"某一件够了、
 * 另一件不够"的自相矛盾状态。成员自身只贡献物品 ID。
 * <p>
 * 在"物品/耐久消耗解锁"({@code durabilityCost > 0})时改为按耐久值消耗。
 * 物品匹配只比较注册物品类型, 忽略组件/NBT。
 * <p>
 * <b>本类必须保持双端通用</b>: 服务端会直接加载它来权威地执行消耗, 因此这里
 * 只允许出现服务端也存在的 API(玩家、背包、注册表)。物品的本地化名称、格式化描述
 * 等需要客户端 {@code Minecraft} 实例的展示逻辑一律放在
 * {@code client.ConditionDisplay} —— 一旦在这里引用客户端专属类, 专用服务端就会
 * 在类加载阶段失败。
 */
public class ItemRequirement {

    /** 单个集合的成员上限(同一 mod 物品的款式可能非常多)。 */
    public static final int MAX_SET_MEMBERS = 100;

    public String itemId = "minecraft:diamond";
    /** 所需持有数量(解锁消耗模式下同时是整件消耗的数量); 集合时对全部成员生效 */
    public int count = 1;
    /** 解锁消耗模式: >0 时消耗耐久值而非整件物品; 集合时对全部成员生效 */
    public int durabilityCost = 0;

    /**
     * 非空时本条为"物品集合": 集合内任意一件满足即通过。
     * <p>
     * 成员的 {@code count}/{@code durabilityCost} 不参与判定(统一用集合自身的值)。
     * 旧配置没有该字段, Gson 反序列化后为 null, 视作单件约束(向后兼容);
     * 集合不支持嵌套, 成员一律按单件处理(见 {@link #sanitize()})。
     */
    public List<ItemRequirement> members;

    /** 是否为物品集合(集合内任意一件满足即可)。 */
    public boolean isSet() {
        return members != null;
    }

    /** 新建一个空集合条目(尚未添加任何成员)。 */
    public static ItemRequirement newSet() {
        ItemRequirement set = new ItemRequirement();
        set.members = new ArrayList<>();
        return set;
    }

    // --- 解析(双端通用: 用给定 level 的注册表) ---

    /** 解析物品 ID 为注册物品; 无效返回 null。 */
    public static Item resolveItem(Level level, String itemId) {
        if (level == null || itemId == null) return null;
        try {
            return new ItemParser(level.registryAccess()).parse(new StringReader(itemId)).item().value();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析物品 ID 为注册物品(以玩家所在世界为准); 无效返回 null。 */
    public static Item resolveItem(Player player, String itemId) {
        return player == null ? null : resolveItem(player.level(), itemId);
    }

    /**
     * 按 give 格式解析物品为示例堆(带组件), 无效返回 null。
     * <p>
     * 只用于取本地化名称等展示目的, 但本身不依赖客户端类(只要传入的 level 可用),
     * 因此放在这里不影响服务端加载。
     */
    public static ItemStack sampleStack(Level level, String itemId) {
        if (level == null || itemId == null) return null;
        try {
            ItemParser.ItemResult result =
                    new ItemParser(level.registryAccess()).parse(new StringReader(itemId));
            ItemStack stack = new ItemStack(result.item(), 1);
            stack.applyComponentsAndValidate(result.components());
            return stack;
        } catch (Exception e) {
            return null;
        }
    }

    // --- 判定(双端通用) ---

    /** 玩家背包(含副手/盔甲)中该物品的总数量。 */
    public static int countHeld(Player player, String itemId) {
        Item item = resolveItem(player, itemId);
        if (item == null || player == null) return 0;

        int total = 0;
        for (ItemStack stack : allStacks(player)) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 查找一件剩余耐久 >= cost 的匹配物品, 找不到返回 null。
     *
     * @param plannedDamage 本次事务中已经<b>计划</b>扣在该堆上的耐久。同一堆可能被多条需求
     *                      命中, 带上它才不会重复选中同一件而实际扣不够。可为 null。
     */
    public static ItemStack findDurabilityTarget(
            Player player, String itemId, int cost, Map<ItemStack, Integer> plannedDamage) {
        Item item = resolveItem(player, itemId);
        if (item == null || player == null || cost <= 0) return null;

        for (ItemStack stack : allStacks(player)) {
            if (stack.isEmpty() || stack.getItem() != item) continue;
            int maxDamage = stack.getMaxDamage();
            if (maxDamage <= 0) continue;
            int planned = plannedDamage != null ? plannedDamage.getOrDefault(stack, 0) : 0;
            if (maxDamage - stack.getDamageValue() - planned >= cost) {
                return stack;
            }
        }
        return null;
    }

    /**
     * 本条约束(单件或集合)是否满足。
     *
     * @param consumeMode 所属条件是否为消耗解锁类; 为 true 且 {@link #durabilityCost} > 0 时按耐久值判定。
     */
    public boolean isSatisfied(Player player, boolean consumeMode) {
        boolean durabilityMode = consumeMode && durabilityCost > 0;
        if (isSet()) {
            for (ItemRequirement member : members) {
                if (member != null && member.matchesSelf(player, durabilityMode, durabilityCost, count)) {
                    return true;
                }
            }
            return false;
        }
        return matchesSelf(player, durabilityMode, durabilityCost, count);
    }

    /** 按给定的数量/耐久判定本条目自身(集合成员借用集合的统一数值)。 */
    public boolean matchesSelf(Player player, boolean durabilityMode, int cost, int amount) {
        if (resolveItem(player, itemId) == null) return false;
        if (durabilityMode) {
            return findDurabilityTarget(player, itemId, cost, null) != null;
        }
        return countHeld(player, itemId) >= Math.max(1, amount);
    }

    // --- 消耗(双端通用; 服务端权威执行的入口) ---

    /** 扣耐久; 耐久耗尽则整件损坏。 */
    public static void damage(Player player, ItemStack stack, int cost) {
        if (player == null || stack == null || stack.isEmpty() || cost <= 0) return;

        int newDamage = stack.getDamageValue() + cost;
        if (stack.getMaxDamage() > 0 && newDamage >= stack.getMaxDamage()) {
            // 耐久耗尽, 物品损坏
            stack.setCount(0);
        } else {
            stack.setDamageValue(newDamage);
        }
        player.getInventory().setChanged();
    }

    /** 移除指定数量的物品; 数量不足时不改动并返回 false。 */
    public static boolean removeItems(Player player, String itemId, int amount) {
        if (player == null || amount <= 0) return false;
        Item item = resolveItem(player, itemId);
        if (item == null) return false;
        // 先整体校验再扣除: 中途失败会留下扣了一半的烂摊子
        if (countHeld(player, itemId) < amount) return false;

        int left = amount;
        for (List<ItemStack> list : allStackLists(player)) {
            for (int i = 0; i < list.size() && left > 0; i++) {
                ItemStack stack = list.get(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    int take = Math.min(left, stack.getCount());
                    stack.shrink(take);
                    left -= take;
                }
            }
        }

        player.getInventory().setChanged();
        return left == 0;
    }

    /**
     * 执行一次"物品/耐久"消耗: 耐久模式扣耐久, 条数模式移除整件物品。
     * <p>
     * 校验与扣除都基于传入玩家的真实背包, 因此服务端调用它时客户端无法伪造消耗内容。
     */
    public static boolean consume(Player player, String itemId, int count, int durabilityCost) {
        if (player == null) return false;

        if (durabilityCost > 0) {
            ItemStack stack = findDurabilityTarget(player, itemId, durabilityCost, null);
            if (stack == null) return false;
            damage(player, stack, durabilityCost);
            return true;
        }
        return removeItems(player, itemId, Math.max(1, count));
    }

    private static List<List<ItemStack>> allStackLists(Player player) {
        var inv = player.getInventory();
        return List.of(inv.items, inv.armor, inv.offhand);
    }

    private static List<ItemStack> allStacks(Player player) {
        var inv = player.getInventory();
        return Stream.of(inv.items, inv.armor, inv.offhand)
                .flatMap(List::stream)
                .toList();
    }

    /** 补齐字段并丢弃无效内容; 集合成员一律按单件处理(不支持嵌套)。 */
    public void sanitize() {
        if (itemId == null) itemId = "minecraft:diamond";
        count = Math.max(1, count);
        durabilityCost = Math.max(0, durabilityCost);

        if (members != null) {
            members.removeIf(member -> member == null);
            for (ItemRequirement member : members) {
                member.members = null;
                member.sanitize();
                // 集合的数量/耐久统一由集合提供, 成员自身不保留这两项
                member.count = 1;
                member.durabilityCost = 0;
            }
        }
    }

    public ItemRequirement copy() {
        ItemRequirement copy = new ItemRequirement();
        copy.itemId = itemId;
        copy.count = count;
        copy.durabilityCost = durabilityCost;
        if (members != null) {
            copy.members = new ArrayList<>();
            for (ItemRequirement member : members) {
                copy.members.add(member != null ? member.copy() : null);
            }
        }
        return copy;
    }
}
