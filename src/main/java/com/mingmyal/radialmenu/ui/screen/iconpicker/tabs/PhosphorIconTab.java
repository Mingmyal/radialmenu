package com.mingmyal.radialmenu.ui.screen.iconpicker.tabs;

import com.mingmyal.radialmenu.render.SlotRenderHelper;
import com.mingmyal.radialmenu.ui.screen.iconpicker.GridIconTab;
import com.mingmyal.radialmenu.ui.screen.iconpicker.IconSearch;
import com.mingmyal.radialmenu.util.PhosphorIconCache;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class PhosphorIconTab extends GridIconTab<PhosphorIconCache.PhosphorIcon> {

    public PhosphorIconTab(String currentId, Consumer<String> onSelect, Runnable onClose) {
        super(currentId, onSelect, onClose);
    }

    @Override
    public Component getTitle() {
        return Component.translatable("screen.radialmenu.editor.icon_picker.phosphor");
    }

    @Override
    public boolean accepts(String iconId) {
        return iconId.startsWith(SlotRenderHelper.PHOSPHOR_PREFIX);
    }

    @Override
    protected List<PhosphorIconCache.PhosphorIcon> search(String query) {
        return IconSearch.rank(
                PhosphorIconCache.getIcons(),
                query,
                icon -> icon.name().toLowerCase(),
                PhosphorIconCache.PhosphorIcon::searchText);
    }

    @Override
    protected void renderIcon(
            GuiGraphics graphics,
            int x,
            int y,
            int mouseX,
            int mouseY,
            PhosphorIconCache.PhosphorIcon icon,
            boolean hovered) {
        SlotRenderHelper.renderIcon(graphics, getIconId(icon), x, y, getSlotSize(), 0xFFFFFFFF, () -> null, 1.0F);

        if (hovered) {
            graphics.renderTooltip(
                    Minecraft.getInstance().font, Component.literal(icon.name()), mouseX, mouseY);
        }
    }

    @Override
    protected String getIconId(PhosphorIconCache.PhosphorIcon icon) {
        return SlotRenderHelper.PHOSPHOR_PREFIX + icon.name();
    }

    @Override
    protected Component getItemNarration(PhosphorIconCache.PhosphorIcon icon) {
        return Component.literal(icon.name());
    }
}
