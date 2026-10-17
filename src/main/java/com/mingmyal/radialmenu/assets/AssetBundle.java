package com.mingmyal.radialmenu.assets;

import com.mingmyal.radialmenu.RadialCommon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 一组导入资源的<b>二进制打包</b>, 是"资产随配置一起走"的传输与落盘单位。
 * <p>
 * 为什么不塞进配置 JSON: base64 会把体积放大约 1/3, 而原版客户端→服务端自定义包
 * 的硬上限是 <b>32767 字节</b>({@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}),
 * 一个几百 KB 的 MP3 根本装不进一个包。因此资源走独立的二进制帧,
 * 但<b>生命周期与配置完全一致</b>: 上传配置时一起交, 服务端存一份,
 * 玩家进服 / 服主广播时随配置一起整包重发。
 * <p>
 * 帧格式(小端无关, DataOutputStream 大端):
 * <pre>
 * magic  "RMA1"
 * varint 条目数
 * 每条目:
 *   byte   kind ordinal
 *   utf    name
 *   int    length
 *   bytes  data
 * int     末尾 CRC32(覆盖前面全部字节)
 * </pre>
 */
public final class AssetBundle {

    private static final byte[] MAGIC = {'R', 'M', 'A', '1'};

    /** 单份打包的总字节上限, 与 S2C 包上限 1 MB 留出余量。 */
    public static final int MAX_BUNDLE_BYTES = 900 * 1024;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final AssetBundle EMPTY = new AssetBundle(List.of());

    private final List<RadialAsset> assets;

    public AssetBundle(List<RadialAsset> assets) {
        this.assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public List<RadialAsset> assets() {
        return assets;
    }

    public boolean isEmpty() {
        return assets.isEmpty();
    }

    public int size() {
        return assets.size();
    }

    /** 按 key 建立索引, 便于"客户端已有这份字节吗"的判定。 */
    public java.util.Map<String, RadialAsset> byKey() {
        java.util.Map<String, RadialAsset> map = new java.util.LinkedHashMap<>();
        for (RadialAsset asset : assets) {
            map.put(asset.key(), asset);
        }
        return map;
    }

    // --- 序列化 ---

    /** 打包成字节。内容不合法时抛 {@link IllegalArgumentException}。 */
    public byte[] encode() {
        if (assets.size() > RadialAsset.MAX_ASSETS) {
            throw new IllegalArgumentException("too many assets: " + assets.size());
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(MAGIC);
            out.writeInt(assets.size());
            for (RadialAsset asset : assets) {
                out.writeByte(asset.kind().ordinal());
                out.writeUTF(asset.name());
                out.writeInt(asset.data().length);
                out.write(asset.data());
            }
            out.flush();

            CRC32 crc = new CRC32();
            byte[] body = buffer.toByteArray();
            crc.update(body);
            out.writeInt((int) crc.getValue());
        } catch (IOException e) {
            // 写内存流不会真正失败, 除非内存耗尽
            throw new IllegalStateException("failed to encode asset bundle", e);
        }

        byte[] encoded = buffer.toByteArray();
        if (encoded.length > MAX_BUNDLE_BYTES) {
            throw new IllegalArgumentException(
                    "asset bundle too large: " + encoded.length + " > " + MAX_BUNDLE_BYTES);
        }
        return encoded;
    }

    /**
     * 解码字节, 失败返回 {@code null} 并记录原因 —— 调用方据此决定"跳过资源、继续用配置",
     * 而不是让一个坏包把整个下发流程打断。
     */
    public static AssetBundle decode(byte[] raw) {
        if (raw == null || raw.length < MAGIC.length + 8) {
            return raw == null || raw.length == 0 ? EMPTY : null;
        }

        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                RadialCommon.LOGGER.warn("Rejected asset bundle: bad magic");
                return null;
            }

            int count = in.readInt();
            if (count < 0 || count > RadialAsset.MAX_ASSETS) {
                RadialCommon.LOGGER.warn("Rejected asset bundle: bad entry count {}", count);
                return null;
            }

            List<RadialAsset> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                RadialAsset.Kind kind = RadialAsset.Kind.byOrdinal(in.readByte());
                if (kind == null) {
                    RadialCommon.LOGGER.warn("Rejected asset bundle: unknown kind");
                    return null;
                }
                String name = in.readUTF();
                int length = in.readInt();
                if (length < 0 || length > RadialAsset.MAX_ASSET_BYTES) {
                    RadialCommon.LOGGER.warn("Rejected asset bundle: bad length {} for {}", length, name);
                    return null;
                }
                byte[] data = new byte[length];
                in.readFully(data);
                list.add(new RadialAsset(kind, name, data));
            }

            int expected = in.readInt();
            CRC32 crc = new CRC32();
            crc.update(raw, 0, raw.length - 4);
            if ((int) crc.getValue() != expected) {
                RadialCommon.LOGGER.warn("Rejected asset bundle: CRC mismatch");
                return null;
            }

            return new AssetBundle(list);
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Rejected asset bundle: malformed payload", e);
            return null;
        }
    }

    // --- 分块(给 32 KB 的 C2S 上传上限用) ---

    /** 每个上传分块的实际大小, 远小于 32767 的包上限, 给包名与头部留足空间。 */
    public static final int CHUNK_BYTES = 24 * 1024;

    /** 一个上传分块。 */
    public record Chunk(int index, int total, byte[] data) {}

    /** 把打包切成若干块。空包返回空列表(无需上传任何东西)。 */
    public List<Chunk> split() {
        if (assets.isEmpty()) {
            return List.of();
        }
        byte[] encoded = encode();
        int count = (encoded.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        List<Chunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = i * CHUNK_BYTES;
            int to = Math.min(encoded.length, from + CHUNK_BYTES);
            chunks.add(new Chunk(i, count, Arrays.copyOfRange(encoded, from, to)));
        }
        return chunks;
    }

    // --- 服务端落盘用的清单 ---

    /**
     * 资产的明文清单(不含字节), 供服务端随 JSON 一起落盘与调试。
     * 字节本身另存目录, 清单只用于自检和人工排查。
     */
    public String toManifestJson() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (RadialAsset asset : assets) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", asset.kind().name());
            entry.addProperty("name", asset.name());
            entry.addProperty("bytes", asset.data().length);
            entry.addProperty("reference", asset.reference());
            array.add(entry);
        }
        root.add("assets", array);
        return GSON.toJson(root);
    }

    /** 解析清单(仅用于记录数量, 不参与实际传输)。 */
    public static int manifestCount(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("assets") && root.get("assets").isJsonArray()) {
                return root.getAsJsonArray("assets").size();
            }
        } catch (RuntimeException ignored) {
            // 清单损坏不影响主流程
        }
        return 0;
    }
}
