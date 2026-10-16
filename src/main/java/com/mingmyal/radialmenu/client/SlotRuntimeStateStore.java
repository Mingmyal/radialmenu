package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.util.ConfigFiles;
import com.mingmyal.radialmenu.util.SlotPath;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位<b>运行期状态</b>的本地覆盖表(按服务器隔离)。
 * <p>
 * 有两类字段虽然存在槽位树里, 语义上却属于"玩家自己的进度", 与"服主上传了什么"无关:
 * <ul>
 *   <li><b>开关状态</b> {@code toggleState} — 点一下槽位翻转一次;</li>
 *   <li><b>条件解锁状态</b> {@code condition.unlockedUntil} — 玩家消耗物品/经验换来的解锁。</li>
 * </ul>
 * 但在<b>强制模式</b>下生效的是只存在于内存的纯净服务端配置: 它既不能落盘(只读),
 * 又会在每次进服时被服务端下发的内容整体覆盖。于是这两类状态一重进游戏就被打回上传时的样子 ——
 * 解锁状态尤其糟糕, 因为玩家是<b>真的把物品和经验消耗掉了</b>, 解锁却没了。
 * <p>
 * 解决办法是把它们单独存起来: 服务端配置照旧只读, 运行期状态则存在
 * {@code config/radialmenu-servers/<id>.runtime.json} 里, 每次解析完服务端配置后覆盖回去。
 * <p>
 * <b>键</b>用槽位在槽位树中的深度优先位置(如 {@code "0"}、{@code "0.2"}、{@code "0.2.1"}),
 * 因为轮盘本身就是按下标寻址的; 读写两侧走同一套遍历顺序, 因此键天然对齐。
 */
public final class SlotRuntimeStateStore {

    private static final String SUFFIX = ".runtime.json";

    private static final String KEY_TOGGLES = "toggles";
    private static final String KEY_UNLOCKS = "unlocks";

    /** 本地解锁记录的条目上限, 与服务端 {@code ServerUnlockStore} 保持一致。 */
    private static final int MAX_UNLOCK_KEYS = 4096;

    private SlotRuntimeStateStore() {}

    public static Path path(String serverId) {
        return ServerConfigStore.serverDir().resolve(serverId + SUFFIX);
    }

    /** 一个服务器上的全部槽位运行期状态。 */
    public record RuntimeState(Map<String, Boolean> toggles, Map<String, Long> unlocks) {

        public static RuntimeState empty() {
            return new RuntimeState(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        public boolean isEmpty() {
            return toggles.isEmpty() && unlocks.isEmpty();
        }
    }

    // --- 读写 ---

    /** 读取该服务器的运行期状态; 文件不存在或损坏时返回空表。 */
    public static RuntimeState load(String serverId) {
        RuntimeState state = RuntimeState.empty();
        if (serverId == null || serverId.isBlank()) {
            return state;
        }

        Path path = path(serverId);
        if (!Files.exists(path)) {
            return state;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return state;
            }
            JsonObject root = parsed.getAsJsonObject();

            if (root.has(KEY_TOGGLES) || root.has(KEY_UNLOCKS)) {
                readBooleanSection(root.get(KEY_TOGGLES), state.toggles());
                readLongSection(root.get(KEY_UNLOCKS), state.unlocks());
            } else {
                // 早期版本只存开关状态, 且直接平铺在根对象上 —— 按开关状态读入, 不丢玩家数据
                readBooleanSection(root, state.toggles());
            }
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Failed to read per-server slot runtime state at {}", path, e);
        }
        return state;
    }

    private static void readBooleanSection(JsonElement section, Map<String, Boolean> out) {
        if (section == null || !section.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry :
                section.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            // 只认真正的 JSON 布尔值: getAsBoolean() 对字符串会走 Boolean.parseBoolean,
            // 把 "oops" 之类静默变成 false —— 那等于把玩家的开关强行关掉, 不如整条跳过
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                out.put(entry.getKey(), value.getAsBoolean());
            }
        }
    }

