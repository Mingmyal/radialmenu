package com.mingmyal.radialmenu.render;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.util.ImportedImageStore;
import com.mingmyal.radialmenu.util.PhosphorIconCache;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.StringReader;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class SlotRenderHelper {

    public static final ResourceLocation SLOT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "gamemode_switcher/slot");

    public static final String PHOSPHOR_PREFIX = "radialmenu:icon.";
    public static final String GLYPH_PREFIX = "radialmenu:glyph.";
    public static final String EFFECT_PREFIX = "radialmenu:effect.";
    public static final String DYNAMIC_SLOT_PREFIX = "radialmenu:slot.";

    private static final int SLOT_ICON_SIZE = 26;

    private static final Pattern DYNAMIC_SLOT_PATTERN = Pattern.compile(
            "radialmenu:slot\\.(hotbar\\.[0-8]|inventory\\.(1?[0-9]|2[0-6])|armor\\.(head|chest|legs|feet)|offhand)");

    private SlotRenderHelper() {}

    public static ItemStack resolveDynamicItem(String itemId) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return null;
        }

        if (itemId != null && itemId.startsWith(DYNAMIC_SLOT_PREFIX)) {
            String[] parts = itemId.split("\\.");

            if (parts.length >= 2) {
                String type = parts[1];
                Inventory inv = minecraft.player.getInventory();

                try {
                    switch (type) {
                        case "hotbar":
                            if (parts.length >= 3) {
                                int hbIndex = Integer.parseInt(parts[2]);
                                if (hbIndex >= 0 && hbIndex < 9) {
                                    return inv.items.get(hbIndex);
                                }
                            }
                            break;

                        case "inventory":
                            if (parts.length >= 3) {
                                int invIndex = Integer.parseInt(parts[2]);
                                if (invIndex >= 0 && invIndex < 27) {
                                    return inv.items.get(invIndex + 9);
                                }
                            }
                            break;

                        case "armor":
                            if (parts.length >= 3) {
                                switch (parts[2]) {
                                    case "head":
                                        return minecraft.player.getItemBySlot(EquipmentSlot.HEAD);
                                    case "chest":
                                        return minecraft.player.getItemBySlot(EquipmentSlot.CHEST);
                                    case "legs":
                                        return minecraft.player.getItemBySlot(EquipmentSlot.LEGS);
                                    case "feet":
                                        return minecraft.player.getItemBySlot(EquipmentSlot.FEET);
                                }
                            }
                            break;

                        case "offhand":
                            return minecraft.player.getOffhandItem();
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            return ItemStack.EMPTY;
        }

        return null;
    }

    /**
     * Parses an item id with optional components (the /give format), or returns a barrier named
     * "invalid id" if it can't be parsed.
     */
    public static ItemStack parseItemStack(String itemId) {
        try {
            Minecraft client = Minecraft.getInstance();

            if (client.level == null) {
                return new ItemStack(Items.AIR);
            }

            // FIXME(1.21.1): ItemParser constructor + ItemResult shape — verify against actual
            // 1.21.1 Mojang mappings. 1.21.1 ItemParser(RegistryAccess)#parse(StringReader)
            // should return ItemParser.ItemResult { item(), components() }.
            ItemParser.ItemResult result =
                    new ItemParser(client.level.registryAccess()).parse(new StringReader(itemId));

            ItemStack stack = new ItemStack(result.item(), 1);
            stack.applyComponentsAndValidate(result.components());
            return stack;
        } catch (Exception e) {
            ItemStack barrier = new ItemStack(Items.BARRIER);
            barrier.set(DataComponents.CUSTOM_NAME, Component.translatable("radialmenu.item.invalid_id"));
            return barrier;
        }
    }

    /**
     * Whether the icon is drawn as text and can therefore take a tint color.
     */
    public static boolean isTintable(String iconId) {
        return iconId != null && (iconId.startsWith(PHOSPHOR_PREFIX) || iconId.startsWith(GLYPH_PREFIX));
    }

    public static boolean isValidIconId(String iconId) {
        if (iconId == null || iconId.isBlank()) {
            return false;
        }

        if (ImportedImageStore.isImportedImage(iconId)) {
            return ImportedImageStore.getTexture(iconId) != null;
        }

        if (iconId.startsWith(PHOSPHOR_PREFIX)) {
            return PhosphorIconCache.getIcon(iconId.substring(PHOSPHOR_PREFIX.length())) != null;
        }

        if (iconId.startsWith(GLYPH_PREFIX)) {
            return iconId.length() > GLYPH_PREFIX.length();
        }

        if (iconId.startsWith(EFFECT_PREFIX)) {
            ResourceLocation id = ResourceLocation.tryParse(iconId.substring(EFFECT_PREFIX.length()));
            return id != null && BuiltInRegistries.MOB_EFFECT.containsKey(id);
        }

        if (iconId.startsWith(DYNAMIC_SLOT_PREFIX)) {
            return DYNAMIC_SLOT_PATTERN.matcher(iconId).matches();
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            // Components can't be checked without registries, so don't flag anything
            return true;
        }

        try {
            // FIXME(1.21.1): see parseItemStack — verify ItemParser(RegistryAccess)#parse shape.
            new ItemParser(client.level.registryAccess()).parse(new StringReader(iconId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void renderSlotIcon(GuiGraphics graphics, RadialSlot slot, float x, float y, int alpha) {
        renderSlotIcon(graphics, slot, x, y, alpha, 1.0F);
    }

    /**
     * @param itemDim 物品类图标的压暗系数(0-1)。renderFakeItem 无法只对物品纹理使用 alpha,
     *                这里通过 shader color 等比压暗物品像素 RGB, 透明纹素保持透明, 不会出现色块。
     */
    public static void renderSlotIcon(GuiGraphics graphics, RadialSlot slot, float x, float y, int alpha, float itemDim) {
        renderSlotIcon(graphics, slot, x, y, alpha, itemDim, 1.0F, 1.0F, 1.0F);
    }

    /**
     * @param itemDim    物品类图标的压暗系数(0-1), 1 为正常亮度
     * @param tintR/G/B  额外的 RGB 乘法色调(0-1), 与 itemDim 逐通道相乘后作用于图标像素;
     *                   用于开关类型的红/绿状态色调, 1 为不调整
     */
    public static void renderSlotIcon(GuiGraphics graphics, RadialSlot slot, float x, float y, int alpha, float itemDim,
                                      float tintR, float tintG, float tintB) {
        if (slot == null || slot.itemId == null || !slot.mode.shouldRenderIcon()) {
            return;
        }

        int tint = isTintable(slot.itemId) ? slot.getIconColor() : 0xFFFFFF;
        // 文本类图标(Phosphor/字形)按纯色绘制, 色调直接乘进颜色; 其余类型经 shader color 逐通道相乘
        int cr = Math.round(((tint >> 16) & 0xFF) * clampChannel(tintR));
        int cg = Math.round(((tint >> 8) & 0xFF) * clampChannel(tintG));
        int cb = Math.round((tint & 0xFF) * clampChannel(tintB));
        int combined = (cr << 16) | (cg << 8) | cb;

        renderIcon(
                graphics,
                slot.itemId,
                (int) x,
                (int) y,
                SLOT_ICON_SIZE,
                (alpha << 24) | combined,
                slot::getRenderStack,
                itemDim * clampChannel(tintR),
                itemDim * clampChannel(tintG),
                itemDim * clampChannel(tintB));
    }

    private static float clampChannel(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * Draws any icon id centered in a {@code size}x{@code size} box.
     *
     * @param argb      color for text-based and sprite icons; items ignore it since they can't be tinted
     * @param itemStack supplies the stack for plain item ids, letting callers cache the parsed stack
     * @param itemDim   物品类图标的 RGB 压暗系数(0-1), 1 为正常亮度
     */
    public static void renderIcon(
            GuiGraphics graphics,
            String iconId,
            int x,
            int y,
            int size,
            int argb,
            Supplier<ItemStack> itemStack,
            float itemDim) {
        renderIcon(graphics, iconId, x, y, size, argb, itemStack, itemDim, itemDim, itemDim);
    }

    /**
     * Draws any icon id centered in a {@code size}x{@code size} box.
     *
     * @param argb      color for text-based and sprite icons; items ignore it since they can't be tinted
     * @param itemStack supplies the stack for plain item ids, letting callers cache the parsed stack
     * @param dimR/G/B  物品类/图片类图标的逐通道 RGB 乘法系数(0-1), 1 为不调整
     */
    public static void renderIcon(
            GuiGraphics graphics,
            String iconId,
            int x,
            int y,
            int size,
            int argb,
            Supplier<ItemStack> itemStack,
            float dimR,
            float dimG,
            float dimB) {
        Minecraft client = Minecraft.getInstance();

        // --- Imported Image (导入图片, 黑色背景已转透明) ---
        if (ImportedImageStore.isImportedImage(iconId)) {
            ResourceLocation tex = ImportedImageStore.getTexture(iconId);
            if (tex != null) {
                int alpha = (argb >> 24) & 0xFF;
                RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), alpha / 255.0f);
                graphics.blit(tex, x, y, 0, 0, size, size, size, size);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
            return;
        }

        // --- Phosphor Icon ---
        if (iconId.startsWith(PHOSPHOR_PREFIX)) {
            PhosphorIconCache.PhosphorIcon icon = PhosphorIconCache.getIcon(iconId.substring(PHOSPHOR_PREFIX.length()));
            if (icon == null) {
                return;
            }

            Component component =
                    Component.literal(icon.character()).setStyle(Style.EMPTY.withFont(PhosphorIconCache.FONT));

            // The Phosphor font's glyphs are centered on the baseline, so the box center is the text origin
            int textX = x + Math.round((size - client.font.width(component)) / 2.0F);
            graphics.drawString(client.font, component, textX, y + size / 2, argb, false);
            return;
        }

        // --- Glyph ---
        if (iconId.startsWith(GLYPH_PREFIX)) {
            String glyph = iconId.substring(GLYPH_PREFIX.length());

            int textX = x + Math.round((size - client.font.width(glyph)) / 2.0F);
            int textY = y + Math.round((size - 8) / 2.0F);
            graphics.drawString(client.font, glyph, textX, textY, argb);
            return;
        }

        // --- Effect ---
        if (iconId.startsWith(EFFECT_PREFIX)) {
            ResourceLocation id = ResourceLocation.tryParse(iconId.substring(EFFECT_PREFIX.length()));
            if (id == null) {
                return;
            }

            // 1.21.1 的药水效果图标位于独立的 mob_effects 图集(MobEffectTextureManager),
            // 不在 GUI sprite 图集中, blitSprite 只会画出缺失纹理。
            // 与原版 Gui#renderEffects 一致: 取图集 sprite 后 blit(x, y, blitOffset, 18, 18, sprite)。
            BuiltInRegistries.MOB_EFFECT.getHolder(id).ifPresent(holder -> {
                TextureAtlasSprite sprite = client.getMobEffectTextures().get(holder);

                // Drawn at the sprite's native 18px so it stays crisp
                int offset = (size - 18) / 2;
                RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), 1.0F);
                graphics.blit(x + offset, y + offset, 0, 18, 18, sprite);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            });
            return;
        }

        // --- Dynamic Slot / Item ---
        ItemStack stack = resolveDynamicItem(iconId);
        if (stack == null) {
            stack = itemStack.get();
        }

        if (stack != null && !stack.isEmpty()) {
            int offset = (size - 16) / 2;
            // shader color 只乘物品像素 RGB(透明纹素 alpha=0 不受影响), 压暗/染色而非覆盖色块
            RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), 1.0F);
            graphics.renderFakeItem(stack, x + offset, y + offset);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
