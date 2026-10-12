package com.mingmyal.radialmenu.server;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.network.RadialNetworking;
import com.mingmyal.radialmenu.network.ServerConfigResultS2CPayload;
import com.mingmyal.radialmenu.network.ServerConfigSyncS2CPayload;
import com.mingmyal.radialmenu.network.SetClientConfigSourceC2SPayload;
import com.mingmyal.radialmenu.network.SetServerConfigStateC2SPayload;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

        // 全局开关 + 发布配置(仅 OP)
        ServerPlayNetworking.registerGlobalReceiver(
                SetServerConfigStateC2SPayload.TYPE,
                (payload, context) -> handleSetState(payload, context.player(), context.server()));

        // 个人配置来源切换(人人可用, 强制时被拒)
        ServerPlayNetworking.registerGlobalReceiver(
                SetClientConfigSourceC2SPayload.TYPE,
                (payload, context) -> handleSetSource(payload, context.player(), context.server()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null) {
                sendSync(player);
            }
        });

        registerCommands();
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
                                    return 1;
                                }))));
    }

    /** 命令源是否为 OP: 控制台(无玩家)始终允许, 玩家需达到 OP 等级。 */
    private static boolean isOpSource(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null || isOp(player);
    }
}
