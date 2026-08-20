package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.TargetScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Cyclea entry point (v1). Registers the toggle + cycle keys, scans for the
 * active target a few times a second, and calls out the nearest one in chat
 * with a compass heading and distance — a lightweight "finder" pointer that
 * pairs with your minimap. In-world guide-beams land in a later version once
 * the 26.2 render pipeline is wired up.
 */
public class CycleaClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
        new KeyMapping.Category(Identifier.fromNamespaceAndPath("cyclea", "keys"));

    private KeyMapping toggleKey;
    private KeyMapping cycleKey;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.cyclea.toggle", GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY));
        cycleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.cyclea.cycle", GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        while (toggleKey.consumeClick()) {
            boolean on = CycleaState.get().toggle();
            say(mc, "§bCyclea " + (on ? "§aON" : "§7off")
                + "§b — target: " + CycleaState.get().getTarget().label);
        }
        while (cycleKey.consumeClick()) {
            CycleaState.get().cycleTarget();
            say(mc, "§bCyclea target → §f" + CycleaState.get().getTarget().label);
        }

        if (!CycleaState.get().isActive() || mc.level == null) {
            return;
        }
        if (++tickCounter < 40) {
            return;
        }
        tickCounter = 0;

        List<BlockPos> found = TargetScanner.scan(mc);
        CycleaState.get().setFound(found);
        reportNearest(mc, found);
    }

    private void reportNearest(Minecraft mc, List<BlockPos> found) {
        if (found.isEmpty()) {
            String where = CycleaState.get().getTarget() == CycleaState.Target.CAVES
                ? " within " + CycleaState.get().getRadius() + " blocks"
                : " in loaded chunks";
            say(mc, "§7Cyclea: no " + CycleaState.get().getTarget().label + where);
            return;
        }
        BlockPos me = mc.player.blockPosition();
        CycleaState.Target target = CycleaState.get().getTarget();

        // Look for clumps first (double chests, shulker walls, storage rooms).
        boolean clumpable = target == CycleaState.Target.CHESTS
            || target == CycleaState.Target.SHULKERS
            || target == CycleaState.Target.SPAWNERS;
        if (clumpable) {
            List<BlockPos> clumps = TargetScanner.clumps(found, 3, 2);
            if (!clumps.isEmpty()) {
                BlockPos c = nearest(me, clumps);
                int cd = (int) Math.sqrt(me.distSqr(c));
                say(mc, "§c⚠ CLUMP found! §f" + clumps.size() + " "
                    + target.label + " clump" + (clumps.size() > 1 ? "s" : "")
                    + " §7— nearest §f" + cd + "m " + heading(c.getX() - me.getX(), c.getZ() - me.getZ())
                    + " §c(" + c.getX() + ", " + c.getY() + ", " + c.getZ() + ")");
                return;
            }
        }

        BlockPos best = nearest(me, found);
        int dist = (int) Math.sqrt(me.distSqr(best));
        String dir = heading(best.getX() - me.getX(), best.getZ() - me.getZ());
        say(mc, "§bCyclea ▶ §f" + found.size() + " " + target.label
            + "§7 — nearest §f" + dist + "m " + dir
            + " §8(" + best.getX() + ", " + best.getY() + ", " + best.getZ() + ")");
    }

    private static BlockPos nearest(BlockPos me, List<BlockPos> pts) {
        BlockPos best = pts.get(0);
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : pts) {
            double d = me.distSqr(p);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static String heading(int dx, int dz) {
        String ns = dz < 0 ? "N" : "S";
        String ew = dx < 0 ? "W" : "E";
        if (Math.abs(dx) < Math.abs(dz) / 2.0) {
            return ns;
        }
        if (Math.abs(dz) < Math.abs(dx) / 2.0) {
            return ew;
        }
        return ns + ew;
    }

    private static void say(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
