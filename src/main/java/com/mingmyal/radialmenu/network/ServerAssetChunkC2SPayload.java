package com.mingmyal.radialmenu.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端: 上传配置时附带的导入资源(图标/音频)分块。
 * <p>
 * 为什么要分块: 原版客户端→服务端自定义包的硬上限是 <b>32767 字节</b>
 * ({@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}), 一个 MP3 往往就有几百 KB,
 * 塞不进单个包。资源本身仍<b>随配置一起上传</b>, 只是拆成若干帧发送, 服务端按
 * {@code uploadId} 归并并靠 {@code total} 判断收齐。
 * <p>
 * 与"按需下载"的区别: 这里没有任何"客户端向服务端索要某个文件"的请求语义,
 * 资源就是配置的一部分, 上传一次、服务端存一份、之后随配置一起下发。
 *
 * @param uploadId 本次上传的标识(客户端生成), 用于把同一批分块归并到一起
 * @param index    分块序号(从 0 开始)
 * @param total    分块总数; 服务端收到第 total 块即视为收齐
 * @param data     本块字节
 */
public record ServerAssetChunkC2SPayload(int uploadId, int index, int total, byte[] data)
        implements CustomPacketPayload {

    /** 单块上限: 远小于 32767 的包上限, 给通道名与序号留足头部空间。 */
    public static final int MAX_CHUNK_BYTES = 24 * 1024;

    /** 单次上传的最大分块数(24KB × 64 ≈ 1.5MB, 足够放下整套图标与若干音频)。 */
    public static final int MAX_CHUNKS = 64;

    public static final CustomPacketPayload.Type<ServerAssetChunkC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(RadialNetworking.MOD_ID, "server_asset_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerAssetChunkC2SPayload> CODEC =
            CustomPacketPayload.codec(
                    ServerAssetChunkC2SPayload::write, ServerAssetChunkC2SPayload::new);

    public ServerAssetChunkC2SPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readByteArray(MAX_CHUNK_BYTES));
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(uploadId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data == null ? new byte[0] : data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
