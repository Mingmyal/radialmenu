package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.assets.AssetBundle;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.network.ServerAssetChunkC2SPayload;
import com.mingmyal.radialmenu.network.SetClientConfigSourceC2SPayload;
import com.mingmyal.radialmenu.network.SetServerConfigStateC2SPayload;
import com.mingmyal.radialmenu.util.ConfigFiles;

import java.nio.file.Files;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 客户端配置来源状态机。
 * <p>
 * 本地共存在三种配置来源:
 * <ol>
 *   <li><b>强制纯净配置</b> — 服务器开启强制时使用。每次进服重新从服务端拉取, 只存内存,
 *       本地改不动。此模式下只有 OP 能编辑, 普通玩家按 K 会被拒绝。</li>
 *   <li><b>按服自定义副本</b> — 玩家在服务端配置基础上自定义的内容, 按服务器隔离存放。
 *       服务器应用了配置但没开强制时使用它, 因此玩家既能"在原基础上自定义", 又不会
 *       影响他人。</li>
 *   <li><b>客户端全局配置</b> — 玩家自己的那份 {@code radialmenu.json}, 与服务器无关。</li>
 * </ol>
 * <p>
 * <b>权限只用原版 OP 等级</b>, 不使用权限节点。且 OP 状态是<b>实时读取</b>客户端自己的
 * 权限等级(原版在 op/撤销 op 时会立即更新它), 不做缓存, 因此不存在"权限过期"的问题。
 */
public final class ServerConfigState {

    // --- 服务端下发的权威状态 ---

    /** 服务端下发的<b>纯净</b>配置(只读, 仅内存)。 */
    private static volatile RadialConfig serverConfig;

    /** 上一次解析用的原始文本, 避免重复解析。 */
    private static volatile String lastJson = "";

    /** 服务端是否存在可用配置。 */
    private static volatile boolean available;

    /** 服务端是否分发服务端配置(全服)。 */
    private static volatile boolean applied;

    /** 服务端是否强制所有客户端使用服务端配置(全服)。 */
    private static volatile boolean force;

    /** 本玩家是否选择使用自己的全局配置。 */
    private static volatile boolean useClient;

    /** 是否收到过服务端下发。 */
    private static volatile boolean synced;

    /** 当前服务器的标识(按服隔离用)。 */
    private static volatile String serverId = "";

    /** 按服自定义副本; 未创建时为 null。 */
    private static volatile RadialConfig customCopy;

    /**
     * 状态变化回调(供设置界面刷新)。
     * <p>
     * 设置界面在创建时就捕获了"生效配置"的对象引用, 一旦切换配置来源
     * (服务器设置 ↔ 客户端设置), 该引用就指向不再生效的那份配置, 继续编辑会改错对象。
     * 因此需要通知界面重建。
     */
    private static volatile Runnable changeListener;

    private ServerConfigState() {}

