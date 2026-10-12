package com.mingmyal.radialmenu.integration.toggle;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.api.toggle.ToggleSourceProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 通过 MaLiLib(0.21.10+) 的配置界面注册表打开 masa 系模组的设置界面。
 * <p>
 * 与 {@link MaLiLibToggleProvider} 分开的原因:
 * <ul>
 *   <li>开关发现走的是"反射私有 configHandlers 字段", 两个版本都可行;</li>
 *   <li>但配置界面注册表 {@code Registry.CONFIG_SCREEN} <b>只在 0.21.10 及以后存在</b>,
 *       0.21.1 没有。放在同一个提供者里会让 {@code isAvailable()} 的语义变模糊。</li>
 * </ul>
 * 依赖 0.21.1 时本提供者自动不可用, 不影响开关发现功能。
 */
public class MaLiLibConfigScreenProvider implements ToggleSourceProvider {

    public static final String ID = "malilib_config";

    private static final String REGISTRY = "fi.dy.masa.malilib.registry.Registry";
    private static final String CONFIG_SCREEN_REGISTRY =
            "fi.dy.masa.malilib.gui.config.registry.ConfigScreenRegistry";
    private static final String MOD_INFO = "fi.dy.masa.malilib.util.data.ModInfo";

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public Component displayName() {
        return Component.translatable("radialmenu.toggle.provider.malilib_config");
    }

    @Override
    public boolean isAvailable() {
        if (!FabricLoader.getInstance().isModLoaded("malilib")) return false;
        // 0.21.1 没有这套注册表, 此时本提供者不可用
        return ReflectUtil.loadClass(REGISTRY) != null
                && ReflectUtil.loadClass(CONFIG_SCREEN_REGISTRY) != null
                && ReflectUtil.loadClass(MOD_INFO) != null;
    }

    /** 本提供者只负责界面, 不贡献开关。 */
    @Override
    public void discover(Consumer<ToggleSource> sink) {
        // 无开关可发现
    }

    @Override
    public boolean canOpenConfigScreens() {
        return !collectModInfos().isEmpty();
    }

    /** masa 系模组在注册表里注册过的模组 ID。 */
    @Override
    public Collection<String> knownConfigScreenModIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Object modInfo : collectModInfos()) {
            String id = modIdOf(modInfo);
            if (id != null && !id.isBlank()) ids.add(id);
        }
        return ids;
    }

    /**
     * 取注册表里的全部 ModInfo。
     * <p>
     * {@code getAllModsWithConfigScreens()} 返回 ImmutableList, 用反射调用避免编译期依赖
     * (0.21.1 上这些类根本不存在)。
     */
    private List<Object> collectModInfos() {
        try {
            Class<?> registryClass = ReflectUtil.loadClass(REGISTRY);
            if (registryClass == null) return List.of();

            // Registry.CONFIG_SCREEN 是 public static final 字段
            Field field = ReflectUtil.findField(registryClass, "CONFIG_SCREEN");
            Object configScreenRegistry = ReflectUtil.getField(field, null);
            if (configScreenRegistry == null) return List.of();

            Method getAll = ReflectUtil.findMethod(configScreenRegistry.getClass(), "getAllModsWithConfigScreens", 0);
            Object result = ReflectUtil.invoke(getAll, configScreenRegistry);

            if (result instanceof Iterable<?> iterable) {
                List<Object> out = new ArrayList<>();
                for (Object element : iterable) {
                    if (element != null) out.add(element);
                }
                return out;
            }
        } catch (Throwable t) {
            RadialCommon.LOGGER.debug("Could not read MaLiLib config screen registry", t);
        }
        return List.of();
    }

    private String modIdOf(Object modInfo) {
        Object id = ReflectUtil.invoke(ReflectUtil.findMethod(modInfo.getClass(), "getModId", 0), modInfo);
        return id instanceof String s ? s : null;
    }

    @Override
    public Screen createConfigScreen(String modId, Screen parent) {
        if (modId == null || modId.isBlank()) return null;

        for (Object modInfo : collectModInfos()) {
            if (!modId.equals(modIdOf(modInfo))) continue;

            try {
                Object supplier = ReflectUtil.invoke(
                        ReflectUtil.findMethod(modInfo.getClass(), "getConfigScreenSupplier", 0), modInfo);
                if (supplier instanceof Supplier<?> s) {
                    Object gui = s.get();
                    // GuiBase extends Screen, 因此可直接交给 MC
                    return gui instanceof Screen screen ? screen : null;
                }
            } catch (Throwable t) {
                RadialCommon.LOGGER.error("Failed to build MaLiLib config screen for '{}'", modId, t);
            }
            return null;
        }
        return null;
    }
}
