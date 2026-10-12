package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.util.ConfigFiles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端轮盘配置的<b>控制状态</b>(与配置文件本身分开存放)。
 * <p>
 * 单独一个文件的原因: {@code radialmenu-server.json} 的格式必须与客户端
 * {@code radialmenu.json} 完全一致(客户端直接用它反序列化), 不能塞入额外字段。
 * <p>
 * 包含两类状态:
 * <ul>
 *   <li><b>全局开关</b>(全服生效): 是否分发服务端配置、是否强制所有客户端使用它。
 *       由服主设置, 影响所有人。</li>
 *   <li><b>个人偏好</b>(按玩家): 该玩家选择使用服务端配置还是自己的本地配置。
 *       有权限的玩家可以切回自己的配置, 除非服务器开启了强制。</li>
 * </ul>
 */
public final class RadialServerControl {

    /** 控制文件名(位于服务端 config 目录)。 */
    public static final String FILE_NAME = "radialmenu-server-control.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path controlPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 是否分发服务端配置(全服)。 */
    private static volatile boolean applied;

    /** 是否强制所有客户端使用服务端配置(全服)。 */
    private static volatile boolean force;

    /** 个人偏好: UUID → 是否使用本地配置(true = 用自己的)。 */
    private static final Map<UUID, Boolean> useClientPrefs = new ConcurrentHashMap<>();

    private RadialServerControl() {}

    public static synchronized void load() {
        Path path = controlPath();
        if (!Files.exists(path)) {
            applied = false;
            force = false;
            useClientPrefs.clear();
            RadialCommon.LOGGER.info("No server radial control file at {}, server config disabled", path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            applied = readBoolean(root, "applied", false);
            force = readBoolean(root, "force", false);
            useClientPrefs.clear();
            if (root.has("useClient") && root.get("useClient").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("useClient").entrySet()) {
                    try {
                        useClientPrefs.put(UUID.fromString(e.getKey()), e.getValue().getAsBoolean());
                    } catch (IllegalArgumentException ignored) {
                        // 非法 UUID 直接跳过, 不影响其余记录
                    }
                }
            }
            RadialCommon.LOGGER.info(
                    "Loaded server radial control: applied={}, force={}, playerPrefs={}",
                    applied, force, useClientPrefs.size());
        } catch (Exception e) {
            applied = false;
            force = false;
            useClientPrefs.clear();
            RadialCommon.LOGGER.error(
                    "Failed to load server radial control at {}, server config disabled", path, e);
        }

        // 强制必须是"已分发"的子集: 未分发却强制没有意义, 且会让客户端状态自相矛盾
        if (!applied) {
            force = false;
        }
    }

    public static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("applied", applied);
            root.addProperty("force", force);
            JsonObject prefs = new JsonObject();
            useClientPrefs.forEach((id, useClient) -> prefs.addProperty(id.toString(), useClient));
            root.add("useClient", prefs);
            ConfigFiles.writeTextAtomically(controlPath(), GSON.toJson(root));
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save server radial control", e);
        }
    }

    // --- 全局开关 ---

    public static boolean isApplied() {
        return applied;
    }

    public static boolean isForce() {
        return force;
    }

    /** 设置全局开关并落盘。force 在未 applied 时会被自动收敛为 false。 */
    public static synchronized void set(boolean newApplied, boolean newForce) {
        applied = newApplied;
        force = newApplied && newForce;
        save();
    }

    // --- 个人偏好 ---

    /**
     * 该玩家是否选择使用自己的本地配置。
     * <p>
     * 服务器强制时恒为 false(强制使用服务端配置)。
     */
    public static boolean isUsingClientConfig(UUID playerId) {
        if (force && applied) {
            // 强制模式下个人偏好无效
            return false;
        }
        return playerId != null && Boolean.TRUE.equals(useClientPrefs.get(playerId));
    }

    /** 记录玩家的个人偏好。强制模式下拒绝写入(避免留下与生效值不符的记录)。 */
    public static synchronized boolean setUsingClientConfig(UUID playerId, boolean useClient) {
        if (playerId == null) {
            return false;
        }
        if (force && applied) {
            return false;
        }
        useClientPrefs.put(playerId, useClient);
        save();
        return true;
    }

    /** 玩家是否被允许切回自己的本地配置(服务器未强制, 且服务端确有配置可用)。 */
    public static boolean canChooseClientConfig() {
        return !(force && applied) && applied && RadialServerConfig.isAvailable();
    }

    /** 读取布尔字段, 缺失或类型不符时返回回退值。 */
    private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            return root.get(key).getAsBoolean();
        }
        return fallback;
    }
}
