package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
    private int targetX = 0;   // spawn
    private int targetZ = 0;
    private boolean eating = false;
    private int prevSlot = -1;
    private int scanTick = 0;

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
        if (!active) {
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

        // 1) survival guard
        if (player.getHealth() <= 6.0f) {
            stop(mc, "§clow health");
            return;
        }

        // 2) base found? (throttled — scanning every tick would lag) report + halt
        if (++scanTick >= 10) {
            scanTick = 0;
            TargetScanner.Scan scan = TargetScanner.scan(mc);
            if (!scan.bases().isEmpty()) {
                TargetScanner.Base best = richest(scan.bases());
                releaseAll(mc);
                say(mc, "§a★ BASE FOUND §f" + best.chests() + " chests, " + best.shulkers()
                    + " shulkers §7at §f" + best.center().getX() + "," + best.center().getY()
                    + "," + best.center().getZ() + " §7— " + lootRating(best));
                MinimapBridge.pushBases(scan.bases());
                active = false;
                return;
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

        // 5) aim toward the target (horizontal tunnel)
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
            stop(mc, "reached target area");
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setXRot(0f);
        Direction dir = Direction.fromYRot(yaw);

        BlockPos feet = player.blockPosition();
        BlockPos aheadFeet = feet.relative(dir);
        BlockPos aheadHead = aheadFeet.above();

        // 6) lava / hazard guard
        if (isLava(mc, aheadFeet) || isLava(mc, aheadHead)
            || isLava(mc, aheadFeet.below()) || isLava(mc, feet.below())) {
            stop(mc, "§clava ahead");
            return;
        }

        // 7) mine what's in the way, else walk forward
        boolean headBlocked = !passable(mc, aheadHead);
        boolean feetBlocked = !passable(mc, aheadFeet);
        if (headBlocked) {
            mine(mc, aheadHead, dir);
        } else if (feetBlocked) {
            mine(mc, aheadFeet, dir);
        } else {
            // clear ahead — check we're not about to walk off a deep ledge
            if (passable(mc, aheadFeet.below()) && passable(mc, aheadFeet.below().below())) {
                stop(mc, "§edrop ahead (avoiding a fall)");
                return;
            }
            mc.gameMode.stopDestroyBlock();
            key(mc, mc.options.keyAttack, false);
            key(mc, mc.options.keyUp, true);
        }
    }

    private void mine(Minecraft mc, BlockPos pos, Direction dir) {
        key(mc, mc.options.keyUp, false);
        mc.gameMode.continueDestroyBlock(pos, dir.getOpposite());
        key(mc, mc.options.keyAttack, false);
    }

    /** @return true while eating (caller should pause). */
    private boolean handleEating(Minecraft mc) {
        var player = mc.player;
        int food = player.getFoodData().getFoodLevel();
        Inventory inv = player.getInventory();

        if (eating) {
            if (player.isUsingItem()) {
                key(mc, mc.options.keyUse, true);
                return true;
            }
            key(mc, mc.options.keyUse, false);
            eating = false;
            if (prevSlot >= 0) {
                inv.setSelectedSlot(prevSlot);
            }
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
