package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.assets.AssetBundle;
import com.mingmyal.radialmenu.assets.RadialAsset;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.util.ImportedImageStore;
import com.mingmyal.radialmenu.util.ImportedSoundStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * 客户端侧的导入资源收集与落盘。
 * <p>
 * 上传方向: 配置里引用了哪些 {@code imported:*} / {@code radialmenu:custom.*},
 * 就把本机上对应的文件读出来, 随配置一起交给服务端。
 * <p>
 * 下发方向: 服务端把资源随配置一起发回来, 这里落到
 * {@code config/radialmenu/icons|sounds/}, 与玩家自己导入的文件落在同一位置 ——
 * 因此渲染/播放逻辑完全不需要知道"这个图标是本地导入的还是服务端下发的"。
 */
public final class ClientAssetSync {

    private ClientAssetSync() {}

    /**
     * 收集配置引用到、且本机确实存在的资源字节。
     * <p>
     * 只收集<b>能读到文件</b>的那些: 引用存在但文件缺失时无法凭空造出内容,
     * 记一条警告即可(这种情况意味着服主自己的配置就已经指向了不存在的文件)。
     */
    public static AssetBundle collectForConfig(RadialConfig config) {
        List<RadialAsset.Ref> refs = RadialAsset.collectReferences(config);
        if (refs.isEmpty()) {
            return AssetBundle.EMPTY;
        }

        List<RadialAsset> assets = new ArrayList<>(refs.size());
        List<String> missing = new ArrayList<>();

        for (RadialAsset.Ref ref : refs) {
            byte[] data = readLocalBytes(ref);
            if (data == null) {
                missing.add(ref.reference());
                continue;
            }
            try {
                assets.add(new RadialAsset(ref.kind(), ref.name(), data));
            } catch (IllegalArgumentException e) {
                RadialCommon.LOGGER.warn("Skipping asset {}: {}", ref.reference(), e.getMessage());
            }
        }

        if (!missing.isEmpty()) {
            // 这类资源下发后别人会看到空白/静音, 因此必须留下明确线索
            RadialCommon.LOGGER.warn(
                    "{} imported asset(s) referenced by config have no local file and cannot be uploaded: {}",
                    missing.size(), missing);
        }

        return assets.isEmpty() ? AssetBundle.EMPTY : new AssetBundle(assets);
    }

    /** 读取本机对应的资源字节; 不存在或读取失败返回 null。 */
    private static byte[] readLocalBytes(RadialAsset.Ref ref) {
        File file = localFile(ref);
        if (file == null || !file.isFile()) {
            // 音频兼容旧版本导入的 .ogg
            if (ref.kind() == RadialAsset.Kind.SOUND) {
                File ogg = new File(soundsDir(), ref.name() + ".ogg");
                if (ogg.isFile()) {
                    return readAll(ogg);
                }
            }
            return null;
        }
        return readAll(file);
    }

    private static byte[] readAll(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            RadialCommon.LOGGER.warn("Failed to read imported asset {}", file, e);
            return null;
        }
    }

    private static File localFile(RadialAsset.Ref ref) {
        return new File(
                ref.kind() == RadialAsset.Kind.ICON ? iconsDir() : soundsDir(),
                ref.kind().fileName(ref.name()));
    }

    // --- 下发落盘 ---

    /**
     * 把服务端下发的资源落到本地。
     * <p>
     * 图标写完会让该名字的纹理缓存失效并重新注册; 音频则在下次播放时由
     * {@code SoundBufferLibraryMixin} 从磁盘重新解码, 因此无需额外处理。
     *
     * @return 实际写入的资源数量
     */
    public static int applyFromServer(AssetBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return 0;
        }

        int written = 0;
        for (RadialAsset asset : bundle.assets()) {
            boolean ok = switch (asset.kind()) {
                case ICON -> ImportedImageStore.writeBytes(asset.name(), asset.data());
                case SOUND -> ImportedSoundStore.writeBytes(asset.name(), asset.data());
            };
            if (ok) {
                written++;
            } else {
                RadialCommon.LOGGER.warn(
                        "Failed to store server-provided {} asset {}",
                        asset.kind(), asset.name());
            }
        }

        RadialCommon.LOGGER.info(
                "Applied {} of {} server radial asset(s)", written, bundle.size());
        return written;
    }

    private static File iconsDir() {
        return new File(gameDir(), "config/radialmenu/icons");
    }

    private static File soundsDir() {
        return new File(gameDir(), "config/radialmenu/sounds");
    }

    private static File gameDir() {
        return Minecraft.getInstance().gameDirectory;
    }
}
