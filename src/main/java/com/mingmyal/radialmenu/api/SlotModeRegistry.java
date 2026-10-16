package com.mingmyal.radialmenu.api;

import com.mingmyal.radialmenu.mode.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

public class SlotModeRegistry {
    private static final Map<ResourceLocation, SlotMode> REGISTRY = new LinkedHashMap<>();
    private static final ResourceLocation EMPTY_ID = ResourceLocation.fromNamespaceAndPath("radialmenu", "empty");
    private static boolean initialized = false;

    public static void register(ResourceLocation id, SlotMode mode) {
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate registration for mode ID: " + id);
        }
        REGISTRY.put(id, mode);
    }

    public static Map<ResourceLocation, SlotMode> getRegisteredModes() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        register(EMPTY_ID, new EmptySlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "delay"), new DelaySlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "chat"), new ChatSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "keybind"), new KeybindSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "shortcut"), new ShortcutSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "malilib"), new MalilibSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "modconfig"), new ModConfigSlotMode());
        // 触发外部模组开关切换(不读取状态; 唯一能承载"状态不可读"来源的动作)
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "externaltoggle"),
                new ExternalToggleSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "submenu"), new SubmenuSlotMode());
        register(ResourceLocation.fromNamespaceAndPath("radialmenu", "macro"), new MacroSlotMode());

        FabricLoader.getInstance()
                .getEntrypointContainers("radialmenu", RadialApiEntrypoint.class)
                .forEach(container -> container.getEntrypoint().registerSlotModes());
    }

    public static SlotMode getDefaultMode() {
        SlotMode mode = REGISTRY.get(EMPTY_ID);
        if (mode == null) {
            throw new IllegalStateException("Critical error: radial:empty mode was not registered!");
        }
        return mode;
    }
}
