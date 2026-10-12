package com.mingmyal.radialmenu.config.adapters;

import com.mingmyal.radialmenu.api.SlotMode;
import com.mingmyal.radialmenu.api.SlotModeRegistry;

import com.google.gson.*;
import java.lang.reflect.Type;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public class SlotModeTypeAdapter implements JsonSerializer<SlotMode>, JsonDeserializer<SlotMode> {

    @Override
    public SlotMode deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        String idString = json.getAsString();

        // --- ON-THE-FLY MIGRATION ---
        // If the string doesn't have a colon (e.g., "EMPTY", "KEYBIND"), it's from v1.
        // We automatically convert it to the v2 namespaced format ("radialmenu:empty").
        if (!idString.contains(":")) {
            idString = "radialmenu:" + idString.toLowerCase();
        }

        // Parse the identifier safely
        ResourceLocation id = ResourceLocation.tryParse(idString);
        if (id != null) {
            SlotMode mode = SlotModeRegistry.getRegisteredModes().get(id);
            if (mode != null) {
                return mode;
            }
        }

        // If the mode doesn't exist (e.g., they uninstalled an addon mod), fallback safely
        return SlotModeRegistry.getDefaultMode();
    }

    @Override
    public JsonElement serialize(SlotMode src, Type typeOfSrc, JsonSerializationContext context) {
        // Reverse lookup: Find the ResourceLocation for the given SlotMode instance
        for (Map.Entry<ResourceLocation, SlotMode> entry :
                SlotModeRegistry.getRegisteredModes().entrySet()) {
            // We check by class type to ensure we match the right mode safely
            if (entry.getValue().getClass() == src.getClass()) {
                return new JsonPrimitive(entry.getKey().toString());
            }
        }

        // Fallback if something went catastrophically wrong
        return new JsonPrimitive("radialmenu:empty");
    }
}
