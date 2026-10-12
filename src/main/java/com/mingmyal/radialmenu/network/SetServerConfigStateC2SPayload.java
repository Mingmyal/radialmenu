package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 修改服务端轮盘配置的<b>全局</b>开关。
 * <p>
 * 携带配置文本, 使"服主点一下即全服生效"成为<b>一个原子操作</b>:
 * 点击"应用为服务器设置"时同时把服主当前的配置发布为服务端配置。
 * 若拆成两个包, 中间失败会留下"已启用但没有配置"的不一致状态。
 * <p>
 * 权限校验完全在服务端完成; 客户端本地不做权威判定。
 *
 * @param applied 期望的分发状态
 * @param force   期望的强制状态
 * @param json    要发布为服务端配置的 JSON 文本; 空字符串表示不修改现有配置
 */
public record SetServerConfigStateC2SPayload(boolean applied, boolean force, String json)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetServerConfigStateC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "set_server_config_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetServerConfigStateC2SPayload> CODEC =
            CustomPacketPayload.codec(
                    SetServerConfigStateC2SPayload::write, SetServerConfigStateC2SPayload::new);

    public SetServerConfigStateC2SPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(ServerConfigSyncS2CPayload.MAX_JSON_LENGTH));
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(applied);
        buf.writeBoolean(force);
        buf.writeUtf(json == null ? "" : json, ServerConfigSyncS2CPayload.MAX_JSON_LENGTH);
    }

    /** 是否携带了要发布的配置。 */
    public boolean hasJson() {
        return json != null && !json.isBlank();
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
