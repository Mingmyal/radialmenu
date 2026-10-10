package dev.velolib.radialmenu.ui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.velolib.radialmenu.RadialClient;
import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotActionContext;
import dev.velolib.radialmenu.config.RadialConfig;
import dev.velolib.radialmenu.config.RadialConfigScreen;
import dev.velolib.radialmenu.render.DonutRenderer;
import dev.velolib.radialmenu.render.SlotRenderHelper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public class RadialScreen extends Screen {

    // How far the detection ring extends beyond the hovered slot (used by detection radius config)
    public static final float SLOT_PUSH = 7.5F;

    private static final int ITEM_SIZE = 16;
    private static final float STAGGER_STEP_FRACTION = 0.4F;

    private static final DonutRenderer SECTOR_RENDERER = new DonutRenderer("main");

    private static final ItemStack MISSING_ICON = new ItemStack(Items.BARRIER);

    private record MenuState(List<RadialSlot> slots, int slotCount) {}

    private final Deque<MenuState> history = new ArrayDeque<>();

    private final List<RadialSlot> rootSlots;

    private List<RadialSlot> activeSlots;
    private int currentSlotCount;
    private int hoveredSlot = -1;
    private boolean editMode = false;

    // 呼出键松开确认已被消费(进入子菜单等保持开启的场景), 防止重复触发
    private boolean releaseConsumed = false;

    private double revealElapsedSeconds = 0.0;
    private double menuElapsedSeconds = 0.0;
    private long lastNano;

    public RadialScreen() {
        super(Component.empty());
        this.rootSlots = RadialConfig.INSTANCE.slots;
        this.activeSlots = rootSlots;
        this.currentSlotCount = RadialConfig.INSTANCE.slotCount;
    }

    @Override
    protected void init() {
        // 编辑模式开关按钮
        addRenderableWidget(Button.builder(
                Component.translatable("radialmenu.ui.edit_mode")
                        .append(": ")
                        .append(editMode
                                ? Component.translatable("radialmenu.ui.edit_mode.on")
                                : Component.translatable("radialmenu.ui.edit_mode.off")),
                b -> {
                    editMode = !editMode;
                    rebuildWidgets();
                })
                .bounds(width - 130, 8, 120, 16)
                .build());

        RadialConfig.ActivationMode mode = RadialConfig.INSTANCE.activationMode;
        if (mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE) {
            hoveredSlot = 0; // Default to first slot in scroll mode
            GLFW.glfwSetInputMode(
                    minecraft.getWindow().getWindow(),
                    GLFW.GLFW_CURSOR,
                    GLFW.GLFW_CURSOR_HIDDEN);
        }
    }

    @Override
    public void onClose() {
        // 关闭轮盘时锁定呼出键, 防止呼出键仍按着时立即重开
        RadialClient.lockKey();
        super.onClose();
    }

    @Override
    public void removed() {
        GLFW.glfwSetInputMode(
                minecraft.getWindow().getWindow(),
                GLFW.GLFW_CURSOR,
                GLFW.GLFW_CURSOR_NORMAL);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- State Helpers ---

    private boolean isSubmenu() {
        return !history.isEmpty();
    }

    private int getRenderCount() {
        return currentSlotCount;
    }

    /**
     * Maps the visual hovered slot index to the actual RadialSlot index in the active list.
     * Returns null if the index is out of bounds.
     */
    private RadialSlot getTargetSlot(int index) {
        if (index >= 0 && index < activeSlots.size()) {
            return activeSlots.get(index);
        }
        return null;
    }

    // --- Renderer Preparation & Geometry ---

    /**
     * 自适应缩放: 基于窗口较短边的比例, 使轮盘在全屏/窗口模式下占屏比例一致。
     * 基准高度 540 (对应 1080p + GUI 缩放 2), 此时 scale = radialSize。
     */
    private float getAdaptiveScale() {
        int minDim = Math.min(width, height);
        if (minDim <= 0) return RadialConfig.INSTANCE.radialSize;
        return (minDim / 540.0F) * RadialConfig.INSTANCE.radialSize;
    }

    // --- Render Loop ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        RadialConfig config = RadialConfig.INSTANCE;
        RadialConfig.ActivationMode mode = config.activationMode;
        boolean isScrollMode =
                mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE;

        // 1. Check Key Release —— 松开呼出键即在所有模式下确认当前悬停动作
        boolean openDown = RadialClient.isPhysicallyDown(KeyBindingHelper.getBoundKeyOf(RadialClient.OPEN_RADIAL));
        if (openDown) {
            // 呼出键重新按下, 重置松开消费标志
            releaseConsumed = false;
        } else if (!releaseConsumed) {
            // 首次检测到松开
            releaseConsumed = true;
            // 仅"释放确认"模式(RELEASE / SCROLL_RELEASE)才在松开时触发动作;
            // CLICK / SCROLL_CLICK 模式松开只关闭轮盘, 动作由鼠标点击触发
            boolean releaseConfirms =
                    mode == RadialConfig.ActivationMode.RELEASE
                            || mode == RadialConfig.ActivationMode.SCROLL_RELEASE;
            if (releaseConfirms && hoveredSlot != -1) {
                RadialSlot slot = getTargetSlot(hoveredSlot);
                if (slot != null && slot.mode.activateOnRelease()) {
                    performAction(slot);
                    return;
                }
            }
            // 无有效悬停/非释放确认模式/动作不支持松开确认 → 关闭轮盘
            onClose();
            return;
        }
        // releaseConsumed == true: 松开已处理, 轮盘保持开启(如进入子菜单后), 等待鼠标操作或手动回退

        // 2. Timing
        long now = System.nanoTime();
        if (lastNano == 0) lastNano = now;
        float dt = (float) Math.min((now - lastNano) / 1.0e9, 0.1);
        lastNano = now;

        revealElapsedSeconds += dt;
        menuElapsedSeconds += dt;

        int cx = width / 2;
        int cy = height / 2;
        int renderCount = getRenderCount();

        // 3. Hit Test (Mouse mode only)
        float scale = getAdaptiveScale();
        // 文本/图标等界面元素与轮盘几何同源缩放, 使整个轮盘 UI 作为整体等比变化; 加极端值保护
        float uiScale = Mth.clamp(scale, 0.6F, 2.5F);
        // 图标与文字在整体缩放基础上再略放大, 提升可读性
        float iconScale = uiScale * 1.45F;
        float slotTextScale = uiScale * 1.15F;
        // 介绍文字比槽位名称小一点
        float slotDescScale = uiScale;
        float centerTextScale = uiScale * 1.15F;
        float selectedHintScale = uiScale * 1.15F;
        if (!isScrollMode) {
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            double dist = Math.sqrt(dx * dx + dy * dy);

            float detInner = config.getDetectionInnerRadius() * scale;
            float detOuter = config.getDetectionOuterRadius() * scale;
            if (dist >= detInner && dist <= detOuter) {
                double angle = Math.atan2(dy, dx);
                if (angle < 0.0) angle += Math.PI * 2.0;

                double sectorSize = Math.PI * 2.0 / renderCount;
                double shiftedAngle = (angle + Math.PI / 2.0 + sectorSize / 2.0) % (Math.PI * 2.0);
                hoveredSlot = (int) (shiftedAngle / sectorSize) % renderCount;
            } else {
                hoveredSlot = -1;
            }
        }

        // 4. 第一层: 外围浅黑色渐隐阴影(由内向外变淡, 稍向外延伸)
        float baseInner = config.getVisibleInnerRadius() * scale;
        float baseOuter = config.getVisibleOuterRadius() * scale;
        float globalReveal = easeOutQuint(getGlobalRevealProgress(config));
        int globalAlpha = Mth.clamp((int) (globalReveal * 255.0F + 0.5F), 0, 255);
        if (config.showActivationZone) {
            float discR = baseOuter * globalReveal;
            if (discR > 0) {
                DonutRenderer.drawFadingRing(cx, cy, discR, discR + 6.0F, 0x38000000, globalReveal);
            }
        }

        // 5. 第二层: 扇环(半径随 revealEase 从中心展开; 无选中位移, 高亮只强调填充色+边框色)
        if (config.showActivationZone) {
            for (int i = 0; i < renderCount; i++) {
                float slotAngle = (float) ((Math.PI * 2.0 / renderCount) * i - Math.PI / 2.0);
                float revealEase = easeOutQuint(getRevealProgress(i, renderCount, config));

                SECTOR_RENDERER.renderSector(
                        graphics, cx, cy, slotAngle,
                        baseInner * revealEase, baseOuter * revealEase,
                        0.0F, (i == hoveredSlot), revealEase, renderCount);
            }
        }

        // 6. 中心圆盘: 填充/边框颜色与扇环共用同一配置; 半径比扇环内缘小一圈, 露出窄空隙不粘连
        float discRadius = baseInner * globalReveal - Math.max(5.0F, baseOuter * 0.04F);
        if (discRadius > 0) {
            boolean sectorHovered = hoveredSlot != -1;
            // 填充: 直接用 backgroundColor(含自身 alpha), 与扇环同源同透明度
            DonutRenderer.drawDisc(cx, cy, discRadius, config.backgroundColor.getRGB(), globalReveal);
            // 边框: 与扇环边框共用 borderColor/highlightBorderColor
            int ringARGB = sectorHovered
                    ? config.highlightBorderColor.getRGB()
                    : config.borderColor.getRGB();
            float ringW = Math.max(1.0F, config.sectorBorderWidth);
            DonutRenderer.drawRing(cx, cy, discRadius, discRadius + ringW, ringARGB, globalReveal);
        }

        // 7. 第三层: 图标 + 标题/副标题两行文本
        for (int i = 0; i < renderCount; i++) {
            float revealProgress = getRevealProgress(i, renderCount, config);
            if (revealProgress <= 0.0F) continue;
            float revealEase = easeOutQuint(revealProgress);
            if (revealEase < 0.08F) continue;

            int revealAlpha = Mth.clamp((int) (revealEase * 255.0F + 0.5F), 0, 255);
            if (revealAlpha <= 0) continue;

            float slotAngle = (float) ((Math.PI * 2.0 / renderCount) * i - Math.PI / 2.0);
            float finalRadius = config.slotRadius * scale * revealEase;
            float slotX = (float) (cx + Math.cos(slotAngle) * finalRadius);
            float slotY = (float) (cy + Math.sin(slotAngle) * finalRadius);

            // 图标(中心位于径向中点上方), 比整体略大, 随轮盘等比缩放
            float iconCenterY = slotY - 16.0F * uiScale;
            var iconPose = graphics.pose();
            iconPose.pushPose();
            iconPose.translate(slotX, iconCenterY, 0.0F);
            iconPose.scale(iconScale, iconScale, 1.0F);
            RadialSlot slot = getTargetSlot(i);
            if (slot != null) {
                // 槽位图标为 26x26, 以 (slotX, iconCenterY) 为中心绘制
                SlotRenderHelper.renderSlotIcon(graphics, slot, -13.0F, -13.0F, revealAlpha);
            } else {
                graphics.renderItem(MISSING_ICON, -ITEM_SIZE / 2, -ITEM_SIZE / 2);
            }
            iconPose.popPose();

            // 标题(白) + 副标题(灰) —— 使用槽位自定义颜色, 随整体缩放; 整体下移一点并加大行距
            if (slot == null) continue;
            int titleColor = applyAlpha(slot.getNameColor(), revealAlpha / 255.0F);
            drawScaledCentered(
                    graphics,
                    Component.nullToEmpty(slot.name),
                    slotX,
                    slotY + 10.0F * uiScale,
                    titleColor,
                    slotTextScale);
            String descStr = slot.description != null ? slot.description : "";
            String subtitle = descStr.split("\n", 2)[0];
            if (!subtitle.isEmpty()) {
                int subColor = applyAlpha(slot.getDescriptionColor(), revealAlpha / 255.0F);
                drawScaledCentered(
                        graphics,
                        Component.nullToEmpty(subtitle),
                        slotX,
                        slotY + 24.0F * uiScale,
                        subColor,
                        slotDescScale);
            }
        }

        // 8. Center Text: 悬停时显示槽位名称+介绍(用槽位色); 未选中时显示可自定义的中心提示(支持2行)
        {
            String centerText;
            String centerSub = null;
            int centerColor;
            int subColor;
            if (editMode && !isSubmenu()) {
                centerText = Component.translatable("radialmenu.ui.edit_main").getString();
                centerColor = applyAlpha(config.getCenterHintColorLine1(), globalAlpha / 255.0F);
                subColor = centerColor;
            } else if (hoveredSlot != -1) {
                RadialSlot hovered = getTargetSlot(hoveredSlot);
                if (hovered != null) {
                    centerText = hovered.name;
                    centerColor = applyAlpha(hovered.getNameColor(), globalAlpha / 255.0F);
                    String desc = hovered.description != null ? hovered.description : "";
                    String sub = desc.split("\n", 2)[0];
                    if (!sub.isEmpty()) {
                        centerSub = sub;
                        subColor = applyAlpha(hovered.getDescriptionColor(), globalAlpha / 255.0F);
                    } else {
                        subColor = centerColor;
                    }
                } else {
                    centerText = "";
                    centerColor = 0;
                    subColor = 0;
                }
            } else {
                // 未选中: 用配置的两行中心提示(独立字段, 一行时居中, 两行时上下分布), 两行颜色独立
                centerText = config.centerHintLine1 != null ? config.centerHintLine1 : "";
                String line2 = config.centerHintLine2 != null ? config.centerHintLine2 : "";
                centerSub = line2.isEmpty() ? null : line2;
                centerColor = applyAlpha(config.getCenterHintColorLine1(), globalAlpha / 255.0F);
                subColor = applyAlpha(config.getCenterHintColorLine2(), globalAlpha / 255.0F);
            }

            boolean hasFirstLine = centerText != null && !centerText.isEmpty();
            boolean hasSecondLine = centerSub != null && !centerSub.isEmpty();
            if (hasFirstLine && hasSecondLine) {
                // 两行: 以中心为轴上下分布
                drawScaledCentered(
                        graphics,
                        Component.nullToEmpty(centerText),
                        cx,
                        cy - 11.0F * uiScale,
                        centerColor,
                        centerTextScale);
                drawScaledCentered(
                        graphics,
                        Component.nullToEmpty(centerSub),
                        cx,
                        cy + 11.0F * uiScale,
                        subColor,
                        centerTextScale);
            } else {
                // 一行(或只有第二行有内容): 居中
                String single = hasFirstLine ? centerText : centerSub;
                int singleColor = hasFirstLine ? centerColor : subColor;
                if (single != null && !single.isEmpty()) {
                    drawScaledCentered(
                            graphics,
                            Component.nullToEmpty(single),
                            cx,
                            cy - 4.0F * uiScale,
                            singleColor,
                            centerTextScale);
                }
            }
        }

        // 底部确认提示基准 Y: 距屏幕底部 37*缩放, 且不压到轮盘外圆, 保证缩放/窗口模式下不错位
        float confirmYf = Math.max(
                height - 37.0F * uiScale,
                cy + baseOuter + 14.0F * uiScale);
        float selectedYf = confirmYf - 17.0F * uiScale;

        // 9. 选中提示(悬停槽位时显示在底部提示上方) —— 用槽位自定义文字和颜色, 随整体缩放
        {
            String selectedHint = null;
            int hintColor = 0;
            if (hoveredSlot != -1) {
                RadialSlot hovered = getTargetSlot(hoveredSlot);
                if (hovered != null && hovered.selectedHint != null && !hovered.selectedHint.isEmpty()) {
                    selectedHint = hovered.selectedHint;
                    hintColor = applyAlpha(hovered.getSelectedHintColor(), globalAlpha / 255.0F);
                }
            }
            if (selectedHint != null) {
                drawScaledCentered(
                        graphics,
                        Component.nullToEmpty(selectedHint),
                        cx,
                        selectedYf,
                        hintColor,
                        selectedHintScale);
            }
        }

        // 10. Bottom Hint —— 用配置颜色, 随整体缩放
        {
            Component keyName = RadialClient.OPEN_RADIAL.getTranslatedKeyMessage();
            Component hint = Component.translatable("radialmenu.ui.hint_confirm_back", keyName);
            drawScaledCentered(
                    graphics,
                    hint,
                    cx,
                    confirmYf,
                    applyAlpha(config.getBottomHintColor(), globalAlpha / 255.0F),
                    uiScale);
        }

        // 11. 顶部标题 "快捷轮盘" —— 字号比其余文本稍大, 位置上移一点, 仍随整体缩放
        {
            String title = config.titleText != null ? config.titleText : "";
            if (!title.isEmpty()) {
                float titleScale = uiScale * 1.25F;
                float titleYf = Math.max(
                        18.0F * uiScale,
                        cy - baseOuter - 38.0F * uiScale);
                drawScaledCentered(
                        graphics,
                        Component.nullToEmpty(title),
                        cx,
                        titleYf,
                        applyAlpha(config.getTitleColor(), globalAlpha / 255.0F),
                        titleScale);
            }
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    /**
     * 在 (x, y) 处以给定缩放系数绘制水平居中的文本(内部通过 pose 缩放, 使文字随轮盘整体等比变化)。
     *
     * @param x     文本水平锚点(中心点)
     * @param y     文本顶部基线
     * @param scale 缩放系数
     */
    private void drawScaledCentered(GuiGraphics graphics, Component text,
                                    float x, float y, int color, float scale) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale(scale, scale, 1.0F);
        graphics.drawString(this.font, text, -this.font.width(text) / 2, 0, color);
        pose.popPose();
    }

    /** 将颜色自身的 alpha 乘以额外系数, 返回合成后的 ARGB。 */
    private static int applyAlpha(int argb, float alphaScale) {
        int a = Mth.clamp((int) (((argb >>> 24) & 0xFF) * alphaScale), 0, 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // --- Math & Animations ---

    private int getStaggerIndex(int index, int count, RadialConfig.RevealAnimation animation) {
        if (count <= 1) return 0;
        return switch (animation) {
            case STAGGERED_CLOCKWISE -> index;
            case STAGGERED_COUNTERCLOCKWISE -> (count - index) % count;
            case STAGGERED_BOTH -> Math.min(index, count - index);
            default -> index;
        };
    }

    private float getRevealProgress(int index, int count, RadialConfig config) {
        if (config.revealDurationMs <= 0) return 1.0F;

        float totalDuration = config.revealDurationMs / 1000.0F;
        RadialConfig.RevealAnimation animation = config.revealAnimation;

        if (animation == RadialConfig.RevealAnimation.ZOOM || count <= 1) {
            return Mth.clamp((float) (menuElapsedSeconds / totalDuration), 0.0F, 1.0F);
        }

        int staggerCount = (animation == RadialConfig.RevealAnimation.STAGGERED_BOTH) ? (count / 2) + 1 : count;
        float step = totalDuration * STAGGER_STEP_FRACTION / (staggerCount - 1);
        float elementDuration = Math.max(0.001F, totalDuration - step * (staggerCount - 1));

        return Mth.clamp(
                ((float) menuElapsedSeconds - (step * getStaggerIndex(index, count, animation))) / elementDuration,
                0.0F,
                1.0F);
    }

    public float getGlobalRevealProgress(RadialConfig config) {
        if (config.revealDurationMs <= 0) return 1.0F;
        return Mth.clamp((float) (revealElapsedSeconds / (config.revealDurationMs / 1000.0F)), 0.0F, 1.0F);
    }

    public float easeOutQuint(float value) {
        value = Mth.clamp(value, 0.0F, 1.0F);
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse * inverse * inverse;
    }

    // --- Input & Actions ---

    private void resetCursorPosition() {
        RadialConfig.ActivationMode mode = RadialConfig.INSTANCE.activationMode;
        if (mode == RadialConfig.ActivationMode.CLICK || mode == RadialConfig.ActivationMode.RELEASE) {
            long windowHandle = minecraft.getWindow().getWindow();
            double centerX = minecraft.getWindow().getWidth() / 2.0;
            double centerY = minecraft.getWindow().getHeight() / 2.0;
            GLFW.glfwSetCursorPos(windowHandle, centerX, centerY);
        }
    }

    private void performAction(RadialSlot slot) {
        slot.mode.performAction(slot, new SlotActionContext() {
            @Override
            public void closeScreen() {
                RadialClient.lockKey();
                RadialScreen.this.onClose();
            }

            @Override
            public void openSubmenu(List<RadialSlot> children, int slotCount) {
                // Hard limit the submenu depth
                if (history.size() >= 5) {
                    return;
                }

                history.push(new MenuState(activeSlots, currentSlotCount));

                activeSlots = children;
                currentSlotCount = slotCount;
                resetAnims();
                if (RadialConfig.INSTANCE.resetCursorOnSubmenu) {
                    resetCursorPosition();
                }
            }

            @Override
            public boolean isRoot() {
                return activeSlots == rootSlots;
            }
        });
    }

    private void goBack() {
        if (!history.isEmpty()) {
            MenuState previous = history.pop();
            activeSlots = previous.slots;
            currentSlotCount = previous.slotCount;
        } else {
            // Fallback just in case, though it shouldn't be reached if the back button is hidden on root
            activeSlots = rootSlots;
            currentSlotCount = RadialConfig.INSTANCE.slotCount;
        }

        resetAnims();
        if (RadialConfig.INSTANCE.resetCursorOnSubmenu) {
            resetCursorPosition();
        }
    }

    private void resetAnims() {
        RadialConfig.ActivationMode mode = RadialConfig.INSTANCE.activationMode;
        hoveredSlot =
                (mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE)
                        ? 0
                        : -1;

        menuElapsedSeconds = 0.0;
        lastNano = System.nanoTime();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        RadialConfig.ActivationMode mode = RadialConfig.INSTANCE.activationMode;
        if (mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE) {
            int renderCount = getRenderCount();
            if (renderCount > 0 && scrollY != 0) {
                int shift = scrollY > 0 ? -1 : 1;
                hoveredSlot = (hoveredSlot + shift + renderCount) % renderCount;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先让 widget (编辑模式按钮等) 处理点击
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        int cx = width / 2;
        int cy = height / 2;
        float scale = getAdaptiveScale();
        float discRadius = RadialConfig.INSTANCE.getVisibleInnerRadius() * scale * 0.85F;

        // Feature 9: 点击中心圆盘编辑主轮盘 (仅在编辑模式且根菜单时)
        if (button == InputConstants.MOUSE_BUTTON_LEFT && editMode && !isSubmenu()) {
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            if (dx * dx + dy * dy <= discRadius * discRadius) {
                if (isYacLAvailable()) {
                    minecraft.setScreen(RadialConfigScreen.create(this));
                } else {
                    minecraft.player.displayClientMessage(
                            Component.literal("需要安装 YACL (YetAnotherConfigLib) 才能编辑主轮盘"),
                            true);
                }
                return true;
            }
        }

        if (hoveredSlot != -1) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                RadialSlot slot = getTargetSlot(hoveredSlot);
                if (slot != null) {
                    performAction(slot);
                    return true;
                }
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (editMode) {
                    // 编辑模式: 右键编辑槽位
                    RadialSlot slot = getTargetSlot(hoveredSlot);
                    if (slot != null) {
                        minecraft.setScreen(new SlotEditorScreen(slot));
                        return true;
                    }
                } else {
                    // 非编辑模式: 右键回退
                    if (isSubmenu()) {
                        goBack();
                        return true;
                    }
                    onClose();
                    return true;
                }
            }
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            onClose();
            return true;
        }

        if (button == InputConstants.MOUSE_BUTTON_RIGHT && !editMode) {
            if (isSubmenu()) {
                goBack();
                return true;
            }
            onClose();
            return true;
        }

        return false;
    }

    private boolean isYacLAvailable() {
        try {
            Class.forName("dev.isxander.yacl3.api.Controller");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 与右键行为一致: 子菜单回退一层, 根菜单才关闭
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (isSubmenu()) {
                goBack();
            } else {
                onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Intentionally skip super.renderBackground to remove the dark gradient
        // so the game world shows through behind the radial.
        // FIXME(1.21.1): Screen#renderBlurredBackground does not exist in 1.21.1 (added
        // later, ~1.21.5). The original `extractBlurredBackground` call (gated by
        // RadialConfig#enableBackgroundBlur) has been removed entirely; the world now
        // renders behind the radial without any blur, regardless of that config flag.
    }
}
