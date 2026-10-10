package dev.velolib.radialmenu.ui.screen;

import dev.velolib.radialmenu.api.RadialSlot;
import dev.velolib.radialmenu.api.SlotMode;
import dev.velolib.radialmenu.mode.MacroSlotMode;
import dev.velolib.radialmenu.ui.widget.DropdownButtonWidget;
import dev.velolib.radialmenu.ui.widget.DropdownMenuWidget;
import dev.velolib.radialmenu.ui.widget.MacroActionList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class MacroEditorScreen extends Screen implements MacroActionList.DropdownHost {

    private static final int HEADER_HEIGHT = 40;
    private static final int FOOTER_HEIGHT = 36;
    private static final int BUTTON_WIDTH = 150;

    private final Screen parent;
    private final RadialSlot slot;

    private final List<RadialSlot.Macro> oldMacros;
    private boolean isSaved = false;

    private MacroActionList actionList;
    private DropdownButtonWidget<SlotMode> openDropdown;

    public MacroEditorScreen(Screen parent, RadialSlot slot) {
        super(Component.translatable("screen.radialmenu.macro.title"));
        this.parent = parent;
        this.slot = slot;

        if (this.slot.macros == null) {
            this.slot.macros = new ArrayList<>();
        }

        this.oldMacros = new ArrayList<>(this.slot.macros);
    }

    @Override
    protected void init() {
        this.openDropdown = null;

        double previousScroll = this.actionList != null ? this.actionList.getScrollAmount() : 0.0;

        this.actionList = new MacroActionList(
                this.minecraft,
                this.width,
                this.height - HEADER_HEIGHT - FOOTER_HEIGHT,
                HEADER_HEIGHT,
                this,
                this.slot,
                this);
        this.actionList.setScrollAmount(previousScroll);
        this.addRenderableWidget(this.actionList);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.radialmenu.editor.save"), b -> {
                    this.isSaved = true;
                    this.onClose();
                })
                .bounds(this.width / 2 - BUTTON_WIDTH - 4, this.height - 28, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.radialmenu.macro.save.tooltip")))
                .build());

        this.addRenderableWidget(
                Button.builder(Component.translatable("screen.radialmenu.editor.cancel"), b -> this.onClose())
                        .bounds(this.width / 2 + 4, this.height - 28, BUTTON_WIDTH, 20)
                        .build());
    }

    // --- Dropdown handling ---

    private DropdownMenuWidget<SlotMode> getOpenMenu() {
        return this.openDropdown != null ? this.openDropdown.getActiveMenu() : null;
    }

    @Override
    public void onDropdownOpened(DropdownButtonWidget<SlotMode> button) {
        if (this.openDropdown != null && this.openDropdown != button) {
            this.openDropdown.closeMenu();
        }
        this.openDropdown = button;

        DropdownMenuWidget<SlotMode> menu = button.getActiveMenu();
        if (menu != null && menu.getY() + menu.getHeight() > this.height) {
            menu.setY(button.getY() - menu.getHeight());
        }
    }

    @Override
    public void onDropdownClosed(DropdownButtonWidget<SlotMode> button) {
        if (this.openDropdown == button) {
            this.openDropdown = null;
        }
    }

    @Override
    public void closeDropdown() {
        if (this.openDropdown != null) {
            this.openDropdown.closeMenu();
        }
    }

    // --- Rendering ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        // Hide hover effects underneath the open dropdown menu
        DropdownMenuWidget<SlotMode> menu = this.getOpenMenu();
        boolean hoveringMenu = menu != null && menu.isMouseOver(mouseX, mouseY);
        int passMouseX = hoveringMenu ? -999 : mouseX;
        int passMouseY = hoveringMenu ? -999 : mouseY;

        super.render(graphics, passMouseX, passMouseY, delta);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        int columnY = HEADER_HEIGHT - 12;
        graphics.drawString(
                this.font,
                Component.translatable("screen.radialmenu.macro.column.type"),
                this.actionList.getTypeColumnX(),
                columnY,
                0xFFA0A0A0);
        graphics.drawString(
                this.font,
                Component.translatable("screen.radialmenu.macro.column.value"),
                this.actionList.getValueColumnX(),
                columnY,
                0xFFA0A0A0);

        // Action counter, highlighted once the limit is reached
        int actionCount = this.actionList.getActionCount();
        Component counter = Component.translatable("screen.radialmenu.macro.count", actionCount, MacroSlotMode.MAX_ACTIONS);
        graphics.drawString(
                this.font,
                counter,
                this.actionList.getRowRight() - 2 - this.font.width(counter),
                columnY,
                actionCount >= MacroSlotMode.MAX_ACTIONS ? 0xFFFF5555 : 0xFFA0A0A0);

        if (menu != null) {
            // graphics.nextStratum() dropped (no direct 1.21.1 equivalent)
            menu.render(graphics, mouseX, mouseY, delta);
        }
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        DropdownMenuWidget<SlotMode> menu = this.getOpenMenu();
        if (menu != null) {
            if (menu.isMouseOver(mouseX, mouseY)) {
                menu.mouseClicked(mouseX, mouseY, button);
                return true;
            }

            // Clicking the owning button lets it toggle itself closed
            if (!this.openDropdown.isMouseOver(mouseX, mouseY)) {
                this.closeDropdown();
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        DropdownMenuWidget<SlotMode> menu = this.getOpenMenu();
        if (menu != null) {
            if (menu.isMouseOver(mouseX, mouseY)) {
                return menu.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }

            // The menu is anchored to its row, so close it before the list scrolls away
            this.closeDropdown();
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.openDropdown != null) {
            this.closeDropdown();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (!this.isSaved) {
            this.slot.macros = new ArrayList<>(this.oldMacros);
        }

        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