    private static void readLongSection(JsonElement section, Map<String, Long> out) {
        if (section == null || !section.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry :
                section.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            // 同理只认数值, 避免布尔/字符串被 getAsLong() 解析成意外的时间戳
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out.put(entry.getKey(), value.getAsLong());
            }
        }
    }

    /** 写入该服务器的运行期状态。 */
    public static void save(String serverId, RuntimeState state) {
        if (serverId == null || serverId.isBlank() || state == null) {
            return;
        }

        try {
            Path path = path(serverId);
            Files.createDirectories(path.getParent());

            JsonObject toggles = new JsonObject();
            for (Map.Entry<String, Boolean> entry : state.toggles().entrySet()) {
                toggles.addProperty(entry.getKey(), entry.getValue());
            }

            JsonObject unlocks = new JsonObject();
            for (Map.Entry<String, Long> entry : state.unlocks().entrySet()) {
                unlocks.addProperty(entry.getKey(), entry.getValue());
            }

            JsonObject root = new JsonObject();
            root.add(KEY_TOGGLES, toggles);
            root.add(KEY_UNLOCKS, unlocks);

            ConfigFiles.writeTextAtomically(
                    path,
                    new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save per-server slot runtime state for {}", serverId, e);
        }
    }

    // --- 覆盖 ---

    /**
     * 把已保存的运行期状态写回槽位树(进服/服务端配置更新后调用)。
     * <p>
     * 只有表中确实存在的键才会覆盖, 因此首次进服(表为空)时服务端上传的状态仍然是初始状态。
     */
    public static void apply(String serverId, List<RadialSlot> slots) {
        if (slots == null) {
            return;
        }
        RuntimeState state = load(serverId);
        if (state.isEmpty()) {
            return;
        }
        applyRecursive(slots, "", state);
    }

    /**
     * 把槽位树当前的运行期状态合并进覆盖表并落盘。
     * <p>
     * 用<b>合并</b>而不是整体替换: 服务端配置可能在不同版本间来回切换, 某个开关/条件槽位
     * 在本次配置里不存在时, 它的状态不应该被顺手抹掉。
     */
    public static void mergeAndSave(String serverId, List<RadialSlot> slots) {
        if (slots == null) {
            return;
        }

        RuntimeState state = load(serverId);
        collectRecursive(slots, "", state);
        save(serverId, state);
    }

    /**
     * 用服务端的<b>权威</b>解锁状态整体替换本地解锁段(保留本地开关状态)。
     * <p>
     * 这是防篡改的关键一步: 解锁状态存在服务端, 进服时下发全量快照。这里先<b>清空</b>
     * 本地解锁记录再写入服务端的那一份, 因此玩家手工往 {@code *.runtime.json} 里
     * 编造的解锁会被一条不留地冲掉 —— 若只做"逐键覆盖", 多出来的键就会活下来。
     * <p>
     * 开关状态不在同步范围内: 它只是界面偏好, 不涉及消耗掉的东西, 归玩家自己。
     */
    public static void replaceUnlocks(String serverId, Map<String, Long> serverUnlocks) {
        if (serverId == null || serverId.isBlank()) {
            return;
        }

        RuntimeState state = load(serverId);
        state.unlocks().clear();

        if (serverUnlocks != null) {
            for (Map.Entry<String, Long> entry : serverUnlocks.entrySet()) {
                String key = entry.getKey();
                Long until = entry.getValue();
                if (key == null || key.isBlank() || until == null || until <= 0L) {
                    continue;
                }
                if (state.unlocks().size() >= MAX_UNLOCK_KEYS) {
                    RadialCommon.LOGGER.warn(
                            "Per-server radial runtime state hit {} unlock keys; dropping the rest",
                            MAX_UNLOCK_KEYS);
                    break;
                }
                state.unlocks().put(key, until);
            }
        }

        save(serverId, state);
    }

    // --- 权威解锁状态覆盖 ---

    /**
     * 把槽位树上<b>所有</b>解锁状态清零。
     * <p>
     * 权威覆盖必须先清后写, 不能只做"逐键写入": 按键写入只会改动服务端记录里出现过的键,
     * 而玩家自己往按服副本 {@code <server>.json} 或运行期文件里塞的额外解锁会原地留下。
     */
    public static void clearUnlocks(List<RadialSlot> slots) {
        SlotPath.forEach(slots, (slot, key) -> {
            if (slot.condition != null) {
                slot.condition.unlockedUntil = 0L;
            }
        });
    }

    /**
     * 按服务端快照写入解锁状态。
     * <p>
     * 与 {@link #collectRecursive} 走<b>同一个</b> {@link SlotPath#forEach} 遍历,
     * 因此键天然对齐; 快照里不存在的键保持"未解锁"。
     */
    public static void applyUnlocks(List<RadialSlot> slots, Map<String, Long> serverUnlocks) {
        if (serverUnlocks == null || serverUnlocks.isEmpty()) {
            return;
        }
        SlotPath.forEach(slots, (slot, key) -> {
            Long until = serverUnlocks.get(key);
            if (until != null && until > 0L && slot.condition != null) {
                slot.condition.unlockedUntil = until;
            }
        });
    }

    // --- 槽位树遍历(读写共用同一顺序, 保证键对齐) ---

    private static void collectRecursive(List<RadialSlot> slots, String prefix, RuntimeState state) {
        for (int i = 0; i < slots.size(); i++) {
            RadialSlot slot = slots.get(i);
            if (slot == null) {
                continue;
            }

            String key = childKey(prefix, i);
            if (isLocalToggle(slot)) {
                state.toggles().put(key, slot.toggleState);
            }
            recordUnlock(state, key, slot);
            // 开关类型的两套状态动作槽位各自可能带条件约束, 用后缀区分
            recordUnlock(state, SlotPath.withAction(key, true), slot.toggleActionOn);
            recordUnlock(state, SlotPath.withAction(key, false), slot.toggleActionOff);

            if (slot.children != null) {
                collectRecursive(slot.children, key, state);
            }
        }
    }

    private static void applyRecursive(List<RadialSlot> slots, String prefix, RuntimeState state) {
        for (int i = 0; i < slots.size(); i++) {
            RadialSlot slot = slots.get(i);
            if (slot == null) {
                continue;
            }

            String key = childKey(prefix, i);
            if (isLocalToggle(slot)) {
                Boolean toggle = state.toggles().get(key);
                if (toggle != null) {
                    slot.toggleState = toggle;
                }
            }
            applyUnlock(state, key, slot);
            applyUnlock(state, SlotPath.withAction(key, true), slot.toggleActionOn);
            applyUnlock(state, SlotPath.withAction(key, false), slot.toggleActionOff);

            if (slot.children != null) {
                applyRecursive(slot.children, key, state);
            }
        }
    }

    private static void recordUnlock(RuntimeState state, String key, RadialSlot slot) {
        if (slot != null && hasUnlockState(slot)) {
            state.unlocks().put(key, slot.condition.unlockedUntil);
        }
    }

    private static void applyUnlock(RuntimeState state, String key, RadialSlot slot) {
        if (slot == null || !hasUnlockState(slot)) {
            return;
        }
        Long unlockedUntil = state.unlocks().get(key);
        if (unlockedUntil != null) {
            slot.condition.unlockedUntil = unlockedUntil;
        }
    }

    private static String childKey(String prefix, int index) {
        return SlotPath.child(prefix, index);
    }

    /**
     * 是否为"状态存在本地的开关槽位"。
     * <p>
     * 绑定外部来源的槽位状态实时读自目标模组, 不需要本地副本。
     * 这里用 {@link RadialSlot#hasToggleSource()}(配置里是否写了绑定)而不是
     * {@code resolveToggleSource()}(来源此刻是否可用)来判断, 有两个原因:
     * <ul>
     *   <li><b>确定性</b>: 覆盖表的键必须在"读"和"写"两侧完全一致。若按"此刻是否可用"判断,
     *       某个模组刚好加载慢一点, 同一个槽位在两次遍历中就会被归到不同类别。</li>
     *   <li><b>开销</b>: 该方法会在服务端配置下发时对整棵槽位树遍历, 而
     *       {@code resolveToggleSource()} 在缓存未命中时会触发一次全量模组扫描。</li>
     * </ul>
     */
    private static boolean isLocalToggle(RadialSlot slot) {
        return slot.toggleEnabled && !slot.hasToggleSource();
    }

    /**
     * 该槽位是否有需要单独保存的解锁状态。
     * <p>
     * {@link RadialSlot#sanitize()} 在条件约束关闭时会把 {@code condition} 置空,
     * 因此这里判空即可, 不需要再查 {@code conditionEnabled}。
     */
    private static boolean hasUnlockState(RadialSlot slot) {
        return slot.condition != null;
    }
}
