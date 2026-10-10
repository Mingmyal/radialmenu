package dev.velolib.radialmenu.ui.screen.iconpicker.tabs;

import dev.velolib.radialmenu.render.SlotRenderHelper;
import dev.velolib.radialmenu.ui.screen.iconpicker.GridIconTab;
import dev.velolib.radialmenu.ui.screen.iconpicker.IconSearch;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class EffectIconTab extends GridIconTab<EffectIconTab.EffectEntry> {

    // Built per picker so the translated names follow the current language
    private final List<EffectEntry> effects = BuiltInRegistries.MOB_EFFECT.stream()
            .map(effect -> {
                ResourceLocation id = Objects.requireNonNull(BuiltInRegistries.MOB_EFFECT.getKey(effect));
                Component name = Component.translatable(effect.getDescriptionId());
                String lowerName = name.getString().toLowerCase();

                return new EffectEntry(id, name, lowerName, lowerName + " " + id);
            })
            .toList();

    public EffectIconTab(String currentId, Consumer<String> onSelect, Runnable onClose) {
        super(currentId, onSelect, onClose);
    }

    @Override
    public Component getTitle() {
        return Component.translatable("screen.radialmenu.editor.icon_picker.effects");
    }

    @Override
    public boolean accepts(String iconId) {
        return iconId.startsWith(SlotRenderHelper.EFFECT_PREFIX);
    }

    @Override
    protected List<EffectEntry> search(String query) {
        return IconSearch.rank(effects, query, EffectEntry::lowerName, EffectEntry::searchText);
    }

    @Override
    protected void renderIcon(
            GuiGraphics graphics, int x, int y, int mouseX, int mouseY, EffectEntry effect, boolean hovered) {
        SlotRenderHelper.renderIcon(graphics, getIconId(effect), x, y, getSlotSize(), 0xFFFFFFFF, () -> null);

        if (hovered) {
            graphics.renderTooltip(Minecraft.getInstance().font, effect.name(), mouseX, mouseY);
        }
    }

    @Override
    protected String getIconId(EffectEntry effect) {
        return SlotRenderHelper.EFFECT_PREFIX + effect.id();
    }

    @Override
    protected Component getItemNarration(EffectEntry effect) {
        return effect.name();
    }

    public record EffectEntry(ResourceLocation id, Component name, String lowerName, String searchText) {}
}
