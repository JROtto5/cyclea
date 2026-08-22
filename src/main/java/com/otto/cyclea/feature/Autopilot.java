package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaConfig;
import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A survival auto-explorer. Walks/tunnels toward a target (spawn by default),
 * mining what's in the way, eating when hungry, swapping to a fresh pickaxe
 * before one breaks, refusing to walk into lava or off ledges, and halting the
 * moment Cyclea spots a base — reporting how looted it looks.
 *
 * Deliberately cautious: any uncertainty stops the bot rather than risking the
 * player's diamond kit. Single-player / your-own-server use only.
 */
public final class Autopilot {

    private static final Autopilot INSTANCE = new Autopilot();

    public static Autopilot get() {
        return INSTANCE;
    }

    private boolean active = false;
    private int targetX = 0;      // spawn (the sweep goal)
    private int targetZ = 0;
    private int targetY = -59;    // depth to hold / return to
    private boolean approaching = false;   // true while navigating to a found base
    private boolean eating = false;
    private int eatingTicks = 0;
    private int prevSlot = -1;
    private int scanTick = 0;
    private BlockPos mining = null;   // the block we're committed to breaking (held for a steady camera)
    private double driftYaw = 0;      // gentle random-walk so the camera looks alive, not locked
    private double driftPitch = 0;
    private final java.util.Random rng = new java.util.Random();
    private double startX = 0;        // where this leg began — for diagonal staircasing to the target
    private double startZ = 0;
    private boolean axisX = false;    // which axis we're currently advancing (alternates in ~6-block segments)
    private int glanceTimer = 0;      // countdown to the next "look around" while walking
    private float glanceYaw = 0;      // current look-around offset (degrees) added while walking
    private float glancePitch = 0;
    private Direction detourDir = null;   // committed detour while routing around lava
    private int detourTicks = 0;
    private List<BlockPos> path = null;   // A* route to the base we're approaching
    private int pathIndex = 0;
    private int fightCd = 0;              // attack cooldown ticks
    private BlockPos oreGoal = null;      // an ore we're detouring to dig out
    private int oreScanTick = 0;
    private int oreGoalTicks = 0;         // how long we've chased the current ore
    private final java.util.HashSet<Long> oreBlacklist = new java.util.HashSet<>();
    private int mineTicks = 0;            // how long we've been on the current mining target
    private long lastMiningKey = 0;
    private final java.util.ArrayDeque<BlockPos> placedTrapdoors = new java.util.ArrayDeque<>();
    private double lastProgX = Double.NaN;   // anti-stuck progress tracking
    private double lastProgZ = Double.NaN;
    private int stuckTicks = 0;
    private double idleX = Double.NaN;        // activity watchdog (catches any freeze)
    private double idleZ = Double.NaN;
    private int idleTicks = 0;
    private BlockPos pillarSpot = null;       // spot to fill mid-jump when climbing out of Y-60
    private int jumpTicks = 0;
    private double blocksTraveled = 0;    // session stats
    private long sessionStart = 0;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

    // human-intervention metrics
    private int takeovers = 0;
    private long humanControlMs = 0;
    private long lastStopMs = 0;
    private BlockPos lastStopPos = null;
    private String lastStopReason = "";
    private boolean handedOff = false;
    private final java.util.LinkedHashMap<String, Integer> stopReasons = new java.util.LinkedHashMap<>();

    public int getTakeovers() {
        return takeovers;
    }

    public long getHumanControlSeconds() {
        return humanControlMs / 1000;
    }

    public String getLastStopReason() {
        return lastStopReason;
    }

    public String topStopReasons() {
        return stopReasons.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(3)
            .map(e -> e.getKey() + " ×" + e.getValue())
            .reduce((a, b) -> a + ", " + b).orElse("none");
    }

    public int getBlocksTraveled() {
        return (int) blocksTraveled;
    }

    public long getSessionSeconds() {
        return sessionStart == 0 ? 0 : (System.currentTimeMillis() - sessionStart) / 1000;
    }

    // search strategy
    public enum SearchMode { SWEEP, SPAWN }
    private SearchMode searchMode = SearchMode.SPAWN;   // default: head toward spawn (press K for area-sweep)
    private static final int[][] DIRS = {{1, 0}, {0, -1}, {-1, 0}, {0, 1}};   // E, N, W, S
    private static final int SWEEP_STEP = 48;           // blocks per spiral leg unit
    private int spiralCornerX = 0;
    private int spiralCornerZ = 0;
    private int legLen = 1;
    private int stepInPair = 0;
    private int dirIdx = 0;

    public SearchMode getSearchMode() {
        return searchMode;
    }

    public String toggleSearchMode(Minecraft mc) {
        searchMode = searchMode == SearchMode.SWEEP ? SearchMode.SPAWN : SearchMode.SWEEP;
        if (active) {
            retarget(mc);
        }
        return searchMode.name();
    }

    // surface-scout state
    private int stashScanTick = 0;
    private int surfDetourTicks = 0;
    private int surfDetourSign = 1;
    private final java.util.Set<Long> alertedStashes = new java.util.HashSet<>();

    /** Switch between Miner and Surface-scout mode (persists to config). */
    public String toggleMode(Minecraft mc) {
        CycleaConfig.get().cycleMode();
        if (active) {
            resetRunState();
            searchMode = CycleaConfig.get().mode == 1 ? SearchMode.SWEEP : searchMode;
            retarget(mc);
        }
        String lbl = CycleaConfig.get().modeLabel();
        say(mc, "§b[Cyclea] mode → §f" + lbl);
        return lbl;
    }

    /** Point the bot at its first goal for the current mode. */
    private void retarget(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        if (searchMode == SearchMode.SWEEP) {
            newSpiral(mc);
        } else {
            targetX = 0;
            targetZ = 0;
            newLeg(mc);
        }
    }

    private void newSpiral(Minecraft mc) {
        spiralCornerX = (int) mc.player.getX();
        spiralCornerZ = (int) mc.player.getZ();
        legLen = 1;
        stepInPair = 0;
        dirIdx = 0;
        nextSpiralTarget();
        newLeg(mc);
    }

    private void nextSpiralTarget() {
        int step = CycleaConfig.get().sweepStep;
        targetX = spiralCornerX + DIRS[dirIdx][0] * legLen * step;
        targetZ = spiralCornerZ + DIRS[dirIdx][1] * legLen * step;
    }

    private void advanceSpiral() {
        spiralCornerX = targetX;
        spiralCornerZ = targetZ;
        dirIdx = (dirIdx + 1) & 3;
        if (++stepInPair == 2) {
            stepInPair = 0;
            legLen++;
        }
        nextSpiralTarget();
    }

    private void newLeg(Minecraft mc) {
        if (mc.player != null) {
            startX = mc.player.getX();
            startZ = mc.player.getZ();
        }
        mining = null;
    }

    private Autopilot() {
    }

