package com.otto.cyclea;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared runtime state for Cyclea. Holds the on/off switch, the active target,
 * the latest located positions, and the live tallies the HUD reads (chests,
 * shulkers, and detected bases).
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
        BASES("Bases (cluster bomb)", 0xFF3860),
        LOOT("Loot: chests≤Y20 + shulkers", 0x00E5FF),
        SHULKERS("Shulkers (all levels)", 0xD070FF),
        SPAWNERS("Spawners & Vaults (≤Y20)", 0xFFB300),
        CAVES("Caves", 0x8CFF66);

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
    private Target target = Target.BASES;
    private int radius = 48;
    private final List<BlockPos> found = new ArrayList<>();

    // live tallies for the HUD (updated every scan, regardless of active target)
    private volatile int chestCount = 0;
    private volatile int shulkerCount = 0;
    private volatile int baseCount = 0;
    private volatile String nearestLine = "";

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
        nearestLine = "";
    }

    public int getRadius() {
        return radius;
    }

    public synchronized List<BlockPos> getFound() {
        return new ArrayList<>(found);
    }

    public synchronized void setFound(List<BlockPos> positions) {
        found.clear();
        found.addAll(positions);
    }

    public int getChestCount() {
        return chestCount;
    }

    public int getShulkerCount() {
        return shulkerCount;
    }

    public int getBaseCount() {
        return baseCount;
    }

    public String getNearestLine() {
        return nearestLine;
    }

    public void setTallies(int chests, int shulkers, int bases, String nearest) {
        this.chestCount = chests;
        this.shulkerCount = shulkers;
        this.baseCount = bases;
        this.nearestLine = nearest;
    }
}
