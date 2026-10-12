package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 全局开关的修改结果。
 * <p>
 * 被拒绝时携带原因(翻译键), 客户端据此以红色文本提示。
 * 区分"无权限"与"服务端未配置", 避免把后者也报成权限问题。
 *
 * @param granted   是否允许本次修改
 * @param applied   服务端最终生效的分发状态
 * @param force     服务端最终生效的强制状态
 * @param useClient 服务端记录的"该玩家是否使用本地配置"
 * @param reasonKey 拒绝原因的翻译键; 允许时为空字符串
 */
public record ServerConfigResultS2CPayload(
        boolean granted, boolean applied, boolean force, boolean useClient, String reasonKey)
        implements CustomPacketPayload {

    /** 拒绝原因: 无权限。 */
    public static final String REASON_NO_PERMISSION = "radialmenu.server_config.no_permission";

    /** 拒绝原因: 服务端没有可用配置。 */
    public static final String REASON_UNAVAILABLE = "radialmenu.server_config.unavailable";

    /** 拒绝原因: 提交的配置内容不合法。 */
    public static final String REASON_INVALID_CONFIG = "radialmenu.server_config.invalid";

    /** 拒绝原因: 服务器已强制使用服务端配置, 无法切回本地。 */
    public static final String REASON_FORCED = "radialmenu.server_config.forced_locked";

    /** 允许时的空原因。 */
    public static final String REASON_NONE = "";

    public static final CustomPacketPayload.Type<ServerConfigResultS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "server_config_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigResultS2CPayload> CODEC =
            CustomPacketPayload.codec(ServerConfigResultS2CPayload::write, ServerConfigResultS2CPayload::new);

    public ServerConfigResultS2CPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(granted);
        buf.writeBoolean(applied);
        buf.writeBoolean(force);
        buf.writeBoolean(useClient);
        buf.writeUtf(reasonKey == null ? REASON_NONE : reasonKey);
    }

    /** 构造一个"允许"结果。 */
    public static ServerConfigResultS2CPayload granted(
            boolean applied, boolean force, boolean useClient) {
        return new ServerConfigResultS2CPayload(true, applied, force, useClient, REASON_NONE);
    }

    /** 构造一个"拒绝"结果, 状态回退为服务端当前真实值。 */
    public static ServerConfigResultS2CPayload denied(
            boolean applied, boolean force, boolean useClient, String reasonKey) {
        return new ServerConfigResultS2CPayload(false, applied, force, useClient, reasonKey);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
