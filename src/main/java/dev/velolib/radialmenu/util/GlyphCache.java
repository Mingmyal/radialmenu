package dev.velolib.radialmenu.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.velolib.radialmenu.RadialClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

public final class GlyphCache {

    private static List<Glyph> cachedGlyphs;

    private GlyphCache() {}

    public static List<Glyph> getGlyphs() {
        if (cachedGlyphs != null) {
            return cachedGlyphs;
        }

        // Keeps the font's order while skipping characters that appear in several providers
        Set<Integer> codePoints = new LinkedHashSet<>();

        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation targetFont = ResourceLocation.fromNamespaceAndPath("minecraft", "font/include/default.json");
        Optional<Resource> resourceOpt = manager.getResource(targetFont);

        if (resourceOpt.isEmpty()) {
            targetFont = ResourceLocation.fromNamespaceAndPath("minecraft", "font/default.json");
            resourceOpt = manager.getResource(targetFont);
        }

        if (resourceOpt.isPresent()) {
            try (Reader reader =
                    new BufferedReader(new InputStreamReader(resourceOpt.get().open(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray providers = json.getAsJsonArray("providers");

                for (int i = 0; i < providers.size(); i++) {
                    JsonObject provider = providers.get(i).getAsJsonObject();

                    if (!provider.has("type")
                            || !provider.get("type").getAsString().equals("bitmap")) {
                        continue;
                    }

                    JsonArray chars = provider.getAsJsonArray("chars");

                    for (int j = 0; j < chars.size(); j++) {
                        // Code points rather than chars, so characters outside the BMP aren't split in half
                        chars.get(j).getAsString().codePoints().forEach(cp -> {
                            if (cp != 0 && cp != ' ') {
                                codePoints.add(cp);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                RadialClient.LOGGER.error("Failed to parse dynamic glyphs.", e);
            }
        }

        if (codePoints.isEmpty()) {
            RadialClient.LOGGER.error("Glyph cache parsed empty, using fallback list.");

            "★☆♥♦♣♠☠☢☣⚠⚡↑↓←→↕↔⟳✖✔⚙⌂✉☺☻☼♀♂♪♫►◄⛄⛏".codePoints().forEach(codePoints::add);
        }

        cachedGlyphs = codePoints.stream().map(Glyph::of).toList();

        return cachedGlyphs;
    }

    /**
     * @param name the Unicode character name, or null for code points without one (e.g. private use)
     * @param hex  the code point formatted as U+XXXX
     */
    public record Glyph(String character, String name, String hex, String searchText) {

        private static Glyph of(int codePoint) {
            String hex = String.format("U+%04X", codePoint);
            String name = Character.getName(codePoint);

            return new Glyph(
                    Character.toString(codePoint),
                    name,
                    hex,
                    ((name != null ? name + " " : "") + hex + " " + Integer.toHexString(codePoint))
                            .toLowerCase(Locale.ROOT));
        }

        public String displayName() {
            return name != null ? name : hex;
        }
    }
}
