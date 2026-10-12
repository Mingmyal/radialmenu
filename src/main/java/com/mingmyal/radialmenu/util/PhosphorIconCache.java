package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.RadialCommon;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class PhosphorIconCache {

    public static final ResourceLocation FONT =
            ResourceLocation.fromNamespaceAndPath("radialmenu", "phosphor");

    private static final Gson GSON = new Gson();

    private static final Type ICON_LIST_TYPE = new TypeToken<List<PhosphorIcon>>() {}.getType();

    private static List<PhosphorIcon> cachedIcons;
    private static Map<String, PhosphorIcon> iconsByName;

    private PhosphorIconCache() {}

    public static List<PhosphorIcon> getIcons() {
        if (cachedIcons != null) {
            return cachedIcons;
        }

        cachedIcons = List.of();
        iconsByName = Map.of();

        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation identifier = ResourceLocation.fromNamespaceAndPath("radialmenu", "phosphor/icons.json");
        Optional<Resource> resource = manager.getResource(identifier);

        if (resource.isEmpty()) {
            RadialCommon.LOGGER.error("Phosphor icon catalog not found: {}", identifier);
            return cachedIcons;
        }

        try (Reader reader =
                new BufferedReader(new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8))) {
            List<PhosphorIcon> icons = GSON.fromJson(reader, ICON_LIST_TYPE);

            if (icons != null) {
                cachedIcons = List.copyOf(icons);

                Map<String, PhosphorIcon> byName = new HashMap<>(cachedIcons.size() * 2);
                for (PhosphorIcon icon : cachedIcons) {
                    byName.putIfAbsent(icon.name(), icon);
                }
                iconsByName = byName;
            }
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to load Phosphor icon catalog.", e);
        }

        return cachedIcons;
    }

    /**
     * Looks up an icon by name, or returns null if the catalog has no icon with that name.
     */
    public static PhosphorIcon getIcon(String name) {
        getIcons();
        return iconsByName.get(name);
    }

    public record PhosphorIcon(String name, int codepoint, List<String> tags) {

        public String searchText() {
            if (tags == null || tags.isEmpty()) {
                return name.toLowerCase();
            }

            return (name + " " + String.join(" ", tags)).toLowerCase();
        }

        public String character() {
            return new String(Character.toChars(codepoint));
        }
    }
}
