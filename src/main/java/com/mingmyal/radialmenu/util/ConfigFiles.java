package com.mingmyal.radialmenu.util;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigFiles {

    /** 写入临时文件的内容生产者; 由调用方决定是序列化对象还是写现成文本。 */
    @FunctionalInterface
    private interface ContentWriter {
        void write(Writer writer) throws IOException;
    }

    private ConfigFiles() {}

    /**
     * Writes the value as JSON to a sibling temp file, then moves it over the target so a crash mid-write can't
     * leave a truncated file behind.
     */
    public static void writeJsonAtomically(Path target, Gson gson, Object value) throws IOException {
        writeAtomically(target, writer -> gson.toJson(value, writer));
    }

    /**
     * 与 {@link #writeJsonAtomically} 同样的原子写入, 但内容已是序列化好的文本。
     * 服务端配置按原始 JSON 文本保存, 因此需要这个入口。
     */
    public static void writeTextAtomically(Path target, String text) throws IOException {
        writeAtomically(target, writer -> writer.write(text));
    }

    /**
     * 先写同级临时文件, 再整体替换目标文件。
     * <p>
     * 这样即使写到一半崩溃, 也只会留下一个临时文件, 不会把原配置截断成半份。
     */
    private static void writeAtomically(Path target, ContentWriter content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            content.write(writer);
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some file systems can't replace atomically; a plain replace still beats losing the save
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
