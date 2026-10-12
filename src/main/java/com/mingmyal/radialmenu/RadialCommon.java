package com.mingmyal.radialmenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双端共用的模组常量。
 * <p>
 * 特意不放在 {@link RadialClient} 中: 客户端入口类的静态初始化会注册 {@code KeyMapping},
 * 在专用服务端上加载该 class 会因客户端专属类缺失而失败。服务端代码一律引用本类。
 */
public final class RadialCommon {

    public static final String MOD_ID = "radialmenu";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 服务器相关操作所需的原版权限等级。
     * <p>
     * 本模组<b>不使用权限节点</b>, 只用原版 OP 等级判定, 这样不依赖任何权限模组、
     * 也不存在"权限节点缓存过期"之类的问题。等级 4 是原版默认的 OP 级别,
     * 若服务端改过 {@code op-permission-level}, 实际门槛会随之变化。
     */
    public static final int OP_PERMISSION_LEVEL = 4;

    private RadialCommon() {}
}
