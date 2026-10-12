package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 下发服务端配置与全局开关。
 * <p>
 * 所有字段都是服务端权威状态, 客户端只做缓存与展示。玩家进服即收到一次,
 * 服主改动后再广播一次, 因此客户端<b>无需任何操作</b>就会自动应用。
 * <p>
 * 不含"是否 OP": 客户端自己的权限等级由原版实时维护(被 op / 撤销 op 时会立即更新),
 * 因此在客户端直接读取即可, 不存在缓存过期的问题。
 *
 * @param json      服务端配置的原始 JSON 文本
 * @param available 服务端是否存在可用配置(文件缺失/损坏时为 false)
 * @param applied   是否分发服务端配置(全服)
 * @param force     是否强制所有客户端使用服务端配置(全服)
 * @param useClient 当前玩家是否选择使用自己的本地配置(个人偏好)
 */
public record ServerConfigSyncS2CPayload(
        String json,
        boolean available,
        boolean applied,
        boolean force,
        boolean useClient)
        implements CustomPacketPayload {

    /** 配置文本上限: 正常配置仅数 KB, 1MB 足以容纳极复杂的宏/子菜单树。 */
    public static final int MAX_JSON_LENGTH = 1_048_576;

    public static final CustomPacketPayload.Type<ServerConfigSyncS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "server_config_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSyncS2CPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigSyncS2CPayload::write, ServerConfigSyncS2CPayload::new);

    public ServerConfigSyncS2CPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readUtf(MAX_JSON_LENGTH),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(json == null ? "" : json, MAX_JSON_LENGTH);
        buf.writeBoolean(available);
        buf.writeBoolean(applied);
        buf.writeBoolean(force);
        buf.writeBoolean(useClient);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
