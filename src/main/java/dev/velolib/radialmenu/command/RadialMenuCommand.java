package dev.velolib.radialmenu.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.velolib.radialmenu.config.RadialConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * 客户端命令: /radialmenu size <值> 调节轮盘尺寸 (YACL 未安装时的替代方案)
 */
public class RadialMenuCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("radialmenu")
                .then(ClientCommandManager.literal("size")
                        .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.5f, 3.0f))
                                .executes(ctx -> {
                                    float value = FloatArgumentType.getFloat(ctx, "value");
                                    RadialConfig.INSTANCE.radialSize = value;
                                    RadialConfig.save();
                                    ctx.getSource().sendFeedback(
                                            Component.literal("轮盘尺寸已设置为: " + value));
                                    return 1;
                                })))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(
                            Component.literal("当前轮盘尺寸: " + RadialConfig.INSTANCE.radialSize
                                    + " (范围 0.5 - 3.0)"));
                    return 1;
                }));
    }
}
