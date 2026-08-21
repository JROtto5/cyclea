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
    private double lastProgX = Double.NaN;   // anti-stuck progress tracking
    private double lastProgZ = Double.NaN;
    private int stuckTicks = 0;
    private int jumpTicks = 0;
    private double blocksTraveled = 0;    // session stats
    private long sessionStart = 0;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

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
            approaching = false;
            if (mc.player != null) {
                targetY = -59;   // default diamond level, just above bedrock
                retarget(mc);
            }
        } else {
            releaseAll(mc);
        }
        return active;
    }

    public void stop(Minecraft mc, String reason) {
        active = false;
        releaseAll(mc);
        say(mc, "§6[Autopilot] §7stopped — " + reason);
    }

    /** Called every client tick. Fully guarded — any trouble halts safely. */
    public void tick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null) {
            return;
        }
        try {
            step(mc);
        } catch (Throwable t) {
            stop(mc, "internal error (safe halt)");
        }
    }

    private void step(Minecraft mc) {
        var player = mc.player;

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

        // 1c) inventory full — stop so nothing valuable is lost
        if (player.getInventory().getFreeSlot() < 0) {
            stop(mc, "§einventory full — empty me (into a shulker), then press O");
            return;
        }

        // 2) base found? (throttled — scanning every tick would lag)
        //    switch to navigating toward it; we hand control back on arrival.
        if (!approaching && ++scanTick >= 40) {
            scanTick = 0;
            TargetScanner.Scan scan = TargetScanner.scan(mc);
            if (!scan.bases().isEmpty()) {
                TargetScanner.Base best = richest(scan.bases());
                MinimapBridge.pushBases(scan.bases());
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

        // 4b) anti-stuck: if we're not making progress, unstick — then bail if truly stuck
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
        if (stuckTicks == 60) {           // ~3s no progress → shake it loose
            mining = null;
            oreGoal = null;
            detourTicks = 0;
            jumpTicks = 8;
        }
        if (stuckTicks > 220) {           // ~11s → give up, hand off
            stop(mc, "§estuck — need you (clear the area, then press O)");
            return;
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
        // ore-seek: detour to dig out configured ores, clearing the whole vein
        if (oreGoal != null && !wantedOre(mc, oreGoal)) {
            // seed ore mined — keep going if the vein continues nearby
            oreGoal = findWantedOre(mc, player.blockPosition(), 4);
        }
        if (oreGoal == null && CycleaConfig.get().oreSeekLevel > 0 && ++oreScanTick >= 8) {
            oreScanTick = 0;
            oreGoal = findWantedOre(mc, player.blockPosition(), 5);
            if (oreGoal != null) {
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

        // 7) if we're already committed to a block, keep on it until it's gone
        //    (holding one target is what keeps the camera steady instead of jerking).
        // TOP PRIORITY: clear anything that fell/exists in our own head or feet
        // (gravel & sand avalanche as we dig) so we never suffocate or jam.
        if (mineHere(mc, feet.above())) {
            return;   // head first
        }
        if (mineHere(mc, feet)) {
            return;   // then feet
        }

        if (mining != null && !passable(mc, mining) && !hazard(mc, mining)
            && !hasHazardNeighbor(mc, mining) && !isUnbreakable(mc.level.getBlockState(mining))) {
            selectToolFor(mc, mc.level.getBlockState(mining));
            key(mc, mc.options.keyUp, false);
            key(mc, mc.options.keySprint, false);
            aimAtFast(player, center(mining));
            key(mc, mc.options.keyAttack, lookingAt(mc, mining));
            return;
        }
        mining = null;

        // grab any ore exposed in the tunnel walls first (that's the point of mining)
        BlockPos target = adjacentOre(mc, feet);
        // else clear the way: head, then feet, then (if too shallow) descend
        if (target == null) {
            if (!passable(mc, aheadHead)) {
                target = aheadHead;
            } else if (!passable(mc, aheadFeet)) {
                target = aheadFeet;
            } else if (feet.getY() > targetY && !passable(mc, aheadFeet.below())
                && !hazard(mc, aheadFeet.below()) && !hasHazardNeighbor(mc, aheadFeet.below())
                && !isUnbreakable(mc.level.getBlockState(aheadFeet.below()))) {
                target = aheadFeet.below();   // staircase down toward Y-59 (never into bedrock)
            }
        }

        if (target != null) {
            mining = target;
            selectToolFor(mc, mc.level.getBlockState(target));
            key(mc, mc.options.keyUp, false);
            key(mc, mc.options.keySprint, false);
            aimAtFast(player, center(target));
            key(mc, mc.options.keyAttack, lookingAt(mc, target));
        } else {
            // path clear: walk smooth and straight (body stays on the travel line);
            // glancing is mostly pitch (harmless) + a small yaw so it doesn't weave.
            key(mc, mc.options.keyAttack, false);
            mc.gameMode.stopDestroyBlock();
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
            // 1×1 trapdoor mode: cap the tunnel with a trapdoor above head for a sprint-lane
            if (CycleaConfig.get().oneByOne && mc.level.getBlockState(feet.above(2)).isAir()) {
                place(mc, feet.above(2), "trapdoor");
            }
            // walk (and sprint) while pointed down the tunnel — tighter tolerance = straighter
            boolean facing = Math.abs(Mth.degreesDifference(player.getYRot(), travelYaw)) < 30f;
            key(mc, mc.options.keyUp, facing);
            key(mc, mc.options.keySprint, facing);
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
        Direction[] base = {primary, secondary, secondary.getOpposite(), primary.getOpposite()};
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

    /** If a mineable solid block occupies {@code pos}, dig it out now. @return true if mining. */
    private boolean mineHere(Minecraft mc, BlockPos pos) {
        if (passable(mc, pos) || hazard(mc, pos) || hasHazardNeighbor(mc, pos)
            || isUnbreakable(mc.level.getBlockState(pos))) {
            return false;
        }
        mining = pos;
        selectToolFor(mc, mc.level.getBlockState(pos));
        key(mc, mc.options.keyUp, false);
        key(mc, mc.options.keySprint, false);
        aimAtFast(mc.player, center(pos));
        key(mc, mc.options.keyAttack, lookingAt(mc, pos));
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
    private static BlockPos adjacentOre(Minecraft mc, BlockPos feet) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos base : new BlockPos[]{feet, feet.above()}) {
            for (Direction d : Direction.values()) {
                BlockPos p = base.relative(d);
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

    private static boolean wantedOre(Minecraft mc, BlockPos p) {
        String path = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath();
        return CycleaConfig.get().wantsOre(path);
    }

    /**
     * Nearest configured ore that is actually EXPOSED (has an open face — visible
     * in a cave wall or opening) within a small radius. No X-ray: fully-buried
     * ores are ignored, so it only ever detours for ore it can really see.
     */
    private static BlockPos findWantedOre(Minecraft mc, BlockPos c, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
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

    /** Rough loot read: shulkers + chests still present vs. a picked-over shell. */
    public static String lootRating(TargetScanner.Base b) {
        if (b.shulkers() >= 1 || b.chests() >= 8) {
            return "§a★ LOADED (likely untouched)";
        }
        if (b.chests() >= 4) {
            return "§e◆ some loot left";
        }
        return "§7likely raided";
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
