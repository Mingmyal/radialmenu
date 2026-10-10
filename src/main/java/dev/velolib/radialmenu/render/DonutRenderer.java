package dev.velolib.radialmenu.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.config.RadialConfig;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A highly optimized, asynchronous, texture-based renderer for radial menu sectors (donut slices).
 * <p>
 * Instead of calculating complex polygons or shaders every frame, this class generates a single,
 * high-resolution, anti-aliased texture of a radial sector using CPU-side distance fields and
 * 2x2 supersampling. This texture is then cached and rendered multiple times with different
 * matrix rotations to form the complete radial menu.
 * <p>
 * <b>Key Features:</b>
 * <ul>
 *     <li><b>Asynchronous Generation:</b> Texture math is offloaded to a background thread to prevent client stuttering.</li>
 *     <li><b>Texture Caching:</b> Regenerates only when configuration or geometry changes.</li>
 *     <li><b>Supersampled Anti-Aliasing (SSAA):</b> Ensures smooth, curved edges at any resolution using a 2x2 sub-pixel grid.</li>
 * </ul>
 */
public class DonutRenderer implements AutoCloseable {

    private static final float AA_WIDTH = 1.0f;
    private static final int AA_SAMPLES = 4;
    private static final float ZERO_GAP_EPSILON = 0.0001f;

    private final String idSuffix;

    private DynamicTexture baseTexture;
    private DynamicTexture hotTexture;

    private ResourceLocation baseTexId;
    private ResourceLocation hotTexId;

    /**
     * Tracks the current active background generation task.
     * Prevents older, slower async tasks from overwriting newer textures if the user
     * rapidly changes configurations (e.g., dragging a radius slider).
     */
    private long generationId = 0;

    // Cached geometry/configuration to detect when a regeneration is required.
    private int lastCount = -1;
    private float lastInner = -1.0f;
    private float lastOuter = -1.0f;
    private float lastGap = -1.0f;
    private float lastResScale = -1.0f;
    private int lastTexSize = -1;

    private boolean lastDrawSectorBorders = true;
    private float lastSectorBorderWidth = -1.0f;
    private boolean lastDrawOuterBorders = false;
    private int lastBackgroundColor = -1;
    private int lastActivationColor = -1;
    private int lastBorderColor = -1;
    private int lastHighlightBorderColor = -1;

    // Size of the currently registered textures. Only touched on the main thread, since a newer
    // generation may already be running at a different size.
    private int texSize = 0;

    public DonutRenderer(String idSuffix) {
        this.idSuffix = idSuffix;
    }

    /**
     * Validates the current cache against the provided geometry and config.
     * <p>
     * If any parameter (radius, gaps, colors, etc.) has changed, this triggers
     * an asynchronous regeneration of the sector textures. If nothing has changed,
     * this method exits immediately (zero cost).
     *
     * @param count    The total number of sectors in the radial menu.
     * @param inner    The inner radius of the donut.
     * @param outer    The outer radius of the donut.
     * @param resScale The resolution scale multiplier for sharp textures on high-DPI displays.
     */
    public void prepare(int count, float inner, float outer, float resScale) {
        RadialConfig config = RadialConfig.INSTANCE;

        float maxR = outer + 4.0f;
        int requiredTexSize = (int) (maxR * 2.0f * resScale) + 4;

        if (requiredTexSize <= 0) {
            return;
        }

        boolean needsRegeneration = baseTexture == null
                || hotTexture == null
                || lastCount != count
                || lastInner != inner
                || lastOuter != outer
                || lastGap != config.sectorGap
                || lastResScale != resScale
                || lastTexSize != requiredTexSize
                || lastDrawSectorBorders != config.drawSectorBorders
                || lastSectorBorderWidth != config.sectorBorderWidth
                || lastDrawOuterBorders != config.drawOuterBorders
                || lastBackgroundColor != config.backgroundColor.getRGB()
                || lastActivationColor != config.activationColor.getRGB()
                || lastBorderColor != config.borderColor.getRGB()
                || lastHighlightBorderColor != config.highlightBorderColor.getRGB();

        if (!needsRegeneration) {
            return;
        }

        regenerateTextures(requiredTexSize, count, inner, outer, resScale, config);

        lastCount = count;
        lastInner = inner;
        lastOuter = outer;
        lastGap = config.sectorGap;
        lastResScale = resScale;
        lastTexSize = requiredTexSize;
        lastDrawSectorBorders = config.drawSectorBorders;
        lastSectorBorderWidth = config.sectorBorderWidth;
        lastDrawOuterBorders = config.drawOuterBorders;
        lastBackgroundColor = config.backgroundColor.getRGB();
        lastActivationColor = config.activationColor.getRGB();
        lastBorderColor = config.borderColor.getRGB();
        lastHighlightBorderColor = config.highlightBorderColor.getRGB();
    }

