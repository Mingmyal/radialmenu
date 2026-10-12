package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 切换<b>本玩家自己</b>使用服务端配置还是本地配置。
 * <p>
 * 这是个人偏好(不影响其他人), 因此与服务端的全局开关分开。
 * 服务端记录该偏好, 使得玩家换设备/重进后仍然保持自己的选择。
 *
 * @param useClient true 表示使用本玩家的本地配置, false 表示使用服务端配置
 */
public record SetClientConfigSourceC2SPayload(boolean useClient) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetClientConfigSourceC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "set_client_config_source"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetClientConfigSourceC2SPayload> CODEC =
            CustomPacketPayload.codec(
                    SetClientConfigSourceC2SPayload::write, SetClientConfigSourceC2SPayload::new);

    public SetClientConfigSourceC2SPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(useClient);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
