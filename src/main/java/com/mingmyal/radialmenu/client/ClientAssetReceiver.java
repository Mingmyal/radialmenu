package com.mingmyal.radialmenu.client;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.assets.AssetBundle;

import java.io.ByteArrayOutputStream;

/**
 * 客户端接收"随配置下发的导入资源"。
 * <p>
 * 服务端把资源整包发出(总量超过单包上限时按 {@code part/parts} 拆开), 这里负责
 * 拼回来、解码、落盘。收齐之前不做任何写入, 避免半份资源覆盖掉本地好用的文件。
 * <p>
 * 落盘后的效果: 图标与音频出现在玩家自己的 {@code config/radialmenu/icons|sounds/},
 * 与手工导入的文件完全等价 —— 因此渲染与播放逻辑一行都不用改。
 */
public final class ClientAssetReceiver {

    /** 一次下发最多这么多个分段(与服务端的发送逻辑对称)。 */
    private static final int MAX_PARTS = 32;

    private ClientAssetReceiver() {}

    /** 正在拼接的一批。 */
    private static byte[][] chunks;

    /** 本批的总段数; 0 表示当前没有在收。 */
    private static int total;

    /** 处理一个下发分段。 */
    public static synchronized void onChunk(byte[] data, int part, int parts) {
        // 空包是服务端表示"这份配置没有资源"的显式信号: 直接当作收齐
        if (parts <= 1) {
            if (data == null || data.length == 0) {
                RadialCommon.LOGGER.info("Server reported no imported assets for this config");
                reset();
                return;
            }
            apply(AssetBundle.decode(data));
            return;
        }

        if (parts > MAX_PARTS || part < 0 || part >= parts) {
            RadialCommon.LOGGER.warn(
                    "Ignoring malformed asset sync chunk {}/{}", part, parts);
            return;
        }

        if (chunks == null || total != parts) {
            // 新的一批(或上一批没收到完整就来了新的): 重新开始
            chunks = new byte[parts][];
            total = parts;
        }

        if (chunks[part] == null) {
            chunks[part] = data == null ? new byte[0] : data;
        }

        if (!complete()) {
            return;
        }

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            joined.write(chunk, 0, chunk.length);
        }
        reset();
        apply(AssetBundle.decode(joined.toByteArray()));
    }

    private static boolean complete() {
        for (byte[] chunk : chunks) {
            if (chunk == null) {
                return false;
            }
        }
        return true;
    }

    private static void apply(AssetBundle bundle) {
        if (bundle == null) {
            RadialCommon.LOGGER.warn("Server sent a malformed radial asset bundle; keeping local files");
            return;
        }
        if (bundle.isEmpty()) {
            return;
        }
        ClientAssetSync.applyFromServer(bundle);
    }

    /** 断线清空, 避免把上个服务器的半份资源拼到这次的批里。 */
    public static synchronized void reset() {
        chunks = null;
        total = 0;
    }
}
