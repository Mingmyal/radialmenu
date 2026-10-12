package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.network.ServerConfigResultS2CPayload;
import com.mingmyal.radialmenu.network.ServerConfigSyncS2CPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端网络接收器: 接收服务端下发的配置与开关状态。
 * <p>
 * 处理逻辑统一投递到客户端主线程, 避免在网络线程上触碰配置与界面状态。
 */
public final class ClientNetworkHandlers {

    private ClientNetworkHandlers() {}

    /** 注册接收器与断线清理。 */
    public static void register() {
        // 服务端下发配置 / 开关 / 个人偏好
        ClientPlayNetworking.registerGlobalReceiver(ServerConfigSyncS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ServerConfigState.onSync(
                        payload.json(),
                        payload.available(),
                        payload.applied(),
                        payload.force(),
                        payload.useClient())));

        // 修改请求的处理结果(被拒绝时提示)
        ClientPlayNetworking.registerGlobalReceiver(ServerConfigResultS2CPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ServerConfigState.onResult(
                        payload.granted(),
                        payload.applied(),
                        payload.force(),
                        payload.useClient(),
                        payload.reasonKey())));

        // 断开连接后回到纯本地配置, 防止把上个服务器的设置带进其它服务器
        // 注意: 不在 JOIN 时重置 —— 单人游戏的集成服务端同样会下发配置,
        // 若在 JOIN 重置会与下发产生竞态并丢掉刚收到的状态。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ServerConfigState.reset());
    }
}
