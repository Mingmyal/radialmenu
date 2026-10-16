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

    /** @return the default icon size (also used as fallback when a slot doesn't set custom dimensions). */
    public static int getDefaultSlotIconSize() { return SLOT_ICON_SIZE; }

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
                slot.getIconWidth(),
                slot.getIconHeight(),
                (alpha << 24) | combined,
                slot::getRenderStack,
                itemDim * clampChannel(tintR),
                itemDim * clampChannel(tintG),
                itemDim * clampChannel(tintB),
                slot.getIconSizeMode(),
                slot.hasCustomIconSize());
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
        renderIcon(graphics, iconId, x, y, size, size, argb, itemStack, dimR, dimG, dimB);
    }

    /**
     * Draws any icon id centered in a {@code width}x{@code height} box.
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
            int width,
            int height,
            int argb,
            Supplier<ItemStack> itemStack,
            float itemDim) {
        renderIcon(graphics, iconId, x, y, width, height, argb, itemStack, itemDim, itemDim, itemDim);
    }

    /**
     * Draws any icon id centered in a {@code width}x{@code height} box.
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
            int width,
            int height,
            int argb,
            Supplier<ItemStack> itemStack,
            float dimR,
            float dimG,
            float dimB) {
        // 通用重载不做自定义缩放: 物品/效果等按原生像素居中, 导入图片铺满整框
        renderIcon(
                graphics, iconId, x, y, width, height, argb, itemStack, dimR, dimG, dimB,
                RadialSlot.IconSizeMode.STRETCH, false);
    }

    /**
     * Draws any icon id in a {@code width}x{@code height} box, honouring the slot's size mode.
     *
     * @param argb        color for text-based and sprite icons; items ignore it since they can't be tinted
     * @param itemStack   supplies the stack for plain item ids, letting callers cache the parsed stack
     * @param dimR/G/B    物品类/图片类图标的逐通道 RGB 乘法系数(0-1), 1 为不调整
     * @param sizeMode    填充方式: {@code FIT} 等比缩放不变形, {@code STRETCH} 铺满整框
     * @param customSize  槽位是否手动填过长/宽。
     *                    未填写时保持原版观感 —— 物品/效果/字形按原生像素居中, 只有导入图片铺满整框;
     *                    填写后所有图标类型都按 {@code sizeMode} 缩放。
     */
    public static void renderIcon(
            GuiGraphics graphics,
            String iconId,
            int x,
            int y,
            int width,
            int height,
            int argb,
            Supplier<ItemStack> itemStack,
            float dimR,
            float dimG,
            float dimB,
            RadialSlot.IconSizeMode sizeMode,
            boolean customSize) {
        Minecraft client = Minecraft.getInstance();

        // --- Imported Image (导入图片, 黑色背景已转透明) ---
        if (ImportedImageStore.isImportedImage(iconId)) {
            ResourceLocation tex = ImportedImageStore.getTexture(iconId);
            if (tex != null) {
                int alpha = (argb >> 24) & 0xFF;
                // 图片始终按自定义尺寸绘制(未自定义时就是默认 26x26 框),
                // 自适应模式按原始宽高比居中缩放, 拉伸模式铺满整框
                int drawW = width;
                int drawH = height;
                if (sizeMode == RadialSlot.IconSizeMode.FIT) {
                    int[] size = ImportedImageStore.getImageSize(iconId);
                    if (size != null && size[0] > 0 && size[1] > 0) {
                        float scale = Math.min(width / (float) size[0], height / (float) size[1]);
                        drawW = Math.max(1, Math.round(size[0] * scale));
                        drawH = Math.max(1, Math.round(size[1] * scale));
                    }
                }

                int offsetX = x + (width - drawW) / 2;
                int offsetY = y + (height - drawH) / 2;

                RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), alpha / 255.0f);
                // uv 始终取满整张纹理, 由 drawW/drawH 决定实际显示尺寸
                graphics.blit(tex, offsetX, offsetY, 0, 0, drawW, drawH, drawW, drawH);
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

            int naturalW = Math.max(1, client.font.width(component));
            int naturalH = client.font.lineHeight;

            // 未自定义尺寸时保持原版: 字形按基线居中, 不缩放
            if (!customSize) {
                int textX = x + Math.round((width - naturalW) / 2.0F);
                graphics.drawString(client.font, component, textX, y + height / 2, argb, false);
                return;
            }

            int[] box = resolveBox(x, y, naturalW, naturalH, width, height, sizeMode);
            var pose = graphics.pose();
            pose.pushPose();
            // Phosphor 字形的绘制原点就是字形垂直中心, 所以平移到框中心再缩放绘制,
            // 与未缩放分支的 y + height / 2 完全等价
            pose.translate(box[0], box[1] + box[3] / 2.0F, 0.0F);
            pose.scale(box[2] / (float) naturalW, box[3] / (float) naturalH, 1.0F);
            graphics.drawString(client.font, component, 0, 0, argb, false);
            pose.popPose();
            return;
        }

        // --- Glyph ---
        if (iconId.startsWith(GLYPH_PREFIX)) {
            String glyph = iconId.substring(GLYPH_PREFIX.length());

            int naturalW = Math.max(1, client.font.width(glyph));
            // 字形本体高 8px(与未缩放分支的 (height - 8) / 2 保持一致)
            int naturalH = 8;

            // 未自定义尺寸时保持原版: 按原生像素居中
            if (!customSize) {
                int textX = x + Math.round((width - naturalW) / 2.0F);
                int textY = y + Math.round((height - 8) / 2.0F);
                graphics.drawString(client.font, glyph, textX, textY, argb);
                return;
            }

            int[] box = resolveBox(x, y, naturalW, naturalH, width, height, sizeMode);
            var pose = graphics.pose();
            pose.pushPose();
            // 字形绘制原点在左上角, 平移到框左上角再缩放绘制
            pose.translate(box[0], box[1], 0.0F);
            pose.scale(box[2] / (float) naturalW, box[3] / (float) naturalH, 1.0F);
            graphics.drawString(client.font, glyph, 0, 0, argb);
            pose.popPose();
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
            // 与原版 Gui#renderEffects 一致: 取图集 sprite 后 blit(x, y, blitOffset, w, h, sprite)。
            BuiltInRegistries.MOB_EFFECT.getHolder(id).ifPresent(holder -> {
                TextureAtlasSprite sprite = client.getMobEffectTextures().get(holder);

                // 未自定义尺寸时按 sprite 原生 18px 绘制, 保证清晰; 自定义后按模式缩放
                int[] box = customSize
                        ? resolveBox(x, y, 18, 18, width, height, sizeMode)
                        : new int[] {x + (width - 18) / 2, y + (height - 18) / 2, 18, 18};
                RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), 1.0F);
                graphics.blit(box[0], box[1], 0, box[2], box[3], sprite);
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
            // 未自定义尺寸时保持原版 16px 居中; 自定义后按模式缩放
            int[] box = customSize
                    ? resolveBox(x, y, 16, 16, width, height, sizeMode)
                    : new int[] {x + (width - 16) / 2, y + (height - 16) / 2, 16, 16};

            // shader color 只乘物品像素 RGB(透明纹素 alpha=0 不受影响), 压暗/染色而非覆盖色块
            RenderSystem.setShaderColor(clampChannel(dimR), clampChannel(dimG), clampChannel(dimB), 1.0F);
            if (box[2] == 16 && box[3] == 16) {
                graphics.renderFakeItem(stack, box[0], box[1]);
            } else {
                // renderFakeItem 固定画 16x16, 自定义尺寸需靠 pose 缩放
                var pose = graphics.pose();
                pose.pushPose();
                pose.translate(box[0], box[1], 0.0F);
                pose.scale(box[2] / 16.0F, box[3] / 16.0F, 1.0F);
                graphics.renderFakeItem(stack, 0, 0);
                pose.popPose();
            }
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * 在 {@code x,y} 起点的 {@code width}x{@code height} 框内, 按填充方式算出图标的实际绘制区域。
     *
     * @param naturalW/H 图标原生像素尺寸
     * @param sizeMode   {@code FIT} 等比缩放不变形; {@code STRETCH} 铺满整框
     * @return {@code {x, y, w, h}} 绘制区域(绝对坐标), 已按框居中
     */
    private static int[] resolveBox(
            int x, int y, int naturalW, int naturalH, int width, int height, RadialSlot.IconSizeMode sizeMode) {
        int drawW = Math.max(1, width);
        int drawH = Math.max(1, height);

        if (sizeMode == RadialSlot.IconSizeMode.FIT && naturalW > 0 && naturalH > 0) {
            float scale = Math.min(width / (float) naturalW, height / (float) naturalH);
            drawW = Math.max(1, Math.round(naturalW * scale));
            drawH = Math.max(1, Math.round(naturalH * scale));
        }

        return new int[] {x + (width - drawW) / 2, y + (height - drawH) / 2, drawW, drawH};
    }
}
