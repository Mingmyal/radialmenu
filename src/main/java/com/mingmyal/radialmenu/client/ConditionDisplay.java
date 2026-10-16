package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.api.ItemRequirement;
import com.mingmyal.radialmenu.api.SlotCondition;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 条件约束的<b>客户端展示</b>辅助。
 * <p>
 * 这些方法只服务于界面(依赖客户端 {@code Minecraft} 实例、需要把物品解析成带组件
 * 的 {@link ItemStack} 才能取到本地化名称), 因此<b>特意从 {@link SlotCondition} /
 * {@link ItemRequirement} 中拆出来</b>:
 * 那两个类现在是双端通用的数据与判定模型, 服务端会直接加载它们来权威地执行消耗,
 * 一旦它们引用客户端专属类, 专用服务端就有 {@code NoClassDefFoundError} 的风险。
 * <p>
 * 需要"数一数、扣一扣"的判定与消耗请走那两个类(双端通用), 不要在这里实现。
 */
public final class ConditionDisplay {

    private ConditionDisplay() {}

    // --- 物品名称与描述 ---

    /** 物品约束的显示名称; 无效物品返回占位文本。 */
    public static String displayName(ItemRequirement req) {
        ItemStack sample = sampleStack(req.itemId);
        return sample != null
                ? sample.getHoverName().getString()
                : Component.translatable("radialmenu.condition.invalid_item").getString();
    }

    /** 按 give 格式解析物品为示例堆(用于取名称), 无效返回 null。 */
    public static ItemStack sampleStack(String itemId) {
        return ItemRequirement.sampleStack(Minecraft.getInstance().level, itemId);
    }

    /**
     * 本条约束的显示文本(单件如 "3×钻石"; 集合如 "3×[钻石 / 铁锭]")。
     */
    public static String describe(ItemRequirement req, boolean consumeMode) {
        boolean durabilityMode = consumeMode && req.durabilityCost > 0;

        if (req.isSet()) {
            List<String> names = new ArrayList<>();
            for (ItemRequirement member : req.members) {
                if (member != null) names.add(displayName(member));
            }
            if (names.isEmpty()) {
                return Component.translatable("radialmenu.condition.tag.any_empty").getString();
            }
            // 用方括号把候选物品括起来, 让前面的数量明确作用于"整个集合"而不是第一项
            // (否则 "3×钻石 / 铁锭" 会被读成"3个钻石 或 1个铁锭")
            String joined = "[" + String.join(" / ", names) + "]";
            if (durabilityMode) {
                return Component.translatable("radialmenu.condition.tag.durability", joined,
                        req.durabilityCost).getString();
            }
            int amount = Math.max(1, req.count);
            return amount > 1 ? amount + "×" + joined : joined;
        }

        String name = displayName(req);
        return durabilityMode
                ? Component.translatable("radialmenu.condition.tag.durability", name, req.durabilityCost)
                        .getString()
                : Math.max(1, req.count) + "×" + name;
    }

    /**
     * 本条约束"将要消耗什么"的显示文本, 例如 "3×钻石"。
     * <p>
     * 集合按当前背包实际会扣掉的那一件显示, 而不是罗列所有候选。
     * 资源不足时返回 null, 由调用方回退到需求描述。
     */
    public static String describeConsumption(ItemRequirement req, Player player, boolean consumeMode) {
        ItemRequirement picked = pickConsumable(req, player, consumeMode);
        if (picked == null) return null;

        String name = displayName(picked);
        boolean durabilityMode = consumeMode && req.durabilityCost > 0;
        if (durabilityMode) {
            return Component.translatable("radialmenu.condition.tag.durability", name, req.durabilityCost)
                    .getString();
        }
        return Math.max(1, req.count) + "×" + name;
    }

    /** 实际会被消耗的那一条(集合取第一个可消耗的成员; 单件则返回自身)。 */
    public static ItemRequirement pickConsumable(ItemRequirement req, Player player, boolean consumeMode) {
        boolean durabilityMode = consumeMode && req.durabilityCost > 0;
        if (req.isSet()) {
            for (ItemRequirement member : req.members) {
                if (member != null && matches(member, player, durabilityMode, req.durabilityCost, req.count)) {
                    return member;
                }
            }
            return null;
        }
        return matches(req, player, durabilityMode, req.durabilityCost, req.count) ? req : null;
    }

    /** 作为集合成员, 用所属集合的数量/耐久判定本条目(仅用于依赖明细展示)。 */
    public static boolean isSatisfiedAsMember(
            ItemRequirement member, Player player, boolean consumeMode, ItemRequirement set) {
        if (set == null) return false;
        return matches(member, player, consumeMode && set.durabilityCost > 0, set.durabilityCost, set.count);
    }

