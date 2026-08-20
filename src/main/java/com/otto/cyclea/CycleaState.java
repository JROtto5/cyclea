package com.otto.cyclea;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared runtime state for Cyclea. Holds the on/off switch, which target the
 * finder is currently cycling to, the scan radius, and the most recent list of
 * located targets that the renderer draws guide-lines toward.
 */
public final class CycleaState {
    private static final CycleaState INSTANCE = new CycleaState();

    public static CycleaState get() {
        return INSTANCE;
    }

    /** Chests, spawners and vaults are only reported at or below this Y (deep loot). */
    public static final int DEEP_MAX_Y = 20;

    /** The kinds of things Cyclea can hunt for. Cycle through them with the key. */
    public enum Target {
        CHESTS("Chests & Barrels (≤Y20)", 0x00E5FF),
        SHULKERS("Shulkers (all levels)", 0xD070FF),
        SPAWNERS("Spawners & Vaults (≤Y20)", 0xFF4D4D),
        VILLAGES("Villages", 0x8CFF66),
        CAVES("Caves", 0xFFB300);

        public final String label;
        public final int color;

        Target(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public Target next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private boolean active = false;
    private Target target = Target.CHESTS;
    private int radius = 48;
    private final List<BlockPos> found = new ArrayList<>();

    private CycleaState() {
    }

    public boolean isActive() {
        return active;
    }

    public boolean toggle() {
        active = !active;
        return active;
    }

    public Target getTarget() {
        return target;
    }

    public void cycleTarget() {
        target = target.next();
        found.clear();
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int r) {
        radius = Math.max(16, Math.min(128, r));
    }

    public synchronized List<BlockPos> getFound() {
        return new ArrayList<>(found);
    }

    public synchronized void setFound(List<BlockPos> positions) {
        found.clear();
        found.addAll(positions);
    }
}
