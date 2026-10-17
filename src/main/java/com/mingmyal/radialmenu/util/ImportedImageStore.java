package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.RadialCommon;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 导入图片管理: 加载图片文件, 自动将黑色背景替换为透明, 并注册为动态纹理。
 * itemId 格式: imported:filename (例如 imported:my_icon)
 */
public class ImportedImageStore {

    public static final String PREFIX = "imported:";
    private static final String ICONS_DIR = "radialmenu/icons";
    private static final int BLACK_THRESHOLD = 30;

    private static final Map<String, ResourceLocation> textureMap = new HashMap<>();

    /** 导入图片的原始像素尺寸, 供"自适应"模式按原始宽高比缩放使用。 */
    private static final Map<String, int[]> sizeMap = new HashMap<>();

    public static boolean isImportedImage(String itemId) {
        return itemId != null && itemId.startsWith(PREFIX);
    }

    public static String getFileName(String itemId) {
        if (!isImportedImage(itemId)) return null;
        return itemId.substring(PREFIX.length());
    }

    /**
     * 获取导入图片的 ResourceLocation。
     * 如果尚未注册, 尝试从磁盘加载。
     */
    public static ResourceLocation getTexture(String itemId) {
        if (!isImportedImage(itemId)) return null;
        String name = getFileName(itemId);
        if (name == null) return null;

        return textureMap.computeIfAbsent(name, ImportedImageStore::loadTexture);
    }

    /**
     * 获取导入图片的原始像素尺寸 {@code {宽, 高}}。
     * 图片不存在或尚未加载时返回 null。
     */
    public static int[] getImageSize(String itemId) {
        if (!isImportedImage(itemId)) return null;
        String name = getFileName(itemId);
        if (name == null) return null;

        // 确保纹理已加载(sizeMap 在注册纹理时一并写入)
        getTexture(itemId);
        return sizeMap.get(name);
    }

    /**
     * 从文件导入图片: 加载 → 黑色背景转透明 → 保存PNG → 注册纹理。
     * 返回 itemId (imported:filename), 失败返回 null。
     */
    public static String importFromFile(File file) {
        if (file == null || !file.isFile()) return null;

        String rawName = file.getName();
        int dotIdx = rawName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? rawName.substring(0, dotIdx) : rawName;
        String safeName = sanitizeFileName(baseName);

        try {
            NativeImage image = NativeImage.read(new FileInputStream(file));
            replaceBlackWithTransparent(image);

            // 保存到 icons 目录
            File dir = getIconsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                RadialCommon.LOGGER.warn("Failed to create icons directory");
            }
            File outFile = new File(dir, safeName + ".png");
            image.writeToFile(outFile.toPath());

            // 注册纹理
            registerTexture(safeName, image);

            return PREFIX + safeName;
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to import image: " + rawName, e);
            return null;
        }
    }

    private static void replaceBlackWithTransparent(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixelRGBA(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                int red = (pixel) & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = (pixel >> 16) & 0xFF;

                // 如果像素接近黑色且不透明, 设为透明
                if (alpha > 0 && red < BLACK_THRESHOLD && green < BLACK_THRESHOLD && blue < BLACK_THRESHOLD) {
                    image.setPixelRGBA(x, y, 0x00000000);
                }
            }
        }
    }

    private static ResourceLocation loadTexture(String name) {
        File file = new File(getIconsDir(), name + ".png");
        if (!file.isFile()) return null;

        try {
            NativeImage image = NativeImage.read(new FileInputStream(file));
            return registerTexture(name, image);
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to load imported image: " + name, e);
            return null;
        }
    }

    private static ResourceLocation registerTexture(String name, NativeImage image) {
        // 记录原始尺寸供"自适应"模式使用(替换同名图片时覆盖为最新值)
        sizeMap.put(name, new int[] {image.getWidth(), image.getHeight()});

        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation rl = Minecraft.getInstance().getTextureManager()
                .register("radialmenu/" + name, texture);
        return rl;
    }

    private static File getIconsDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(ICONS_DIR)
                .toFile();
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
    }

    /** 把任意字符串清洗成安全的文件名主干(与服务端下发资源时的归一化规则一致)。 */
    public static String sanitizeForFile(String name) {
        return name == null ? "" : sanitizeFileName(name);
    }

    /**
     * 把一段字节写成导入图标文件(供服务端下发资源时落盘)。
     * <p>
     * 写完会让该名字的纹理缓存失效并重新注册, 否则界面会一直用旧纹理
     * (或一直显示空白, 因为失败结果被 {@code computeIfAbsent} 缓存住了)。
     *
     * @return 是否写入成功
     */
    public static boolean writeBytes(String name, byte[] data) {
        String safe = sanitizeForFile(name);
        if (safe.isBlank() || data == null || data.length == 0) {
            return false;
        }
        try {
            File dir = getIconsDir();
            if (!dir.exists() && !dir.mkdirs()) {
                RadialCommon.LOGGER.warn("Failed to create icons directory");
                return false;
            }
            File outFile = new File(dir, safe + ".png");
            Files.write(outFile.toPath(), data);

            // 旧纹理若存在先释放, 再清缓存, 让下次 getTexture 重新从新文件加载
            releaseTexture(safe);
            return true;
        } catch (IOException e) {
            RadialCommon.LOGGER.error("Failed to write imported image: " + name, e);
            return false;
        }
    }

    /** 释放并移除某个导入图标的纹理缓存(换图后必须调用, 否则一直显示旧图)。 */
    public static void releaseTexture(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        ResourceLocation old = textureMap.remove(name);
        sizeMap.remove(name);
        if (old != null) {
            Minecraft.getInstance().getTextureManager().release(old);
        }
    }

    /** 该名字的图标文件是否已在本地存在。 */
    public static boolean hasLocalFile(String name) {
        String safe = sanitizeForFile(name);
        return !safe.isBlank() && new File(getIconsDir(), safe + ".png").isFile();
    }
}