    public boolean isActive() {
        return active;
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetZ() {
        return targetZ;
    }

    public void setTarget(int x, int z) {
        targetX = x;
        targetZ = z;
    }

    public boolean toggle(Minecraft mc) {
        active = !active;
        if (active) {
            resetRunState();   // clean slate — no stale counters from the last run can freeze it
            approaching = false;
            if (mc.player != null) {
                // if the bot handed off and you took over, log how you helped
                if (handedOff && lastStopMs > 0) {
                    long secs = (System.currentTimeMillis() - lastStopMs) / 1000;
                    humanControlMs += System.currentTimeMillis() - lastStopMs;
                    double moved = lastStopPos == null ? 0
                        : Math.sqrt(mc.player.blockPosition().distSqr(lastStopPos));
                    say(mc, "§a[Assist] §7you took over for §f" + secs + "s§7, moved it §f"
                        + (int) moved + "m §7past '" + lastStopReason + "'");
                    handedOff = false;
                }
                targetY = -59;   // default diamond level, just above bedrock
                retarget(mc);
            }
        } else {
            releaseAll(mc);
        }
        return active;
    }

    /** Clear all transient per-run state so a fresh engage never inherits a stale
     *  counter (a leftover stuckTicks / manualPause / mining target would freeze it). */
    private void resetRunState() {
        mining = null;
        breakingPos = null;
        oreGoal = null;
        oreGoalTicks = 0;
        oreScanTick = 0;
        oreBlacklist.clear();
        mineTicks = 0;
        lastMiningKey = 0;
        detourDir = null;
        detourTicks = 0;
        path = null;
        pathIndex = 0;
        fightCd = 0;
        eating = false;
        eatingTicks = 0;
        axisX = false;
        glanceTimer = 0;
        placedTrapdoors.clear();
        lastProgX = Double.NaN;
        lastProgZ = Double.NaN;
        stuckTicks = 0;
        idleX = Double.NaN;
        idleZ = Double.NaN;
        idleTicks = 0;
        pillarSpot = null;
        deadTicks = 0;
        aliveX = Double.NaN;
        aliveInv = -1;
        errStreak = 0;
        stashScanTick = 0;
        surfDetourTicks = 0;
        alertedStashes.clear();
        jumpTicks = 0;
        botYaw = Float.NaN;
        manualPauseTicks = 0;
        paceCounter = 0;
        scanTick = 0;
    }

    public void stop(Minecraft mc, String reason) {
        active = false;
        releaseAll(mc);
        // log this as a hand-off to the human, categorized
        takeovers++;
        lastStopReason = stripCodes(reason);
        String key = reasonKey(lastStopReason);
        stopReasons.merge(key, 1, Integer::sum);
        lastStopMs = System.currentTimeMillis();
        lastStopPos = mc.player != null ? mc.player.blockPosition() : null;
        handedOff = true;
        say(mc, "§6[Autopilot] §7stopped — " + reason
            + " §8(handoff #" + takeovers + ")");
    }

    private static String stripCodes(String s) {
        return s.replaceAll("§.", "").trim();
    }

    /** Bucket a stop message into a short category for the metrics. */
    private static String reasonKey(String r) {
        String s = r.toLowerCase();
        if (s.contains("lava")) {
            return "lava";
        }
        if (s.contains("water")) {
            return "water";
        }
        if (s.contains("stuck")) {
            return "stuck";
        }
        if (s.contains("boxed")) {
            return "boxed-in";
        }
        if (s.contains("base")) {
            return "reached base";
        }
        if (s.contains("inventory")) {
            return "inventory full";
        }
        if (s.contains("health")) {
            return "low health";
        }
        if (s.contains("drop")) {
            return "drop/ledge";
        }
        if (s.contains("pickaxe")) {
            return "no pickaxe";
        }
        return "other";
    }

    private float botYaw = Float.NaN;   // rotation the bot set last tick (to detect YOUR mouse input)
    private float botPitch = 0;
    private int manualPauseTicks = 0;

    /** Called every client tick. Fully guarded — any trouble halts safely. */
    public void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null) {
            return;
        }
        // HARD WATCHDOG: if it's genuinely frozen (not moving AND not mining anything),
        // auto-restart the whole thing — same as you toggling off/on, but automatic.
        // You should never have to babysit it.
        if (frozenSoRestart(mc)) {
            return;   // restarted this tick; run fresh next tick
        }
        try {
            step(mc);
            errStreak = 0;   // a clean tick clears the error streak
        } catch (Throwable t) {
            // one hiccup shouldn't kill the run — swallow, release keys, keep going.
            // Only truly halt if we error every tick for a while (something real is wrong).
            errStreak++;
            releaseAll(mc);
            if (errStreak >= 40) {
                errStreak = 0;
                stop(mc, "internal error (safe halt)");
            }
        }
        // remember where the bot left the camera, so next tick we can tell if YOU moved it
        if (active && mc.player != null && manualPauseTicks == 0) {
            botYaw = mc.player.getYRot();
            botPitch = mc.player.getXRot();
        }
    }

    private int errStreak = 0;
    private int deadTicks = 0;
    private double aliveX = Double.NaN;
    private double aliveY;
    private double aliveZ;
    private int aliveInv = -1;
    private int restartCount = 0;

    /** True if it looked frozen and we just auto-restarted (so skip this tick's step). */
    private boolean frozenSoRestart(Minecraft mc) {
        var p = mc.player;
        if (manualPauseTicks > 0) {   // you're driving — don't count that as frozen
            deadTicks = 0;
            return false;
        }
        int inv = invCount(p);
        boolean moved = Double.isNaN(aliveX)
            || Math.hypot(p.getX() - aliveX, p.getZ() - aliveZ) > 0.35
            || Math.abs(p.getY() - aliveY) > 0.35;
        boolean produced = inv != aliveInv;   // broke/collected a block = making progress
        aliveX = p.getX();
        aliveY = p.getY();
        aliveZ = p.getZ();
        aliveInv = inv;
        if (moved || produced || eating) {
            deadTicks = 0;
            return false;
        }
        deadTicks++;
        if (deadTicks >= 70) {        // ~3.5s of doing absolutely nothing → full restart
            deadTicks = 0;
            restartCount++;
            releaseAll(mc);
            resetRunState();          // clears every transient state, just like a fresh engage
            axisX = !axisX;           // and try a different heading in case a wall trapped us
            newLeg(mc);
            say(mc, "§e⟳ auto-restart — I was stuck, resuming on my own (restart #"
                + restartCount + ")");
            return true;
        }
        return false;
    }

    private static int invCount(net.minecraft.client.player.LocalPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            n += s.getCount();
        }
        return n;
    }

    private int paceCounter = 0;

    private void step(Minecraft mc) {
        var player = mc.player;

        // CRUISE-CONTROL YIELD: the instant you move the mouse, hand full control
        // back to you (release everything) and stay out of the way; resume shortly
        // after you stop. This is what lets you mine/aim yourself while it's on.
        if (!Float.isNaN(botYaw)
            && (Math.abs(Mth.degreesDifference(player.getYRot(), botYaw)) > 3f
            || Math.abs(player.getXRot() - botPitch) > 3f)) {
            manualPauseTicks = 30;
        }
        if (manualPauseTicks > 0) {
            manualPauseTicks--;
            releaseAll(mc);
            return;
        }

        // pace vs the server: on "off" ticks, hold still (don't advance).
        if (++paceCounter < CycleaConfig.get().actEveryNTicks()) {
            key(mc, mc.options.keyUp, false);
            key(mc, mc.options.keySprint, false);
            return;
        }
        paceCounter = 0;

        // gentle random-walk drift so the aim looks human, not machine-locked
        driftYaw = Mth.clamp(driftYaw + (rng.nextDouble() - 0.5) * 0.35, -1.5, 1.5);
        driftPitch = Mth.clamp(driftPitch + (rng.nextDouble() - 0.5) * 0.25, -1.0, 1.0);

        // session stats: accumulate horizontal distance travelled
        if (sessionStart == 0) {
            sessionStart = System.currentTimeMillis();
        }
        if (!Double.isNaN(lastX)) {
            double dxT = player.getX() - lastX;
            double dzT = player.getZ() - lastZ;
            blocksTraveled += Math.sqrt(dxT * dxT + dzT * dzT);
        }
        lastX = player.getX();
        lastZ = player.getZ();

        // 1) survival guard (heal attempts happen in step 3; only bail if truly critical)
        if (player.getHealth() <= 4.0f) {
            stop(mc, "§ccritical health");
            return;
        }

        // 1b) self-defense: fight off a hostile that's on us
        if (fightCd > 0) {
            fightCd--;
        }
        if (fightNearbyHostile(mc)) {
            return;   // fighting takes priority this tick
        }

        // 1b2) SURFACE SCOUT mode: walk on top, deep-scan for stashes, alert you.
        if (CycleaConfig.get().mode == 1) {
            surfaceStep(mc);
            return;
        }

        // 1b3) Y-60 CLIMB — TOP PRIORITY. If we've sunk below the target depth, getting
        //      back up beats everything else. This runs BEFORE the anti-stuck jumping (which
        //      was keeping us airborne so the old grounded check never fired — the bug that
        //      made it "never figure out Y-60"). Works whether grounded OR airborne.
        BlockPos feetC = player.blockPosition();
        if (feetC.getY() < targetY) {
            key(mc, mc.options.keyUp, false);
            key(mc, mc.options.keySprint, false);

            // (a) BEST: step onto adjacent higher ground (usually the tunnel we came
            //     from, one block up). No blocks needed. Check cardinals + diagonals.
            Direction stepUp = findStepUp(mc, feetC);
            if (stepUp != null) {
                aimAtFast(player, Vec3.atCenterOf(feetC.relative(stepUp)));
                key(mc, mc.options.keyUp, true);
                key(mc, mc.options.keyJump, true);
                return;
            }

            // (b) clear the ceiling so we can rise in place
            BlockPos head2 = feetC.above(2);
            if (canMine(mc, head2)) {
                swingAt(mc, head2);
                return;
            }

            // (c) PILLAR: jump and fill the cell under us so we land a level higher.
            if (player.onGround()) {
                if (ensureHotbarBlock(mc)) {
                    pillarSpot = feetC;    // fill it next tick, mid-air
                }
                key(mc, mc.options.keyJump, true);
            } else if (pillarSpot != null) {
                place(mc, pillarSpot, "");
                key(mc, mc.options.keyJump, true);
            } else {
                key(mc, mc.options.keyJump, true);
            }
            return;
        }
        pillarSpot = null;   // at/above target depth — no pillar pending

        // 1c) inventory full — don't just stop and wait for you. Toss junk (cobble/dirt/
        //     gravel/etc.) to make room and keep mining. Only stop if it's full of stuff
        //     actually worth keeping.
        if (player.getInventory().getFreeSlot() < 0) {
            if (dropJunk(mc)) {
                return;   // freed a slot this tick — carry on next tick
            }
            stop(mc, "§einventory full of keepers — empty me (into a shulker), then press O");
            return;
        }

        // 2) base found? (throttled — scanning every tick would lag)
        //    switch to navigating toward it; we hand control back on arrival.
        if (!approaching && ++scanTick >= 40) {
            scanTick = 0;
            TargetScanner.Scan scan = TargetScanner.scan(mc);
            // always pin every base to the map so you can find them yourself
            int pinned = MinimapBridge.pushBases(scan.bases());
            java.util.List<TargetScanner.Base> worthGoing = new java.util.ArrayList<>();
            // "Skip all bases" (default on): don't auto-navigate — just pin and keep sweeping
            if (!CycleaConfig.get().skipBases) {
                for (TargetScanner.Base b : scan.bases()) {
                    if (!CycleaConfig.get().skipRaided || !b.status().equals("RAIDED")) {
                        worthGoing.add(b);
                    }
                }
            } else if (pinned > 0) {
                say(mc, "§b⚑ " + pinned + " base" + (pinned == 1 ? "" : "s")
                    + " pinned to map §7(skip-all-bases on — I'll keep mining, you go check them)");
            }
            if (!worthGoing.isEmpty()) {
                TargetScanner.Base best = richest(worthGoing);
                // A* a real route to the base; fall back to dig-toward if none found
                path = Pathfinder.find(mc, mc.player.blockPosition(), best.center());
                pathIndex = 0;
                if (path != null && !path.isEmpty()) {
                    targetX = path.get(0).getX();
                    targetZ = path.get(0).getZ();
                } else {
                    targetX = best.center().getX();
                    targetZ = best.center().getZ();
                }
                approaching = true;
                newLeg(mc);
                mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                say(mc, "§a★ BASE FOUND §f" + best.chests() + " chests, " + best.shulkers()
                    + " shulkers §7at §f" + best.center().getX() + "," + best.center().getY()
                    + "," + best.center().getZ() + " §7— " + lootRating(best)
                    + (path != null ? " §e→ pathing there (" + path.size() + " steps)…"
                        : " §e→ heading there…"));
                if (!best.loot().isEmpty()) {
                    say(mc, "§7   loot: §f" + best.loot());
                }
            }
        }

        // 3) eat if hungry
        if (handleEating(mc)) {
            return;   // stay put while eating
        }

        // 4) keep a working pickaxe
        if (!ensurePickaxe(mc)) {
            stop(mc, "§cno usable pickaxe left");
            return;
        }

        // 4b) anti-stuck: it NEVER gives up — it escalates (shake → tower up →
        //     redirect) until it frees itself, so it should never need you.
        if (Double.isNaN(lastProgX)) {
            lastProgX = player.getX();
            lastProgZ = player.getZ();
        }
        if (Math.hypot(player.getX() - lastProgX, player.getZ() - lastProgZ) > 0.6) {
            lastProgX = player.getX();
            lastProgZ = player.getZ();
            stuckTicks = 0;
        } else {
            stuckTicks++;
        }
        if (stuckTicks == 40) {           // step 1: shake loose — drop targets, hop
            mining = null;
            if (oreGoal != null) {
                oreBlacklist.add(oreGoal.asLong());
                oreGoal = null;
            }
            detourTicks = 0;
            jumpTicks = 6;
        }
        if (stuckTicks >= 90) {           // step 2: tower straight UP to escape the pocket
            BlockPos up = player.blockPosition().above(2);
            if (canMine(mc, up)) {
                mining = up;
                swingAt(mc, up);
                jumpTicks = 4;
                return;
            }
            if (passable(mc, up)) {
                jumpTicks = 4;             // clear above → keep hopping up out of it
            }
        }
        if (stuckTicks >= 170) {          // step 3: give up on THIS route, not the job —
            startX = player.getX();       // re-anchor and head a different way, then retry
            startZ = player.getZ();
            axisX = !axisX;
            detourDir = null;
            detourTicks = 0;
            oreGoal = null;
            stuckTicks = 0;               // fresh attempt — never hard-stop
        }
        if (jumpTicks > 0) {
            key(mc, mc.options.keyJump, true);
            jumpTicks--;
        } else {
            key(mc, mc.options.keyJump, false);
        }


        // 5) travel direction toward the target
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        if (Math.abs(dx) < 3 && Math.abs(dz) < 3) {
            if (approaching) {
                // following an A* path? advance to the next node instead of stopping
                if (path != null && pathIndex < path.size() - 1) {
                    pathIndex++;
                    BlockPos n = path.get(pathIndex);
                    targetX = n.getX();
                    targetZ = n.getZ();
                    newLeg(mc);
                    return;
                }
                approaching = false;
                path = null;
                stop(mc, "§a✔ arrived at base — §fyour turn (press O to resume the sweep)");
                return;
            }
            if (searchMode == SearchMode.SWEEP) {
                advanceSpiral();   // reached a sweep corner — turn and keep covering ground
                newLeg(mc);
                return;
            }
            stop(mc, "reached spawn area");
            return;
        }
        // ore-seek: detour to dig out configured ores, but NEVER get stuck on one —
        // if it can't be reached in a few seconds, blacklist it and keep mining.
        if (oreGoal != null) {
            if (++oreGoalTicks > 80) {                 // ~4s chasing one ore = give up
                oreBlacklist.add(oreGoal.asLong());
                if (oreBlacklist.size() > 128) {
                    oreBlacklist.clear();
                }
                oreGoal = null;
                say(mc, "§7ore unreachable — back to mining");
            } else if (!wantedOre(mc, oreGoal)) {
                oreGoal = findWantedOre(mc, player.blockPosition(), 4);   // vein continues?
                oreGoalTicks = 0;
            }
        }
        if (oreGoal == null && CycleaConfig.get().oreSeekLevel > 0 && ++oreScanTick >= 8) {
            oreScanTick = 0;
            oreGoal = findWantedOre(mc, player.blockPosition(), 5);
            if (oreGoal != null) {
                oreGoalTicks = 0;
                say(mc, "§b⛏ ore spotted — detouring to grab it");
            }
        }

        Direction primary;
        Direction secondary;
        if (oreGoal != null) {
            // head straight for the ore
            int odx = oreGoal.getX() - Mth.floor(player.getX());
            int odz = oreGoal.getZ() - Mth.floor(player.getZ());
            if (Math.abs(odx) >= Math.abs(odz)) {
                primary = odx >= 0 ? Direction.EAST : Direction.WEST;
                secondary = odz >= 0 ? Direction.SOUTH : Direction.NORTH;
            } else {
                primary = odz >= 0 ? Direction.SOUTH : Direction.NORTH;
                secondary = odx >= 0 ? Direction.EAST : Direction.WEST;
            }
        } else {
            // staircase diagonally toward the sweep target, alternating in ~6-block segments
            double totDx = targetX - startX;
            double totDz = targetZ - startZ;
            double adX = Math.abs(totDx);
            double adZ = Math.abs(totDz);
            double progX = (player.getX() - startX) * Math.signum(totDx);
            double progZ = (player.getZ() - startZ) * Math.signum(totDz);
            if (adX < 1) {
                axisX = false;
            } else if (adZ < 1) {
                axisX = true;
            } else if (axisX && progX > progZ * (adX / adZ) + 6) {
                axisX = false;
            } else if (!axisX && progZ > progX * (adZ / adX) + 6) {
                axisX = true;
            }
            primary = axisX
                ? (totDx >= 0 ? Direction.EAST : Direction.WEST)
                : (totDz >= 0 ? Direction.SOUTH : Direction.NORTH);
            secondary = axisX
                ? (totDz >= 0 ? Direction.SOUTH : Direction.NORTH)
                : (totDx >= 0 ? Direction.EAST : Direction.WEST);
        }

        BlockPos feet = player.blockPosition();

        // 6) route AROUND lava/water instead of just stopping: try the preferred
        //    direction, then the other axis, then sidesteps. Only halt if boxed in.
        Direction dir = chooseSafeDir(mc, feet, primary, secondary);
        if (dir == null) {
            // horizontally boxed in — tunnel straight UP to find a way out (tower up)
            BlockPos above = feet.above(2);   // the block above our head
            if (mineHere(mc, above)) {
                jumpTicks = 4;   // hop up into the space as it clears
                return;
            }
            stop(mc, "§cfully boxed in (lava/water/bedrock) — need you");
            return;
        }
        if (dir != primary) {
            mining = null;   // rerouting — drop the old dig target
        }
        float travelYaw = dir.toYRot();
        BlockPos aheadFeet = feet.relative(dir);
        BlockPos aheadHead = aheadFeet.above();

        // 1×1 trapdoor lane (runs every tick, not just when walking): place a
        // trapdoor at head-level behind, and mine back the older one to reuse it.
        if (CycleaConfig.get().oneByOne) {
            BlockPos old = placedTrapdoors.peekFirst();
            if (old != null && !isTrapdoor(mc.level.getBlockState(old))) {
                placedTrapdoors.pollFirst();   // already recovered
            } else if (old != null && old.distManhattan(feet) > 3) {
                swingAt(mc, old);
                return;   // break it to pick the trapdoor back up
            }
            BlockPos spot = feet.above().relative(dir.getOpposite());   // head-level, behind (air)
            if (placedTrapdoors.size() < 2 && mc.level.getBlockState(spot).isAir()
                && place(mc, spot, "trapdoor")) {
                placedTrapdoors.addLast(spot.immutable());
            }
        }

        // 7) if we're already committed to a block, keep on it until it's gone
        //    (holding one target is what keeps the camera steady instead of jerking).
        // if we've been on one mining target too long (can't reach or break it),
        // blacklist it and drop it — this is what stops the "stare at a diamond
        // forever" freeze, whatever the cause.
        if (mining != null) {
            long k = mining.asLong();
            if (k == lastMiningKey) {
                mineTicks++;
            } else {
                lastMiningKey = k;
                mineTicks = 0;
            }
            if (mineTicks > 40 && !passable(mc, mining)) {
                oreBlacklist.add(k);
                if (oreBlacklist.size() > 256) {
                    oreBlacklist.clear();
                }
                mining = null;
                mineTicks = 0;
                oreGoal = null;
            }
        } else {
            mineTicks = 0;
        }

        // TOP PRIORITY: clear anything that fell/exists in our own head or feet
        // (gravel & sand avalanche as we dig) so we never suffocate or jam.
        if (mineHere(mc, feet.above())) {
            return;   // head first
        }
        if (mineHere(mc, feet)) {
            return;   // then feet
        }

        if (mining != null && canMine(mc, mining)) {
            swingAt(mc, mining);
            return;
        }
        mining = null;

        // grab any ore exposed right next to us first
        BlockPos target = adjacentOre(mc, feet);

        // if we've sunk below the target depth (e.g. wedged at Y-60 in the bedrock
        // layer), climb back up to Y-59 — mine the block above head and hop up.
        if (target == null && feet.getY() < targetY) {
            BlockPos up = feet.above(2);
            if (canMine(mc, up)) {
                target = up;
            } else if (passable(mc, up)) {
                jumpTicks = 3;
            }
        }

        // chasing an ore that's above/below? dig a 3D staircase toward it (mine up
        // and hop, or dig down) so ore at any height is actually reachable —
        // but never dig below Y-59 (that's the bedrock zone).
        if (target == null && oreGoal != null) {
            int dy = oreGoal.getY() - feet.getY();
            if (dy >= 1) {
                BlockPos up = feet.above(2);   // block above our head
                if (canMine(mc, up)) {
                    target = up;
                } else if (passable(mc, up)) {
                    jumpTicks = 3;             // already clear → hop up toward it
                }
            } else if (dy <= -1 && feet.getY() > targetY) {
                BlockPos down = feet.below();
                if (canMine(mc, down)) {
                    target = down;
                }
            }
        }

        // else clear the way forward: head, then feet, then (if too shallow) descend
        if (target == null) {
            if (!passable(mc, aheadHead)) {
                target = aheadHead;
            } else if (!passable(mc, aheadFeet)) {
                target = aheadFeet;
            } else if (feet.getY() > targetY && canMine(mc, aheadFeet.below())) {
                target = aheadFeet.below();   // staircase down toward Y-59 (never into bedrock)
            }
        }

        if (target != null) {
            mining = target;
            swingAt(mc, target);
        } else {
            // path clear: walk smooth and straight (body stays on the travel line);
            // glancing is mostly pitch (harmless) + a small yaw so it doesn't weave.
            key(mc, mc.options.keyAttack, false);
            mc.gameMode.stopDestroyBlock();
            breakingPos = null;
            float yawAmp = CycleaConfig.get().glanceYawAmp();
            float pitchAmp = CycleaConfig.get().glancePitchAmp();
            if (yawAmp <= 0f) {
                aim(player, travelYaw, 0f);
            } else {
                if (--glanceTimer <= 0) {
                    glanceYaw = (rng.nextFloat() - 0.5f) * (yawAmp * 0.35f);   // small — keep walking straight
                    glancePitch = (rng.nextFloat() - 0.5f) * pitchAmp;         // full — doesn't curve the path
                    glanceTimer = 14 + rng.nextInt(30);
                }
                aim(player, travelYaw + glanceYaw, glancePitch);
            }
            if (passable(mc, aheadFeet.below()) && passable(mc, aheadFeet.below().below())) {
                // gap ahead — bridge it with a block if we have one, else stop
                key(mc, mc.options.keyUp, false);
                key(mc, mc.options.keySprint, false);
                if (place(mc, aheadFeet.below(), "")) {
                    return;   // placed a bridge block
                }
                stop(mc, "§edrop ahead, no blocks to bridge — need you");
                return;
            }
            // walk (no sprint — sprinting runs into blocks it hasn't broken yet and
            // spams the server). Only move when the next 2 blocks are genuinely clear.
            boolean facing = Math.abs(Mth.degreesDifference(player.getYRot(), travelYaw)) < 25f;
            boolean clearAhead = passable(mc, aheadFeet) && passable(mc, aheadHead);
            key(mc, mc.options.keyUp, facing && clearAhead);
            key(mc, mc.options.keySprint, false);
        }
    }

    /**
     * Pick a direction to head into — preferring progress toward the target, but
     * routing around lava/water. Looks 3 blocks ahead so it detours early, and
     * commits to a detour for a while so it clears the pocket instead of hugging
     * the edge. Null only if boxed in on every side.
     */
    private Direction chooseSafeDir(Minecraft mc, BlockPos feet,
                                    Direction primary, Direction secondary) {
        // toward-target first, then sidestep — NO full-retreat (primary.getOpposite),
        // which caused it to drift backwards through cleared tunnel. Dead-ends are
        // handled by the tower-up / redirect recovery instead.
        Direction[] base = {primary, secondary, secondary.getOpposite()};
        Direction committed = detourTicks > 0 ? detourDir : null;

        // pass 1: a direction that's clear for 3 blocks ahead (route early)
        Direction pick = firstSafe(mc, feet, committed, base, 3);
        // pass 2: fall back to a direction that's at least safe right in front
        if (pick == null) {
            pick = firstSafe(mc, feet, committed, base, 1);
        }
        if (pick == null) {
            return null;
        }
        if (pick != primary) {
            detourDir = pick;
            detourTicks = 25;   // stick with the detour to clear the pocket
        } else if (detourTicks > 0) {
            detourTicks--;
        }
        return pick;
    }

    private static Direction firstSafe(Minecraft mc, BlockPos feet, Direction first,
                                       Direction[] base, int dist) {
        if (first != null && pathClear(mc, feet, first, dist)) {
            return first;
        }
        for (Direction d : base) {
            if (pathClear(mc, feet, d, dist)) {
                return d;
            }
        }
        return null;
    }

    /**
     * True if we can safely travel {@code dist} blocks along {@code d}: never
     * stepping into lava/water, and never mining a block that has lava or water
     * directly behind it (which would flood in). Solid rock next to lava is fine
     * to pass through — that's what lets it thread a lava maze and back out.
     */
    private static boolean pathClear(Minecraft mc, BlockPos feet, Direction d, int dist) {
        for (int i = 1; i <= dist; i++) {
            BlockPos af = feet.relative(d, i);
            if (!travelOk(mc, af) || !travelOk(mc, af.above())) {
                return false;
            }
            if (i == 1 && mc.level.getBlockState(af.below()).is(Blocks.LAVA)) {
                return false;   // would step onto/into lava floor
            }
        }
        return true;
    }

    /** A block is OK to move through: not a hazard to enter, not unbreakable, and
     *  if solid (we'd mine it) with no lava/water neighbor that would flood in. */
    private static boolean travelOk(Minecraft mc, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
            return false;
        }
        if (passable(mc, pos)) {
            return true;
        }
        if (isUnbreakable(st)) {
            return false;   // bedrock etc. — can't dig it, route around
        }
        return !hasHazardNeighbor(mc, pos);
    }

    /** Blocks the autopilot can never mine — treat as walls to go around. */
    private static boolean isUnbreakable(BlockState st) {
        return st.is(Blocks.BEDROCK) || st.is(Blocks.BARRIER)
            || st.is(Blocks.REINFORCED_DEEPSLATE) || st.is(Blocks.END_PORTAL_FRAME);
    }

    /** Any of the 6 neighbors is lava or water (so mining this block risks a flood). */
    private static boolean hasHazardNeighbor(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState st = mc.level.getBlockState(pos.relative(d));
            if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static final float TURN_EASE = 0.18f;   // fraction of the remaining angle per tick (gentle)
    private static final float TURN_MAX = 7.5f;     // hard cap on degrees/tick (fast pans stay smooth)
    private static final float TURN_DEAD = 0.35f;   // snap the last fraction of a degree — kills micro-jitter

    /**
     * Ease smoothly toward a yaw/pitch: cover a fraction of the remaining angle
     * each tick (so the camera decelerates and settles like a hand on a mouse),
     * capped per tick, with a deadzone so it stops dead instead of shimmering.
     */
    private void aim(net.minecraft.client.player.LocalPlayer p, float yaw, float pitch) {
        aim(p, yaw, pitch, CycleaConfig.get().turnMax());
    }

    private void aim(net.minecraft.client.player.LocalPlayer p, float yaw, float pitch, float maxSpeed) {
        float ease = CycleaConfig.get().turnEase();
        // fold the human drift into the goal so we ease toward a subtly-moving point
        float goalYaw = yaw + (float) driftYaw;
        float goalPitch = pitch + (float) driftPitch;
        float dyaw = Mth.wrapDegrees(goalYaw - p.getYRot());
        float dpitch = goalPitch - p.getXRot();
        float sy = Math.abs(dyaw) < TURN_DEAD ? dyaw : Mth.clamp(dyaw * ease, -maxSpeed, maxSpeed);
        float sp = Math.abs(dpitch) < TURN_DEAD ? dpitch : Mth.clamp(dpitch * ease, -maxSpeed, maxSpeed);
        p.setYRot(p.getYRot() + sy);
        p.setXRot(Mth.clamp(p.getXRot() + sp, -90f, 90f));
    }

    /** Aim smoothly at a world point (block center). */
    private void aimAt(net.minecraft.client.player.LocalPlayer p, Vec3 t) {
        aimAt(p, t, CycleaConfig.get().turnMax());
    }

    /** Lock onto a block FAST (for mining): snap up to 40°/tick with no easing or
     *  drift, so the crosshair lands on it in 1–3 ticks and we actually swing. */
    private void aimAtFast(net.minecraft.client.player.LocalPlayer p, Vec3 t) {
        Vec3 eye = p.getEyePosition();
        double d0 = t.x - eye.x;
        double d1 = t.y - eye.y;
        double d2 = t.z - eye.z;
        double dxz = Math.sqrt(d0 * d0 + d2 * d2);
        float yaw = (float) (Math.toDegrees(Math.atan2(d2, d0)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(d1, dxz)));
        // ease-out but fast: cover ~55% of the remaining angle/tick (capped 26°),
        // so it locks on in ~3 ticks and decelerates onto the block — smooth, not a snap.
        float dyawFull = Mth.wrapDegrees(yaw - p.getYRot());
        float dpitchFull = pitch - p.getXRot();
        float dyaw = Math.abs(dyawFull) < 0.5f ? dyawFull : Mth.clamp(dyawFull * 0.55f, -26f, 26f);
        float dpitch = Math.abs(dpitchFull) < 0.5f ? dpitchFull : Mth.clamp(dpitchFull * 0.55f, -26f, 26f);
        p.setYRot(p.getYRot() + dyaw);
        p.setXRot(Mth.clamp(p.getXRot() + dpitch, -90f, 90f));
    }

    private void aimAt(net.minecraft.client.player.LocalPlayer p, Vec3 t, float speed) {
        Vec3 eye = p.getEyePosition();
        double d0 = t.x - eye.x;
        double d1 = t.y - eye.y;
        double d2 = t.z - eye.z;
        double dxz = Math.sqrt(d0 * d0 + d2 * d2);
        float yaw = (float) (Math.toDegrees(Math.atan2(d2, d0)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(d1, dxz)));
        aim(p, yaw, pitch, speed);
    }

    /**
     * Place a held item at {@code target} by clicking a solid neighbour's face.
     * {@code suffix} filters the hotbar item (e.g. "trapdoor"); empty = any
     * placeable block. Knows the player position via the aim. @return true if placed.
     */
    private boolean place(Minecraft mc, BlockPos target, String suffix) {
        if (!mc.level.getBlockState(target).isAir()) {
            return false;   // something's already there
        }
        Inventory inv = mc.player.getInventory();
        int slot = findHotbar(inv, s -> suffix.isEmpty()
            ? s.getItem() instanceof BlockItem
            : BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(suffix));
        if (slot < 0) {
            return false;   // no matching block/trapdoor in hotbar
        }
        for (Direction d : Direction.values()) {
            BlockPos n = target.relative(d);
            BlockState st = mc.level.getBlockState(n);
            if (st.isAir() || st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
                continue;   // need a solid face to click against
            }
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            inv.setSelectedSlot(slot);
            aimAtFast(mc.player, hit);
            BlockHitResult bhr = new BlockHitResult(hit, face, n, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    /** A horizontal direction we can step UP one block into (solid step, 2 air above,
     *  head clear to move) — typically the tunnel we came from. null if none. Checks
     *  the four cardinals first, then the diagonals (with corner-clearance). */
    private Direction findStepUp(Minecraft mc, BlockPos feet) {
        if (!passable(mc, feet.above())) {
            return null;   // our own head is blocked — can't move sideways yet
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (isStepUp(mc, n)) {
                return d;
            }
        }
        // diagonals: need the step corner solid AND both flanking cardinals open to pass
        Direction[] h = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 4; i++) {
            Direction a = h[i];
            Direction b = h[(i + 1) & 3];
            BlockPos diag = feet.relative(a).relative(b);
            if (isStepUp(mc, diag)
                && passable(mc, feet.relative(a).above()) && passable(mc, feet.relative(b).above())) {
                return a;   // head that general way; the jump carries us onto the corner
            }
        }
        return null;
    }

    /** True if {@code n} (at feet level) is a solid block we can jump up onto: solid,
     *  safe, with two air blocks above to stand in. */
    private boolean isStepUp(Minecraft mc, BlockPos n) {
        return !passable(mc, n) && !hazard(mc, n) && !hasHazardNeighbor(mc, n)
            && passable(mc, n.above()) && passable(mc, n.above(2));
    }

    /** Bulk stone/dirt/gravel we mine through but don't care to keep. */
    private static final java.util.Set<String> JUNK = java.util.Set.of(
        "cobblestone", "cobbled_deepslate", "stone", "deepslate", "dirt", "gravel",
        "granite", "diorite", "andesite", "tuff", "netherrack", "blackstone", "basalt",
        "smooth_basalt", "calcite", "dripstone_block", "end_stone", "cobbled_tuff",
        "diorite_wall", "rooted_dirt");

    /** Toss one junk stack (from anywhere in the inventory) to free a slot. True if it did. */
    private boolean dropJunk(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {                 // 0-8 hotbar, 9-35 main
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()
                || !JUNK.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) {
                continue;
            }
            int menuSlot = (i < 9) ? i + 36 : i;       // player menu: hotbar is 36-44, main 9-35
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, 1,
                net.minecraft.world.inventory.ContainerInput.THROW, mc.player);
            return true;
        }
        return false;
    }

    // ---------------- Surface scout mode ----------------

    /** One tick of surface-scout: eat if needed, deep-scan for stashes, travel on top. */
    private void surfaceStep(Minecraft mc) {
        if (handleEating(mc)) {
            return;
        }
        if (++stashScanTick >= 30) {          // ~1.5s between scans
            stashScanTick = 0;
            scanForStashes(mc);
            if (!active) {
                return;                       // stashPause stopped us
            }
        }
        surfaceTravel(mc);
    }

    /** Scan loaded chunks for big underground container clusters; alert + pin new ones. */
    private void scanForStashes(Minecraft mc) {
        TargetScanner.Scan scan = TargetScanner.scan(mc);
        int[] th = CycleaConfig.get().stashThreshold();
        for (TargetScanner.Base b : scan.bases()) {
            boolean big = b.chests() >= th[0] || b.shulkers() >= th[1];
            if (!big || !alertedStashes.add(b.center().asLong())) {
                continue;                     // too small, or already alerted
            }
            int depth = Mth.floor(mc.player.getY()) - b.center().getY();
            MinimapBridge.pushStash(b, depth);
            mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 0.6f);
            say(mc, "§6§l⚑ BIG STASH BELOW §r§e" + b.chests() + " chests, " + b.shulkers()
                + " shulkers §7~" + depth + " down at §f" + b.center().getX() + ","
                + b.center().getY() + "," + b.center().getZ() + " §7— pinned. " + lootRating(b));
            if (!b.loot().isEmpty()) {
                say(mc, "§7   loot: §f" + b.loot());
            }
            if (CycleaConfig.get().stashPause) {
                stop(mc, "§6big stash found — dig down, or press O to keep scouting");
                return;
            }
        }
    }

    /** Walk across the surface toward the serpentine sweep target, non-destructively:
     *  step up hills, swim, and veer around water/lava/cliffs (never mines). */
    private void surfaceTravel(Minecraft mc) {
        var player = mc.player;
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        if (Math.abs(dx) < 3 && Math.abs(dz) < 3) {
            if (searchMode == SearchMode.SWEEP) {
                advanceSpiral();              // reached a corner — turn, keep covering ground
                newLeg(mc);
            } else {
                stop(mc, "§areached spawn — surface scout done (press O to sweep again)");
            }
            return;
        }

        float baseYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = baseYaw;
        if (surfDetourTicks > 0) {
            yaw = baseYaw + surfDetourSign * 55f;
            surfDetourTicks--;
        }
        aim(player, yaw, 15f);               // look where we're going, slightly down

        key(mc, mc.options.keyUp, true);
        key(mc, mc.options.keySprint, true);

        double r = Math.toRadians(yaw);
        double lx = -Math.sin(r);
        double lz = Math.cos(r);
        BlockPos feet = player.blockPosition();
        BlockPos ahead = new BlockPos(Mth.floor(player.getX() + lx * 1.4),
            feet.getY(), Mth.floor(player.getZ() + lz * 1.4));

        boolean liquidAhead = hazard(mc, ahead) || hazard(mc, ahead.below()) || hazard(mc, ahead.above());
        boolean stepAhead = !passable(mc, ahead) && passable(mc, ahead.above()) && passable(mc, ahead.above(2));
        boolean cliffAhead = passable(mc, ahead) && passable(mc, ahead.below())
            && passable(mc, ahead.below().below());

        if (stepAhead || player.isInWater()) {
            key(mc, mc.options.keyJump, true);   // hop a rise, or swim up
        } else {
            key(mc, mc.options.keyJump, false);
        }

        if ((liquidAhead || cliffAhead) && surfDetourTicks == 0) {
            surfDetourSign = ((feet.getX() + feet.getZ()) & 1) == 0 ? 1 : -1;
            surfDetourTicks = 14;                // veer aside for ~0.7s, then re-aim at target
        }
    }

    /** Make sure a placeable block is selected in the hotbar. True if one is/was found. */
    private boolean ensureHotbarBlock(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        if (inv.getSelectedItem().getItem() instanceof BlockItem) {
            return true;
        }
        int slot = findHotbar(inv, s -> s.getItem() instanceof BlockItem);
        if (slot < 0) {
            return false;
        }
        inv.setSelectedSlot(slot);
        return true;
    }

    private BlockPos breakingPos = null;   // block we're actively breaking (start/continue)

    /** A block we may dig: solid, safe, not unbreakable, not given-up-on. */
    private boolean canMine(Minecraft mc, BlockPos p) {
        return !passable(mc, p) && !hazard(mc, p) && !hasHazardNeighbor(mc, p)
            && !isUnbreakable(mc.level.getBlockState(p)) && !oreBlacklist.contains(p.asLong());
    }

    /**
     * Break the block the RELIABLE way: snap-aim at it and HOLD attack, letting
     * vanilla mining accumulate break progress (exactly like a human holding
     * left-click). The old direct start/continueDestroyBlock approach reset its
     * own progress whenever we briefly walked/paced — this doesn't.
     */
    private void swingAt(Minecraft mc, BlockPos pos) {
        selectToolFor(mc, mc.level.getBlockState(pos));
        key(mc, mc.options.keyUp, false);
        key(mc, mc.options.keySprint, false);
        aimAtFast(mc.player, center(pos));
        key(mc, mc.options.keyAttack, true);   // hold to mine whatever we're aimed at
    }

    /** A face of {@code pos} that's open to air (prefer the one toward the player). */
    private Direction faceToward(Minecraft mc, BlockPos pos) {
        Direction best = Direction.UP;
        double bestDot = -2;
        Vec3 toPlayer = mc.player.getEyePosition().subtract(Vec3.atCenterOf(pos)).normalize();
        for (Direction d : Direction.values()) {
            if (!passable(mc, pos.relative(d))) {
                continue;   // that face is buried
            }
            double dot = d.getStepX() * toPlayer.x + d.getStepY() * toPlayer.y + d.getStepZ() * toPlayer.z;
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    /** If a mineable solid block occupies {@code pos}, dig it out now. @return true if mining. */
    private boolean mineHere(Minecraft mc, BlockPos pos) {
        if (!canMine(mc, pos)) {
            return false;
        }
        mining = pos;
        swingAt(mc, pos);
        return true;
    }

    private static boolean lookingAt(Minecraft mc, BlockPos pos) {
        return mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos);
    }

    private static Vec3 center(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    /** Face and hit the nearest hostile within reach; @return true if fighting. */
    private boolean fightNearbyHostile(Minecraft mc) {
        Entity target = null;
        double bestD = 4.5;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Monster && e.isAlive()) {
                double d = mc.player.distanceTo(e);
                if (d < bestD) {
                    bestD = d;
                    target = e;
                }
            }
        }
        if (target == null) {
            return false;
        }
        // grab a sword if we have one
        Inventory inv = mc.player.getInventory();
        int sword = findHotbar(inv, s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("sword"));
        if (sword >= 0) {
            inv.setSelectedSlot(sword);
        }
        key(mc, mc.options.keyUp, false);
        aimAt(mc.player, target.getEyePosition());
        if (fightCd == 0) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            fightCd = 11;   // vanilla attack cooldown-ish
        }
        return true;
    }

    /** @return true while eating (caller should pause). */
    private boolean handleEating(Minecraft mc) {
        var player = mc.player;
        int food = player.getFoodData().getFoodLevel();
        Inventory inv = player.getInventory();

        if (eating) {
            eatingTicks++;
            boolean full = food >= 18;
            // give it a few ticks to actually start using before checking for completion
            boolean done = (eatingTicks > 6 && !player.isUsingItem()) || full || eatingTicks > 45;
            if (done) {
                key(mc, mc.options.keyUse, false);
                eating = false;
                if (prevSlot >= 0) {
                    inv.setSelectedSlot(prevSlot);
                }
                return true;
            }
            key(mc, mc.options.keyUse, true);
            return true;
        }

        // heal first: low HP + a golden apple on the hotbar
        if (player.getHealth() <= 10f) {
            int gap = findHotbar(inv, s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
            if (gap >= 0) {
                startConsume(mc, inv, gap);
                return true;
            }
        }
        // then hunger: real food only (don't waste golden apples on hunger)
        if (food > 14) {
            return false;
        }
        int foodSlot = findHotbar(inv, s -> s.has(DataComponents.FOOD)
            && !s.is(Items.GOLDEN_APPLE) && !s.is(Items.ENCHANTED_GOLDEN_APPLE));
        if (foodSlot < 0) {
            return false;   // no food; keep going and hope
        }
        startConsume(mc, inv, foodSlot);
        return true;
    }

    private void startConsume(Minecraft mc, Inventory inv, int slot) {
        prevSlot = inv.getSelectedSlot();
        inv.setSelectedSlot(slot);
        key(mc, mc.options.keyUp, false);
        mc.gameMode.stopDestroyBlock();
        key(mc, mc.options.keyUse, true);
        eating = true;
        eatingTicks = 0;
    }

    /**
     * Nearest ore that is FACE-adjacent to the player (feet or head) — i.e.
     * directly exposed to the air the player stands in, so it's actually
     * mineable. Ores behind a wall are reached by digging to them (the detour),
     * never by swinging through the wall.
     */
    private BlockPos adjacentOre(Minecraft mc, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos base : new BlockPos[]{feet, feet.above()}) {
            for (Direction d : Direction.values()) {
                BlockPos p = base.relative(d);
                if (oreBlacklist.contains(p.asLong())) {
                    continue;   // gave up on this one — don't re-grab it
                }
                String path = BuiltInRegistries.BLOCK.getKey(
                    mc.level.getBlockState(p).getBlock()).getPath();
                if (CycleaConfig.get().wantsOre(path) && !hasHazardNeighbor(mc, p)) {
                    double dist = p.distSqr(feet);
                    if (dist < bestD) {
                        bestD = dist;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isOre(BlockState st) {
        String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private static boolean isTrapdoor(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("trapdoor");
    }

    private static boolean wantedOre(Minecraft mc, BlockPos p) {
        String path = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath();
        return CycleaConfig.get().wantsOre(path);
    }

    /**
     * Nearest configured ore that is actually EXPOSED (has an open face — visible
     * in a cave wall or opening) within a small radius. No X-ray: fully-buried
     * ores are ignored, so it only ever detours for ore it can really see.
     */
    private BlockPos findWantedOre(Minecraft mc, BlockPos c, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (oreBlacklist.contains(m.asLong())) {
                        continue;   // gave up on this one already
                    }
                    String path = BuiltInRegistries.BLOCK.getKey(
                        mc.level.getBlockState(m).getBlock()).getPath();
                    if (CycleaConfig.get().wantsOre(path)
                        && isExposed(mc, m) && !hasHazardNeighbor(mc, m)) {
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = m.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** An ore is "visible" if at least one of its faces is open to air. */
    private static boolean isExposed(Minecraft mc, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (passable(mc, p.relative(d))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShovelBlock(BlockState st) {
        String p = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        return p.contains("dirt") || p.contains("gravel") || p.contains("sand")
            || p.contains("clay") || p.contains("podzol") || p.contains("mycelium")
            || p.contains("mud") || p.contains("snow") || p.contains("soul_")
            || p.equals("grass_block") || p.equals("farmland") || p.equals("dirt_path");
    }

    private static boolean isAxeBlock(BlockState st) {
        String p = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        return p.contains("log") || p.contains("wood") || p.contains("planks")
            || p.contains("stem") || p.contains("hyphae") || p.contains("fence")
            || p.contains("barrel") || p.contains("chest") || p.contains("bookshelf")
            || p.contains("crafting_table") || p.contains("mushroom_block");
    }

    /** Pick the right tool: shovel for soft ground, axe for wood, pickaxe otherwise. */
    private void selectToolFor(Minecraft mc, BlockState st) {
        Inventory inv = mc.player.getInventory();
        String want = isShovelBlock(st) ? "shovel" : isAxeBlock(st) ? "axe" : "pickaxe";
        int slot = findHotbar(inv, s -> tool(s, want) && !nearlyBroken(s));
        if (slot < 0 && !want.equals("pickaxe")) {
            slot = findHotbar(inv, s -> tool(s, "pickaxe") && !nearlyBroken(s));   // fallback
        }
        if (slot >= 0 && slot != inv.getSelectedSlot()) {
            inv.setSelectedSlot(slot);
        }
    }

    private static boolean tool(ItemStack s, String suffix) {
        return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(suffix);
    }

    /** Ensure a healthy pickaxe is selected; switch or fail. */
    private boolean ensurePickaxe(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        ItemStack held = inv.getSelectedItem();
        if (isPickaxe(held) && !nearlyBroken(held)) {
            return true;
        }
        int slot = findHotbar(inv, s -> isPickaxe(s) && !nearlyBroken(s));
        if (slot < 0) {
            return false;
        }
        inv.setSelectedSlot(slot);
        return true;
    }

    private static int findHotbar(Inventory inv, java.util.function.Predicate<ItemStack> p) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && p.test(s)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPickaxe(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("pickaxe");
    }

    private static boolean nearlyBroken(ItemStack s) {
        return s.isDamageableItem() && (s.getMaxDamage() - s.getDamageValue()) < 20;
    }

    private static boolean passable(Minecraft mc, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        return st.isAir() || st.is(Blocks.WATER) || st.is(Blocks.CAVE_AIR) || st.is(Blocks.VOID_AIR);
    }

    private static boolean isLava(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.LAVA);
    }

    /** Lava or water — both are "do not enter". */
    private static boolean hazard(Minecraft mc, BlockPos pos) {
        BlockState st = mc.level.getBlockState(pos);
        return st.is(Blocks.LAVA) || st.is(Blocks.WATER);
    }

    private static TargetScanner.Base richest(List<TargetScanner.Base> bases) {
        TargetScanner.Base best = bases.get(0);
        for (TargetScanner.Base b : bases) {
            if (b.total() > best.total()) {
                best = b;
            }
        }
        return best;
    }

    /** Intact vs picked-over vs gutted ruins (by loot present, not by griefing blocks). */
    public static String lootRating(TargetScanner.Base b) {
        return switch (b.status()) {
            case "LOADED" -> "§a★ LOADED (" + b.chests() + "c " + b.shulkers() + "s)";
            case "partial" -> "§e◆ some loot (" + b.chests() + "c)";
            default -> "§7☠ RAIDED — ruins (" + b.stations() + " stations, no loot)";
        };
    }

    private void releaseAll(Minecraft mc) {
        if (mc.options == null) {
            return;
        }
        key(mc, mc.options.keyUp, false);
        key(mc, mc.options.keyAttack, false);
        key(mc, mc.options.keyUse, false);
        key(mc, mc.options.keyJump, false);
        key(mc, mc.options.keySprint, false);
        mc.gameMode.stopDestroyBlock();
        breakingPos = null;
        eating = false;
    }

    private static void key(Minecraft mc, net.minecraft.client.KeyMapping km, boolean down) {
        km.setDown(down);
    }

    private static void say(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
