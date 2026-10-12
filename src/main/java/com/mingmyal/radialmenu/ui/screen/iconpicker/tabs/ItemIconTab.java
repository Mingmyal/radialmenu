package com.mingmyal.radialmenu.ui.screen.iconpicker.tabs;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.ui.screen.iconpicker.GridIconTab;
import com.mingmyal.radialmenu.ui.screen.iconpicker.IconSearch;
import com.mingmyal.radialmenu.util.EncoderUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ItemIconTab extends GridIconTab<ItemIconTab.ItemSearchEntry> {

    private static List<ItemSearchEntry> ITEM_INDEX;

    // The registries the index was built against; a new world or server means different variants
    private static HolderLookup.Provider indexedRegistries;

    private String lastQuery = "";
    private List<ItemSearchEntry> lastResults = new ArrayList<>();

    public ItemIconTab(String currentId, Consumer<String> onSelect, Runnable onClose) {
        super(currentId, onSelect, onClose);
        ensureItemIndex();
    }

    @Override
    public Component getTitle() {
        return Component.translatable("screen.radialmenu.editor.icon_picker.items");
    }

    @Override
    public boolean accepts(String iconId) {
        return !iconId.startsWith("radialmenu:");
    }

    @Override
    protected List<ItemSearchEntry> search(String query) {
        if (query.isEmpty()) {
            lastQuery = "";
            lastResults = ITEM_INDEX;
            return ITEM_INDEX;
        }

        List<ItemSearchEntry> source = !lastQuery.isEmpty() && query.startsWith(lastQuery) ? lastResults : ITEM_INDEX;

        List<ItemSearchEntry> results = new ArrayList<>();

        for (ItemSearchEntry entry : source) {
            if (entry.searchText().contains(query)) {
                results.add(entry);
            }
        }

        // Narrowing keeps index order; ranking happens on a separate copy so ties stay in index order
        lastQuery = query;
        lastResults = results;

        return IconSearch.rank(results, query, ItemSearchEntry::lowerName, ItemSearchEntry::searchText);
    }

    @Override
    protected void renderIcon(
            GuiGraphics graphics,
            int x,
            int y,
            int mouseX,
            int mouseY,
            ItemSearchEntry item,
            boolean hovered) {

        graphics.renderFakeItem(item.stack(), x + 2, y + 2);

        if (hovered) {
            graphics.renderTooltip(Minecraft.getInstance().font, item.stack(), mouseX, mouseY);
        }
    }

    @Override
    protected String getIconId(ItemSearchEntry item) {
        return item.id();
    }

    @Override
    protected Component getItemNarration(ItemSearchEntry item) {
        return item.stack().getHoverName();
    }

    private static void ensureItemIndex() {
        Minecraft client = Minecraft.getInstance();
        HolderLookup.Provider registries = client.level != null ? client.level.registryAccess() : null;

        if (ITEM_INDEX != null && indexedRegistries == registries) return;

        Map<String, ItemSearchEntry> index = new LinkedHashMap<>();

        // Creative search tab contents include variants such as potions, enchanted books and goat horns
        if (registries != null && client.player != null) {
            for (ItemStack stack : getCreativeSearchItems(client, client.player, registries)) {
                addEntry(index, stack, encodeId(stack, registries));
            }
        }

        // Anything the creative tabs leave out (air, operator items without permission, unlisted mod items)
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!index.containsKey(id)) {
                addEntry(index, item.getDefaultInstance(), id);
            }
        }

        ITEM_INDEX = List.copyOf(index.values());
        indexedRegistries = registries;
    }

    // FIXME(1.21.1): SessionSearchTrees / CreativeModeTabs.tryRebuildTabContents creative search-tree API
    // differs from 26.3; verify against 1.21.1 vanilla before relying on tooltip/tag refresh here.
    private static List<ItemStack> getCreativeSearchItems(
            Minecraft client, LocalPlayer player, HolderLookup.Provider registries) {
        try {
            boolean hasPermissions = player.canUseGameMasterBlocks()
                    && client.options.operatorItemsTab().get();

            if (CreativeModeTabs.tryRebuildTabContents(
                    player.connection.enabledFeatures(), hasPermissions, registries)) {
                // The creative screen only refreshes its search trees when it performs the rebuild itself,
                // so do the same here or its search would keep using stale contents
                List<ItemStack> searchItems =
                        List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
                SessionSearchTrees searchTrees = player.connection.searchTrees();
                searchTrees.updateCreativeTooltips(registries, searchItems);
                searchTrees.updateCreativeTags(searchItems);
            }

            return List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
        } catch (Exception e) {
            RadialCommon.LOGGER.warn("Failed to read creative tab items, listing default items only", e);
            return List.of();
        }
    }

    private static String encodeId(ItemStack stack, HolderLookup.Provider registries) {
        try {
            return EncoderUtils.toGiveCommandString(stack, registries);
        } catch (Exception e) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
    }

    private static void addEntry(Map<String, ItemSearchEntry> index, ItemStack stack, String id) {
        String lowerName = stack.getHoverName().getString().toLowerCase();
        index.putIfAbsent(id, new ItemSearchEntry(stack, id, lowerName, (id + " " + lowerName).toLowerCase()));
    }

    public record ItemSearchEntry(ItemStack stack, String id, String lowerName, String searchText) {}
}
