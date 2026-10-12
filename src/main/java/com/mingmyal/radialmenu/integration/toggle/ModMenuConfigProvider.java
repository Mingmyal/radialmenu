package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通过 ModMenu 打开任意模组的配置界面。
 * <p>
 * ModMenu 不提供"该模组是否有配置界面"的无副作用查询, 唯一的办法是调用
 * {@code ModMenu.getConfigScreen(modId, parent)} 并检查是否为 null ——
 * 它会真正构造界面, 且内部
 * ({@code factory.create(menuScreen)}) 未加保护, 可能抛出
 * {@code NoClassDefFoundError}。因此:
 * <ul>
 *   <li>调用点必须以 {@code isModLoaded("modmenu")} 保护;</li>
 *   <li>实际调用被隔离到本类(未安装 ModMenu 时本类不会被加载);</li>
 *   <li>所有异常一律捕获并视为"没有界面"。</li>
 * </ul>
 * 本提供者不发现开关, 只负责配置界面, 因此 {@link #discover} 为空实现。
 */
public class ModMenuConfigProvider implements ToggleSourceProvider {

    public static final String ID = "modmenu";

    private static final String MODMENU_CLASS = "com.terraformersmc.modmenu.ModMenu";

    /** 已确认可打开界面的模组 ID 缓存, 避免重复构造界面。 */
    private static final Set<String> KNOWN_SCREEN_MODS = new LinkedHashSet<>();

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.modmenu");
    }

    @Override
    public boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("modmenu");
    }

    /** 本提供者不贡献开关, 只提供配置界面。 */
    @Override
    public void discover(Consumer<ToggleSource> sink) {
        // 无开关可发现
    }

    /**
     * ModMenu 无法预先枚举有哪些模组带配置界面
     * (只能调用 {@code getConfigScreen} 并构造界面才知道), 因此这里直接声明具备该能力。
     */
    @Override
    public boolean canOpenConfigScreens() {
        return true;
    }

    /** 已缓存的、确认存在配置界面的模组。 */
    @Override
    public Collection<String> knownConfigScreenModIds() {
        synchronized (KNOWN_SCREEN_MODS) {
            return List.copyOf(KNOWN_SCREEN_MODS);
        }
    }

    /**
     * 构造指定模组的配置界面; 不存在时返回 null。
     * 必须在客户端渲染线程调用。
     */
    @Override
    public Screen createConfigScreen(String modId, Screen parent) {
        return buildConfigScreen(modId, parent);
    }

    /** 反射调用 {@code ModMenu.getConfigScreen(String, Screen)}; 完全隔离异常。 */
    private static Screen buildConfigScreen(String modId, Screen parent) {
        if (modId == null || modId.isBlank()) return null;
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) return null;

        try {
            Class<?> modMenu = ReflectUtil.loadClass(MODMENU_CLASS);
            if (modMenu == null) return null;

            java.lang.reflect.Method method = null;
            for (java.lang.reflect.Method candidate : modMenu.getMethods()) {
                if (candidate.getName().equals("getConfigScreen") && candidate.getParameterCount() == 2) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) return null;

            Object result = method.invoke(null, modId, parent);
            return result instanceof Screen screen ? screen : null;
        } catch (Throwable t) {
            // 目标模组的配置界面构造失败(常为缺依赖), 视为没有界面
            RadialCommon.LOGGER.warn("ModMenu could not build a config screen for '{}'", modId, t);
            return null;
        }
    }
}
