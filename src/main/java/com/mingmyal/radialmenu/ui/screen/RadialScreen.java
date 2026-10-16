package com.mingmyal.radialmenu.ui.screen;

import com.mingmyal.radialmenu.RadialClient;
import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.api.SlotCondition;
import com.mingmyal.radialmenu.api.toggle.ToggleSource;
import com.mingmyal.radialmenu.client.ConditionDisplay;
import com.mingmyal.radialmenu.client.ServerConfigState;
import com.mingmyal.radialmenu.client.UnlockRequestManager;
import com.mingmyal.radialmenu.config.RadialConfig;
import com.mingmyal.radialmenu.config.RadialConfigScreen;
import com.mingmyal.radialmenu.render.DonutRenderer;
import com.mingmyal.radialmenu.render.SlotRenderHelper;
import com.mingmyal.radialmenu.ui.screen.condition.DependencyScreen;
import com.mingmyal.radialmenu.util.SlotPath;
import com.mingmyal.radialmenu.util.SoundPlayer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Axis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public class RadialScreen extends Screen implements UnlockRequestManager.Listener {

    // How far the detection ring extends beyond the hovered slot (used by detection radius config)
    public static final float SLOT_PUSH = 7.5F;

    private static final int ITEM_SIZE = 16;
    private static final float STAGGER_STEP_FRACTION = 0.4F;

    private static final ItemStack MISSING_ICON = new ItemStack(Items.BARRIER);

    private record MenuState(List<RadialSlot> slots, int slotCount) {}

    private final Deque<MenuState> history = new ArrayDeque<>();

    private final List<RadialSlot> rootSlots;

    private List<RadialSlot> activeSlots;
    private int currentSlotCount;
    private int hoveredSlot = -1;
    // 编辑模式为常驻全局状态, 存于 RadialConfig.INSTANCE.editMode

    // 呼出键松开确认已被消费(进入子菜单等保持开启的场景), 防止重复触发
    private boolean releaseConsumed = false;

    // 条件约束: 等待确认消耗解锁的槽位(非空时显示确认对话框)
    private RadialSlot confirmingSlot;
    // 待确认的条件。必须与 confirmingSlot 一起记录: 开关类型的条件挂在"状态动作"子槽位上,
    // 而 confirmingSlot 指向父槽位, 若事后从父槽位重新取 condition 会拿到 null,
    // 导致对话框没有说明文字、点"是"也不消耗。
    private SlotCondition confirmingCondition;
    private int confirmYesX, confirmNoX, confirmY;
    private int confirmPanelX, confirmPanelY, confirmPanelW, confirmPanelH;
    private static final int CONFIRM_BTN_W = 70;
    private static final int CONFIRM_BTN_H = 20;

    // 等待服务端"消耗解锁"回执期间挂起的槽位动作: 只有服务端确认真的扣掉了东西,
    // 才继续播放音效并执行槽位动作。非空时轮盘挡住输入, 避免重复发起请求。
    private RadialSlot awaitingAction;

    private double revealElapsedSeconds = 0.0;
    private double menuElapsedSeconds = 0.0;
    private long lastNano;

    // super.render 内部会再次调用 renderBackground, 用此标记跳过避免模糊二次叠加
    private boolean suppressBackground = false;

    public RadialScreen() {
        super(Component.empty());
        RadialConfig config = currentConfig();
        this.rootSlots = config.slots;
        this.activeSlots = rootSlots;
        this.currentSlotCount = config.slotCount;
    }

    /**
     * 当前生效的配置: 采用服务端配置时为服务端配置, 否则为本地配置。
     * <p>
     * 槽位列表在构造时就要确定, 因为服务端配置与本地配置的槽位树是两个独立对象。
     */
    private static RadialConfig currentConfig() {
        return ServerConfigState.getEffectiveConfig();
    }

    /** 编辑模式是否生效(采用服务端配置且无权限时恒为关闭)。 */
    private static boolean isEditModeActive() {
        return ServerConfigState.isEditModeActive();
    }

    @Override
    protected void init() {
        // 按生效激活模式同步光标显隐与默认选中(编辑模式下滚轮类激活暂时退化为光标类)
        applyActivationCursor();
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

        // 轮盘已关闭: 不再回调本界面执行槽位动作(否则会把玩家这期间打开的界面顶掉)。
        // 注意只是解除回调 —— 若服务端已经扣过费, UnlockRequestManager 仍会按槽位键
        // 把解锁状态记下来, 玩家不会白花。
        if (UnlockRequestManager.isPending()) {
            UnlockRequestManager.cancel();
            awaitingAction = null;
        }

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
        float radialSize = currentConfig().radialSize;
        if (minDim <= 0) return radialSize;
        return (minDim / 540.0F) * radialSize;
    }

    // --- Render Loop ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        RadialConfig config = currentConfig();
        // 生效模式: 编辑模式下"滚轮 + 点击/释放"暂时退化为"光标 + 点击/释放"
        RadialConfig.ActivationMode mode = getEffectiveActivationMode();
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
            if (confirmingSlot != null || UnlockRequestManager.isPending()) {
                // 确认对话框打开期间, 或正在等待服务端扣除回执:
                // 松开呼出键只标记消费, 不关闭轮盘也不触发动作 —— 否则请求还没回来界面就没了
            } else {
                // 仅"释放确认"模式(RELEASE / SCROLL_RELEASE)才在松开时触发动作;
                // CLICK / SCROLL_CLICK 模式松开只关闭轮盘, 动作由鼠标点击触发
                boolean releaseConfirms =
                        mode == RadialConfig.ActivationMode.RELEASE
                                || mode == RadialConfig.ActivationMode.SCROLL_RELEASE;
                if (releaseConfirms && hoveredSlot != -1) {
                    RadialSlot slot = getTargetSlot(hoveredSlot);
                    // 已绑定的外部开关直接切换, 不受状态动作模式(如"无操作")影响
                    boolean canRelease = slot != null
                            && (slot.isBoundExternalToggle() || slot.resolveActionSlot().mode.activateOnRelease());
                    if (canRelease && tryActivate(slot)) {
                        return;
                    }
                }
                // 无有效悬停/非释放确认模式/动作不支持松开确认 → 关闭轮盘
                onClose();
                return;
            }
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

        // 确认对话框 / 等待服务端回执期间禁止选中任何槽位: 避免玩家以为还能再点一次
        if (confirmingSlot != null || UnlockRequestManager.isPending()) {
            hoveredSlot = -1;
        }

        // 3.5 背景层: 模糊必须在轮盘 UI 之前渲染, 否则 UI 会一起被模糊
        this.renderBackground(graphics, mouseX, mouseY, delta);

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

                DonutRenderer.renderSector(
                        cx, cy, slotAngle,
                        baseInner * revealEase, baseOuter * revealEase,
                        (i == hoveredSlot), isSlotUnmet(getTargetSlot(i)), revealEase, renderCount);
            }
        }

        // 6. 中心圆盘: 填充/边框颜色与扇环共用同一配置; 半径比扇环内缘小一圈, 露出窄空隙不粘连
        float discRadius = baseInner * globalReveal - Math.max(5.0F, baseOuter * 0.04F);
        if (discRadius > 0) {
            boolean sectorHovered = hoveredSlot != -1;
            // 填充: 直接用 backgroundColor(含自身 alpha), 与扇环同源同透明度
            DonutRenderer.drawDisc(cx, cy, discRadius, config.backgroundColor.getRGB(), globalReveal);
            // 边框: 与扇环边框共用 borderColor/highlightBorderColor(未满足条件时用独立配色)
            int ringARGB;
            if (sectorHovered) {
                RadialSlot hoveredSlotObj = getTargetSlot(hoveredSlot);
                ringARGB = isSlotUnmet(hoveredSlotObj)
                        ? config.unmetHighlightBorderColor.getRGB()
                        : config.highlightBorderColor.getRGB();
            } else {
                ringARGB = config.borderColor.getRGB();
            }
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
            boolean unmet = isSlotUnmet(slot);
            if (slot != null) {
                // 槽位图标为 26x26, 以 (slotX, iconCenterY) 为中心绘制
                // 未满足条件且开启"上调透明度"时: 纹理/字形图标走 alpha 变淡,
                // 物品图标通过 shader color 等比压暗像素 RGB(透明部分保持透明, 不产生色块)
                boolean dimIcon = unmet && config.dimUnmetIcon;
                // 开关类型色调: 关=淡红 / 开=薄荷绿(可在槽位设置中独立关闭);
                // 与条件压暗系数逐通道相乘, 两者可叠加
                Integer toggleTint = slot.getToggleTintColor();
                float tintR = 1.0F;
                float tintG = 1.0F;
                float tintB = 1.0F;
                if (toggleTint != null) {
                    // 逐通道乘法: 白色(或字形默认色)图标乘完正好等于设定色
                    tintR = ((toggleTint >> 16) & 0xFF) / 255.0F;
                    tintG = ((toggleTint >> 8) & 0xFF) / 255.0F;
                    tintB = (toggleTint & 0xFF) / 255.0F;
                }
                float dimFactor = dimIcon ? 0.45F : 1.0F;
                SlotRenderHelper.renderSlotIcon(
                        graphics, slot,
                        -slot.getIconWidth() / 2.0F, -slot.getIconHeight() / 2.0F,
                        revealAlpha, dimFactor, tintR, tintG, tintB);
            } else {
                graphics.renderItem(MISSING_ICON, -ITEM_SIZE / 2, -ITEM_SIZE / 2);
            }
            // 未满足条件时在图标上绘制斜杠标识(抬升到物品像素图层之上, 保证可见)
            if (unmet && config.drawUnmetSlash) {
                int slashColor = applyAlpha(config.unmetSlashColor.getRGB(), revealAlpha / 255.0F);
                iconPose.pushPose();
                iconPose.translate(0.0F, 0.0F, 300.0F);
                iconPose.mulPose(Axis.ZP.rotationDegrees(-45.0F));
                graphics.fill(-17, -1, 17, 1, slashColor);
                iconPose.popPose();
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
            if (isEditModeActive() && !isSubmenu()) {
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
            Component hintComponent = null;
            int hintColor = 0;
            if (hoveredSlot != -1) {
                RadialSlot hovered = getTargetSlot(hoveredSlot);
                if (hovered != null) {
                    // 开关类型: 条件约束由各状态动作独立配置, 检查"即将执行"的状态动作条件
                    RadialSlot condSlot = hovered.resolveActionSlot();
                    SlotCondition condition = condSlot.conditionEnabled ? condSlot.condition : null;
                    // 编辑模式下右键用于编辑槽位, 不显示条件相关提示, 直接归还原选中提示
                    boolean showConditionHint = condition != null && !isEditModeActive();

                    if (!showConditionHint || condition.isUnlocked() || isItemCheckSatisfied(condition)) {
                        // 归还原选中提示; 限期解锁在文本末尾追加剩余时间(自动换算单位)
                        String base = hovered.selectedHint != null ? hovered.selectedHint : "";
                        if (showConditionHint
                                && condition.isUnlocked()
                                && condition.timedUnlock
                                && !condition.isUnlockedForever()) {
                            String remain = ConditionDisplay.remainingTimeText(condition);
                            if (!remain.isEmpty()) {
                                String suffix = Component.translatable("radialmenu.condition.remaining", remain)
                                        .getString();
                                base = base.isEmpty() ? suffix : base + " " + suffix;
                            }
                        }
                        hintColor = applyAlpha(hovered.getSelectedHintColor(), globalAlpha / 255.0F);
                        hintComponent = buildHintComponent(base, hovered);
                    } else if (condition.isConsumeType() && condition.canAfford(this.minecraft.player)) {
                        // 消耗解锁类资源充足: 提示点击解锁(绿色), 具体依赖由右键界面查看
                        hintColor = applyAlpha(SlotCondition.GREEN, globalAlpha / 255.0F);
                        hintComponent = Component.translatable("radialmenu.condition.hint.click_unlock");
                    } else {
                        // 条件未满足: 用红色提示引导玩家右键查看解锁依赖, 不再直接铺开物品清单
                        hintColor = applyAlpha(SlotCondition.RED, globalAlpha / 255.0F);
                        hintComponent = Component.translatable("radialmenu.condition.hint.view_dependency");
                    }
                }
            }
            if (hintComponent != null) {
                drawScaledCentered(
                        graphics,
                        hintComponent,
                        cx,
                        selectedYf,
                        hintColor,
                        selectedHintScale);
            }
        }

        // 10. Bottom Hint —— 用配置颜色, 随整体缩放
        {
            Component hint;
            if (UnlockRequestManager.isPending()) {
                // 已把扣除请求交给服务端, 等回执: 明确告知玩家正在处理, 而不是像卡住了
                hint = Component.translatable("radialmenu.ui.hint_consuming");
            } else if (confirmingSlot != null) {
                // 确认框打开期间: 提示如何关闭
                hint = Component.translatable("radialmenu.ui.hint_close_overlay");
            } else {
                Component keyName = RadialClient.OPEN_RADIAL.getTranslatedKeyMessage();
                hint = Component.translatable("radialmenu.ui.hint_confirm_back", keyName);
                // 非编辑模式下悬停带条件的槽位: 追加右键查看解锁依赖的提示
                if (!isEditModeActive() && hoveredSlot != -1) {
                    RadialSlot hovered = getTargetSlot(hoveredSlot);
                    if (shouldShowDependency(resolveConditionSlot(hovered))) {
                        hint = Component.translatable(
                                "radialmenu.ui.hint_confirm_back_dependency", keyName);
                    }
                }
            }
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

        // 12. 控件层(编辑模式按钮等)。super.render 内部会再次调用 renderBackground,
        // 用 suppressBackground 标记跳过, 避免模糊二次叠加到轮盘 UI 上
        suppressBackground = true;
        super.render(graphics, mouseX, mouseY, delta);
        suppressBackground = false;

        // 13. 条件约束消耗确认对话框(渲染在最顶层, 覆盖轮盘与控件)
        if (confirmingSlot != null) {
            renderConfirmDialog(graphics, mouseX, mouseY);
        }
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

    /**
     * 构建选中提示的 MutableComponent。
     * 开关类型启用时, 在基础文本后追加一个空格再拼接状态文本,
     * 并将状态文本着色为绿色(开启)/红色(关闭); 基础文本为空时仅显示着色后的状态文本。
     *
     * @param base 选中提示基础文本(已含限期解锁剩余时间后缀, 可能为空)
     * @param slot 当前悬停槽位
     * @return 组装后的提示组件, 为 null 表示不显示任何提示
     */
    private static MutableComponent buildHintComponent(String base, RadialSlot slot) {
        if (!slot.toggleEnabled) {
            return base.isEmpty() ? null : Component.literal(base);
        }
        String stateText = slot.getToggleStateText();
        if (stateText.isEmpty()) {
            return base.isEmpty() ? null : Component.literal(base);
        }
        // 开启=薄荷绿, 关闭=淡红(与图标状态色调保持同一套配色)
        TextColor stateColor = TextColor.fromRgb(
                slot.getEffectiveToggleState() ? RadialSlot.TOGGLE_TINT_ON : RadialSlot.TOGGLE_TINT_OFF);
        if (base.isEmpty()) {
            return Component.literal(stateText).withStyle(Style.EMPTY.withColor(stateColor));
        }
        return Component.literal(base)
                .append(Component.literal(" " + stateText).withStyle(Style.EMPTY.withColor(stateColor)));
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

    private float getGlobalRevealProgress(RadialConfig config) {
        if (config.revealDurationMs <= 0) return 1.0F;
        return Mth.clamp((float) (revealElapsedSeconds / (config.revealDurationMs / 1000.0F)), 0.0F, 1.0F);
    }

    private float easeOutQuint(float value) {
        value = Mth.clamp(value, 0.0F, 1.0F);
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse * inverse * inverse;
    }

    // --- Input & Actions ---

    private void resetCursorPosition() {
        RadialConfig.ActivationMode mode = getEffectiveActivationMode();
        if (mode == RadialConfig.ActivationMode.CLICK || mode == RadialConfig.ActivationMode.RELEASE) {
            long windowHandle = minecraft.getWindow().getWindow();
            double centerX = minecraft.getWindow().getWidth() / 2.0;
            double centerY = minecraft.getWindow().getHeight() / 2.0;
            GLFW.glfwSetCursorPos(windowHandle, centerX, centerY);
        }
    }

    private void performAction(RadialSlot slot) {
        // 开关类型: 先应用新状态, 再执行该状态对应的动作配置。
        // 绑定外部开关来源时写入目标模组, 否则翻转本地状态(随配置持久化)。
        if (slot.toggleEnabled) {
            ToggleSource source = slot.resolveToggleSource();
            if (source != null) {
                boolean target = !slot.getEffectiveToggleState();
                try {
                    source.setState(target);
                } catch (Throwable t) {
                    RadialCommon.LOGGER.error(
                            "Failed to write external toggle '{}' for slot '{}'", slot.toggleSourceId, slot.name, t);
                }
                // 本地状态同步一份, 保证来源消失时界面仍能显示合理的状态
                slot.toggleState = target;
                saveRuntimeState();

                // 已绑定的外部开关: 点击就是"切换那个开关", 不再执行任何动作模式。
                // 这样玩家不需要为开关再配一遍开启/关闭动作。
                RadialClient.lockKey();
                RadialScreen.this.onClose();
                return;
            }
            slot.toggleState = !slot.toggleState;
            saveRuntimeState();
        }
        RadialSlot actionSlot = slot.resolveActionSlot();
        actionSlot.mode.performAction(actionSlot, new SlotActionContext() {
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
                if (currentConfig().resetCursorOnSubmenu) {
                    resetCursorPosition();
                }
            }

            @Override
            public boolean isRoot() {
                return activeSlots == rootSlots;
            }
        });
    }

    // --- 条件约束 (Conditional Constraints) ---

    /**
     * 持久化槽位的运行期状态(开关状态 / 条件解锁状态)。
     * <p>
     * 采用服务端配置时必须额外写一份按服覆盖表: 强制模式下生效的是只读的纯净配置,
     * {@link RadialConfig#save()} 会被直接忽略, 这些状态一重进游戏就会被服务端下发的内容重置
     * —— 解锁状态尤其严重, 玩家是真的把物品/经验消耗掉了。
     * 非强制时这份副本同样可能因服主更新配置而被重建, 所以两条路径都写。
     * 使用自己的全局配置时这些状态本来就在配置本体里, 走原有的保存即可。
     */
    private static void saveRuntimeState() {
        if (ServerConfigState.isUsingServerConfig()) {
            // 同步写覆盖表: 强制模式下这是唯一能落盘的地方(纯净配置只读),
            // 非强制时也能兜住"服主更新配置导致副本被重建"的情况
            ServerConfigState.saveRuntimeStateNow();
        }
        // 非强制时按原有路径保存副本/全局配置; 强制时该调用会被只读检查忽略
        RadialConfig.save();
    }

    /**
     * 返回用于条件约束检查的槽位: 开关类型启用时使用"即将执行"的状态动作槽位,
     * 非开关类型使用自身。条件约束在开关类型下由各状态动作独立配置。
     */
    private RadialSlot resolveConditionSlot(RadialSlot slot) {
        return slot != null ? slot.resolveActionSlot() : null;
    }

    /** 槽位是否因条件约束未满足而不可点击。 */
    private boolean isSlotUnmet(RadialSlot slot) {
        RadialSlot cond = resolveConditionSlot(slot);
        return cond != null
                && cond.conditionEnabled
                && cond.condition != null
                && !cond.condition.isSatisfied(this.minecraft.player);
    }

    /** 背包持有检测类条件已满足时, 直接归还槽位自身的选中提示并允许点击。 */
    private boolean isItemCheckSatisfied(SlotCondition condition) {
        return condition.type == SlotCondition.ConditionType.ITEM_CHECK
                && condition.isSatisfied(this.minecraft.player);
    }

    /**
     * 右键是否应打开"查看解锁依赖"面板。
     * <p>
     * 消耗解锁类在<b>尚未解锁</b>时都可查看(资源够了也能先看清单再点);
     * 持有检测类只在<b>不满足</b>时查看(满足了槽位本就可直接用)。
     * 编辑模式优先进入槽位编辑, 不走这里。
     */
    private boolean shouldShowDependency(RadialSlot condSlot) {
        if (condSlot == null || !condSlot.conditionEnabled || condSlot.condition == null) {
            return false;
        }
        SlotCondition condition = condSlot.condition;
        return condition.isConsumeType()
                ? !condition.isUnlocked()
                : !condition.isSatisfied(this.minecraft.player);
    }

    /**
     * 尝试激活槽位: 条件未满足时忽略; 消耗解锁类未解锁时按配置弹确认对话框或直接消耗。
     *
     * @return true 表示已处理(轮盘保持开启), false 表示条件未满足(调用方决定吞掉点击或关闭轮盘)
     */
    private boolean tryActivate(RadialSlot slot) {
        // 等待服务端回执期间不接受新的激活: 否则玩家连点会发出多次扣费请求
        if (UnlockRequestManager.isPending()) {
            return true;
        }
        if (slot == null || isSlotUnmet(slot)) {
            return false;
        }

        RadialSlot condSlot = resolveConditionSlot(slot);
        RadialConfig config = currentConfig();
        if (condSlot.conditionEnabled && condSlot.condition != null) {
            SlotCondition condition = condSlot.condition;

            if (condition.needsUnlockConfirm()) {
                // 物品/耐久 或 经验 消耗解锁
                boolean confirm = condition.type == SlotCondition.ConditionType.ITEM_CONSUME
                        ? config.confirmItemConsumeUnlock
                        : config.confirmXpConsumeUnlock;
                if (confirm) {
                    confirmingSlot = slot;
                    confirmingCondition = condition;
                    // 保持轮盘开启, 等待对话框确认(呼出键松开不再关闭)
                    releaseConsumed = true;
                    return true;
                }
                return finalizeConsume(slot);
            }
        }

        playSlotSound(slot);
        performAction(slot);
        return true;
    }

    /** 播放槽位点击音效(槽位关闭了点击音效时静音)。 */
    private void playSlotSound(RadialSlot slot) {
        if (slot == null || !slot.isClickSoundEnabled()) {
            return;
        }
        String sound = RadialSlot.normalizeClickSound(slot.clickSound);
        SoundPlayer.play(sound);
    }

    /** 执行消耗解锁: 交由服务端权威扣除, 成功后继续执行槽位动作。 */
    private boolean finalizeConsume(RadialSlot slot) {
        // 消耗必须由服务端执行: 本地扣除只会改本地 ItemStack, 服务端背包从未变化,
        // 退出重进时会被权威背包同步"返还"。这里只发请求, 等回执再继续。
        RadialSlot condSlot = resolveConditionSlot(slot);
        String key = slotKeyOf(slot, condSlot);

        if (key == null) {
            // 找不到定位键: 无法让服务端定位到同一条件, 明确失败而不是退回本地扣除
            notifyConsumeFailure("radialmenu.condition.consume.invalid_slot");
            return true;
        }

        awaitingAction = slot;

        if (!UnlockRequestManager.request(slot, condSlot, key, this)) {
            awaitingAction = null;
        }
        return true;
    }

    /**
     * 服务端回执到达: 成功则执行槽位动作, 失败则提示原因。
     * <p>
     * 由 {@link UnlockRequestManager} 在主线程回调。
     */
    @Override
    public void onConsumeUnlockResult(boolean granted, String reasonKey) {
        RadialSlot slot = awaitingAction;
        awaitingAction = null;

        if (!granted) {
            if (reasonKey != null && !reasonKey.isEmpty()) {
                notifyConsumeFailure(reasonKey);
            }
            return;
        }

        if (slot == null) {
            return;
        }

        // 解锁已由服务端记账并回写本地状态; 这里接着走原本"消耗成功后"的动作流程。
        playSlotSound(slot);
        performAction(slot);
    }

    /** 在操作栏以红色文本提示失败原因。 */
    private void notifyConsumeFailure(String translationKey) {
        LocalPlayer player = this.minecraft != null ? this.minecraft.player : null;
        if (player != null) {
            player.displayClientMessage(Component.translatable(translationKey), true);
        }
    }

    /**
     * 求槽位在槽位树中的定位键, 供服务端按同一路径定位条件。
     * <p>
     * 开关类型的条件挂在状态动作子槽位上, 因此键需要带上 {@code #on}/{@code #off} 后缀,
     * 与服务端/覆盖表的遍历保持一致。遍历顺序复用 {@link SlotPath}, 避免两处各写一份而错位。
     */
    private static String slotKeyOf(RadialSlot slot, RadialSlot condSlot) {
        if (slot == null) {
            return null;
        }

        // 记录目标槽位自身与其两个状态动作槽位的键
        final String[] selfKey = {null};
        final String[] onKey = {null};
        final String[] offKey = {null};

        SlotPath.forEach(currentConfig().slots, (visited, key) -> {
            if (visited == slot) {
                selfKey[0] = key;
            } else if (visited == slot.toggleActionOn) {
                onKey[0] = key;
            } else if (visited == slot.toggleActionOff) {
                offKey[0] = key;
            }
        });

        if (selfKey[0] == null) {
            return null;
        }
        if (condSlot == null || condSlot == slot) {
            return selfKey[0];
        }
        if (condSlot == slot.toggleActionOn) {
            return onKey[0] != null ? onKey[0] : SlotPath.withAction(selfKey[0], true);
        }
        if (condSlot == slot.toggleActionOff) {
            return offKey[0] != null ? offKey[0] : SlotPath.withAction(selfKey[0], false);
        }
        return selfKey[0];
    }

    /** 条件约束确认对话框(覆盖在轮盘之上, 保持轮盘状态)。 */
    private void renderConfirmDialog(GuiGraphics graphics, int mouseX, int mouseY) {
        SlotCondition condition = confirmingCondition;

        var pose = graphics.pose();
        pose.pushPose();
        // 抬升到物品像素(z≈200)/图标遮罩(z250)/斜杠(z300)之上, 确保对话框真正置顶
        pose.translate(0.0F, 0.0F, 400.0F);

        graphics.fill(0, 0, width, height, 0x88000000);

        int padding = 12;
        int titleHeight = 14;
        int lineHeight = 12;
        int hintHeight = 14;
        int gap = 6;

        // 内容: 只列当前背包实际会被消耗的东西(集合只显示真正扣掉的那一件), 每行一项
        List<Component> lines = condition != null
                ? ConditionDisplay.describeConsumptionLines(condition)
                : List.of();

        // 面板宽度按最长一行计算, 上限为屏幕宽度减去边距
        int textWidth = font.width(Component.translatable("radialmenu.ui.confirm.title"));
        textWidth = Math.max(textWidth, font.width(Component.translatable("radialmenu.ui.confirm.consume_hint")));
        for (Component line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        confirmPanelW = Math.min(Math.max(textWidth + padding * 2, 200), width - 40);

        // 高度按实际行数动态计算, 保证换行/多行时不会与下方文本重叠。
        // 底部固定区(提示 + 按钮 + 内边距)先留出, 剩余空间才给物品行;
        // 这样"物品行"与"提示/按钮"在结构上就不可能重叠。
        int bottomFixed = padding + CONFIRM_BTN_H + gap + hintHeight;
        int headerHeight = padding + titleHeight + gap;

        int desiredH = headerHeight + lines.size() * lineHeight + bottomFixed;
        int maxPanelH = Math.max(bottomFixed + headerHeight + lineHeight, height - 40);
        confirmPanelH = Math.min(desiredH, maxPanelH);

        // 物品行可用空间(可能为 0, 此时只显示截断提示)
        int linesSpace = confirmPanelH - headerHeight - bottomFixed;
        int maxLines = Math.max(0, linesSpace / lineHeight);
        int shown = Math.min(lines.size(), maxLines);
        // 放不下时, 末行让位给"还有N项"
        boolean truncated = shown < lines.size();
        if (truncated && shown > 0) {
            shown--;
        }

        confirmPanelX = (width - confirmPanelW) / 2;
        confirmPanelY = (height - confirmPanelH) / 2;
        int panelEndX = confirmPanelX + confirmPanelW;
        int panelEndY = confirmPanelY + confirmPanelH;

        graphics.fill(confirmPanelX, confirmPanelY, panelEndX, panelEndY, 0xF00E0E0E);
        // 白色边框
        graphics.fill(confirmPanelX, confirmPanelY, panelEndX, confirmPanelY + 1, 0xFFFFFFFF);
        graphics.fill(confirmPanelX, panelEndY - 1, panelEndX, panelEndY, 0xFFFFFFFF);
        graphics.fill(confirmPanelX, confirmPanelY, confirmPanelX + 1, panelEndY, 0xFFFFFFFF);
        graphics.fill(panelEndX - 1, confirmPanelY, panelEndX, panelEndY, 0xFFFFFFFF);

        int y = confirmPanelY + padding;
        graphics.drawCenteredString(
                font,
                Component.translatable("radialmenu.ui.confirm.title"),
                width / 2,
                y,
                0xFFFFFFFF);
        y += titleHeight + gap;

        for (int i = 0; i < shown; i++) {
            graphics.drawCenteredString(font, lines.get(i), width / 2, y, 0xFFCCCCCC);
            y += lineHeight;
        }
        if (truncated) {
            graphics.drawCenteredString(
                    font,
                    Component.translatable("radialmenu.ui.confirm.more", lines.size() - shown),
                    width / 2,
                    y,
                    0xFF999999);
        }

        // 提示与按钮锚定在面板底部, 与上面的物品行天然分离
        graphics.drawCenteredString(
                font,
                Component.translatable("radialmenu.ui.confirm.consume_hint"),
                width / 2,
                panelEndY - padding - CONFIRM_BTN_H - gap - hintHeight,
                0xFF999999);

        confirmYesX = width / 2 - CONFIRM_BTN_W - 6;
        confirmNoX = width / 2 + 6;
        confirmY = panelEndY - CONFIRM_BTN_H - padding;
        drawDialogButton(
                graphics,
                confirmYesX,
                confirmY,
                Component.translatable("radialmenu.ui.confirm.yes"),
                0xFF3F5A3F,
                isConfirmButtonHovered(confirmYesX, mouseX, mouseY));
        drawDialogButton(
                graphics,
                confirmNoX,
                confirmY,
                Component.translatable("radialmenu.ui.confirm.no"),
                0xFF5A3F3F,
                isConfirmButtonHovered(confirmNoX, mouseX, mouseY));

        pose.popPose();
    }

    private boolean isConfirmButtonHovered(int btnX, int mouseX, int mouseY) {
        return mouseX >= btnX && mouseX < btnX + CONFIRM_BTN_W
                && mouseY >= confirmY && mouseY < confirmY + CONFIRM_BTN_H;
    }

    // --- 解锁依赖 (Unlock Requirements) ---

    /**
     * 打开解锁依赖界面: 依赖可能很多(集合可展开到上百条), 因此用独立的可滚动界面,
     * 而不是盖在轮盘上的固定面板。只应在 {@link #shouldShowDependency(RadialSlot)} 为真时调用。
     * <p>
     * 以轮盘自身作为父界面, 关闭后回到轮盘(子菜单层级与选中状态都还在), 方便直接点击解锁。
     */
    private void openDependencyScreen(RadialSlot slot) {
        RadialSlot condSlot = resolveConditionSlot(slot);
        if (!shouldShowDependency(condSlot)) {
            return;
        }
        // 标记松开已消费: 玩家可能在依赖界面里松开呼出键, 返回轮盘时不应立即触发释放动作
        releaseConsumed = true;
        minecraft.setScreen(new DependencyScreen(this, condSlot.condition));
    }

    private void drawDialogButton(GuiGraphics graphics, int x, int y, Component text, int fillColor, boolean hovered) {
        // 选中(悬停)时提亮底色, 并以 2px 强调边框替代默认 1px 白边
        graphics.fill(x, y, x + CONFIRM_BTN_W, y + CONFIRM_BTN_H, hovered ? brighten(fillColor) : fillColor);
        int borderColor = hovered
                ? currentConfig().highlightBorderColor.getRGB() | 0xFF000000
                : 0xFFFFFFFF;
        int t = hovered ? 2 : 1;
        graphics.fill(x, y, x + CONFIRM_BTN_W, y + t, borderColor);
        graphics.fill(x, y + CONFIRM_BTN_H - t, x + CONFIRM_BTN_W, y + CONFIRM_BTN_H, borderColor);
        graphics.fill(x, y, x + t, y + CONFIRM_BTN_H, borderColor);
        graphics.fill(x + CONFIRM_BTN_W - t, y, x + CONFIRM_BTN_W, y + CONFIRM_BTN_H, borderColor);
        graphics.drawCenteredString(font, text, x + CONFIRM_BTN_W / 2, y + (CONFIRM_BTN_H - 8) / 2, 0xFFFFFFFF);
    }

    /** 提亮颜色 RGB 各通道(保持 alpha)。 */
    private static int brighten(int color) {
        int r = Math.min(255, (color >> 16 & 0xFF) + 0x20);
        int g = Math.min(255, (color >> 8 & 0xFF) + 0x20);
        int b = Math.min(255, (color & 0xFF) + 0x20);
        return color & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** 对话框打开期间的所有鼠标点击都由对话框处理。 */
    private boolean handleConfirmClick(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }

        boolean insidePanel =
                mouseX >= confirmPanelX && mouseX < confirmPanelX + confirmPanelW
                        && mouseY >= confirmPanelY && mouseY < confirmPanelY + confirmPanelH;

        boolean yes = mouseX >= confirmYesX
                && mouseX < confirmYesX + CONFIRM_BTN_W
                && mouseY >= confirmY
                && mouseY < confirmY + CONFIRM_BTN_H;
        boolean no = mouseX >= confirmNoX
                && mouseX < confirmNoX + CONFIRM_BTN_W
                && mouseY >= confirmY
                && mouseY < confirmY + CONFIRM_BTN_H;

        if (yes) {
            RadialSlot slot = confirmingSlot;
            SlotCondition condition = confirmingCondition;
            confirmingSlot = null;
            confirmingCondition = null;
            if (condition != null) {
                finalizeConsume(slot);
            }
            return true;
        }

        if (no || !insidePanel) {
            // "否"或点击面板外: 取消消耗, 返回轮盘
            confirmingSlot = null;
            confirmingCondition = null;
            return true;
        }

        return true;
    }

    private void goBack() {
        if (!history.isEmpty()) {
            MenuState previous = history.pop();
            activeSlots = previous.slots;
            currentSlotCount = previous.slotCount;
        } else {
            // Fallback just in case, though it shouldn't be reached if the back button is hidden on root
            activeSlots = rootSlots;
            currentSlotCount = currentConfig().slotCount;
        }

        resetAnims();
        if (currentConfig().resetCursorOnSubmenu) {
            resetCursorPosition();
        }
    }

    private void resetAnims() {
        RadialConfig.ActivationMode mode = getEffectiveActivationMode();
        hoveredSlot =
                (mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE)
                        ? 0
                        : -1;

        menuElapsedSeconds = 0.0;
        lastNano = System.nanoTime();
    }

    /**
     * 生效的激活模式: 编辑模式下"滚轮 + 点击/释放"暂时失效, 退化为"光标 + 点击/释放",
     * 以便用原生光标瞄准进行编辑操作。
     */
    private RadialConfig.ActivationMode getEffectiveActivationMode() {
        RadialConfig.ActivationMode mode = currentConfig().activationMode;
        if (isEditModeActive()) {
            return switch (mode) {
                case SCROLL_CLICK -> RadialConfig.ActivationMode.CLICK;
                case SCROLL_RELEASE -> RadialConfig.ActivationMode.RELEASE;
                default -> mode;
            };
        }
        return mode;
    }

    /** 按生效激活模式同步原生光标显隐(滚轮类隐藏, 光标类显示)与默认选中槽位。 */
    private void applyActivationCursor() {
        RadialConfig.ActivationMode mode = getEffectiveActivationMode();
        long windowHandle = minecraft.getWindow().getWindow();
        if (mode == RadialConfig.ActivationMode.SCROLL_CLICK || mode == RadialConfig.ActivationMode.SCROLL_RELEASE) {
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        } else {
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        resetAnims();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 确认对话框打开期间吞掉滚轮, 防止切换选中槽位
        if (confirmingSlot != null) {
            return true;
        }
        RadialConfig.ActivationMode mode = getEffectiveActivationMode();
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
        // 等待服务端扣除回执期间吞掉所有点击: 此时 hoveredSlot 已被清空,
        // 若不拦住, 左键会一路走到最下面的 onClose() 把轮盘关掉, 请求连同挂起的动作一起作废
        if (UnlockRequestManager.isPending()) {
            return true;
        }

        // 条件约束确认对话框打开时, 所有点击先交给对话框处理
        if (confirmingSlot != null) {
            return handleConfirmClick(mouseX, mouseY, button);
        }

        // 先让 widget (编辑模式按钮等) 处理点击
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        int cx = width / 2;
        int cy = height / 2;
        float scale = getAdaptiveScale();
        float discRadius = currentConfig().getVisibleInnerRadius() * scale * 0.85F;

        // Feature 9: 点击中心圆盘编辑主轮盘 (仅在编辑模式且根菜单时)
        if (button == InputConstants.MOUSE_BUTTON_LEFT && isEditModeActive() && !isSubmenu()) {
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            if (dx * dx + dy * dy <= discRadius * discRadius) {
                if (!ServerConfigState.canOpenSettings()) {
                    // 强制应用服务端配置且无权限: 拒绝进入设置界面并提示红色文本
                    ServerConfigState.notifyNoPermission();
                    return true;
                }
                if (isYacLAvailable()) {
                    minecraft.setScreen(RadialConfigScreen.create(this));
                } else {
                    ServerConfigState.notifyInfo("radialmenu.ui.yacl_missing");
                }
                return true;
            }
        }

        if (hoveredSlot != -1) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                RadialSlot slot = getTargetSlot(hoveredSlot);
                if (slot != null) {
                    // 条件未满足时吞掉点击(轮盘保持开启, 提示为红色); 其余走确认/消耗/动作流程
                    tryActivate(slot);
                    return true;
                }
            } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (isEditModeActive()) {
                    // 编辑模式: 右键编辑槽位(优先级高于"查看解锁依赖", 编辑模式下不改右键语义)
                    RadialSlot slot = getTargetSlot(hoveredSlot);
                    if (slot != null) {
                        if (!ServerConfigState.canEditSlots()) {
                            // 强制模式下槽位改不了也存不下, 明确告知而不是静默丢弃
                            ServerConfigState.notifyRed(
                                    Component.translatable(
                                            "radialmenu.server_config.forced_slot_edit"));
                            return true;
                        }
                        minecraft.setScreen(new SlotEditorScreen(slot));
                        return true;
                    }
                } else {
                    // 非编辑模式: 有条件约束时右键查看解锁依赖; 否则回退
                    RadialSlot slot = getTargetSlot(hoveredSlot);
                    RadialSlot condSlot = resolveConditionSlot(slot);
                    if (shouldShowDependency(condSlot)) {
                        openDependencyScreen(slot);
                        return true;
                    }
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

        if (button == InputConstants.MOUSE_BUTTON_RIGHT && !isEditModeActive()) {
            if (isSubmenu()) {
                goBack();
                return true;
            }
            onClose();
            return true;
        }

        return false;
    }

    private static boolean isYacLAvailable() {
        return RadialConfigScreen.isAvailable();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 与右键行为一致: 子菜单回退一层, 根菜单才关闭
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (confirmingSlot != null) {
                // 先取消条件约束确认对话框
                confirmingSlot = null;
                confirmingCondition = null;
                return true;
            }
            if (isSubmenu()) {
                goBack();
            } else {
                onClose();
            }
            return true;
        }

        // K 键切换编辑模式(常驻全局状态, 不打开轮盘也可切换), 切换时在操作栏显示提示
        // 采用服务端配置且无权限时: 拒绝切换并提示红色文本
        if (RadialClient.TOGGLE_EDIT.matches(keyCode, scanCode)) {
            ServerConfigState.toggleEditMode();
            // 编辑模式下滚轮类激活暂时失效: 同步原生光标显隐与默认选中槽位
            applyActivationCursor();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Intentionally skip super.renderBackground to remove the dark gradient
        // so the game world shows through behind the radial.
        // 背景模糊: 1.21.1 的 Screen#renderBlurredBackground(float) 存在(protected, 之前 FIXME
        // 注释有误), 它调用 GameRenderer 的原生模糊后期着色器, 在世界渲染之上生效,
        // 与原版暂停菜单同路径, 兼容各图形驱动且无额外性能开销。
        if (suppressBackground) {
            // super.render 内部的二次调用: 模糊已在轮盘 UI 渲染前执行过
            return;
        }
        RadialConfig config = currentConfig();
        if (config.enableBackgroundBlur && config.backgroundBlurRadius > 0) {
            // 自定义模糊程度: 临时改写菜单模糊半径选项(processBlurEffect 同帧同步读取), 用完恢复
            var blurOption = this.minecraft.options.menuBackgroundBlurriness();
            int previous = blurOption.get();
            if (previous != config.backgroundBlurRadius) {
                blurOption.set(config.backgroundBlurRadius);
            }
            this.renderBlurredBackground(delta);
            if (previous != config.backgroundBlurRadius) {
                blurOption.set(previous);
            }
        }
    }
}
