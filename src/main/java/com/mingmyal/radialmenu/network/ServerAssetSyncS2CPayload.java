package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 随配置一起下发的导入资源(图标/音频)。
 * <p>
 * 与 {@link ServerConfigSyncS2CPayload} 成对发送, 生命周期完全一致 ——
 * 玩家进服收到一次, 服主更新配置后广播再收到一次; 客户端只是把这些字节落到
 * {@code config/radialmenu/icons|sounds/}, 之后渲染与播放走的是和本地导入完全相同的路径。
 * <p>
 * 整包下发而不做按需拉取: 资源总量就是配置里引用的那几十个文件, 一次发完即可。
 * 若总量超过单个 S2C 包上限(1MB), 服务端会拆成多次下发, {@code part}/{@code parts} 表示进度。
 *
 * @param bundle 资产打包字节({@link com.mingmyal.radialmenu.assets.AssetBundle#encode()})
 * @param part   当前第几段(从 0 开始)
 * @param parts  总段数; 客户端收齐 {@code parts} 段后再统一落盘
 */
public record ServerAssetSyncS2CPayload(byte[] bundle, int part, int parts)
        implements CustomPacketPayload {

    /** 单包字节上限, 略低于 S2C 的 1MB 硬上限, 给通道名与头部留余量。 */
    public static final int MAX_BUNDLE_BYTES = 900 * 1024;

    public static final CustomPacketPayload.Type<ServerAssetSyncS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "server_asset_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerAssetSyncS2CPayload> CODEC =
            CustomPacketPayload.codec(
                    ServerAssetSyncS2CPayload::write, ServerAssetSyncS2CPayload::new);

    public ServerAssetSyncS2CPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readByteArray(MAX_BUNDLE_BYTES),
                buf.readVarInt(),
                buf.readVarInt());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeByteArray(bundle == null ? new byte[0] : bundle);
        buf.writeVarInt(part);
        buf.writeVarInt(parts);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
