package dev.velolib.radialmenu.api;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.*;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class ShortcutRegistry {
    private static final Map<ResourceLocation, ShortcutEntry> REGISTRY = new LinkedHashMap<>();
    private static boolean initialized = false;

    public static void register(ResourceLocation id, ShortcutEntry shortcutEntry) {
        if (REGISTRY.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate registration for shortcut ID: " + id);
        }
        REGISTRY.put(id, shortcutEntry);
    }

    public static Map<ResourceLocation, ShortcutEntry> getRegisteredShortcuts() {
        return java.util.Collections.unmodifiableMap(REGISTRY);
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        // Main Options
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "options"),
                new ShortcutEntry(Component.translatable("menu.options"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new OptionsScreen(parent, client.options));
                }));

        // Video Settings
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "video_settings"),
                new ShortcutEntry(Component.translatable("options.videoTitle"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new VideoSettingsScreen(parent, client, client.options));
                }));

        // Audio Settings
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "sound_options"),
                new ShortcutEntry(Component.translatable("options.sounds.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new SoundOptionsScreen(parent, client.options));
                }));

        // Chat Settings
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "chat_options"),
                new ShortcutEntry(Component.translatable("options.chat.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ChatOptionsScreen(parent, client.options));
                }));

        // Accessibility Settings
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "accessibility_options"),
                new ShortcutEntry(Component.translatable("options.accessibility.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new AccessibilityOptionsScreen(parent, client.options));
                }));

        // Skin Customization
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "skin_customization"),
                new ShortcutEntry(Component.translatable("options.skinCustomisation.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new SkinCustomizationScreen(parent, client.options));
                }));

        // Main Controls Screen
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "controls"),
                new ShortcutEntry(Component.translatable("options.controls"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ControlsScreen(parent, client.options));
                }));

        // Keybinds specific screen
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "keybinds"),
                new ShortcutEntry(Component.translatable("controls.keybinds.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new KeyBindsScreen(parent, client.options));
                }));

        // Mouse Settings
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "mouse_settings"),
                new ShortcutEntry(Component.translatable("options.mouse_settings.title"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new MouseSettingsScreen(parent, client.options));
                }));

        // Language Select (Requires LanguageManager in constructor)
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "language"),
                new ShortcutEntry(Component.translatable("options.language"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new LanguageSelectScreen(parent, client.options, client.getLanguageManager()));
                }));

        // Online Options
        register(
                ResourceLocation.fromNamespaceAndPath("radialmenu", "online_options"),
                new ShortcutEntry(Component.translatable("options.online"), (parent) -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new OnlineOptionsScreen(parent, client.options));
                }));

        // Other Mods
        FabricLoader.getInstance()
                .getEntrypointContainers("radialmenu", RadialApiEntrypoint.class)
                .forEach(container -> container.getEntrypoint().registerShortcuts());
    }
}
