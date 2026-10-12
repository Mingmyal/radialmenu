package com.mingmyal.radialmenu.util;

import com.mingmyal.radialmenu.RadialCommon;
import com.mingmyal.radialmenu.api.RadialSlot;
import com.mingmyal.radialmenu.api.SlotActionContext;
import com.mingmyal.radialmenu.mode.DelaySlotMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Runs macros in the background after the radial menu closes.
 * <p>
 * Every call to {@link #start} creates an independent run with its own cursor and timer,
 * so multiple macros (or the same macro triggered repeatedly) execute in parallel
 * instead of waiting for or cancelling each other.
 */
public final class MacroExecutor {

    // Minimum gap between two consecutive actions of the same run, to avoid flooding the server
    private static final int ACTION_GAP_TICKS = 1;

    private static final List<MacroRun> ACTIVE_RUNS = new ArrayList<>();
    private static long currentTick = 0;

    // A dummy context since macros run in the background after the menu closes
    private static final SlotActionContext MACRO_CONTEXT = new SlotActionContext() {
        @Override
        public void closeScreen() {}

        @Override
        public void openSubmenu(List<RadialSlot> children, int slotCount) {}

        @Override
        public boolean isRoot() {
            return true;
        }
    };

    private MacroExecutor() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> cancelAll());
    }

    /**
     * Starts a new run of the given macro. The actions are snapshotted, so editing the slot
     * afterwards does not affect runs that are already in progress.
     */
    public static void start(List<RadialSlot.Macro> macros) {
        if (macros == null) return;

        List<RadialSlot.Macro> snapshot =
                macros.stream().filter(Objects::nonNull).toList();
        if (!snapshot.isEmpty()) {
            ACTIVE_RUNS.add(new MacroRun(snapshot));
        }
    }

    /**
     * Stops every running macro.
     *
     * @return the number of runs that were stopped
     */
    public static int cancelAll() {
        int stopped = ACTIVE_RUNS.size();
        ACTIVE_RUNS.clear();
        return stopped;
    }

    private static void tick() {
        currentTick++;
        if (ACTIVE_RUNS.isEmpty()) return;

        // Iterate over a copy so actions can safely start new runs
        for (MacroRun run : List.copyOf(ACTIVE_RUNS)) {
            run.advance(currentTick);
        }
        ACTIVE_RUNS.removeIf(MacroRun::isFinished);
    }

    private static boolean isDelay(RadialSlot.Macro macro) {
        return macro.mode() instanceof DelaySlotMode;
    }

    private static void execute(RadialSlot.Macro macro) {
        // Create a dummy slot to satisfy the SlotMode API
        RadialSlot dummySlot = new RadialSlot("", macro.mode(), macro.value(), "minecraft:air");

        try {
            macro.mode().performAction(dummySlot, MACRO_CONTEXT);
        } catch (Exception e) {
            RadialCommon.LOGGER.error(
                    "Macro action failed for mode: {}",
                    macro.mode().getTranslatedName().getString(),
                    e);
        }
    }

    private static final class MacroRun {
        private final List<RadialSlot.Macro> actions;
        private int index = 0;
        private long resumeAt = 0;

        private MacroRun(List<RadialSlot.Macro> actions) {
            this.actions = actions;
        }

        private boolean isFinished() {
            return index >= actions.size();
        }

        /**
         * Executes at most one action per tick. Delays directly following an action are measured
         * from that action, so "Action, Delay 1, Action" fires the second action exactly 1 second later.
         */
        private void advance(long now) {
            if (now < resumeAt) return;

            while (index < actions.size()) {
                RadialSlot.Macro macro = actions.get(index++);
                if (macro.mode() == null) continue;

                if (isDelay(macro)) {
                    // Leading delay (or a delay after a skipped action)
                    int ticks = DelaySlotMode.toTicks(macro.value());
                    if (ticks > 0) {
                        resumeAt = now + ticks;
                        return;
                    }
                    continue;
                }

                execute(macro);

                long gap = 0;
                while (index < actions.size() && isDelay(actions.get(index))) {
                    gap += DelaySlotMode.toTicks(actions.get(index++).value());
                }
                resumeAt = now + Math.max(ACTION_GAP_TICKS, gap);
                return;
            }
        }
    }
}
