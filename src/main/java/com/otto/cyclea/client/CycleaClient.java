package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.TargetScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cyclea entry point (v3). Registers keys + HUD, scans on a cadence, and builds
 * the full HUD snapshot: nearest target with heading/vertical/turn hint, radar
 * blips, richest base, loot counts, entity radar, plus the session log with
 * sound + chat + file export on newly discovered bases.
 */
public class CycleaClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
        new KeyMapping.Category(Identifier.fromNamespaceAndPath("cyclea", "keys"));

    private KeyMapping toggleKey;
    private KeyMapping cycleKey;
    private KeyMapping yUpKey;
    private KeyMapping yDownKey;
    private KeyMapping compactKey;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        toggleKey = reg("key.cyclea.toggle", GLFW.GLFW_KEY_LEFT_BRACKET);
        cycleKey = reg("key.cyclea.cycle", GLFW.GLFW_KEY_RIGHT_BRACKET);
        yUpKey = reg("key.cyclea.yup", GLFW.GLFW_KEY_EQUAL);
        yDownKey = reg("key.cyclea.ydown", GLFW.GLFW_KEY_MINUS);
        compactKey = reg("key.cyclea.compact", GLFW.GLFW_KEY_BACKSLASH);

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cyclea", "radar"), new CycleaHud());
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static KeyMapping reg(String id, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(id, key, CATEGORY));
    }

    private void onTick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        CycleaState st = CycleaState.get();

        while (toggleKey.consumeClick()) {
            boolean on = st.toggle();
            say(mc, "§bCyclea " + (on ? "§aON" : "§7off") + "§b — " + st.getTarget().label);
        }
        while (cycleKey.consumeClick()) {
            st.cycleTarget();
            say(mc, "§bCyclea target → §f" + st.getTarget().label);
        }
        while (yUpKey.consumeClick()) {
            st.adjustDeepMaxY(8);
            say(mc, "§bCyclea deep-Y ≤ §f" + st.getDeepMaxY());
        }
        while (yDownKey.consumeClick()) {
            st.adjustDeepMaxY(-8);
            say(mc, "§bCyclea deep-Y ≤ §f" + st.getDeepMaxY());
        }
        while (compactKey.consumeClick()) {
            st.toggleCompact();
        }

        if (!st.isActive() || mc.level == null) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        update(mc, st);
    }

    private void update(Minecraft mc, CycleaState st) {
        TargetScanner.Scan scan = TargetScanner.scan(mc);
        st.setFound(scan.hits());

        BlockPos me = mc.player.blockPosition();

        // session log + new-base alert (#6, #5, #12, #7)
        List<BlockPos> baseCenters = new ArrayList<>();
        int richest = 0;
        for (TargetScanner.Cluster c : scan.baseClusters()) {
            baseCenters.add(c.center());
            richest = Math.max(richest, c.size());
        }
        int fresh = st.recordBases(baseCenters);
        if (fresh > 0) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);
            say(mc, "§a✚ " + fresh + " new base" + (fresh > 1 ? "s" : "")
                + " logged §7(session total " + st.getSessionBaseTotal() + ")");
            exportFinds(st.getSessionBases());
        }

        // nearest target + its detail
        String nearestLine = "";
        String vertical = "";
        String bearing = "";
        int nearestDist = -1;
        int nearestLoot = 0;
        List<int[]> blips = new ArrayList<>();

        if (!scan.hits().isEmpty()) {
            BlockPos best = nearest(me, scan.hits());
            nearestDist = (int) Math.sqrt(me.distSqr(best));
            nearestLine = nearestDist + "m " + heading(best.getX() - me.getX(), best.getZ() - me.getZ())
                + " (" + best.getX() + "," + best.getY() + "," + best.getZ() + ")";
            int dy = best.getY() - me.getY();
            vertical = dy > 3 ? "▲ +" + dy : dy < -3 ? "▼ " + dy : "● level";
            bearing = turnHint(mc, best.getX() - me.getX(), best.getZ() - me.getZ());

            // loot count of the base nearest the player
            for (TargetScanner.Cluster c : scan.baseClusters()) {
                if (c.center().equals(best)) {
                    nearestLoot = c.size();
                    break;
                }
            }
            // radar blips (cap 48)
            for (BlockPos p : scan.hits()) {
                blips.add(new int[]{p.getX() - me.getX(), p.getZ() - me.getZ()});
                if (blips.size() >= 48) {
                    break;
                }
            }
        }

        st.setSnapshot(scan.chests(), scan.shulkers(), baseCenters.size(), scan.beacons(),
            scan.players(), scan.hostiles(), richest, nearestLoot, nearestDist,
            nearestLine, vertical, bearing, blips);
    }

    /** Turn hint relative to where the player is facing. */
    private static String turnHint(Minecraft mc, int dx, int dz) {
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = targetYaw - mc.player.getYRot(1.0f);
        while (rel <= -180) {
            rel += 360;
        }
        while (rel > 180) {
            rel -= 360;
        }
        if (Math.abs(rel) < 22) {
            return "▲ ahead";
        }
        return rel > 0 ? "→ turn right " + (int) Math.abs(rel) + "°"
            : "← turn left " + (int) Math.abs(rel) + "°";
    }

    private void exportFinds(List<BlockPos> bases) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("cyclea-finds.txt");
            List<String> lines = new ArrayList<>();
            lines.add("# Cyclea discovered bases (x, y, z)");
            for (BlockPos b : bases) {
                lines.add(b.getX() + ", " + b.getY() + ", " + b.getZ());
            }
            Files.write(file, lines);
        } catch (IOException ignored) {
            // non-fatal; export is a convenience
        }
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
