package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.RadialCommon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;

/**
 * 导入音频管理: 将 .mp3 文件复制到 config/radialmenu/sounds/ 供播放。
 * clickSound 格式: radialmenu:custom.<name>
 * 实际文件路径: config/radialmenu/sounds/<name>.mp3 (兼容旧版本导入的 <name>.ogg)
 *
 * 播放通过 SoundManagerMixin + SoundBufferLibraryMixin 拦截,
 * 对 radialmenu:custom.* 从 config 目录加载, 无需注册资源包。
 */
public class ImportedSoundStore {

    public static final String PREFIX = "radialmenu:custom.";
    private static final String SOUNDS_DIR = "radialmenu/sounds";

    private ImportedSoundStore() {}

    public static boolean isImportedSound(String clickSound) {
        return clickSound != null && clickSound.startsWith(PREFIX);
    }

    public static String getName(String clickSound) {
        if (!isImportedSound(clickSound)) return null;
        return clickSound.substring(PREFIX.length());
    }

    /**
     * 从文件导入音频: 复制 .mp3 到 sounds 目录, 返回 clickSound id。
     * 失败返回 null。
     */
    public static String importFromFile(File file) {
        if (file == null || !file.isFile()) return null;

        String rawName = file.getName();
        int dotIdx = rawName.lastIndexOf('.');
        String ext = (dotIdx > 0) ? rawName.substring(dotIdx).toLowerCase() : "";
        if (!".mp3".equals(ext)) {
            RadialCommon.LOGGER.warn("Only .mp3 audio files are supported, got: {}", rawName);
            return null;
        }

        String baseName = (dotIdx > 0) ? rawName.substring(0, dotIdx) : rawName;
        String safeName = sanitize(baseName);

        try {
            File dir = getSoundsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                RadialCommon.LOGGER.warn("Failed to create sounds directory");
                return null;
            }
            File outFile = new File(dir, safeName + ".mp3");
            Files.copy(file.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return PREFIX + safeName;
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to import sound: " + rawName, e);
            return null;
        }
    }

    /** 获取导入音频文件的绝对路径, 供 SoundBufferLibraryMixin 加载。优先 mp3, 兼容旧导入的 ogg。 */
    public static File getSoundFile(String name) {
        if (name == null || name.isBlank()) return null;
        String safe = sanitize(name);
        File mp3 = new File(getSoundsDir(), safe + ".mp3");
        if (mp3.isFile()) return mp3;
        return new File(getSoundsDir(), safe + ".ogg");
    }

    private static File getSoundsDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(SOUNDS_DIR)
                .toFile();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
    }

    /**
     * 把任意字符串清洗成安全的文件名主干。
     * <p>
     * 服务端下发资源时用同一套规则归一化名字, 否则"服务端存的名字"与
     * "客户端落盘的名字"会不一致, 表现为图标/音频明明传上去了却依然空白。
     */
    public static String sanitizeForFile(String name) {
        return name == null ? "" : sanitize(name);
    }

    /**
     * 把一段字节写成导入音频文件(供服务端下发资源时落盘)。
     * <p>
     * 覆盖写入后必须让解码缓存失效, 否则换掉的音频仍然播旧内容 ——
     * 具体清理由 {@code SoundBufferLibraryMixin#radialmenu$invalidate} 完成。
     *
     * @return 是否写入成功
     */
    public static boolean writeBytes(String name, byte[] data) {
        String safe = sanitizeForFile(name);
        if (safe.isBlank() || data == null || data.length == 0) {
            return false;
        }
        try {
            File dir = getSoundsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                RadialCommon.LOGGER.warn("Failed to create sounds directory");
                return false;
            }
            Files.write(new File(dir, safe + ".mp3").toPath(), data);
            return true;
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to write imported sound: " + name, e);
            return false;
        }
    }

    /** 该名字的音频文件是否已在本地存在(用于判断是否需要下发)。 */
    public static boolean hasLocalFile(String name) {
        String safe = sanitizeForFile(name);
        return !safe.isBlank() && getSoundFile(safe) != null && getSoundFile(safe).isFile();
    }
}