    /** 展示用的判定: 与 {@link ItemRequirement#isSatisfied} 同义, 但可指定集合统一数值。 */
    private static boolean matches(
            ItemRequirement self, Player player, boolean durabilityMode, int cost, int amount) {
        return player != null && self.matchesSelf(player, durabilityMode, cost, amount);
    }

    // --- 条件文本 ---

    /** 条件总述(单行, 不含物品清单): 只说明要满足什么类型、以及解锁方式。 */
    public static Component describeHeader(SlotCondition condition) {
        String unlock = unlockTag(condition);

        return switch (condition.type) {
            case ITEM_CONSUME ->
                Component.translatable("radialmenu.condition.header.item_consume", unlock);
            case ITEM_CHECK -> Component.translatable("radialmenu.condition.header.item_check");
            case XP_CONSUME ->
                Component.translatable("radialmenu.condition.header.xp_consume", xpTag(condition), unlock);
        };
    }

    /** 解锁方式标签("永久" / "限时N秒")。 */
    private static String unlockTag(SlotCondition condition) {
        return condition.timedUnlock
                ? Component.translatable("radialmenu.condition.tag.timed", condition.unlockDurationSeconds)
                        .getString()
                : Component.translatable("radialmenu.condition.tag.permanent").getString();
    }

    /** 经验数量标签("N级" / "N点")。 */
    private static String xpTag(SlotCondition condition) {
        return condition.xpLevels
                ? Component.translatable("radialmenu.condition.tag.levels", condition.xpAmount).getString()
                : Component.translatable("radialmenu.condition.tag.points", condition.xpAmount).getString();
    }

    /** 剩余时间的紧凑文本, 自动换算单位(秒/分/小时/天)。 */
    public static String remainingTimeText(SlotCondition condition) {
        long total = condition.remainingSeconds();
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

    /**
     * "确认消耗"对话框的内容: 只列<b>当前背包里实际会被消耗</b>的东西, 而不是全部依赖。
     * <p>
     * 注意: 这只是给玩家看的预览。真正的扣除由服务端按其权威配置执行,
     * 因此使用按服自定义副本时, 实际消耗可能与这里显示的不同。
     */
    public static List<Component> describeConsumptionLines(SlotCondition condition) {
        List<Component> lines = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        if (player == null) return lines;

        switch (condition.type) {
            case ITEM_CONSUME -> {
                boolean consumeMode = true;
                for (ItemRequirement req : condition.items) {
                    if (req == null) continue;
                    String text = describeConsumption(req, player, consumeMode);
                    // 资源不足时回退到需求描述, 至少让玩家知道缺什么
                    lines.add(Component.literal(text != null ? text : describe(req, consumeMode)));
                }
            }
            case XP_CONSUME -> lines.add(Component.literal(xpTag(condition)));
            default -> {
                // 持有检测类不消耗, 无内容可列
            }
        }

        return lines;
    }

    /**
     * 解锁依赖明细: 悬停槽位时右键弹出的界面逐行显示, 并标注每条当前是否已满足。
     * <p>
     * 第一行为条件总述, 其后每行对应一条约束物品(集合则展开到各成员)。
     */
    public static List<Component> describeDependencyLines(SlotCondition condition) {
        List<Component> lines = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        boolean consumeMode = condition.type == SlotCondition.ConditionType.ITEM_CONSUME;

        lines.add(describeHeader(condition));

        switch (condition.type) {
            case ITEM_CONSUME, ITEM_CHECK -> {
                for (ItemRequirement req : condition.items) {
                    if (req == null) continue;
                    if (req.isSet()) {
                        // 集合: 只给一行"任意一件"的汇总状态, 成员名称由下面的明细行逐条列出,
                        // 汇总行里再重复一遍物品名是多余的
                        lines.add(detailLine(
                                Component.translatable("radialmenu.condition.detail.any").getString(),
                                player != null && req.isSatisfied(player, consumeMode)));
                        for (ItemRequirement member : req.members) {
                            if (member == null) continue;
                            lines.add(detailLine(
                                    "  · " + displayName(member),
                                    player != null && isSatisfiedAsMember(member, player, consumeMode, req)));
                        }
                    } else {
                        lines.add(detailLine(
                                describe(req, consumeMode),
                                player != null && req.isSatisfied(player, consumeMode)));
                    }
                }
            }
            case XP_CONSUME -> lines.add(detailLine(
                    xpTag(condition), player != null && condition.hasXp(player)));
        }

        return lines;
    }

    private static Component detailLine(String text, boolean satisfied) {
        return Component.literal((satisfied ? "✔ " : "✘ ") + text);
    }

    /** 该行依赖当前是否已满足(用于右键面板的绿/红着色)。 */
    public static boolean isDependencySatisfied(Component line) {
        return line.getString().startsWith("✔");
    }
}
