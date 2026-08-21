package com.otto.cyclea;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared runtime state for Cyclea. Holds the switches, the active target, the
 * latest located positions, and every tally / snapshot the HUD reads. Written
 * from the client tick, read from the HUD — fields the HUD touches are volatile.
 */
public final class CycleaState {
    private static final CycleaState INSTANCE = new CycleaState();

    public static CycleaState get() {
        return INSTANCE;
    }

    public enum Target {
        BASES("Bases — chest/shulker clusters", 0xFF3860),
        CONTAINERS("Every chest & shulker", 0x00E5FF),
        SPAWNERS("Spawners & Vaults", 0xFFB300),
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
    private boolean compactHud = false;
    private Target target = Target.BASES;
    private int radius = 48;
    private final List<BlockPos> found = new ArrayList<>();

    // cumulative unique bases discovered this session (#6), keyed by 16-block grid
    private final Set<Long> seenBaseKeys = new LinkedHashSet<>();
    private final List<BlockPos> sessionBases = new ArrayList<>();

    // HUD snapshot (#1–#14)
    private volatile int chestCount = 0;
    private volatile int shulkerCount = 0;
    private volatile int baseCount = 0;
    private volatile int beaconCount = 0;
    private volatile int playerCount = 0;
    private volatile int hostileCount = 0;
    private volatile int richestSize = 0;
    private volatile int nearestLootCount = 0;
    private volatile int nearestDist = -1;
    private volatile String nearestLine = "";
    private volatile String vertical = "";
    private volatile String bearing = "";
    private volatile List<int[]> blips = List.of();   // {dx, dz} relative to player

    private CycleaState() {
    }

    public boolean isActive() {
        return active;
    }

    public boolean toggle() {
        active = !active;
        return active;
    }

    public void setActive(boolean value) {
        active = value;
    }

    public boolean isCompact() {
        return compactHud;
    }

    public void toggleCompact() {
        compactHud = !compactHud;
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

    /** Record base positions into the cumulative session log; returns how many were new. */
    public synchronized int recordBases(List<BlockPos> bases) {
        int fresh = 0;
        for (BlockPos b : bases) {
            long key = (((long) (b.getX() >> 4)) & 0x3FFFFFFL) << 34
                | (((long) (b.getZ() >> 4)) & 0x3FFFFFFL);
            if (seenBaseKeys.add(key)) {
                sessionBases.add(b);
                fresh++;
            }
        }
        return fresh;
    }

    public synchronized List<BlockPos> getSessionBases() {
        return new ArrayList<>(sessionBases);
    }

    public int getSessionBaseTotal() {
        return sessionBases.size();
    }

    // getters/setter for the HUD snapshot
    public int getChestCount() {
        return chestCount;
    }

    public int getShulkerCount() {
        return shulkerCount;
    }

    public int getBaseCount() {
        return baseCount;
    }

    public int getBeaconCount() {
        return beaconCount;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public int getHostileCount() {
        return hostileCount;
    }

    public int getRichestSize() {
        return richestSize;
    }

    public int getNearestLootCount() {
        return nearestLootCount;
    }

    public int getNearestDist() {
        return nearestDist;
    }

    public String getNearestLine() {
        return nearestLine;
    }

    public String getVertical() {
        return vertical;
    }

    public String getBearing() {
        return bearing;
    }

    public List<int[]> getBlips() {
        return blips;
    }

    public void setSnapshot(int chests, int shulkers, int bases, int beacons,
                            int players, int hostiles, int richest, int nearestLoot,
                            int nearestDistance, String nearest, String vert,
                            String bear, List<int[]> radarBlips) {
        this.chestCount = chests;
        this.shulkerCount = shulkers;
        this.baseCount = bases;
        this.beaconCount = beacons;
        this.playerCount = players;
        this.hostileCount = hostiles;
        this.richestSize = richest;
        this.nearestLootCount = nearestLoot;
        this.nearestDist = nearestDistance;
        this.nearestLine = nearest;
        this.vertical = vert;
        this.bearing = bear;
        this.blips = radarBlips;
    }
}
