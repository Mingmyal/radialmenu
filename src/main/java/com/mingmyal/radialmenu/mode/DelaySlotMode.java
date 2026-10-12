package com.mingmyal.radialmenu.mode;

import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.api.SlotMode;

import java.util.regex.Pattern;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DelaySlotMode implements SlotMode {

    public static final String DEFAULT_SECONDS = "1";

    private static final int MAX_WHOLE_DIGITS = 4;
    private static final int MAX_DECIMAL_DIGITS = 2;

    // Longest possible delay text, e.g. "9999.99"
    public static final int MAX_LENGTH = MAX_WHOLE_DIGITS + 1 + MAX_DECIMAL_DIGITS;

    private static final Pattern DELAY_PATTERN =
            Pattern.compile("\\d{0," + MAX_WHOLE_DIGITS + "}(\\.\\d{0," + MAX_DECIMAL_DIGITS + "})?");

    private static final int TICKS_PER_SECOND = 20;

    @Override
    public Component getTranslatedName() {
        return Component.translatable("radialmenu.mode.delay");
    }

    @Override
    public boolean isMacroOnly() {
        return true;
    }

    @Override
    public boolean shouldRenderIcon() {
        return false;
    }

    @Override
    public boolean activateOnRelease() {
        return false;
    }

    @Override
    public Component getValueHint() {
        return Component.translatable("screen.radialmenu.editor.hint.delay");
    }

    @Override
    public void performAction(RadialSlot slot, SlotActionContext context) {
        // Does nothing directly. MacroExecutor handles delays.
    }

    @Override
    public void buildEditorWidgets(Screen screen, RadialSlot slot, int width, LinearLayout container) {
        // Delays only exist inside macros and have no standalone editor.
    }

    /**
     * Strips every character that can't be part of a delay (anything other than digits and '.').
     */
    public static String filterChars(String value) {
        if (value == null) return "";

        StringBuilder filtered = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') filtered.append(c);
        }
        return filtered.toString();
    }

    /**
     * Whether the text is a valid (possibly partially typed) delay, such as "", "1", "1." or "0.25".
     */
    public static boolean isValid(String value) {
        return value != null && DELAY_PATTERN.matcher(value).matches();
    }

    /**
     * Returns the value if it's a usable delay, otherwise {@link #DEFAULT_SECONDS}.
     */
    public static String normalize(String value) {
        return isValid(value) && toTicks(value) > 0 ? value : DEFAULT_SECONDS;
    }

    /**
     * Converts a delay in seconds to game ticks, rounded to the nearest tick. Blank or invalid values are no delay.
     */
    public static int toTicks(String value) {
        if (value == null) return 0;

        String trimmed = value.trim();
        if (!isValid(trimmed) || trimmed.isEmpty() || trimmed.equals(".")) return 0;

        return (int) Math.round(Double.parseDouble(trimmed) * TICKS_PER_SECOND);
    }
}
