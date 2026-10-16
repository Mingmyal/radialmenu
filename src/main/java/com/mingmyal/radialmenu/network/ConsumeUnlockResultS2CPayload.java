package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 一次"消耗解锁"请求的处理结果。
 * <p>
 * 只有服务端确认<b>已经真的扣掉了东西</b>, 客户端才会把槽位标成已解锁并执行其动作。
 * 客户端在此之前不做任何本地扣除, 因此不存在"本地解锁了、退出重进却被还原"的中间态。
 * <p>
 * 解锁时间戳由服务端按<b>权威配置</b>计算后下发, 而不是让客户端按自己那份配置算:
 * 使用按服自定义副本时两份配置可能不同(解锁时长/限期或永久), 以服务端为准才能保证
 * "扣了什么"与"解锁多久"始终一致。
 *
 * @param granted       是否已解锁(含"此前就已解锁"的幂等情形)
 * @param slotKey       请求对应的槽位键, 原样回传, 便于客户端确认是同一次请求
 * @param unlockedUntil 解锁截止时间(epoch 毫秒; {@code Long.MAX_VALUE} 表示永久);
 *                      {@code granted} 为 false 时无意义
 * @param reasonKey     失败原因的翻译键; 成功时为空字符串
 */
public record ConsumeUnlockResultS2CPayload(
        boolean granted, String slotKey, long unlockedUntil, String reasonKey)
        implements CustomPacketPayload {

    /** 拒绝原因: 槽位不存在, 或该槽位没有消耗类条件。 */
    public static final String REASON_INVALID_SLOT = "radialmenu.condition.consume.invalid_slot";

    /** 拒绝原因: 玩家资源不足以完成消耗。 */
    public static final String REASON_INSUFFICIENT = "radialmenu.condition.consume.insufficient";

    /** 允许时的空原因。 */
    public static final String REASON_NONE = "";

    public static final CustomPacketPayload.Type<ConsumeUnlockResultS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            RadialNetworking.MOD_ID, "consume_unlock_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConsumeUnlockResultS2CPayload> CODEC =
            CustomPacketPayload.codec(
                    ConsumeUnlockResultS2CPayload::write, ConsumeUnlockResultS2CPayload::new);

    public ConsumeUnlockResultS2CPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean(),
                buf.readUtf(ConsumeUnlockC2SPayload.MAX_SLOT_KEY_LENGTH),
                buf.readVarLong(),
                buf.readUtf());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(granted);
        buf.writeUtf(slotKey == null ? "" : slotKey, ConsumeUnlockC2SPayload.MAX_SLOT_KEY_LENGTH);
        buf.writeVarLong(unlockedUntil);
        buf.writeUtf(reasonKey == null ? REASON_NONE : reasonKey);
    }

    /** 构造一个"已解锁"的结果。 */
    public static ConsumeUnlockResultS2CPayload granted(String slotKey, long unlockedUntil) {
        return new ConsumeUnlockResultS2CPayload(true, slotKey, unlockedUntil, REASON_NONE);
    }

    /** 构造一个"被拒绝"的结果。 */
    public static ConsumeUnlockResultS2CPayload denied(String slotKey, String reasonKey) {
        return new ConsumeUnlockResultS2CPayload(false, slotKey, 0L, reasonKey);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
