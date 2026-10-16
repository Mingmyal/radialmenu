package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.util.ConfigFiles;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * 按<b>服务器隔离</b>的配置存储。
 * <p>
 * 玩家在不同服务器会看到不同的服务端配置, 因此"基于服务端配置的自定义副本"必须分开存放,
 * 否则 A 服的改动用 B 服的去改会互相污染。
 * <p>
 * 每个服务器两个文件, 都放在 {@code config/radialmenu-servers/} 下:
 * <ul>
 *   <li>{@code <id>.json} — 玩家可自定义的配置副本(纯 {@link RadialConfig} 格式)</li>
 *   <li>{@code <id>.meta.json} — 记录该副本是基于哪一版服务端配置生成的</li>
 * </ul>
 * meta 单独一个文件, 是为了不把内部字段混进配置本体 —— 配置本体在服主上传时会原样
 * 成为全服配置, 塞入私有字段会污染它。
 */
public final class ServerConfigStore {

    private static final String DIR_NAME = "radialmenu-servers";

    /** 单人游戏使用的固定标识。 */
    private static final String SINGLEPLAYER_ID = "singleplayer";

    private ServerConfigStore() {}

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(DIR_NAME);
    }

    /** 按服文件的存放目录(供同目录下的其它按服文件复用, 避免重复拼接路径)。 */
    static Path serverDir() {
        return dir();
    }

    /**
     * 当前所在服务器的标识。
     * <p>
     * 多人游戏用服务器地址; 单人游戏用固定值(单人存档之间不隔离, 因为它们共用本地配置,
     * 分工没有意义)。
     */
    public static String currentServerId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isLocalServer()) {
            return SINGLEPLAYER_ID;
        }
        ServerData server = mc.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return SINGLEPLAYER_ID;
        }
        return sanitize(server.ip);
    }

    /** 把服务器地址转成安全的文件名。 */
    private static String sanitize(String raw) {
        // 保留字母数字与 . - _ , 其余(含 : / \\ 空格)统一替换为 _
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        // 避免过长的地址撑爆文件名
        if (cleaned.length() > 96) {
            cleaned = cleaned.substring(0, 96);
        }
        // 附带短哈希, 防止不同地址被清洗成同名(如 a:b 与 a/b)
        return cleaned + "-" + Integer.toHexString(raw.hashCode());
    }

    public static Path configPath(String serverId) {
        return dir().resolve(serverId + ".json");
    }

    /**
     * 强制模式下"纯净"服务端配置的缓存文件。
     * <p>
     * 该文件每次进服都会被服务端下发的内容<b>整体覆盖</b>, 且程序从不读回它作为配置来源
     * (纯净配置始终来自内存中的下发结果)。因此玩家即使手动改了它, 也不会生效 ——
     * 它只用于让玩家/管理员查看"服务器当前强制的是什么"。
     */
    public static Path forcedPath(String serverId) {
        return dir().resolve(serverId + ".forced.json");
    }

    public static Path metaPath(String serverId) {
        return dir().resolve(serverId + ".meta.json");
    }

    /**
     * 读取该服务器的自定义副本。
     *
     * @return 配置对象; 文件不存在或损坏时返回 null
     */
    public static RadialConfig load(String serverId) {
        Path path = configPath(serverId);
        if (!Files.exists(path)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }

            RadialConfig config = RadialConfig.parseFromJson(sb.toString());
            // 该副本可以自由保存回自己的文件
            config.setSavePath(path);
            config.setReadOnly(false);
            return config;
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to read per-server radial config at {}", path, e);
            return null;
        }
    }

    /** 写入该服务器的自定义副本。 */
    public static void save(String serverId, RadialConfig config) {
        if (config == null) {
            return;
        }
        try {
            Files.createDirectories(dir());
            ConfigFiles.writeJsonAtomically(
                    configPath(serverId), RadialConfig.GSON, config);
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save per-server radial config for {}", serverId, e);
        }
    }

    /** 读取记录的服务端配置指纹(该副本基于哪一版服务端配置生成)。 */
    public static String readFingerprint(String serverId) {
        Path path = metaPath(serverId);
        if (!Files.exists(path)) {
            return "";
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root =
                    JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("serverFingerprint") && root.get("serverFingerprint").isJsonPrimitive()) {
                return root.get("serverFingerprint").getAsString();
            }
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Failed to read per-server radial meta at {}", path, e);
        }
        return "";
    }

    /** 记录服务端配置指纹。 */
    public static void writeFingerprint(String serverId, String fingerprint) {
        try {
            Files.createDirectories(dir());
            JsonObject root = new JsonObject();
            root.addProperty("serverFingerprint", fingerprint == null ? "" : fingerprint);
            ConfigFiles.writeTextAtomically(
                    metaPath(serverId), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to write per-server radial meta for {}", serverId, e);
        }
    }

    /** 计算服务端配置文本的指纹, 用于判断服主是否更新了全服配置。 */
    public static String fingerprint(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必然存在; 退化为长度+哈希码
            return json.length() + ":" + Integer.toHexString(json.hashCode());
        }
    }
}
