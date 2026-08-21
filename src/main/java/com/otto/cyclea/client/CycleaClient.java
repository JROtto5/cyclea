package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.TargetScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Cyclea entry point (v2). Registers the toggle + cycle keys and the radar HUD,
 * scans a few times a second, and keeps the shared state's tallies and
 * nearest-target line up to date. Bases target = the "cluster bomb".
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

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cyclea", "radar"), new CycleaHud());

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
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;

        TargetScanner.Scan scan = TargetScanner.scan(mc);
        CycleaState.get().setFound(scan.hits());
        String nearest = buildNearest(mc, scan.hits());
        CycleaState.get().setTallies(scan.chests(), scan.shulkers(), scan.bases(), nearest);
    }

    /** Build the "nearest" line for the HUD, with a red clump/base flag. */
    private String buildNearest(Minecraft mc, List<BlockPos> found) {
        CycleaState.Target target = CycleaState.get().getTarget();
        if (found.isEmpty()) {
            return "§7no " + target.label + " loaded nearby";
        }
        BlockPos me = mc.player.blockPosition();

        boolean clumpable = target == CycleaState.Target.LOOT
            || target == CycleaState.Target.SHULKERS;
        if (clumpable) {
            List<BlockPos> clumps = TargetScanner.clumps(found, 3, 2);
            if (!clumps.isEmpty()) {
                BlockPos c = nearest(me, clumps);
                announceClump(mc, target, clumps.size(), me, c);
                return "§c⚠ " + clumps.size() + " clump" + (clumps.size() > 1 ? "s" : "")
                    + " — nearest " + line(me, c);
            }
        }

        BlockPos best = nearest(me, found);
        String tag = target == CycleaState.Target.BASES ? "§cBASE" : "§fnearest";
        return tag + " §7" + line(me, best);
    }

    private long lastClumpAnnounce = 0;

    private void announceClump(Minecraft mc, CycleaState.Target target, int n, BlockPos me, BlockPos c) {
        // throttle the chat shout so it isn't spammy (once every ~5s of ticks)
        if (tickCounter == 0 && lastClumpAnnounce++ % 10 == 0) {
            say(mc, "§c⚠ CLUMP found! §f" + n + " " + target.label
                + " clump" + (n > 1 ? "s" : "") + " §7— nearest " + line(me, c));
        }
    }

    private static String line(BlockPos me, BlockPos p) {
        int dist = (int) Math.sqrt(me.distSqr(p));
        return "§f" + dist + "m " + heading(p.getX() - me.getX(), p.getZ() - me.getZ())
            + " §8(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
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
