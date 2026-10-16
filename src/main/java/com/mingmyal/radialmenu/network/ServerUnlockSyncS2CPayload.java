package com.mingmyal.radialmenu.network;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端: 下发该玩家在服务端的<b>权威槽位解锁状态</b>(全量快照)。
 * <p>
 * 玩家进服时下发一次, 客户端据此<b>整体覆盖</b>本地记录 —— 这正是防篡改的关键:
 * 本地那份 {@code *.runtime.json} 无论被改成什么样, 一进服就被服务端的权威状态冲掉。
 * <p>
 * 之所以下发全量而不是增量: 客户端缺的键必须被显式清零(视为未解锁), 否则玩家只要在本地
 * 编造一条记录就能白拿解锁。全量快照让"客户端有什么"完全由服务端决定。
 *
 * @param unlocks 槽位键 → 解锁截止时间戳(epoch 毫秒; {@code Long.MAX_VALUE} 表示永久);
 *                不含的键一律视为未解锁
 */
public record ServerUnlockSyncS2CPayload(Map<String, Long> unlocks)
        implements CustomPacketPayload {

    /** 单玩家解锁条目上限, 与 {@code ServerUnlockStore} 保持一致并封住异常报文。 */
    public static final int MAX_ENTRIES = 4096;

    public static final CustomPacketPayload.Type<ServerUnlockSyncS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            RadialNetworking.MOD_ID, "server_unlock_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerUnlockSyncS2CPayload> CODEC =
            CustomPacketPayload.codec(
                    ServerUnlockSyncS2CPayload::write, ServerUnlockSyncS2CPayload::new);

    public ServerUnlockSyncS2CPayload(RegistryFriendlyByteBuf buf) {
        this(readUnlocks(buf));
    }

    private static Map<String, Long> readUnlocks(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        // 上限在读取前生效: 否则一个伪造的长度就能让我们分配巨大 map
        size = Math.clamp(size, 0, MAX_ENTRIES);

        Map<String, Long> out = new LinkedHashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf(ConsumeUnlockC2SPayload.MAX_SLOT_KEY_LENGTH);
            long until = buf.readVarLong();
            if (key != null && !key.isBlank() && until > 0L) {
                out.put(key, until);
            }
        }
        return out;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        Map<String, Long> map = unlocks != null ? unlocks : Map.of();
        int size = Math.min(map.size(), MAX_ENTRIES);
        buf.writeVarInt(size);

        int written = 0;
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            if (written >= size) {
                break;
            }
            String key = entry.getKey();
            Long until = entry.getValue();
            if (key == null || key.isBlank() || until == null || until <= 0L) {
                continue;
            }
            buf.writeUtf(key, ConsumeUnlockC2SPayload.MAX_SLOT_KEY_LENGTH);
            buf.writeVarLong(until);
            written++;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
