package com.mingmyal.radialmenu.network;

import com.mingmyal.radialmenu.RadialCommon;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 网络包类型注册(双端共用)。
 * <p>
 * 必须在客户端与服务端的 {@code onInitialize} 阶段各执行一次,
 * 因此由 {@code RadialClient} 与 {@code RadialServer} 分别调用。
 */
public final class RadialNetworking {

    /** 与 {@link RadialCommon} 共用同一模组 ID, 保证网络通道命名空间唯一。 */
    public static final String MOD_ID = RadialCommon.MOD_ID;

    private static boolean registered;

    private RadialNetworking() {}

    /** 幂等注册: 客户端与服务端入口都会调用, 重复调用无副作用。 */
    public static synchronized void registerPayloadTypes() {
        if (registered) {
            return;
        }
        registered = true;

        // 服务端 -> 客户端: 配置下发 / 开关修改结果 / 消耗解锁结果 / 权威解锁状态
        PayloadTypeRegistry.playS2C().register(
                ServerConfigSyncS2CPayload.TYPE, ServerConfigSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ServerConfigResultS2CPayload.TYPE, ServerConfigResultS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ConsumeUnlockResultS2CPayload.TYPE, ConsumeUnlockResultS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ServerUnlockSyncS2CPayload.TYPE, ServerUnlockSyncS2CPayload.CODEC);

        // 客户端 -> 服务端: 修改全局开关(含上传配置) / 切换个人配置来源 / 请求消耗解锁
        PayloadTypeRegistry.playC2S().register(
                SetServerConfigStateC2SPayload.TYPE, SetServerConfigStateC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(
                SetClientConfigSourceC2SPayload.TYPE, SetClientConfigSourceC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(
                ConsumeUnlockC2SPayload.TYPE, ConsumeUnlockC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(
                ResetUnlocksC2SPayload.TYPE, ResetUnlocksC2SPayload.CODEC);
    }
}
