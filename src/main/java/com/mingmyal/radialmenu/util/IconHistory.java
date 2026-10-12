package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.RadialCommon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Recently picked icon ids.
 *
 * <p>Kept out of radial.json on purpose: the slot editor mutates slots in place and only reverts them on cancel,
 * so saving the main config whenever an icon is picked would persist unsaved slot edits.
 */
public final class IconHistory {

    private static final int MAX_RECENT = 32;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("radial_icon_history.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Data data;

    private IconHistory() {}

    public static List<String> getRecent() {
        return List.copyOf(load().recent);
    }

    public static void recordUse(String id) {
        if (id == null || id.isBlank()) return;

        List<String> recent = load().recent;
        recent.remove(id);
        recent.addFirst(id);

        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }

        save();
    }

    private static Data load() {
        if (data != null) {
            return data;
        }

        data = new Data();

        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                Data loaded = GSON.fromJson(reader, Data.class);
                if (loaded != null) {
                    data.recent = sanitize(loaded.recent);
                }
            } catch (Exception e) {
                RadialCommon.LOGGER.error("Failed to load icon history, starting fresh.", e);
            }
        }

        while (data.recent.size() > MAX_RECENT) {
            data.recent.removeLast();
        }

        return data;
    }

    private static List<String> sanitize(List<String> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        ids.stream().filter(Objects::nonNull).filter(id -> !id.isBlank()).forEach(unique::add);

        return new ArrayList<>(unique);
    }

    private static void save() {
        try {
            ConfigFiles.writeJsonAtomically(FILE, GSON, data);
        } catch (Exception e) {
            RadialCommon.LOGGER.error("Failed to save icon history.", e);
        }
    }

    private static final class Data {
        List<String> recent = new ArrayList<>();
    }
}
