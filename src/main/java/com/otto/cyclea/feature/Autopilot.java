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
        float travelYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        Direction dir = Direction.fromYRot(travelYaw);

        BlockPos feet = player.blockPosition();
        BlockPos aheadFeet = feet.relative(dir);
        BlockPos aheadHead = aheadFeet.above();

        // 6) hazard guard — lava AND water
        if (hazard(mc, aheadFeet) || hazard(mc, aheadHead)
            || hazard(mc, aheadFeet.below()) || hazard(mc, feet.below())) {
            stop(mc, isLava(mc, aheadFeet) || isLava(mc, aheadHead)
                || isLava(mc, aheadFeet.below()) || isLava(mc, feet.below())
                ? "§clava ahead" : "§bwater ahead");
            return;
        }

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
            // path clear: look at a steady point far along the travel line (level), then walk
            key(mc, mc.options.keyAttack, false);
            mc.gameMode.stopDestroyBlock();
            Vec3 eye = player.getEyePosition();
            aimAt(player, new Vec3(eye.x + Math.sin(-Math.toRadians(travelYaw)) * 8.0,
                eye.y, eye.z + Math.cos(Math.toRadians(travelYaw)) * 8.0));
            if (passable(mc, aheadFeet.below()) && passable(mc, aheadFeet.below().below())) {
                stop(mc, "§edrop ahead (avoiding a fall)");
                return;
            }
            boolean facing = Math.abs(Mth.degreesDifference(player.getYRot(), travelYaw)) < 20f;
            key(mc, mc.options.keyUp, facing);
        }
    }

    private static final float TURN_EASE = 0.28f;   // fraction of the remaining angle per tick
    private static final float TURN_MAX = 11f;      // hard cap on degrees/tick (fast pans stay smooth)
    private static final float TURN_DEAD = 0.4f;    // snap the last fraction of a degree — kills micro-jitter

    /**
     * Ease smoothly toward a yaw/pitch: cover a fraction of the remaining angle
     * each tick (so the camera decelerates and settles like a hand on a mouse),
     * capped per tick, with a deadzone so it stops dead instead of shimmering.
     */
    private void aim(net.minecraft.client.player.LocalPlayer p, float yaw, float pitch) {
        // fold the human drift into the goal so we ease toward a subtly-moving point
        float goalYaw = yaw + (float) driftYaw;
        float goalPitch = pitch + (float) driftPitch;
        float dyaw = Mth.wrapDegrees(goalYaw - p.getYRot());
        float dpitch = goalPitch - p.getXRot();
        float sy = Math.abs(dyaw) < TURN_DEAD ? dyaw : Mth.clamp(dyaw * TURN_EASE, -TURN_MAX, TURN_MAX);
        float sp = Math.abs(dpitch) < TURN_DEAD ? dpitch : Mth.clamp(dpitch * TURN_EASE, -TURN_MAX, TURN_MAX);
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
