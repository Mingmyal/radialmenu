package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.util.ConfigFiles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端轮盘配置(权威配置)。
 * <p>
 * 以<b>原始 JSON 文本</b>形式保存与下发, 而不是反序列化成对象再序列化:
 * 这样客户端可以用自己的 {@code RadialConfig.GSON} 完整解析, 避免双端各自演进
 * 导致字段丢失, 也让服务端无需依赖任何客户端渲染相关的类。
 * <p>
 * 该文件格式必须与客户端 {@code radialmenu.json} 完全一致(客户端直接用它反序列化),
 * 因此"应用为服务器设置"等控制开关单独存放在 {@link RadialServerControl}。
 */
public final class RadialServerConfig {

    /** 服务端配置文件名(位于服务端 config 目录)。 */
    public static final String FILE_NAME = "radialmenu-server.json";

    /** 下发的 JSON 文本长度上限, 与 {@code ServerConfigSyncS2CPayload.MAX_JSON_LENGTH} 保持一致。 */
    private static final int MAX_JSON_LENGTH = 1_048_576;

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 当前服务端配置的原始 JSON 文本; null 表示尚未加载或文件不存在。 */
    private static volatile String cachedJson;

    private RadialServerConfig() {}

    // --- 配置文本 ---

    public static synchronized void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            cachedJson = null;
            RadialCommon.LOGGER.info("No server radial config found at {}, clients keep their own config", path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String text = readAll(reader);
            // 解析一次以校验 JSON 合法性, 避免把损坏内容下发给客户端
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root == null) {
                throw new IllegalStateException("root is not a JSON object");
            }
            cachedJson = text;
            RadialCommon.LOGGER.info("Loaded server radial config from {}", path);
        } catch (Exception e) {
            cachedJson = null;
            RadialCommon.LOGGER.error("Failed to load server radial config at {}, clients keep their own config", path, e);
        }
    }

    public static synchronized void save() {
        if (cachedJson == null) {
            return;
        }
        try {
            // 已是 JSON 文本, 直接原子写入
            ConfigFiles.writeTextAtomically(configPath(), cachedJson);
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save server radial config", e);
        }
    }

    /** 用客户端上传的配置文本替换服务端配置(调用方保证已通过权限校验)。 */
    public static synchronized boolean applyFromClient(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_LENGTH) {
            RadialCommon.LOGGER.warn("Rejected server config update: empty or oversized payload");
            return false;
        }

        try {
            JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.warn("Rejected server config update: malformed JSON", e);
            return false;
        }

        cachedJson = json;
        save();
        return true;
    }

    /** 服务端配置的原始 JSON 文本; 无配置时返回空字符串(便于网络传输)。 */
    public static String json() {
        String json = cachedJson;
        return json != null ? json : "";
    }

    /** 是否存在可用的服务端配置。 */
    public static boolean isAvailable() {
        return cachedJson != null;
    }

    // --- 工具 ---

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read);
        }
        return sb.toString();
    }
}
