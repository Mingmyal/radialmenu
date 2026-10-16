package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.network.ConsumeUnlockC2SPayload;
import com.mingmyal.radialmenu.network.RadialNetworking;
import com.mingmyal.radialmenu.network.ResetUnlocksC2SPayload;
import com.mingmyal.radialmenu.network.ServerConfigResultS2CPayload;
import com.mingmyal.radialmenu.network.ServerConfigSyncS2CPayload;
import com.mingmyal.radialmenu.network.SetClientConfigSourceC2SPayload;
import com.mingmyal.radialmenu.network.SetServerConfigStateC2SPayload;
import com.mingmyal.radialmenu.network.ServerUnlockSyncS2CPayload;

import java.util.Map;
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
        // 玩家解锁状态(按玩家, 常驻内存): 属于玩家资产, 权威副本在服务端
        ServerUnlockStore.load();

        // 全局开关 + 发布配置(仅 OP)
        ServerPlayNetworking.registerGlobalReceiver(
                SetServerConfigStateC2SPayload.TYPE,
                (payload, context) -> handleSetState(payload, context.player(), context.server()));

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
                                    return 1;
                                }))));
    }

    /** 命令源是否为 OP: 控制台(无玩家)始终允许, 玩家需达到 OP 等级。 */
    private static boolean isOpSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null || isOp(player);
    }
}
