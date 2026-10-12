package com.mingmyal.radialmenu.api.toggle;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 一组 {@link ToggleSource} 的发现器, 通常对应一个配置库(MaLiLib / Cloth / YACL ...)。
 * <p>
 * {@link #discover} 允许做反射等相对昂贵的操作, 它只在
 * {@link ToggleSourceRegistry#refresh()} 时被调用, 不在渲染循环中调用。
 */
public interface ToggleSourceProvider {

    /** 提供者标识(稳定, 用于日志与分组)。 */
    String providerId();

    /** 展示名, 用于选择界面的分组标题。 */
    Component displayName();

    /** 依赖的配置库是否已加载。 */
    boolean isAvailable();

    /**
     * 发现全部开关并交给 {@code sink}。
     * 实现必须自行捕获所有异常: 单个模组的配置损坏不应影响其它模组。
     */
    void discover(Consumer<ToggleSource> sink);

    /**
     * 该提供者已知能打开配置界面的模组 ID; 空集合表示无法预先枚举(或没有该能力)。
     * 仅用于界面提示, 不保证一定存在配置界面。
     */
    default Collection<String> knownConfigScreenModIds() {
        return List.of();
    }

    /**
     * 该提供者是否具备按模组 ID 打开配置界面的能力。
     * <p>
     * 用于判断"打开模组设置界面"动作是否应该出现: 部分提供者(如 ModMenu)
     * 无法预先枚举有哪些界面, 只能在调用 {@link #createConfigScreen} 时才知道,
     * 因此不能只依赖 {@link #knownConfigScreenModIds()} 是否为空。
     */
    default boolean canOpenConfigScreens() {
        return false;
    }

    /**
     * 为该模组创建配置界面; 不支持或不存在时返回 null。
     * 必须在客户端渲染线程调用。
     */
    default Screen createConfigScreen(String modId, Screen parent) {
        return null;
    }
}
