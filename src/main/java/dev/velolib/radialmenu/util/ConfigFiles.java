package dev.velolib.radialmenu.util;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigFiles {

    private ConfigFiles() {}

    /**
     * Writes the value as JSON to a sibling temp file, then moves it over the target so a crash mid-write can't
     * leave a truncated file behind.
     */
    public static void writeJsonAtomically(Path target, Gson gson, Object value) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            gson.toJson(value, writer);
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some file systems can't replace atomically; a plain replace still beats losing the save
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
