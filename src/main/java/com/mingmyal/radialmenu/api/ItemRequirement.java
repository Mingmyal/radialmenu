package com.mingmyal.radialmenu.api;

import com.mojang.brigadier.StringReader;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 单条物品约束: 约束物品(give 格式 ID) + 所需数量。
 * <p>
 * 在"物品/耐久消耗解锁"({@code durabilityCost > 0})时改为按耐久值消耗。
 * 物品匹配只比较注册物品类型, 忽略组件/NBT。
 */
public class ItemRequirement {

    public String itemId = "minecraft:diamond";
    /** 所需持有数量(解锁消耗模式下同时是整件消耗的数量) */
    public int count = 1;
    /** 解锁消耗模式: >0 时消耗耐久值而非整件物品 */
    public int durabilityCost = 0;

    /** 解析约束物品的注册物品, 无效返回 null。 */
    public Item resolveItem() {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            return new ItemParser(level.registryAccess()).parse(new StringReader(itemId)).item().value();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析约束物品为示例堆(用于显示名称), 无效返回 null。 */
    public ItemStack sampleStack() {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            ItemParser.ItemResult result =
                    new ItemParser(level.registryAccess()).parse(new StringReader(itemId));
            ItemStack stack = new ItemStack(result.item(), 1);
            stack.applyComponentsAndValidate(result.components());
            return stack;
        } catch (Exception e) {
            return null;
        }
    }

    /** 玩家背包(含副手/盔甲)中该物品的总数量。 */
    public int countHeld(Player player) {
        Item item = resolveItem();
        if (item == null) return 0;

        int total = 0;
        for (ItemStack stack : allStacks(player)) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 查找一件剩余耐久 >= cost 的匹配物品, 找不到返回 null。 */
    public ItemStack findDurabilityTarget(Player player, int cost) {
        Item item = resolveItem();
        if (item == null || cost <= 0) return null;

        for (ItemStack stack : allStacks(player)) {
            if (!stack.isEmpty() && stack.getItem() == item
                    && stack.getMaxDamage() > 0
                    && stack.getMaxDamage() - stack.getDamageValue() >= cost) {
                return stack;
            }
        }
        return null;
    }

    /** 是否满足本条约束。durabilityMode 为 true 时按耐久值判定。 */
    public boolean isSatisfied(Player player, boolean durabilityMode, int durabilityAmount) {
        if (resolveItem() == null) return false;
        if (durabilityMode) {
            return findDurabilityTarget(player, durabilityAmount) != null;
        }
        return countHeld(player) >= Math.max(1, count);
    }

    /** 解锁消耗(物品/耐久)。耐久模式扣耐久, 数量模式移除整件物品。 */
    public boolean consumeForUnlock(Player player, boolean durabilityMode) {
        if (durabilityMode) {
            ItemStack stack = findDurabilityTarget(player, durabilityCost);
            if (stack == null) return false;
            applyDurabilityDamage(player, stack, durabilityCost);
            return true;
        }

        int needed = Math.max(1, count);
        if (countHeld(player) < needed) return false;
        removeItems(player, resolveItem(), needed);
        return true;
    }

    private static void applyDurabilityDamage(Player player, ItemStack stack, int cost) {
        int newDamage = stack.getDamageValue() + Math.max(1, cost);
        if (newDamage >= stack.getMaxDamage()) {
            // 耐久耗尽, 物品损坏
            stack.setCount(0);
        } else {
            stack.setDamageValue(newDamage);
        }
        player.getInventory().setChanged();
    }

    private static void removeItems(Player player, Item item, int amount) {
        if (item == null || amount <= 0) return;

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
        if (left < amount) {
            player.getInventory().setChanged();
        }
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

    public ItemRequirement copy() {
        ItemRequirement copy = new ItemRequirement();
        copy.itemId = itemId;
        copy.count = count;
        copy.durabilityCost = durabilityCost;
        return copy;
    }
}
