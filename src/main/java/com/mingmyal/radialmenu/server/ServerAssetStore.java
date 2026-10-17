package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.assets.AssetBundle;
import com.mingmyal.radialmenu.assets.RadialAsset;
import com.mingmyal.radialmenu.network.ServerAssetChunkC2SPayload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端的导入资源库(图标 PNG / 音频 MP3)。
 * <p>
 * 设计前提: 资源是<b>配置的一部分</b>, 生命周期与 {@link RadialServerConfig} 完全绑定 ——
 * <ul>
 *   <li>服主点击"应用为服务器设置"时, 客户端把配置引用到的资源随配置一起上传;</li>
 *   <li>服务端把字节存到 {@code config/radialmenu-server-assets/}, 并缓存进内存;</li>
 *   <li>之后每次下发配置, 资源<b>紧跟着</b>一起整包发给该玩家(进服一次, 广播一次);</li>
 *   <li>配置被替换时, 旧资源一并丢弃 —— 不存在"配置换了但资源还是旧的"。</li>
 * </ul>
 * 刻意不做"客户端按需向服务端索要某个文件"的增量拉取: 那会让资源与配置出现两套
 * 不同的失效时机, 而这恰恰是"配置下发了、图标却是空白"的根源。
 */
public final class ServerAssetStore {

    /** 资源目录名(位于服务端 config 目录)。 */
    public static final String DIR_NAME = "radialmenu-server-assets";

    /** 上传分块在服务端内存里最多停留这么久, 超时丢弃, 避免被反复塞块撑爆内存。 */
    private static final long UPLOAD_TIMEOUT_MS = 2 * 60 * 1000L;

    /** 同时进行的上传批次上限。 */
    private static final int MAX_PENDING_UPLOADS = 8;

    private ServerAssetStore() {}

    /** 当前这份服务端配置携带的资源(与配置同生共死)。 */
    private static volatile AssetBundle current = AssetBundle.EMPTY;

    /** 记录当前资源是否为"尚未收到客户端上传"的占位空包。 */
    private static volatile boolean loaded;

    // --- 上传暂存 ---

    /** 一批正在接收的分块。 */
    private static final class PendingUpload {
        final int total;
        final long startedAt = System.currentTimeMillis();
        final byte[][] chunks;

        PendingUpload(int total) {
            this.total = total;
            this.chunks = new byte[total][];
        }

