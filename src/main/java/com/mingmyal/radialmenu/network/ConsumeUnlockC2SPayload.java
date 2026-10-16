package com.mingmyal.radialmenu.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 请求"消耗物品/经验以解锁某个槽位"。
 * <p>
 * <b>为什么必须有这个包</b>: 客户端本地扣物品只是改了本地 {@code ItemStack}, 而
 * {@code Inventory#setChanged()} 只递增一个计数器、<b>不发包</b>, 服务端的背包从未变化。
 * 于是退出重进时服务端把权威背包重新同步下来, 玩家花掉的物品就"被返还"了。
 * 消耗这类<b>改动游戏状态</b>的操作必须由服务端执行, 客户端只负责发起请求与展示结果。
 * <p>
 * 消耗内容由客户端声明: 轮盘的条件约束本来就是<b>玩家自己配置的菜单规则</b>, 服务端
 * 并不保存一份可用于判定的配置(它只以原始 JSON 文本转发配置, 刻意不在服务端反序列化,
 * 以免依赖客户端渲染类)。服务端的职责是: 校验玩家<b>确实拿得出</b>这些东西, 然后
 * 在权威背包上真正扣掉 —— 这正是修复返还问题的关键。
 *
 * @param slotKey             槽位定位键(如 {@code "0.2"} 或 {@code "0#on"}), 见 {@code SlotPath}
 * @param conditionKind       条件类型: 仅 {@code ITEM_CONSUME} / {@code XP_CONSUME} 有意义
 *                            (不叫 {@code type}, 因为 record 的访问器会与
 *                            {@link CustomPacketPayload#type()} 冲突)
 * @param itemCosts           物品/耐久消耗清单(类型为物品消耗时使用)
 * @param xpAmount            经验消耗量(类型为经验消耗时使用)
 * @param xpLevels            经验按等级(true)还是按点数(false)
 * @param timedUnlock         限期解锁(true)还是永久解锁(false)
 * @param unlockDurationSeconds 限期解锁时长(秒)
 */
public record ConsumeUnlockC2SPayload(
        String slotKey,
        ConditionKind conditionKind,
        List<ItemCost> itemCosts,
        int xpAmount,
        boolean xpLevels,
        boolean timedUnlock,
        long unlockDurationSeconds)
        implements CustomPacketPayload {

    /** 槽位键长度上限: 路径层级很浅, 64 字符足够, 同时防止异常超长字符串。 */
    public static final int MAX_SLOT_KEY_LENGTH = 64;

    /** 单次请求的消耗条目上限: 条件物品条数本就很少, 给足余量并封住滥用。 */
    public static final int MAX_ITEM_COSTS = 64;

    /** 单条消耗的数量上限, 与编辑器输入范围一致。 */
    public static final int MAX_COUNT = 999_999;

    public static final CustomPacketPayload.Type<ConsumeUnlockC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "consume_unlock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsumeUnlockC2SPayload> CODEC =
            CustomPacketPayload.codec(ConsumeUnlockC2SPayload::write, ConsumeUnlockC2SPayload::new);

    /** 条件类型: 只挑出需要服务端扣除的两类, 避免把"持有检测"也发过来。 */
    public enum ConditionKind {
        ITEM_CONSUME, XP_CONSUME;

        /** 序号越界时回落到物品消耗, 保证解码不会因异常数据崩溃。 */
        static ConditionKind byOrdinal(int ordinal) {
            ConditionKind[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ITEM_CONSUME;
        }
    }

    /** 一条物品/耐久消耗: 物品 ID + 数量 + 耐久消耗量(>0 时按耐久扣)。 */
    public record ItemCost(String itemId, int count, int durabilityCost) {}

    public ConsumeUnlockC2SPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(MAX_SLOT_KEY_LENGTH),
                ConditionKind.byOrdinal(buf.readVarInt()),
                readCosts(buf),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarLong());
    }

    private static List<ItemCost> readCosts(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        // 上限在读取前就生效: 否则一个伪造的长度就能让我们分配巨大列表
        size = Math.clamp(size, 0, MAX_ITEM_COSTS);
        List<ItemCost> costs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            costs.add(new ItemCost(
                    buf.readUtf(MAX_SLOT_KEY_LENGTH),
                    buf.readVarInt(),
                    buf.readVarInt()));
        }
        return costs;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(slotKey == null ? "" : slotKey, MAX_SLOT_KEY_LENGTH);
        buf.writeVarInt(conditionKind == null
                ? ConditionKind.ITEM_CONSUME.ordinal()
                : conditionKind.ordinal());

        List<ItemCost> costs = itemCosts != null ? itemCosts : List.of();
        int size = Math.min(costs.size(), MAX_ITEM_COSTS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ItemCost cost = costs.get(i);
            buf.writeUtf(cost.itemId() == null ? "" : cost.itemId(), MAX_SLOT_KEY_LENGTH);
            buf.writeVarInt(cost.count());
            buf.writeVarInt(cost.durabilityCost());
        }

        buf.writeVarInt(xpAmount);
        buf.writeBoolean(xpLevels);
        buf.writeBoolean(timedUnlock);
        buf.writeVarLong(unlockDurationSeconds);
    }

    /**
     * 报文是否在结构上可用。
     * <p>
     * 服务端在扣除前必须过一遍这里: 客户端是<b>不可信</b>的, 数量为负、物品 ID 为空、
     * 清单为空之类的报文都应当直接拒绝, 而不是让它们进到扣除逻辑里。
     */
    public boolean isStructurallyValid() {
        if (slotKey == null || slotKey.isBlank()) return false;
        if (conditionKind == null) return false;
        if (unlockDurationSeconds < 1L) return false;

        return switch (conditionKind) {
            case ITEM_CONSUME -> {
                if (itemCosts == null || itemCosts.isEmpty()) yield false;
                if (itemCosts.size() > MAX_ITEM_COSTS) yield false;
                for (ItemCost cost : itemCosts) {
                    if (cost == null || cost.itemId() == null || cost.itemId().isBlank()) yield false;
                    if (cost.durabilityCost() < 0 || cost.durabilityCost() > MAX_COUNT) yield false;
                    // 耐久模式下数量由耐久值承担, 数量模式必须至少 1 件
                    if (cost.durabilityCost() == 0 && cost.count() < 1) yield false;
                    if (cost.count() > MAX_COUNT) yield false;
                }
                yield true;
            }
            case XP_CONSUME -> xpAmount >= 1 && xpAmount <= MAX_COUNT;
        };
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
