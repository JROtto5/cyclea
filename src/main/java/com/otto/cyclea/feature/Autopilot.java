package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
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
                targetY = mc.player.blockPosition().getY();   // hold the depth you start at
                newLeg(mc);
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

        // 1) survival guard
        if (player.getHealth() <= 6.0f) {
            stop(mc, "§clow health");
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
                targetX = best.center().getX();
                targetZ = best.center().getZ();
                approaching = true;
                newLeg(mc);
                mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                say(mc, "§a★ BASE FOUND §f" + best.chests() + " chests, " + best.shulkers()
                    + " shulkers §7at §f" + best.center().getX() + "," + best.center().getY()
                    + "," + best.center().getZ() + " §7— " + lootRating(best)
                    + " §e→ navigating there…");
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

        // 5) travel direction toward the target
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        if (Math.abs(dx) < 3 && Math.abs(dz) < 3) {
            if (approaching) {
                approaching = false;
                targetX = 0;   // restore the sweep goal (spawn) for next time
                targetZ = 0;
                stop(mc, "§a✔ arrived at base — §fyour turn (press O to resume toward spawn)");
            } else {
                stop(mc, "reached target area");
            }
            return;
        }
        // choose which axis to advance so we staircase diagonally toward the target,
        // switching axes in ~6-block segments instead of committing to one direction.
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
            axisX = false;   // pulled ahead on X — switch to Z
        } else if (!axisX && progZ > progX * (adZ / adX) + 6) {
            axisX = true;    // pulled ahead on Z — switch to X
        }
        Direction primary = axisX
            ? (totDx >= 0 ? Direction.EAST : Direction.WEST)
            : (totDz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction secondary = axisX
            ? (totDz >= 0 ? Direction.SOUTH : Direction.NORTH)
            : (totDx >= 0 ? Direction.EAST : Direction.WEST);

        BlockPos feet = player.blockPosition();

        // 6) route AROUND lava/water instead of just stopping: try the preferred
        //    direction, then the other axis, then sidesteps. Only halt if boxed in.
        Direction dir = chooseSafeDir(mc, feet, primary, secondary);
        if (dir == null) {
            stop(mc, "§cboxed in by lava/water — need you");
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
        if (mining != null && (!passable(mc, mining) && !hazard(mc, mining))) {
            key(mc, mc.options.keyUp, false);
            aimAt(player, center(mining));
            key(mc, mc.options.keyAttack, lookingAt(mc, mining));
            return;
        }
        mining = null;

        // pick the next block to clear: head, then feet, then (if too shallow) descend
        BlockPos target = null;
        if (!passable(mc, aheadHead)) {
            target = aheadHead;
        } else if (!passable(mc, aheadFeet)) {
            target = aheadFeet;
        } else if (feet.getY() > targetY && !passable(mc, aheadFeet.below())
            && !hazard(mc, aheadFeet.below())) {
            target = aheadFeet.below();   // staircase back down toward Y-59
        }

        if (target != null) {
            mining = target;
            key(mc, mc.options.keyUp, false);
            aimAt(player, center(target));
            key(mc, mc.options.keyAttack, lookingAt(mc, target));
        } else {
            // path clear: walk, glancing around like a person scanning the tunnel
            key(mc, mc.options.keyAttack, false);
            mc.gameMode.stopDestroyBlock();
            if (--glanceTimer <= 0) {
                // occasional big flick, otherwise a normal look-around
                boolean flick = rng.nextFloat() < 0.35f;
                float amp = flick ? 60f : 34f;
                glanceYaw = (rng.nextFloat() - 0.5f) * amp;          // ±17° normal, ±30° flick
                glancePitch = (rng.nextFloat() - 0.5f) * (flick ? 34f : 22f);
                glanceTimer = flick ? 6 + rng.nextInt(10) : 12 + rng.nextInt(30);
            }
            float flickSpeed = Math.abs(glanceYaw) > 22f ? 16f : TURN_MAX;   // snap faster on big flicks
            aim(player, travelYaw + glanceYaw, glancePitch, flickSpeed);
            if (passable(mc, aheadFeet.below()) && passable(mc, aheadFeet.below().below())) {
                stop(mc, "§edrop ahead (avoiding a fall)");
                return;
            }
            // keep walking as long as we're roughly pointed down the tunnel
            boolean facing = Math.abs(Mth.degreesDifference(player.getYRot(), travelYaw)) < 42f;
            key(mc, mc.options.keyUp, facing);
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

    /** True if the next {@code dist} blocks along {@code d} are free of lava/water. */
    private static boolean pathClear(Minecraft mc, BlockPos feet, Direction d, int dist) {
        for (int i = 1; i <= dist; i++) {
            BlockPos af = feet.relative(d, i);
            BlockPos ah = af.above();
            if (lavaNear(mc, af, 2) || lavaNear(mc, ah, 2)) {
                return false;
            }
            if (i == 1 && (hazard(mc, af) || hazard(mc, ah) || hazard(mc, af.below()))) {
                return false;
            }
        }
        return true;
    }

    /** Scan a box of half-size r around a position for lava — catches pockets before we dig in. */
    private static boolean lavaNear(Minecraft mc, BlockPos c, int r) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (mc.level.getBlockState(m).is(Blocks.LAVA)) {
                        return true;
                    }
                }
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
        aim(p, yaw, pitch, TURN_MAX);
    }

    private void aim(net.minecraft.client.player.LocalPlayer p, float yaw, float pitch, float maxSpeed) {
        // fold the human drift into the goal so we ease toward a subtly-moving point
        float goalYaw = yaw + (float) driftYaw;
        float goalPitch = pitch + (float) driftPitch;
        float dyaw = Mth.wrapDegrees(goalYaw - p.getYRot());
        float dpitch = goalPitch - p.getXRot();
        float sy = Math.abs(dyaw) < TURN_DEAD ? dyaw : Mth.clamp(dyaw * TURN_EASE, -maxSpeed, maxSpeed);
        float sp = Math.abs(dpitch) < TURN_DEAD ? dpitch : Mth.clamp(dpitch * TURN_EASE, -maxSpeed, maxSpeed);
        p.setYRot(p.getYRot() + sy);
        p.setXRot(Mth.clamp(p.getXRot() + sp, -90f, 90f));
    }

    /** Aim smoothly at a world point (block center). */
    private void aimAt(net.minecraft.client.player.LocalPlayer p, Vec3 t) {
        Vec3 eye = p.getEyePosition();
        double d0 = t.x - eye.x;
        double d1 = t.y - eye.y;
        double d2 = t.z - eye.z;
        double dxz = Math.sqrt(d0 * d0 + d2 * d2);
        float yaw = (float) (Math.toDegrees(Math.atan2(d2, d0)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(d1, dxz)));
        aim(p, yaw, pitch);
    }

    private static boolean lookingAt(Minecraft mc, BlockPos pos) {
        return mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos);
    }

    private static Vec3 center(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
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

        if (food > 14) {
            return false;
        }
        int foodSlot = findHotbar(inv, s -> s.has(DataComponents.FOOD));
        if (foodSlot < 0) {
            return false;   // no food; keep going and hope
        }
        prevSlot = inv.getSelectedSlot();
        inv.setSelectedSlot(foodSlot);
        key(mc, mc.options.keyUp, false);
        mc.gameMode.stopDestroyBlock();
        key(mc, mc.options.keyUse, true);
        eating = true;
        eatingTicks = 0;
        return true;
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