        boolean complete() {
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    return false;
                }
            }
            return true;
        }

        byte[] join() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) {
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }
    }

    private static final Map<UploadKey, PendingUpload> pending =
            new ConcurrentHashMap<>();

    /**
     * 上传批次的键。
     * <p>
     * <b>必须带上上传者</b>: 批次号是客户端自己递增的整数, 两个玩家同时上传时
     * 都会用小数字(1、2...), 只用批次号做键会让他们的分块拼进同一个缓冲,
     * 结果是两人各拿到一半字节、CRC 校验失败、资源静默丢失。
     */
    private record UploadKey(UUID uploader, int uploadId) {}

    private static Path assetsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(DIR_NAME);
    }

    private static Path iconDir() {
        return assetsDir().resolve(RadialAsset.Kind.ICON.dirName());
    }

    private static Path soundDir() {
        return assetsDir().resolve(RadialAsset.Kind.SOUND.dirName());
    }

    // --- 生命周期 ---

    /**
     * 从磁盘加载服务端资源。
     * <p>
     * 与配置分开存放、但一起加载: {@code /radialmenu-server reload} 会把两者都重读,
     * 因此手工替换服务端配置目录下的文件同样会生效。
     */
    public static synchronized void load() {
        Map<String, RadialAsset> loadedAssets = new LinkedHashMap<>();

        for (RadialAsset.Kind kind : RadialAsset.Kind.values()) {
            Path dir = assetsDir().resolve(kind.dirName());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(kind.extension())) {
                        continue;
                    }
                    String base = name.substring(0, name.length() - kind.extension().length());
                    try {
                        byte[] data = Files.readAllBytes(file);
                        RadialAsset asset = new RadialAsset(kind, base, data);
                        loadedAssets.put(asset.key(), asset);
                    } catch (RuntimeException | IOException e) {
                        // 单个文件坏掉不影响其余资源
                        RadialCommon.LOGGER.warn("Skipping unreadable server asset {}", file, e);
                    }
                }
            } catch (IOException e) {
                RadialCommon.LOGGER.warn("Failed to list server asset dir {}", dir, e);
            }
        }

        current = loadedAssets.isEmpty() ? AssetBundle.EMPTY : new AssetBundle(new ArrayList<>(loadedAssets.values()));
        loaded = true;
        RadialCommon.LOGGER.info(
                "Loaded {} server radial asset(s) from {}", current.size(), assetsDir());
    }

    /** 当前服务端配置携带的资源。 */
    public static AssetBundle current() {
        return current;
    }

    public static boolean hasAssets() {
        return !current.isEmpty();
    }

    /** 丢弃当前资源(配置被清空或被替换时调用)。 */
    public static synchronized void clear() {
        current = AssetBundle.EMPTY;
        pending.clear();
    }

    /**
     * 用一批新资源整体替换当前资源(配置上传成功后调用)。
     * <p>
     * 整体替换而不是增量合并: 配置是一份完整体, 资源必须与它严格对应,
     * 否则删掉的旧图标会一直留在服务端目录里, 白白占空间且随下发一起发给玩家。
     */
    public static synchronized boolean replace(AssetBundle bundle) {
        AssetBundle target = bundle == null ? AssetBundle.EMPTY : bundle;

        try {
            // 先清空旧文件, 再把新资源写盘: 避免旧资源残留
            deleteDir(iconDir());
            deleteDir(soundDir());
            Files.createDirectories(iconDir());
            Files.createDirectories(soundDir());

            for (RadialAsset asset : target.assets()) {
                Path dir = assetsDir().resolve(asset.kind().dirName());
                Files.write(dir.resolve(asset.kind().fileName(asset.name())), asset.data());
            }
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to persist server radial assets", e);
            return false;
        }

        current = target;
        loaded = true;
        RadialCommon.LOGGER.info("Stored {} server radial asset(s) at {}", target.size(), assetsDir());
        return true;
    }

    // --- 上传接收 ---

    /**
     * 接收一个上传分块。
     *
     * @return 收齐并解码成功时返回解出的资源包, 否则返回 {@code null}
     */
    public static synchronized AssetBundle acceptChunk(
            UUID uploader, ServerAssetChunkC2SPayload payload) {
        if (payload == null || uploader == null) {
            return null;
        }

        int total = payload.total();
        // total == 0 是"本批明确不含资源"的标记: 不是真正的分块, 由调用方单独处理
        if (total == 0) {
            return AssetBundle.EMPTY;
        }
        if (total < 0 || total > ServerAssetChunkC2SPayload.MAX_CHUNKS) {
            RadialCommon.LOGGER.warn("Rejected asset upload: bad chunk count {}", total);
            return null;
        }

        int index = payload.index();
        if (index < 0 || index >= total) {
            RadialCommon.LOGGER.warn("Rejected asset upload: bad chunk index {}/{}", index, total);
            return null;
        }

        evictExpired();
        UploadKey key = new UploadKey(uploader, payload.uploadId());
        if (pending.size() >= MAX_PENDING_UPLOADS && !pending.containsKey(key)) {
            RadialCommon.LOGGER.warn("Rejected asset upload: too many pending uploads");
            return null;
        }

        PendingUpload upload = pending.computeIfAbsent(
                key, k -> new PendingUpload(total));
        if (upload.total != total) {
            RadialCommon.LOGGER.warn(
                    "Rejected asset upload {}: inconsistent chunk count {} vs {}",
                    payload.uploadId(), total, upload.total);
            pending.remove(key);
            return null;
        }

        if (upload.chunks[index] == null) {
            upload.chunks[index] = payload.data() == null ? new byte[0] : payload.data();
        }

        if (!upload.complete()) {
            return null;
        }

        pending.remove(key);

        AssetBundle bundle = AssetBundle.decode(upload.join());
        if (bundle == null) {
            RadialCommon.LOGGER.warn(
                    "Asset upload {} from {} failed to decode", payload.uploadId(), uploader);
        }
        return bundle;
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().startedAt > UPLOAD_TIMEOUT_MS);
    }

    /** 丢弃某个玩家尚未收齐的上传分块(断线时调用, 防止残留占用内存)。 */
    public static synchronized void purgePendingUploads(UUID uploader) {
        if (uploader == null) {
            return;
        }
        pending.keySet().removeIf(key -> uploader.equals(key.uploader()));
    }

    // --- 工具 ---

    private static void deleteDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            RadialCommon.LOGGER.warn("Failed to clean server asset dir {}", dir, e);
        }
    }

    /** 供命令/调试输出的概要。 */
    public static String summary() {
        AssetBundle bundle = current;
        if (bundle == null || bundle.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        int icons = 0;
        int sounds = 0;
        for (RadialAsset asset : bundle.assets()) {
            if (asset.kind() == RadialAsset.Kind.ICON) {
                icons++;
            } else {
                sounds++;
            }
        }
        parts.add(icons + " icon(s)");
        parts.add(sounds + " sound(s)");
        return String.join(", ", parts);
    }
}