    /**
     * Renders the pre-generated sector texture to the screen.
     * <p>
     * Performs matrix transformations (translation and rotation) to place the single
     * sector texture into its correct slot in the radial ring. Does <b>not</b> perform
     * any heavy calculations or texture generation.
     *
     * @param graphics    The GUI graphics for drawing.
     * @param cx          The X coordinate of the screen center.
     * @param cy          The Y coordinate of the screen center.
     * @param slotAngle   The angle (in radians) to rotate this specific sector.
     * @param push        The distance to push the sector outward (used for hover animations).
     * @param highlighted True to use the highlighted (hover) texture, false for the base texture.
     * @param ease        The animation progress (0.0 to 1.0) used for scaling and opacity interpolation.
     * @param resScale    The resolution scale to properly size the texture on screen.
     */
    /**
     * 用 BufferBuilder 直接绘制扇环四边形(参考 MineMenu 的实现), 完全不使用纹理 blit,
     * 从根本上避免极小缩放时四边形顶点塌缩导致的贯穿屏幕竖线伪影。
     *
     * @param innerR 展开后的内半径(已乘 ease)
     * @param outerR 展开后的外半径(已乘 ease)
     */
    /**
     * 套用 MineMenu 的扇形渲染方式: 用 RenderSystem.getModelViewStack() + 原生 Tesselator/BufferBuilder
     * (POSITION_COLOR), 完全不经过 GuiGraphics 的 bufferSource, 性能好且无竖线伪影。
     */
    public void renderSector(
            GuiGraphics graphics,
            float cx,
            float cy,
            float slotAngle,
            float innerR,
            float outerR,
            float push,
            boolean highlighted,
            float ease,
            int count) {
        if (ease <= 0.0f || outerR <= 0.0f) {
            return;
        }

        RadialConfig config = RadialConfig.INSTANCE;
        float halfSpan = (float) (Math.PI / count);
        float gap = config.sectorGap;
        float borderW = config.sectorBorderWidth;

        int bgColor = highlighted ? config.activationColor.getRGB() : config.backgroundColor.getRGB();
        int borderColor = highlighted ? config.highlightBorderColor.getRGB() : config.borderColor.getRGB();

        float alpha = Mth.clamp(ease * ease, 0.0f, 1.0f);
        float pushOffset = push * ease;

        float ang0 = slotAngle - halfSpan;
        float ang1 = slotAngle + halfSpan;
        if (gap > 0.0f) {
            // 固定角度缝隙(楔形缝, 内窄外宽): 按内半径换算会过度发散, 按缝隙角度系数换算接近均匀细缝
            float halfGapAng = gap * 0.004f;
            ang0 += halfGapAng;
            ang1 -= halfGapAng;
        }

        // 单个 quad 的内外弧都是直弦, 多个扇区拼起来呈多边形而非圆;
        // 每约 5.6° 分一段, 内外边缘才贴合真实圆弧(扇环形状)
        int segments = Math.max(2, Math.round((ang1 - ang0) / (float) (Math.PI / 32.0)));

        // 套用 MineMenu 的矩阵管理方式
        org.joml.Matrix4fStack matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx + pushOffset, cy, 0);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb =
                tess.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        // 背景扇环: 分段逼近, 内外边缘均为圆弧
        for (int s = 0; s < segments; s++) {
            float t0 = ang0 + (ang1 - ang0) * s / segments;
            float t1 = ang0 + (ang1 - ang0) * (s + 1) / segments;
            float ct0 = (float) Math.cos(t0);
            float st0 = (float) Math.sin(t0);
            float ct1 = (float) Math.cos(t1);
            float st1 = (float) Math.sin(t1);
            putQuad(bb,
                    ct0 * innerR, st0 * innerR,
                    ct1 * innerR, st1 * innerR,
                    ct1 * outerR, st1 * outerR,
                    ct0 * outerR, st0 * outerR,
                    bgColor, alpha);
        }

