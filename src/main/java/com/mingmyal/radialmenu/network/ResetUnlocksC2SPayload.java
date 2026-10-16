package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 请求清空<b>本玩家</b>的服务端解锁记录。
 * <p>
 * 解锁状态现在存在服务端, 因此"重置槽位解锁状态"这个按钮若只在本地清空, 下次进服
 * 就会被服务端重新下发回来 —— 表现为"点了重置但没用"。所以重置也必须作用在服务端。
 * <p>
 * 只影响请求者自己: 解锁是玩家各自的进度, 不存在"替别人重置"的语义, 因此不需要 OP 权限,
 * 也没有任何参数。
 */
public record ResetUnlocksC2SPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ResetUnlocksC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "reset_unlocks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResetUnlocksC2SPayload> CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {},
                    buf -> new ResetUnlocksC2SPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
