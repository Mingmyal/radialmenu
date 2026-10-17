package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.assets.AssetBundle;
import com.mingmyal.radialmenu.network.ConsumeUnlockC2SPayload;
import com.mingmyal.radialmenu.network.RadialNetworking;
import com.mingmyal.radialmenu.network.ResetUnlocksC2SPayload;
import com.mingmyal.radialmenu.network.ServerAssetChunkC2SPayload;
import com.mingmyal.radialmenu.network.ServerAssetSyncS2CPayload;
import com.mingmyal.radialmenu.network.ServerConfigResultS2CPayload;
import com.mingmyal.radialmenu.network.ServerConfigSyncS2CPayload;
import com.mingmyal.radialmenu.network.SetClientConfigSourceC2SPayload;
import com.mingmyal.radialmenu.network.SetServerConfigStateC2SPayload;
import com.mingmyal.radialmenu.network.ServerUnlockSyncS2CPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端入口: 维护并下发权威轮盘配置。
 * <p>
 * 权限模型<b>只用原版 OP 等级</b>({@link RadialCommon#OP_PERMISSION_LEVEL}, 默认 4),
 * 不使用任何权限节点 —— 因此不依赖权限模组, 也没有"节点缓存过期"一类的问题。
 * <ul>
 *   <li>服务器配置相关的操作(应用配置、强制开关、管理命令): 需要 OP4</li>
 *   <li>切换自己的配置来源: 人人可用(只影响本人), 但服务器强制时不可切换</li>
 *   <li>强制模式下使用 K 键编辑: <b>仅 OP 可用</b>, 普通玩家被拒绝</li>
 * </ul>
 */
public class RadialServer implements ModInitializer {

    @Override
    public void onInitialize() {
        RadialCommon.LOGGER.info("Initializing Radial Server...");

        RadialNetworking.registerPayloadTypes();
        RadialServerConfig.load();
        RadialServerControl.load();
        // 配置携带的导入资源(图标/音频): 与配置同生命周期, 一起加载、一起下发
        ServerAssetStore.load();
        // 玩家解锁状态(按玩家, 常驻内存): 属于玩家资产, 权威副本在服务端
        ServerUnlockStore.load();

        // 全局开关 + 发布配置(仅 OP)
        ServerPlayNetworking.registerGlobalReceiver(
                SetServerConfigStateC2SPayload.TYPE,
                (payload, context) -> handleSetState(payload, context.player(), context.server()));

        // 上传配置附带的导入资源分块(仅 OP): 收齐后暂存, 等随后的配置包一起生效
        ServerPlayNetworking.registerGlobalReceiver(
                ServerAssetChunkC2SPayload.TYPE,
                (payload, context) -> handleAssetChunk(payload, context.player()));

        // 个人配置来源切换(人人可用, 强制时被拒)
        ServerPlayNetworking.registerGlobalReceiver(
                SetClientConfigSourceC2SPayload.TYPE,
                (payload, context) -> handleSetSource(payload, context.player(), context.server()));

        // 消耗解锁(人人可用): 消耗必须由服务端权威执行, 否则客户端本地扣掉的物品
        // 会在下一次服务端同步背包时被原样还原(表现为"退出重进后被返还")
        ServerPlayNetworking.registerGlobalReceiver(
                ConsumeUnlockC2SPayload.TYPE,
                (payload, context) -> ServerUnlockHandler.handle(payload, context.player()));

        // 重置自己的解锁状态(人人可用): 解锁记在服务端, 只改本地会被下次下发冲回来
        ServerPlayNetworking.registerGlobalReceiver(
                ResetUnlocksC2SPayload.TYPE,
                (payload, context) -> handleResetUnlocks(context.player()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                sendSync(player);
            }
        });

        // 玩家退出时把他的解锁状态落盘 —— 用户下线那一刻的进度才是最新的
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerUnlockStore.save();
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                RadialCommon.LOGGER.info(
                        "Saved radial unlock store on {} leaving ({} tracked player(s))",
                        player.getGameProfile().getName(), ServerUnlockStore.trackedPlayers());
                // 未提交的上传暂存要清掉: 否则长期运行的服务器会一直替已下线的玩家留着字节
                stagedUploads.remove(player.getUUID());
                explicitEmptyUploads.remove(player.getUUID());
                ServerAssetStore.purgePendingUploads(player.getUUID());
            }
        });

        // 关服时再存一次: 兜住"服务器直接停止"以及在线玩家尚未退出的情况
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerUnlockStore.save());

        registerCommands();
    }

    /**
     * 清空该玩家在服务端的解锁记录并回传空快照。
     * <p>
     * 只作用于请求者自己, 因此不要求 OP; 回传空快照会让客户端把本地状态也清零并落盘,
     * 于是"重置"在重进后依然成立(不会被服务端重新下发回来)。
     */
    private static void handleResetUnlocks(ServerPlayer player) {
        if (player == null) {
            return;
        }

        ServerUnlockStore.resetAll(player.getUUID());
        ServerUnlockStore.save();

        RadialCommon.LOGGER.info(
                "Radial unlock state reset for {}", player.getGameProfile().getName());

        sendUnlockSync(player);
    }

    /** 玩家是否为 OP(达到 {@link RadialCommon#OP_PERMISSION_LEVEL})。 */
    public static boolean isOp(ServerPlayer player) {
        return player != null && player.hasPermissions(RadialCommon.OP_PERMISSION_LEVEL);
    }

    /** 生成并下发一次完整状态(配置 + 全局开关 + 个人偏好 + 是否 OP)。 */
    public static void sendSync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, ServerConfigSyncS2CPayload.TYPE)) {
            // 客户端未安装本模组(或版本不支持该通道): 静默跳过
            RadialCommon.LOGGER.info(
                    "Player {} has no radialmenu channel; client mod missing or outdated",
                    player.getGameProfile().getName());
            return;
        }

        boolean isOp = isOp(player);

        // 判定结果对排查问题很关键, 因此逐次记录
        RadialCommon.LOGGER.info(
                "Sync radial config to {}: isOp={}, applied={}, force={}, available={}",
                player.getGameProfile().getName(),
                isOp,
                RadialServerControl.isApplied(),
                RadialServerControl.isForce(),
                RadialServerConfig.isAvailable());

        ServerPlayNetworking.send(player, new ServerConfigSyncS2CPayload(
                RadialServerConfig.json(),
                RadialServerConfig.isAvailable(),
                RadialServerControl.isApplied(),
                RadialServerControl.isForce(),
                RadialServerControl.isUsingClientConfig(player.getUUID())));

        // 紧接着下发权威解锁状态: 客户端据此整体覆盖本地记录, 手工伪造的解锁到此失效。
        // 必须与配置下发成对出现 —— 配置里带着 unlockedUntil, 若只发配置不发这份快照,
        // 客户端就会拿"服务端配置里那个恒为初始值的字段"当结果。
        sendUnlockSync(player);

        // 再下发配置携带的导入资源(图标/音频)。顺序很重要: 配置先到, 客户端才知道
        // 自己缺哪些文件; 资源后到即可直接落盘并刷新纹理。
        sendAssets(player);
    }

    /**
     * 把服务端配置携带的导入资源整包下发。
     * <p>
     * 与配置<b>同生命周期</b>: 进服一次, 服主更新配置后广播再一次, 没有增量/按需语义。
     * 客户端收到后直接落到 {@code config/radialmenu/icons|sounds/}, 之后的渲染与播放
     * 走的是和本地导入完全相同的代码路径, 因此不需要改动任何渲染逻辑。
     * <p>
     * 资源总量小(配置里引用的那几十个文件), 一次发完; 若确实超过单包上限,
     * 就在这里按 {@code part/parts} 拆成多包, 客户端收齐后再统一落盘。
     */
    public static void sendAssets(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, ServerAssetSyncS2CPayload.TYPE)) {
            // 客户端版本较旧, 不认识资源通道: 只发配置, 图标/音频缺失由它自己的日志反映
            return;
        }

        AssetBundle bundle = ServerAssetStore.current();
        if (bundle == null || bundle.isEmpty()) {
            // 没有资源也要发一个空包, 让客户端清掉上一次遗留(换服/换配置后不残留旧图标)
            ServerPlayNetworking.send(player, new ServerAssetSyncS2CPayload(new byte[0], 0, 1));
            return;
        }

        byte[] encoded;
        try {
            encoded = bundle.encode();
        } catch (RuntimeException e) {
            RadialCommon.LOGGER.error("Failed to encode server radial assets for sync", e);
            return;
        }

        int maxChunk = ServerAssetSyncS2CPayload.MAX_BUNDLE_BYTES;
        int parts = Math.max(1, (encoded.length + maxChunk - 1) / maxChunk);
        for (int part = 0; part < parts; part++) {
            int from = part * maxChunk;
            int to = Math.min(encoded.length, from + maxChunk);
            byte[] slice = java.util.Arrays.copyOfRange(encoded, from, to);
            ServerPlayNetworking.send(player, new ServerAssetSyncS2CPayload(slice, part, parts));
        }

        RadialCommon.LOGGER.info(
                "Synced {} radial asset(s) ({} byte(s), {} part(s)) to {}",
                bundle.size(), encoded.length, parts, player.getGameProfile().getName());
    }

    /**
     * 下发该玩家在服务端的权威槽位解锁状态。
     * <p>
     * 玩家进服必发一次(哪怕为空): 空快照同样有意义 —— 它会让客户端把本地记录清零,
     * 这正是"防止本地篡改"生效的地方。
     */
    public static void sendUnlockSync(ServerPlayer player) {
        if (player == null || !ServerPlayNetworking.canSend(player, ServerUnlockSyncS2CPayload.TYPE)) {
            return;
        }
        Map<String, Long> unlocks = ServerUnlockStore.snapshot(player.getUUID());
        ServerPlayNetworking.send(player, new ServerUnlockSyncS2CPayload(unlocks));

        RadialCommon.LOGGER.info(
                "Synced {} radial unlock(s) to {}",
                unlocks.size(), player.getGameProfile().getName());
    }

    /**
     * 接收上传配置附带的资源分块。
     * <p>
     * 只做接收与暂存, 不在这里落盘: 资源必须与配置同时生效, 因此真正替换发生在
     * {@link #handleSetState} 处理配置包的那一刻 —— 若配置被拒(不是 OP / JSON 不合法),
     * 暂存的资源会随之丢弃, 不会留下"配置没换成、资源却换了"的错位状态。
     */
    private static void handleAssetChunk(ServerAssetChunkC2SPayload payload, ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (!isOp(player)) {
            // 非 OP 直接丢弃, 不回报: 这只是上传流程的附属帧, 正式拒绝由配置包统一回执
            RadialCommon.LOGGER.warn(
                    "Ignored radial asset chunk from non-OP {}", player.getGameProfile().getName());
            return;
        }

        if (payload.total() == 0) {
            // 客户端显式声明"这份配置不含任何导入资源": 记住它, 以便配置提交时清空旧资源
            explicitEmptyUploads.add(player.getUUID());
            stagedUploads.remove(player.getUUID());
            return;
        }

        AssetBundle bundle = ServerAssetStore.acceptChunk(player.getUUID(), payload);
        if (bundle != null) {
            stagedUploads.put(player.getUUID(), bundle);
            RadialCommon.LOGGER.info(
                    "Received {} radial asset(s) from {}",
                    bundle.size(), player.getGameProfile().getName());
        }
    }

    /**
     * 暂存"已收齐但尚未随配置生效"的资源, 按玩家索引。
     * <p>
     * 用暂存而不是立即替换: 资源分块先于配置包发出, 等配置包被服务端接受后
     * 再一次性提交, 两者才真正原子。
     * <p>
     * 这里额外记下"这一批是否还没被取走", 是因为提交时必须区分两种沉默:
     * <ul>
     *   <li><b>服主确实没有任何导入资源</b> —— 应该清空旧资源(否则删掉的图标会一直发下去);</li>
     *   <li><b>分块还没收完 / 收失败</b> —— 绝不能清空, 否则会把好好的旧资源配置成空白。</li>
     * </ul>
     * 二者在"暂存里什么都没有"时看起来完全一样, 因此用 {@link #explicitEmptyUploads} 标记
     * 前者为"明确的无资源提交"(客户端在发布配置前会先发一个空标记)。
     */
    private static final Map<UUID, AssetBundle> stagedUploads = new ConcurrentHashMap<>();

    /** 该玩家的本次发布是否"明确不含资源"(客户端发过空标记)。 */
    private static final java.util.Set<UUID> explicitEmptyUploads =
            ConcurrentHashMap.newKeySet();

    /** 取出并清除该玩家的暂存资源。 */
    private static AssetBundle takeStagedUpload(ServerPlayer player) {
        return player == null ? null : stagedUploads.remove(player.getUUID());
    }

    /** 取出并清除"明确为空"的标记。 */
    private static boolean takeExplicitEmpty(ServerPlayer player) {
        return player != null && explicitEmptyUploads.remove(player.getUUID());
    }

    /** 广播给所有在线玩家, 让改动立即对全服生效。 */
    private static void broadcast(MinecraftServer server) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            sendSync(online);
        }
    }

    /**
     * 处理全局开关修改。
     * <p>
     * 点击"应用为服务器设置"时携带服主当前的配置, 一并发布为服务端配置 ——
     * 这样服主"点一下"就把自己当前的轮盘设置推给了全服。
     */
    private static void handleSetState(SetServerConfigStateC2SPayload payload, ServerPlayer player,
                                       MinecraftServer server) {
        if (player == null) {
            return;
        }

        if (!isOp(player)) {
            deny(player, ServerConfigResultS2CPayload.REASON_NO_PERMISSION);
            return;
        }

        // 先发布配置(若携带), 再改开关, 避免出现"已启用但没有配置"的中间态
        if (payload.hasJson() && !RadialServerConfig.applyFromClient(payload.json())) {
            RadialCommon.LOGGER.warn(
                    "Rejected radial server config from {}", player.getGameProfile().getName());
            deny(player, ServerConfigResultS2CPayload.REASON_INVALID_CONFIG);
            return;
        }

        if (payload.applied() && !RadialServerConfig.isAvailable()) {
            // 开启分发但没有配置可用: 拒绝, 否则客户端会失去全部设置来源
            deny(player, ServerConfigResultS2CPayload.REASON_UNAVAILABLE);
            return;
        }

        // 配置已通过校验: 此刻才提交同批上传的导入资源, 使"配置 + 资源"对外表现为一个整体。
        // 三种情况必须分开处理, 否则会把好资源配置成空白:
        //   1. 收到了资源      → 用它整体替换;
        //   2. 客户端声明无资源 → 清空(服主删掉了图标, 服务端不该继续发旧图标);
        //   3. 什么都没收到     → 保持现状(分块丢失/客户端版本较旧), 宁可留着旧资源也不错杀。
        if (payload.hasJson()) {
            AssetBundle staged = takeStagedUpload(player);
            boolean explicitEmpty = takeExplicitEmpty(player);

            if (staged != null) {
                if (!ServerAssetStore.replace(staged)) {
                    RadialCommon.LOGGER.warn(
                            "Radial assets from {} could not be persisted; config kept",
                            player.getGameProfile().getName());
                }
            } else if (explicitEmpty) {
                ServerAssetStore.replace(AssetBundle.EMPTY);
                RadialCommon.LOGGER.info(
                        "Radial config from {} references no imported assets; cleared old ones",
                        player.getGameProfile().getName());
            } else {
                RadialCommon.LOGGER.warn(
                        "Radial config from {} arrived without asset upload; keeping existing {}",
                        player.getGameProfile().getName(), ServerAssetStore.summary());
            }
        }

        RadialServerControl.set(payload.applied(), payload.force());

        RadialCommon.LOGGER.info(
                "Radial server config changed by {}: applied={}, force={}",
                player.getGameProfile().getName(),
                RadialServerControl.isApplied(),
                RadialServerControl.isForce());

        grant(player);
        broadcast(server);
    }

    /**
     * 处理个人配置来源切换(使用服务端配置 ↔ 使用自己的全局配置)。
     * <p>
     * 这是纯粹的个人偏好, 不要求 OP: 切回自己的配置只影响该玩家本人。
     * 服务器开启强制时一律拒绝(强制就是要让所有人一致)。
     */
    private static void handleSetSource(SetClientConfigSourceC2SPayload payload, ServerPlayer player,
                                        MinecraftServer server) {
        if (player == null) {
            return;
        }

        if (payload.useClient() && !RadialServerControl.canChooseClientConfig()) {
            // 服务器已强制使用服务端配置, 不允许切回本地
            deny(player, ServerConfigResultS2CPayload.REASON_FORCED);
            return;
        }

        if (!RadialServerControl.setUsingClientConfig(player.getUUID(), payload.useClient())) {
            deny(player, ServerConfigResultS2CPayload.REASON_FORCED);
            return;
        }

        grant(player);
        sendSync(player);
    }

    /** 回执"已允许", 状态取自服务端当前真实值。 */
    private static void grant(ServerPlayer player) {
        ServerPlayNetworking.send(player, ServerConfigResultS2CPayload.granted(
                RadialServerControl.isApplied(),
                RadialServerControl.isForce(),
                RadialServerControl.isUsingClientConfig(player.getUUID())));
    }

    /** 回执"被拒绝", 状态回退为服务端当前真实值。 */
    private static void deny(ServerPlayer player, String reasonKey) {
        ServerPlayNetworking.send(player, ServerConfigResultS2CPayload.denied(
                RadialServerControl.isApplied(),
                RadialServerControl.isForce(),
                RadialServerControl.isUsingClientConfig(player.getUUID()),
                reasonKey));
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("radialmenu-server")
                        // 管理命令需要 OP; 控制台也按 OP 处理(source.getPlayer() 为 null 时放行)
                        .requires(RadialServer::isOpSource)
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    RadialServerConfig.load();
                                    RadialServerControl.load();
                                    // 解锁状态也一并重载, 否则手工编辑过解锁文件后 /reload 不生效
                                    ServerUnlockStore.load();
                                    // 资源与配置一起重载: 手工替换服务端资源目录后同样生效
                                    ServerAssetStore.load();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    RadialServerConfig.isAvailable()
                                                            ? "radialmenu.server.reload.ok"
                                                            : "radialmenu.server.reload.missing"),
                                            true);
                                    // 重新加载后同步全服, 避免客户端仍用旧配置
                                    broadcast(ctx.getSource().getServer());
                                    return 1;
                                }))
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    RadialServerConfig.isAvailable()
                                                            ? "radialmenu.server.status.available"
                                                            : "radialmenu.server.status.missing"),
                                            false);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    RadialServerControl.isApplied()
                                                            ? "radialmenu.server.status.applied"
                                                            : "radialmenu.server.status.not_applied"),
                                            false);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    RadialServerControl.isForce()
                                                            ? "radialmenu.server.status.forced"
                                                            : "radialmenu.server.status.not_forced"),
                                            false);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    "radialmenu.server.status.unlock_store",
                                                    ServerUnlockStore.trackedPlayers()),
                                            false);
                                    // 资源概要让管理员能一眼确认"图标/音频到底有没有传上来"
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable(
                                                    "radialmenu.server.status.assets",
                                                    ServerAssetStore.summary()),
                                            false);
                                    return 1;
                                }))));
    }

    /** 命令源是否为 OP: 控制台(无玩家)始终允许, 玩家需达到 OP 等级。 */
    private static boolean isOpSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null || isOp(player);
    }
}