        // 内外圆弧边框(分段逼近; 扇环两侧直边不画边框, 避免相邻扇环边框线条重叠)
        if (config.drawOuterBorders && borderW > 0.0f) {
            float bIn = Math.max(0, outerR - borderW);
            float bOut = innerR + borderW;
            for (int s = 0; s < segments; s++) {
                float t0 = ang0 + (ang1 - ang0) * s / segments;
                float t1 = ang0 + (ang1 - ang0) * (s + 1) / segments;
                float ct0 = (float) Math.cos(t0);
                float st0 = (float) Math.sin(t0);
                float ct1 = (float) Math.cos(t1);
                float st1 = (float) Math.sin(t1);
                // 外弧边框带 [bIn, outerR]
                putQuad(bb,
                        ct0 * bIn, st0 * bIn,
                        ct1 * bIn, st1 * bIn,
                        ct1 * outerR, st1 * outerR,
                        ct0 * outerR, st0 * outerR,
                        borderColor, alpha);
                // 内弧边框带 [innerR, bOut]
                putQuad(bb,
                        ct0 * innerR, st0 * innerR,
                        ct1 * innerR, st1 * innerR,
                        ct1 * bOut, st1 * bOut,
                        ct0 * bOut, st0 * bOut,
                        borderColor, alpha);
            }
        }

        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.buildOrThrow());

        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();

        matrix.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    /** 圆周逼近段数, 越大边缘越平滑 */
    private static final int DISC_SEGMENTS = 96;

    /**
     * 画全圆平滑圆盘(三角扇逼近)。用于底盘/中心盘填充,
     * 多边形边缘远比逐行 fill 平滑, 且与扇环同为 Tesselator 渲染路径。
     *
     * @param alphaScale 颜色自身 alpha 的附加乘数(0~1)
     */
    public static void drawDisc(float cx, float cy, float radius, int argb, float alphaScale) {
        if (radius <= 0.0f) return;
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alphaScale), 0, 255);
        if (a <= 0) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float af = a / 255.0f;

        org.joml.Matrix4fStack matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = tess.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLE_FAN,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(0, 0, 0).setColor(r, g, b, af);
        for (int i = 0; i <= DISC_SEGMENTS; i++) {
            float ang = (float) (Math.PI * 2.0 * i / DISC_SEGMENTS);
            bb.addVertex((float) Math.cos(ang) * radius, (float) Math.sin(ang) * radius, 0)
                    .setColor(r, g, b, af);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.buildOrThrow());

        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        matrix.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    /**
     * 画全圆平滑环带(QUADS 分段逼近)。用于外围阴影/边框环。
     *
     * @param alphaScale 颜色自身 alpha 的附加乘数(0~1)
     */
    public static void drawRing(float cx, float cy, float rInner, float rOuter, int argb, float alphaScale) {
        if (rOuter <= 0.0f || rOuter <= rInner) return;
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alphaScale), 0, 255);
        if (a <= 0) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float af = a / 255.0f;

        org.joml.Matrix4fStack matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = tess.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        int argbOpaque = (a << 24) | (((argb >> 16) & 0xFF) << 16) | (((argb >> 8) & 0xFF) << 8) | (argb & 0xFF);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            float a0 = (float) (Math.PI * 2.0 * i / DISC_SEGMENTS);
            float a1 = (float) (Math.PI * 2.0 * (i + 1) / DISC_SEGMENTS);
            putQuad(bb,
                    (float) Math.cos(a0) * rOuter, (float) Math.sin(a0) * rOuter,
                    (float) Math.cos(a1) * rOuter, (float) Math.sin(a1) * rOuter,
                    (float) Math.cos(a1) * rInner, (float) Math.sin(a1) * rInner,
                    (float) Math.cos(a0) * rInner, (float) Math.sin(a0) * rInner,
                    argbOpaque, 1.0f);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.buildOrThrow());

        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        matrix.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    /**
     * 画径向渐隐环带(QUADS 分段逼近, 顶点级 alpha 渐变)。
     * 内缘 alpha 为颜色自身值, 沿径向线性衰减到外缘 0, 用于柔和外阴影。
     *
     * @param alphaScale 颜色自身 alpha 的附加乘数(0~1)
     */
    public static void drawFadingRing(float cx, float cy, float rInner, float rOuter, int argb, float alphaScale) {
        if (rOuter <= 0.0f || rOuter <= rInner) return;
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alphaScale), 0, 255);
        if (a <= 0) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float afIn = a / 255.0f;
        float afOut = 0.0f;

        org.joml.Matrix4fStack matrix = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tess = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = tess.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            float a0 = (float) (Math.PI * 2.0 * i / DISC_SEGMENTS);
            float a1 = (float) (Math.PI * 2.0 * (i + 1) / DISC_SEGMENTS);
            // 内缘两点用完整 alpha, 外缘两点 alpha=0, 顶点插值形成径向渐隐
            bb.addVertex((float) Math.cos(a0) * rInner, (float) Math.sin(a0) * rInner, 0).setColor(r, g, b, afIn);
            bb.addVertex((float) Math.cos(a1) * rInner, (float) Math.sin(a1) * rInner, 0).setColor(r, g, b, afIn);
            bb.addVertex((float) Math.cos(a1) * rOuter, (float) Math.sin(a1) * rOuter, 0).setColor(r, g, b, afOut);
            bb.addVertex((float) Math.cos(a0) * rOuter, (float) Math.sin(a0) * rOuter, 0).setColor(r, g, b, afOut);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bb.buildOrThrow());

        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        matrix.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    /** 向 BufferBuilder 添加一个四边形(顶点顺序需保证顺时针或逆时针一致) */
    private static void putQuad(com.mojang.blaze3d.vertex.BufferBuilder bb,
                                float x0, float y0, float x1, float y1,
                                float x2, float y2, float x3, float y3,
                                int argb, float alpha) {
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alpha), 0, 255);
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        float af = a / 255.0f;
        bb.addVertex(x0, y0, 0).setColor(r, g, b, af);
        bb.addVertex(x1, y1, 0).setColor(r, g, b, af);
        bb.addVertex(x2, y2, 0).setColor(r, g, b, af);
        bb.addVertex(x3, y3, 0).setColor(r, g, b, af);
    }

    /**
     * Spawns a background thread to calculate the pixel data for the sector textures.
     * <p>
     * Uses {@link #generationId} to track the most recent request. If the user rapidly resizes
     * the radial menu, older background tasks will detect a mismatched ID upon completion and
     * gracefully discard their results. GPU texture registration is safely delegated back
     * to the main Minecraft thread.
     */
    private void regenerateTextures(
            int targetTexSize, int count, float inner, float outer, float resScale, RadialConfig config) {
        final long currentGenId = ++this.generationId;

        final int bgColor = config.backgroundColor.getRGB();
        final int borderColor = config.borderColor.getRGB();
        final int hotColor = config.activationColor.getRGB();
        final int hotBorderColor = config.highlightBorderColor.getRGB();
        final boolean drawSectorBorders = config.drawSectorBorders;
        final boolean drawOuterBorders = config.drawOuterBorders;
        final float sectorBorderWidth = config.sectorBorderWidth;
        final float sectorGap = config.sectorGap;

        CompletableFuture.supplyAsync(() -> {
                    NativeImage baseImage = new NativeImage(targetTexSize, targetTexSize, false);
                    NativeImage hotImage = null;

                    try {
                        hotImage = new NativeImage(targetTexSize, targetTexSize, false);

                        generatePixels(
                                baseImage,
                                hotImage,
                                targetTexSize,
                                count,
                                inner,
                                outer,
                                resScale,
                                bgColor,
                                borderColor,
                                hotColor,
                                hotBorderColor,
                                drawSectorBorders,
                                drawOuterBorders,
                                sectorBorderWidth,
                                sectorGap);
                    } catch (RuntimeException e) {
                        // Free the native memory before reporting the failure
                        baseImage.close();
                        if (hotImage != null) hotImage.close();
                        throw e;
                    }

                    return new NativeImage[] {baseImage, hotImage};
                })
                .thenAcceptAsync(
                        images -> {
                            // Drop results if a newer generation request was fired while this was processing
                            if (this.generationId != currentGenId) {
                                images[0].close();
                                images[1].close();
                                return;
                            }

                            if (this.baseTexture != null) this.baseTexture.close();
                            if (this.hotTexture != null) this.hotTexture.close();

                            this.texSize = targetTexSize;
                            this.baseTexId = ResourceLocation.fromNamespaceAndPath("radialmenu", "sector_base_" + this.idSuffix);
                            this.hotTexId = ResourceLocation.fromNamespaceAndPath("radialmenu", "sector_hot_" + this.idSuffix);

                            this.baseTexture = new DynamicTexture(images[0]);
                            this.hotTexture = new DynamicTexture(images[1]);

                            Minecraft.getInstance().getTextureManager().register(this.baseTexId, this.baseTexture);
                            Minecraft.getInstance().getTextureManager().register(this.hotTexId, this.hotTexture);
                        },
                        Minecraft.getInstance())
                .exceptionally(e -> {
                    RadialClient.LOGGER.error("Failed to generate radial sector textures", e);
                    return null;
                });
    }

    /**
     * The core mathematical engine for drawing the anti-aliased sector.
     * <p>
     * Iterates over the bounding box of the texture. To optimize performance, it uses an
     * <b>early exit</b> strategy to completely skip pixels that fall outside the donut's
     * inner and outer radii, explicitly clearing them to transparent to avoid garbage memory.
     * <p>
     * For pixels near the edges, it performs 2x2 supersampling, calculating the radial and
     * angular distance from the center, and applies a {@link #smoothstep(float)} function
     * to blend the colors, resulting in a perfectly smooth curve.
     */
    private void generatePixels(
            NativeImage baseImage,
            NativeImage hotImage,
            int texSize,
            int count,
            float inner,
            float outer,
            float resScale,
            int baseColor,
            int borderColor,
            int hotBaseColor,
            int hotBorderColor,
            boolean drawSectorBorders,
            boolean drawOuterBorders,
            float sectorBorderWidth,
            float sectorGap) {
        float center = texSize / 2.0f;
        float innerR = inner * resScale;
        float outerR = outer * resScale;
        float borderW = sectorBorderWidth * resScale;
        float aa = Math.max(0.75f, AA_WIDTH * resScale);
        float halfSpan = (float) (Math.PI / count);

        float gap = Math.max(0.0f, sectorGap * resScale);
        boolean zeroGap = gap <= ZERO_GAP_EPSILON;
        float halfGap = gap * 0.5f;
        float overlapWidth = 0.8f;

        for (int y = 0; y < texSize; y++) {
            for (int x = 0; x < texSize; x++) {

                float pxCenter = x + 0.5f;
                float pyCenter = y + 0.5f;
                float dxCenter = pxCenter - center;
                float dyCenter = pyCenter - center;
                float distCenter = Mth.sqrt(dxCenter * dxCenter + dyCenter * dyCenter);

                // Early exit: Avoid heavy math on empty space (both inner hole and outer corners)
                if (distCenter < innerR - aa - 1.0f || distCenter > outerR + aa + 1.0f) {
                    baseImage.setPixelRGBA(x, y, 0x00000000);
                    hotImage.setPixelRGBA(x, y, 0x00000000);
                    continue;
                }

                float angleCenter = (float) Mth.atan2(dyCenter, dxCenter);
                float angularDistanceCenter = (halfSpan - Math.abs(angleCenter)) * distCenter;

                float centerAngularAlpha = 0.0f;
                boolean centerInOverlapZone = false;

                if (zeroGap) {
                    if (angularDistanceCenter >= overlapWidth) {
                        centerAngularAlpha = 1.0f;
                    } else if (angularDistanceCenter > -overlapWidth) {
                        centerAngularAlpha = 1.0f;
                        centerInOverlapZone = true;
                    }
                }

                float baseAlphaTotal = 0.0f, baseRTotal = 0.0f, baseGTotal = 0.0f, baseBTotal = 0.0f;
                float hotAlphaTotal = 0.0f, hotRTotal = 0.0f, hotGTotal = 0.0f, hotBTotal = 0.0f;

                // 2x2 Supersampling loop
                for (int sy = 0; sy < 2; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        float px = x + (sx == 0 ? 0.25f : 0.75f);
                        float py = y + (sy == 0 ? 0.25f : 0.75f);
                        float dx = px - center;
                        float dy = py - center;
                        float dist = Mth.sqrt(dx * dx + dy * dy);

                        if (dist < innerR - aa || dist > outerR + aa) continue;

                        float angle = (float) Mth.atan2(dy, dx);
                        float angularDistance = (halfSpan - Math.abs(angle)) * dist;

                        float angularAlpha;
                        boolean inOverlapZone = false;

                        if (zeroGap) {
                            angularAlpha = centerAngularAlpha;
                            inOverlapZone = centerInOverlapZone;
                        } else {
                            float edgeDistance = angularDistance - halfGap;
                            angularAlpha = smoothstep(Mth.clamp(edgeDistance / aa, 0.0f, 1.0f));
                        }

                        if (angularAlpha <= 0.0f) continue;

                        float radialDistance = Math.min(dist - innerR, outerR - dist);
                        float radialAlpha = smoothstep(Mth.clamp(radialDistance / aa, 0.0f, 1.0f));

                        if (radialAlpha <= 0.0f) continue;

                        float coverage = angularAlpha * radialAlpha;
                        boolean border = drawOuterBorders && (dist <= innerR + borderW || dist >= outerR - borderW);

                        if (!border && drawSectorBorders) {
                            float sideDistance = zeroGap ? angularDistance : (angularDistance - halfGap);
                            if (sideDistance >= 0.0f && sideDistance <= borderW) {
                                border = true;
                            }
                        }

                        int baseFinalColor = border ? borderColor : baseColor;
                        int hotFinalColor = border ? hotBorderColor : hotBaseColor;

                        float baseAlpha = (((baseFinalColor >> 24) & 0xFF) / 255.0f) * coverage;
                        float hotAlpha = (((hotFinalColor >> 24) & 0xFF) / 255.0f) * coverage;

                        if (zeroGap && inOverlapZone && !border) {
                            baseAlpha = 1.0f - (float) Math.sqrt(Math.max(0.0f, 1.0f - baseAlpha));
                            hotAlpha = 1.0f - (float) Math.sqrt(Math.max(0.0f, 1.0f - hotAlpha));
                        }

                        baseAlphaTotal += baseAlpha;
                        baseRTotal += ((baseFinalColor >> 16) & 0xFF) * baseAlpha;
                        baseGTotal += ((baseFinalColor >> 8) & 0xFF) * baseAlpha;
                        baseBTotal += (baseFinalColor & 0xFF) * baseAlpha;

                        hotAlphaTotal += hotAlpha;
                        hotRTotal += ((hotFinalColor >> 16) & 0xFF) * hotAlpha;
                        hotGTotal += ((hotFinalColor >> 8) & 0xFF) * hotAlpha;
                        hotBTotal += (hotFinalColor & 0xFF) * hotAlpha;
                    }
                }

                // Average sub-pixels and write to textures
                baseAlphaTotal /= AA_SAMPLES;
                if (baseAlphaTotal <= 0.0f) {
                    baseImage.setPixelRGBA(x, y, 0x00000000);
                } else {
                    float denominator = baseAlphaTotal * AA_SAMPLES;
                    int alpha = Mth.clamp((int) (baseAlphaTotal * 255.0f + 0.5f), 0, 255);
                    int r = Mth.clamp((int) (baseRTotal / denominator + 0.5f), 0, 255);
                    int g = Mth.clamp((int) (baseGTotal / denominator + 0.5f), 0, 255);
                    int b = Mth.clamp((int) (baseBTotal / denominator + 0.5f), 0, 255);

                    int outColor = (alpha << 24) | (r << 16) | (g << 8) | b;
                    baseImage.setPixelRGBA(x, y, toABGR(outColor));
                }

                hotAlphaTotal /= AA_SAMPLES;
                if (hotAlphaTotal <= 0.0f) {
                    hotImage.setPixelRGBA(x, y, 0x00000000);
                } else {
                    float denominator = hotAlphaTotal * AA_SAMPLES;
                    int alpha = Mth.clamp((int) (hotAlphaTotal * 255.0f + 0.5f), 0, 255);
                    int r = Mth.clamp((int) (hotRTotal / denominator + 0.5f), 0, 255);
                    int g = Mth.clamp((int) (hotGTotal / denominator + 0.5f), 0, 255);
                    int b = Mth.clamp((int) (hotBTotal / denominator + 0.5f), 0, 255);

                    int outColor = (alpha << 24) | (r << 16) | (g << 8) | b;
                    hotImage.setPixelRGBA(x, y, toABGR(outColor));
                }
            }
        }
    }

    /**
     * Applies a cubic smoothstep interpolation.
     * Used to create anti-aliased transitions at the mathematical boundaries of the sector.
     *
     * @param t The input value between 0.0 and 1.0.
     * @return The smoothed output.
     */
    private float smoothstep(float t) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Fast bitwise swapper to convert standard ARGB integers into OpenGL ABGR format.
     */
    private int toABGR(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0x00FF0000) >> 16) | ((argb & 0x000000FF) << 16);
    }

    /**
     * Frees the GPU textures and native memory buffers.
     * <p>
     * Must be called when the radial menu is closed or destroyed to prevent VRAM memory leaks.
     * Increments the generation ID to ensure any pending background tasks abort.
     */
    @Override
    public void close() {
        if (baseTexId != null) Minecraft.getInstance().getTextureManager().release(baseTexId);
        if (hotTexId != null) Minecraft.getInstance().getTextureManager().release(hotTexId);

        if (baseTexture != null) baseTexture.close();
        if (hotTexture != null) hotTexture.close();

        baseTexId = null;
        hotTexId = null;
        baseTexture = null;
        hotTexture = null;

        lastCount = -1;
        lastInner = -1.0f;
        lastOuter = -1.0f;
        lastGap = -1.0f;
        lastResScale = -1.0f;
        lastTexSize = -1;
        lastDrawSectorBorders = true;
        lastSectorBorderWidth = -1.0f;
        lastDrawOuterBorders = false;
        lastBackgroundColor = -1;
        lastActivationColor = -1;
        lastBorderColor = -1;
        lastHighlightBorderColor = -1;
        texSize = 0;

        generationId++;
    }
}
