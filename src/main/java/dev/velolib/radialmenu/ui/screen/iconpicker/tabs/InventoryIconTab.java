package dev.velolib.radialmenu.ui.screen.iconpicker.tabs;

import com.mojang.blaze3d.platform.InputConstants;
import dev.velolib.radialmenu.render.SlotRenderHelper;
import dev.velolib.radialmenu.ui.screen.iconpicker.IconTab;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public class InventoryIconTab implements IconTab {

    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/gui/container/inventory.png");
    private static final int INV_WIDTH = 176;
    private static final int INV_HEIGHT = 166;
    private static final int INV_SLOT_SIZE = 18;

    private static final List<InvSlot> SLOTS = buildSlots();

    private final String currentId;
    private final Consumer<String> onSelect;
    private final Runnable onClose;
    private int screenWidth, screenHeight;

    public InventoryIconTab(String currentId, Consumer<String> onSelect, Runnable onClose) {
        this.currentId = currentId;
        this.onSelect = onSelect;
        this.onClose = onClose;
    }

    // Slot positions within the inventory texture, and the dynamic icon id each one maps to
    private record InvSlot(int x, int y, String iconId) {}

    private static List<InvSlot> buildSlots() {
        List<InvSlot> slots = new ArrayList<>();

        // Hotbar
        for (int i = 0; i < 9; i++) {
            slots.add(new InvSlot(7 + i * 18, 141, SlotRenderHelper.DYNAMIC_SLOT_PREFIX + "hotbar." + i));
        }

        // Main Inventory
        for (int i = 0; i < 27; i++) {
            slots.add(new InvSlot(
                    7 + (i % 9) * 18, 83 + (i / 9) * 18, SlotRenderHelper.DYNAMIC_SLOT_PREFIX + "inventory." + i));
        }

        // Armor
        String[] armorNames = {"head", "chest", "legs", "feet"};
        for (int i = 0; i < armorNames.length; i++) {
            slots.add(new InvSlot(7, 7 + i * 18, SlotRenderHelper.DYNAMIC_SLOT_PREFIX + "armor." + armorNames[i]));
        }

        // Offhand
        slots.add(new InvSlot(76, 61, SlotRenderHelper.DYNAMIC_SLOT_PREFIX + "offhand"));

        return List.copyOf(slots);
    }

    @Override
    public Component getTitle() {
        return Component.translatable("screen.radialmenu.editor.icon_picker.inventory");
    }

    @Override
    public boolean accepts(String iconId) {
        return iconId.startsWith(SlotRenderHelper.DYNAMIC_SLOT_PREFIX);
    }

    @Override
    public boolean showSearchBar() {
        return false;
    }

    @Override
    public void updateSearch(String query) {}

    @Override
    public void setup(int width, int height, Consumer<Renderable> addRenderable, Consumer<GuiEventListener> addWidget) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    private int backgroundX() {
        return screenWidth / 2 - INV_WIDTH / 2;
    }

    private int backgroundY() {
        return screenHeight / 2 - INV_HEIGHT / 2 + 10;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int bgX = backgroundX();
        int bgY = backgroundY();

        Component infoText = Component.translatable("screen.radialmenu.editor.icon_picker.inventory.info");
        graphics.drawString(mc.font, infoText, screenWidth / 2 - mc.font.width(infoText) / 2, bgY - 15, 0xFFAAAAAA);
        graphics.blit(
                INVENTORY_TEXTURE,
                bgX,
                bgY,
                0,
                0,
                INV_WIDTH,
                INV_HEIGHT,
                256,
                256,
                0xFFFFFFFF);

        for (InvSlot slot : SLOTS) {
            int x = bgX + slot.x();
            int y = bgY + slot.y();
            ItemStack stack = SlotRenderHelper.resolveDynamicItem(slot.iconId());

            if (stack != null && !stack.isEmpty()) {
                graphics.renderFakeItem(stack, x + 1, y + 1);
            }

            if (slot.iconId().equals(currentId)) {
                // FIXME(1.21.1): GuiGraphics.outline replaced with manual border fills
                graphics.fill(x, y, x + INV_SLOT_SIZE, y + 1, 0xFFFFAA00);
                graphics.fill(x, y + INV_SLOT_SIZE - 1, x + INV_SLOT_SIZE, y + INV_SLOT_SIZE, 0xFFFFAA00);
                graphics.fill(x, y, x + 1, y + INV_SLOT_SIZE, 0xFFFFAA00);
                graphics.fill(x + INV_SLOT_SIZE - 1, y, x + INV_SLOT_SIZE, y + INV_SLOT_SIZE, 0xFFFFAA00);
            }

            if (isHovered(mouseX, mouseY, x, y)) {
                graphics.fill(x, y, x + INV_SLOT_SIZE, y + INV_SLOT_SIZE, 0x40FFFFFF);
                if (stack != null && !stack.isEmpty()) {
                    graphics.renderTooltip(mc.font, stack, mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;

        int bgX = backgroundX();
        int bgY = backgroundY();

        for (InvSlot slot : SLOTS) {
            if (isHovered(mouseX, mouseY, bgX + slot.x(), bgY + slot.y())) {
                onSelect.accept(slot.iconId());
                onClose.run();
                return true;
            }
        }

        return false;
    }

    private boolean isHovered(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + INV_SLOT_SIZE && mouseY >= y && mouseY < y + INV_SLOT_SIZE;
    }
}
