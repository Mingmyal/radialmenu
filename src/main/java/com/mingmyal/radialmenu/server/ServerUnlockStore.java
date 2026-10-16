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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 服务端持有的<b>槽位解锁状态</b>(按玩家, 权威副本)。
 * <p>
 * 与 {@link RadialServerConfig}(配置内容)分开存放: 那份文件的格式必须与客户端
 * {@code radialmenu.json} 完全一致, 不能塞入按玩家的数据。
 * <p>
 * <b>为什么解锁状态必须放在服务端</b>: 它记录的是"玩家真的花掉了物品/经验换取的东西",
 * 属于玩家资产。若只存在客户端, 玩家改一下本地的 {@code *.runtime.json} 就能白拿解锁,
 * 而且卸载配置、换设备都会丢。放在服务端后:
 * <ul>
 *   <li>玩家进服时由服务端下发权威状态, 客户端本地的伪造记录会被<b>整体覆盖</b>;</li>
 *   <li>玩家退出时落盘, 换设备/重装客户端仍然保留;</li>
 *   <li>扣除与记账都发生在服务端, 客户端无法凭空产生解锁。</li>
 * </ul>
 * <p>
 * 内存中常驻, 只在玩家退出、重置与关服时写盘 —— 解锁变更频率很低, 没必要每次点击都落盘。
 */
public final class ServerUnlockStore {

    /** 按玩家解锁状态文件(位于服务端 config 目录)。 */
    public static final String FILE_NAME = "radialmenu-server-unlocks.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 单个玩家最多记录的槽位键数: 槽位树规模有限, 同时封住异常文件的膨胀。 */
    private static final int MAX_KEYS_PER_PLAYER = 4096;

    private static Path storePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** UUID → (槽位键 → 解锁截止时间戳)。常驻内存。 */
    private static final Map<UUID, Map<String, Long>> unlocks = new ConcurrentHashMap<>();

    private ServerUnlockStore() {}

    // --- 载入 / 落盘 ---

    /** 从磁盘载入所有玩家的解锁状态(服务端启动时调用)。 */
    public static synchronized void load() {
        Path path = storePath();
        unlocks.clear();

        if (!Files.exists(path)) {
            RadialCommon.LOGGER.info(
                    "No server radial unlock store at {}; unlock progress starts empty", path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement players = root.get("unlocks");
            if (players != null && players.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry :
                        players.getAsJsonObject().entrySet()) {
                    UUID id;
                    try {
                        id = UUID.fromString(entry.getKey());
                    } catch (IllegalArgumentException ignored) {
                        // 非法 UUID 跳过, 不影响其余玩家
                        continue;
                    }
                    unlocks.put(id, readPlayerUnlocks(entry.getValue()));
                }
            }
            RadialCommon.LOGGER.info(
                    "Loaded radial unlock store from {}: {} player(s)", path, unlocks.size());
        } catch (Exception e) {
            // 损坏时不覆盖文件: 先清空内存保证运行, 落盘交给下一次保存(会覆盖, 但至少不在这里悄悄丢)
            unlocks.clear();
            RadialCommon.LOGGER.error(
                    "Failed to load radial unlock store at {}; starting empty", path, e);
        }
    }

