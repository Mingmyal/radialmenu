package com.mingmyal.radialmenu.render;

import com.mingmyal.radialmenu.client.ServerConfigState;
import com.mingmyal.radialmenu.config.RadialConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4fStack;

/**
 * 径向菜单扇环/圆盘/圆环的即时绘制器。
 * <p>
 * 全部几何都在每帧用 {@link BufferBuilder}(POSITION_COLOR) 直接生成, 不生成也不缓存任何贴图:
 * 早期基于"后台生成扇环贴图再 blit"的方案会在极小缩放下出现贯穿屏幕的竖线伪影, 已弃用。
 * 圆周边缘按固定角度分段逼近真实圆弧, 因此边缘足够平滑。
 */
public final class DonutRenderer {

    /** 圆周逼近段数, 越大边缘越平滑 */
    private static final int DISC_SEGMENTS = 96;

    private DonutRenderer() {}

    /**
     * 当前生效的配置(采用服务端配置时为服务端配置)。
     * 渲染必须与轮盘界面读取同一份配置, 否则颜色/间隔等外观会与设置不符。
     */
    private static RadialConfig effectiveConfig() {
        return ServerConfigState.getEffectiveConfig();
    }

    /**
     * 绘制单个扇环(径向菜单的一格)。
     * <p>
     * 内外弧均按角度分段逼近真实圆弧; 扇环两侧的直边不画边框, 避免相邻扇环的边框线条重叠。
     *
     * @param cx         屏幕中心 X
     * @param cy         屏幕中心 Y
     * @param slotAngle  该扇环的中心角(弧度)
     * @param innerR     内半径
     * @param outerR     外半径
     * @param highlighted 是否为悬停态(使用高亮配色)
     * @param unmet      条件约束未满足(使用独立的红褐配色)
     * @param ease       展开动画进度 0~1
     * @param count      扇环总数
     */
    public static void renderSector(
            float cx,
            float cy,
            float slotAngle,
            float innerR,
            float outerR,
            boolean highlighted,
            boolean unmet,
            float ease,
            int count) {
        if (ease <= 0.0f || outerR <= 0.0f) {
            return;
        }

        RadialConfig config = effectiveConfig();
        float halfSpan = (float) (Math.PI / count);
        float gap = config.sectorGap;
        float borderW = config.sectorBorderWidth;

        // 未满足条件约束的槽位使用独立的红褐配色(悬停时用高亮变体)
        int bgColor = unmet
                ? (highlighted
                        ? config.unmetHighlightBackgroundColor.getRGB()
                        : config.unmetBackgroundColor.getRGB())
                : (highlighted ? config.activationColor.getRGB() : config.backgroundColor.getRGB());
        int borderColor = unmet
                ? (highlighted
                        ? config.unmetHighlightBorderColor.getRGB()
                        : config.unmetBorderColor.getRGB())
                : (highlighted ? config.highlightBorderColor.getRGB() : config.borderColor.getRGB());

        float alpha = Mth.clamp(ease * ease, 0.0f, 1.0f);

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

        Matrix4fStack matrix = RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        RenderSystem.applyModelViewMatrix();

        beginShape();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

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

        BufferUploader.drawWithShader(bb.buildOrThrow());
        endShape();
        matrix.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

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

        Matrix4fStack matrix = RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        RenderSystem.applyModelViewMatrix();
        beginShape();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLE_FAN,
                DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(0, 0, 0).setColor(r, g, b, af);
        for (int i = 0; i <= DISC_SEGMENTS; i++) {
            float ang = (float) (Math.PI * 2.0 * i / DISC_SEGMENTS);
            bb.addVertex((float) Math.cos(ang) * radius, (float) Math.sin(ang) * radius, 0)
                    .setColor(r, g, b, af);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        endShape();
        matrix.popMatrix();
        RenderSystem.applyModelViewMatrix();
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

        Matrix4fStack matrix = RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        RenderSystem.applyModelViewMatrix();
        beginShape();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);
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
        BufferUploader.drawWithShader(bb.buildOrThrow());

        endShape();
        matrix.popMatrix();
        RenderSystem.applyModelViewMatrix();
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

        Matrix4fStack matrix = RenderSystem.getModelViewStack();
        matrix.pushMatrix();
        matrix.translate(cx, cy, 0);
        RenderSystem.applyModelViewMatrix();
        beginShape();

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            float a0 = (float) (Math.PI * 2.0 * i / DISC_SEGMENTS);
            float a1 = (float) (Math.PI * 2.0 * (i + 1) / DISC_SEGMENTS);
            // 内缘两点用完整 alpha, 外缘两点 alpha=0, 顶点插值形成径向渐隐
            bb.addVertex((float) Math.cos(a0) * rInner, (float) Math.sin(a0) * rInner, 0).setColor(r, g, b, afIn);
            bb.addVertex((float) Math.cos(a1) * rInner, (float) Math.sin(a1) * rInner, 0).setColor(r, g, b, afIn);
            bb.addVertex((float) Math.cos(a1) * rOuter, (float) Math.sin(a1) * rOuter, 0).setColor(r, g, b, afOut);
            bb.addVertex((float) Math.cos(a0) * rOuter, (float) Math.sin(a0) * rOuter, 0).setColor(r, g, b, afOut);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        endShape();
        matrix.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /**
     * 建立本绘制器要求的 GL 状态。
     * <p>
     * 每次绘制都成对调用 {@link #beginShape}/{@link #endShape}, 而不是依赖调用方的状态:
     * 这些绘制会直接改 ModelView 矩阵栈与混合/剔除开关, 自己负责收尾才不会污染后续渲染。
     */
    private static void beginShape() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    /** 还原 {@link #beginShape} 改动过的 GL 状态。 */
    private static void endShape() {
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** 向 BufferBuilder 添加一个四边形(顶点顺序需保证顺时针或逆时针一致) */
    private static void putQuad(BufferBuilder bb,
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
}