    /** 注册状态变化回调; 传 null 表示注销。 */
    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void fireChanged() {
        Runnable listener = changeListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.error("Server config change listener failed", e);
        }
    }

    // --- OP 判定(实时, 不缓存) ---

    /**
     * 本玩家当前是否为 OP。
     * <p>
     * 直接读取客户端自己的权限等级: 原版在玩家被 op / 撤销 op 时会立即把新等级同步给
     * 该客户端, 所以这里拿到的永远是最新值, 不需要任何缓存或重新请求。
     * <p>
     * 仅用于界面与本地编辑模式的判定; 任何会改动服务端配置的操作都由服务端重新校验。
     */
    public static boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc != null ? mc.player : null;
        return player != null && player.hasPermissions(RadialCommon.OP_PERMISSION_LEVEL);
    }

    /**
     * 是否处于单人游戏。
     * <p>
     * 单人存档里集成服务端同样会下发配置, 但从玩家视角并不存在"服务器设置"这回事,
     * 因此界面上应当隐藏所有服务端相关选项。
     */
    public static boolean isSingleplayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.isLocalServer();
    }

    /**
     * 是否应当显示服务端<b>管理</b>选项(上传配置、强制开关)。
     * <p>
     * 仅多人游戏中的 OP 可见。单人游戏没有这个概念; 普通玩家点了也只会被拒绝,
     * 显示出来只会造成困惑。
     */
    public static boolean shouldShowServerManagement() {
        return !isSingleplayer() && isOp();
    }

    /**
     * 是否应当显示"切换为客户端/服务器设置"。
     * <p>
     * 仅当服务器确实在分发配置、且<b>没有</b>强制时才显示 —— 强制时切换必然被拒,
     * 显示出来只会让玩家反复点到失败提示。
     * 与是否为 OP 无关: 普通玩家同样需要它来切回自己的配置。
     */
    public static boolean shouldShowSourceSwitch() {
        return !isSingleplayer() && synced && applied && available && !force;
    }

    /**
     * 是否应当显示"本地可调设置"(外观、布局、文字、行为等)。
     * <p>
     * 强制模式下这些设置全部隐藏: 此时生效的配置由服务器统一决定, 改了也不会被保存,
     * 显示出来只会误导玩家以为自己能调。界面上只保留"强制"开关本身, 便于 OP 关闭它。
     * <p>
     * 非强制时一律显示 —— 无论用的是服务端配置的副本还是自己的配置, 都是可编辑、可保存的。
     */
    public static boolean shouldShowLocalSettings() {
        return !isForcedNow();
    }

    // --- 服务端下发 ---

    /** 处理服务端下发的状态。 */
    public static void onSync(String json, boolean available, boolean applied, boolean force,
                              boolean useClient) {
        // 单人游戏一律使用本地配置。
        // 单人存档的集成服务端与客户端共用同一个 config 目录, 若那里恰好存在
        // radialmenu-server.json(例如从服务器拷来的), 集成服务端会照样下发; 而单人模式下
        // 服务端相关按钮是隐藏的, 玩家将无法从界面关掉它 —— 因此这里直接忽略单人模式的下发,
        // 让"单人 = 用自己的配置"始终成立。
        if (isSingleplayer()) {
            reset();
            return;
        }

        ServerConfigState.available = available;
        ServerConfigState.applied = applied;
        ServerConfigState.force = force;
        ServerConfigState.useClient = useClient;
        synced = true;

        String incoming = json != null ? json : "";
        String id = ServerConfigStore.currentServerId();
        if (!id.equals(serverId)) {
            // 换了服务器(或首次): 重置所有按服状态
            serverId = id;
            lastJson = "";
            customCopy = null;
        }

        if (!incoming.equals(lastJson)) {
            lastJson = incoming;
            serverConfig = parsePure(incoming);
            // 把纯净配置落一份缓存文件, 便于玩家查看服务器强制的内容。
            // 注意: 程序从不读回它 —— 纯净配置始终以内存中的下发结果为准,
            // 所以玩家手动改这个文件不会有任何效果。
            cachePureConfig(incoming);
            onServerConfigChanged(incoming);
        } else {
            ensureCustomCopy();
        }

        // 开关状态与条件解锁状态都是玩家的运行期状态, 与"服主上传了什么"无关。
        // 服务端每次下发都会把纯净配置整体重建(强制模式下还会覆盖玩家的自定义副本),
        // 因此这里统一把本地保存的运行期状态覆盖回去, 否则玩家重进游戏就会被打回上传时的状态
        // —— 解锁状态尤其严重, 因为玩家是真的把物品/经验消耗掉了。
        applyStoredRuntimeState();

        enforceEditModePermission();
        fireChanged();
    }

    /**
     * 把本地保存的运行期状态(开关状态 + 条件解锁状态)覆盖回"生效配置"。
     * <p>
     * 强制模式与自定义副本两条路径都要覆盖: 前者是每次下发都会重建的内存对象,
     * 后者会在服主更新全服配置时被丢弃重建, 两者的运行期状态都会被重置。
     * <p>
     * <b>解锁状态只是"本次会话的兜底"</b>: 采用服务端配置时, 权威解锁记录由服务端随配置
     * 一起下发(见 {@link com.mingmyal.radialmenu.client.UnlockRequestManager#onServerUnlockSync}),
     * 那份快照随后会整体覆盖这里读入的本地内容。之所以仍先读本地, 是为了让"服务端尚未回复"
     * 的短暂间隙里界面不闪回未解锁 —— 最终以服务端为准, 本地伪造的解锁不会存活。
     */
    private static void applyStoredRuntimeState() {
        if (serverId == null || serverId.isBlank()) {
            return;
        }
        if (serverConfig != null) {
            SlotRuntimeStateStore.apply(serverId, serverConfig.slots);
        }
        if (customCopy != null && customCopy != serverConfig) {
            SlotRuntimeStateStore.apply(serverId, customCopy.slots);
        }
    }

    /**
     * 立即把"生效配置"里的运行期状态落盘(按服务器隔离)。
     * <p>
     * 这些状态只在玩家点击槽位/确认消耗时改变, 频率很低, 因此这里同步写入而不走 debounce ——
     * 玩家解锁完马上退出世界也不会丢。强制模式下这是运行期状态<b>唯一</b>的持久化途径
     * (纯净服务端配置只读, 无法保存)。
     * <p>
     * 只在采用服务端配置时记录: 用自己全局配置时这些状态本来就写在 {@code radialmenu.json}
     * 里, 若同时记进按服覆盖表, 下次改用服务端配置就会把玩家自己的进度串到服主那份配置上。
     */
    public static void saveRuntimeStateNow() {
        if (serverId == null || serverId.isBlank() || !isUsingServerConfig()) {
            return;
        }
        RadialConfig target = getEffectiveConfig();
        if (target != null) {
            SlotRuntimeStateStore.mergeAndSave(serverId, target.slots);
        }
    }

    /**
     * 服务端配置发生变化时的处理。
     * <p>
     * 关键点: 服主更新了全服配置后, 玩家的自定义副本必须<b>重新以新配置为基础</b>,
     * 否则玩家会一直停留在旧配置上, 服主的更新永远看不到。
     */
    private static void onServerConfigChanged(String json) {
        String fingerprint = ServerConfigStore.fingerprint(json);
        String known = ServerConfigStore.readFingerprint(serverId);

        if (fingerprint.equals(known)) {
            // 服务端配置未变: 加载玩家已有的自定义副本
            customCopy = ServerConfigStore.load(serverId);
            ensureCustomCopy();
            return;
        }

        // 服务端配置变了(或首次见到): 丢弃旧副本, 以新配置为基准
        customCopy = null;
        ServerConfigStore.writeFingerprint(serverId, fingerprint);
        try {
            Files.deleteIfExists(ServerConfigStore.configPath(serverId));
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Failed to drop stale per-server radial config", e);
        }

        ensureCustomCopy();
    }

    /** 确保"按服自定义副本"已就绪(非强制模式下才有意义)。 */
    private static void ensureCustomCopy() {
        if (force || !serverConfigUsable() || customCopy != null) {
            return;
        }
        RadialConfig copy = serverConfig.copyForEditing();
        copy.setSavePath(ServerConfigStore.configPath(serverId));
        copy.setReadOnly(false);
        customCopy = copy;
        ServerConfigStore.save(serverId, copy);
    }

    /** 把服务端下发的纯净配置缓存到按服文件(每次下发覆盖, 因此本地改它没有意义)。 */
    private static void cachePureConfig(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            ConfigFiles.writeTextAtomically(
                    ServerConfigStore.forcedPath(serverId), json);
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Failed to cache pure server config", e);
        }
    }

    /** 处理服务端对修改请求的回执。 */
    public static void onResult(boolean granted, boolean applied, boolean force,
                               boolean useClient, String reasonKey) {
        ServerConfigState.applied = applied;
        ServerConfigState.force = force;
        ServerConfigState.useClient = useClient;

        if (!granted && reasonKey != null && !reasonKey.isEmpty()) {
            notifyRed(Component.translatable(reasonKey));
        }

        enforceEditModePermission();
        fireChanged();
    }

    /** 断开连接时清空所有服务端状态, 回退到本地配置。 */
    public static void reset() {
        serverConfig = null;
        customCopy = null;
        lastJson = "";
        serverId = "";
        available = false;
        applied = false;
        force = false;
        useClient = false;
        synced = false;
        clearDirty();
    }

    // --- 查询 ---

    public static boolean isSynced() {
        return synced;
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean isApplied() {
        return applied;
    }

    public static boolean isForce() {
        return force;
    }

    /** 本玩家是否选择使用自己的全局配置(强制模式下恒为 false)。 */
    public static boolean isUsingClientConfig() {
        return useClient && !force;
    }

    /** 本玩家能否操作服务端设置选项(仅 OP)。 */
    public static boolean canManage() {
        return isOp() && synced;
    }

    /** 服务端配置是否处于可用状态(已应用且确有内容)。 */
    private static boolean serverConfigUsable() {
        return applied && available && serverConfig != null;
    }

    /** 当前是否采用服务端配置(而非玩家自己的全局配置)。 */
    public static boolean isUsingServerConfig() {
        return serverConfigUsable() && !isUsingClientConfig();
    }

    /** 强制模式是否正在生效。 */
    public static boolean isForcedNow() {
        return isUsingServerConfig() && force;
    }

    // --- 生效配置 ---

    /**
     * 当前生效的配置。
     * <p>
     * 强制模式 → 纯净服务端配置(只读、仅内存);
     * 否则采用服务端配置 → 按服自定义副本;
     * 否则 → 玩家自己的客户端全局配置。
     */
    public static RadialConfig getEffectiveConfig() {
        if (!isUsingServerConfig()) {
            return RadialConfig.INSTANCE;
        }

        if (force) {
            // 强制: 永远用纯净配置, 玩家本地改动无效
            return serverConfig;
        }

        RadialConfig copy = customCopy;
        if (copy == null) {
            // 兜底路径, 会被渲染循环调用, 因此只做内存操作不写盘
            copy = serverConfig.copyForEditing();
            copy.setSavePath(ServerConfigStore.configPath(serverId));
            copy.setReadOnly(false);
            customCopy = copy;
        }
        return copy;
    }

    /**
     * 能否切换编辑模式(K 键)。
     * <p>
     * 强制模式下<b>仅 OP 可用</b>。保留 OP 的 K 键是有意为之: 编辑模式下点击轮盘中心圆盘即可
     * 打开主轮盘设置, 而 N 键并不显眼, 禁掉 K 会让 OP 找不到入口去关闭强制。
     * <p>
     * 注意"能进入编辑模式"不等于"能编辑槽位": 强制模式下槽位编辑仍被
     * {@link #canEditSlots()} 拦住, 因为那份配置改不了、保存不了。
     */
    public static boolean canEnterEditMode() {
        if (isForcedNow()) {
            return isOp();
        }
        return true;
    }

    /**
     * 能否编辑槽位(右键槽位 / 修改配置内容)。
     * <p>
     * <b>强制模式下任何人都不行, 包括 OP。</b>强制时生效的是仅存在于内存的纯净配置,
     * 改动无法持久化; 若允许编辑就会出现"改完看起来生效、实际被丢弃"的误导。
     * 要修改全服配置: 先关闭强制 → 修改 → 点击"应用为服务器设置"重新上传 → 再开启强制。
     * <p>
     * 非强制时改的是玩家自己那份副本(或全局配置), 只影响自己, 因此不做限制。
     */
    public static boolean canEditSlots() {
        if (isForcedNow()) {
            return false;
        }
        return true;
    }

    /**
     * 能否打开设置界面。
     * <p>
     * 强制模式下只有 OP 能打开 —— 此时界面里只剩"强制"开关, 正是给 OP 用来关闭强制的;
     * 普通玩家打开也没有任何可操作项, 因此直接不放行。
     * 非强制时一律允许(可能只读), 便于玩家了解当前配置。
     */
    public static boolean canOpenSettings() {
        if (isForcedNow()) {
            return isOp();
        }
        return true;
    }

    // --- 编辑模式 ---

    /** 不允许进入编辑模式时强制关闭它。 */
    public static void enforceEditModePermission() {
        if (!canEnterEditMode() && RadialConfig.INSTANCE.editMode) {
            RadialConfig.INSTANCE.editMode = false;
            RadialCommon.LOGGER.info("Edit mode force-disabled: server config is forced");
        }
    }

    /** 当前编辑模式是否真正生效。 */
    public static boolean isEditModeActive() {
        return RadialConfig.INSTANCE.editMode && canEnterEditMode();
    }

    /** 切换编辑模式; 不允许时拒绝并提示红色文本。 */
    public static void toggleEditMode() {
        if (!canEnterEditMode()) {
            // 强制模式下普通玩家不能进入编辑模式
            notifyRed(Component.translatable("radialmenu.server_config.forced_no_edit"));
            enforceEditModePermission();
            return;
        }
        RadialConfig.INSTANCE.editMode = !RadialConfig.INSTANCE.editMode;
        // 编辑模式属于玩家本地界面偏好, 始终写入客户端全局配置
        try {
            RadialConfig.INSTANCE.saveSelf();
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.error("Failed to persist edit mode preference", e);
        }
        notifyInfo(RadialConfig.INSTANCE.editMode
                ? "radialmenu.ui.edit_mode.on"
                : "radialmenu.ui.edit_mode.off");
    }

    // --- 提示 ---

    /** 在操作栏以红色文本提示玩家没有权限。 */
    public static void notifyNoPermission() {
        notifyRed(Component.translatable("radialmenu.server_config.no_permission"));
    }

    public static void notifyRed(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.displayClientMessage(message.copy().withStyle(ChatFormatting.RED), true);
    }

    public static void notifyInfo(String translationKey) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.displayClientMessage(Component.translatable(translationKey), true);
    }

    /** 服务端未安装本模组(或通道不可用)时的提示。 */
    public static void notifyServerMissing() {
        notifyRed(Component.translatable("radialmenu.server_config.no_server_mod"));
    }

    // --- 修改 ---

    /**
     * 请求修改服务端全局开关(仅 OP, 服务端会再校验)。
     * <p>
     * 发布配置时, 配置里引用到的导入资源(图标/音频)也一起上传:
     * 它们和配置是同一条生命周期上的东西, 只传 JSON 会让别人的客户端画不出图标、
     * 放不出声音(那些文件只存在于上传者的机器上)。
     * <p>
     * 发送顺序是"资源分块 → 配置包": Fabric 按发送顺序投递, 服务端在收到配置包时
     * 才一次性提交同批资源, 于是"配置 + 资源"在服务端表现为一个整体。
     */
    public static boolean requestSetState(boolean applied, boolean force, RadialConfig configToPublish) {
        if (!ClientPlayNetworking.canSend(SetServerConfigStateC2SPayload.TYPE)) {
            notifyServerMissing();
            return false;
        }

        String json = configToPublish != null ? RadialConfig.GSON.toJson(configToPublish) : "";

        if (configToPublish != null && !json.isBlank()) {
            uploadAssetsFor(configToPublish);
        }

        ClientPlayNetworking.send(new SetServerConfigStateC2SPayload(applied, force, json));
        return true;
    }

    /**
     * 把配置引用到的导入资源分块上传。
     * <p>
     * 即使没有任何资源也要发一个空标记({@code total = 0}): 服务端据此区分
     * "这份配置确实不含资源"与"分块还没收完/丢了"。前者要清掉旧资源
     * (服主删掉图标后不该继续发旧图标), 后者必须保持现状, 否则会把好配置清成空白。
     */
    private static void uploadAssetsFor(RadialConfig config) {
        if (!ClientPlayNetworking.canSend(ServerAssetChunkC2SPayload.TYPE)) {
            // 服务端模组版本较旧: 只能传配置, 资源同步不可用。明确告知而不是静默失败。
            RadialCommon.LOGGER.warn(
                    "Server does not accept radial asset uploads; icons/sounds will not sync");
            return;
        }

        int uploadId = UPLOAD_COUNTER.incrementAndGet();

        AssetBundle bundle = ClientAssetSync.collectForConfig(config);
        if (bundle.isEmpty()) {
            RadialCommon.LOGGER.info(
                    "Config references no uploadable imported assets; telling the server so");
            ClientPlayNetworking.send(new ServerAssetChunkC2SPayload(uploadId, 0, 0, new byte[0]));
            return;
        }

        List<AssetBundle.Chunk> chunks;
        try {
            chunks = bundle.split();
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.error("Failed to package imported assets for upload", e);
            notifyRed(Component.translatable("radialmenu.server_config.assets_too_large"));
            return;
        }

        for (AssetBundle.Chunk chunk : chunks) {
            ClientPlayNetworking.send(new ServerAssetChunkC2SPayload(
                    uploadId, chunk.index(), chunk.total(), chunk.data()));
        }

        RadialCommon.LOGGER.info(
                "Uploading {} imported asset(s) in {} chunk(s) with the server config",
                bundle.size(), chunks.size());
    }

    /** 上传批次号: 只需在单次会话内区分开不同批次。 */
    private static final java.util.concurrent.atomic.AtomicInteger UPLOAD_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 请求切换本玩家使用服务端配置还是自己的全局配置。 */
    public static boolean requestSetSource(boolean useClient) {
        if (!ClientPlayNetworking.canSend(SetClientConfigSourceC2SPayload.TYPE)) {
            notifyServerMissing();
            return false;
        }
        ClientPlayNetworking.send(new SetClientConfigSourceC2SPayload(useClient));
        return true;
    }

    /** 把当前生效的按服自定义副本立即写盘(设置界面点击保存时调用)。 */
    public static void saveCurrentCopy() {
        if (force || !isUsingServerConfig()) {
            return;
        }
        ServerConfigStore.save(serverId, getEffectiveConfig());
    }

    // --- 运行期状态变更合并(debounce) ---

    private static volatile boolean dirty;
    private static volatile long dirtySince;

    /** 合并窗口: 窗口内的多次变更只提交一次。 */
    private static final long DEBOUNCE_MS = 500;

    /** 标记"生效配置"有待落盘。 */
    public static void markServerConfigDirty() {
        dirtySince = System.currentTimeMillis();
        dirty = true;
    }

    /** 由客户端 tick 驱动: 合并窗口结束后保存一次。 */
    public static void flushDirty() {
        if (!dirty || System.currentTimeMillis() - dirtySince < DEBOUNCE_MS) {
            return;
        }
        dirty = false;

        if (isForcedNow()) {
            // 强制模式: 纯净配置只读, 运行期状态不持久化
            return;
        }
        if (isUsingServerConfig()) {
            ServerConfigStore.save(serverId, getEffectiveConfig());
            return;
        }
        RadialConfig.save();
    }

    private static void clearDirty() {
        dirty = false;
        dirtySince = 0L;
    }

    /** 解析服务端下发的纯净配置(只读)。 */
    private static RadialConfig parsePure(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return RadialConfig.parseFromJson(json);
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.error("Failed to parse server radial config; keeping local config", e);
            return null;
        }
    }
}