    private static Map<String, Long> readPlayerUnlocks(JsonElement element) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            if (out.size() >= MAX_KEYS_PER_PLAYER) {
                break;
            }
            JsonElement value = e.getValue();
            // 只认数值: getAsLong() 会把布尔/字符串静默解析成意外的时间戳
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.put(e.getKey(), value.getAsLong());
            }
        }
        return out;
    }

    /**
     * 把内存中的解锁状态写盘。
     * <p>
     * 顺带<b>就地</b>清掉已过期的限期解锁: 它们对玩家已无效(客户端按当前时间判定),
     * 留着只会让内存与文件无限增长, 还会白占 {@link #MAX_KEYS_PER_PLAYER} 的额度。
     */
    public static synchronized void save() {
        long now = System.currentTimeMillis();

        try {
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, Map<String, Long>> entry : unlocks.entrySet()) {
                JsonObject perPlayer = new JsonObject();

                // 用迭代器: 过期条目要同时从内存里摘掉, 否则会一直堆积
                Iterator<Map.Entry<String, Long>> it = entry.getValue().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Long> slot = it.next();
                    long until = slot.getValue();
                    if (until != Long.MAX_VALUE && until <= now) {
                        it.remove();
                        continue;
                    }
                    perPlayer.addProperty(slot.getKey(), until);
                }

                if (perPlayer.size() > 0) {
                    players.add(entry.getKey().toString(), perPlayer);
                }
            }

            JsonObject root = new JsonObject();
            root.add("unlocks", players);
            ConfigFiles.writeTextAtomically(storePath(), GSON.toJson(root));
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save radial unlock store", e);
        }
    }

    // --- 读写 ---

    /**
     * 该玩家当前的解锁状态快照(键 → 截止时间戳)。
     * <p>
     * 返回副本: 调用方(下发报文)不应改动内部状态。
     */
    public static Map<String, Long> snapshot(UUID playerId) {
        if (playerId == null) {
            return Map.of();
        }
        Map<String, Long> stored = unlocks.get(playerId);
        return stored == null ? Map.of() : Map.copyOf(stored);
    }

    /**
     * 是否还能为该玩家记录这个键。
     * <p>
     * 由 {@link ServerUnlockHandler} 在<b>扣除之前</b>预检: 先扣费再发现记不下, 玩家就白花了。
     * 正常配置的槽位树远达不到上限, 这个检查只用于挡住畸形配置/异常数据。
     */
    public static boolean canRecord(UUID playerId, String slotKey) {
        if (playerId == null || slotKey == null || slotKey.isBlank()) {
            return false;
        }
        Map<String, Long> map = unlocks.get(playerId);
        if (map == null || map.containsKey(slotKey)) {
            return true;
        }
        return map.size() < MAX_KEYS_PER_PLAYER;
    }

    /**
     * 记录一次成功的消耗解锁。
     * <p>
     * 由 {@link ServerUnlockHandler} 在<b>真正扣掉物品/经验之后</b>调用, 因此这里记下的
     * 每一条都对应玩家实打实的付出。调用前应先用 {@link #canRecord} 预检。
     * <p>
     * 解锁不会"降级": 若该键已存在永久解锁, 而本次是限期解锁, 则保留永久的那条 ——
     * 否则玩家重复支付一次限期解锁反而把自己的永久解锁改短了。
     */
    public static void record(UUID playerId, String slotKey, long unlockedUntil) {
        if (playerId == null || slotKey == null || slotKey.isBlank() || unlockedUntil <= 0L) {
            return;
        }

        unlocks.compute(playerId, (id, existing) -> {
            Map<String, Long> map = existing != null ? existing : new ConcurrentHashMap<>();
            Long current = map.get(slotKey);
            if (current != null && current == Long.MAX_VALUE) {
                // 已是永久解锁, 不被更短的限期覆盖
                return map;
            }
            if (map.size() >= MAX_KEYS_PER_PLAYER && !map.containsKey(slotKey)) {
                // 调用方应当已经用 canRecord 挡下这种情况。真走到这里说明存在竞态,
                // 记录明确报错(而不是静默丢弃)以便排查
                RadialCommon.LOGGER.error(
                        "Radial unlock store for {} reached {} keys; dropping key '{}' after charging",
                        id, MAX_KEYS_PER_PLAYER, slotKey);
                return map;
            }
            map.put(slotKey, unlockedUntil);
            return map;
        });
    }

    /** 清空某个玩家的全部解锁状态(重置按钮)。 */
    public static void resetAll(UUID playerId) {
        if (playerId != null) {
            unlocks.remove(playerId);
        }
    }

    /** 是否有任何玩家的记录(用于日志/状态命令)。 */
    public static int trackedPlayers() {
        return unlocks.size();
    }
}
