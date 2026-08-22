package com.otto.cyclea.client;

import com.otto.cyclea.CycleaConfig;
import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.Autopilot;
import com.otto.cyclea.feature.MinimapBridge;
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
 * Cyclea entry point. A base finder: chests (below Y{@value TargetScanner#CHEST_MAX_Y})
 * plus shulkers (any level) clustered 3+ together = a base. Keeps the HUD/state
 * updated, logs + pings + pins new bases, and pins them to Xaero with the Y level.
 */
public class CycleaClient implements ClientModInitializer {

    private static final KeyMapping.Category CATEGORY =
        new KeyMapping.Category(Identifier.fromNamespaceAndPath("cyclea", "keys"));

    private KeyMapping toggleKey;
    private KeyMapping cycleKey;
    private KeyMapping compactKey;
    private KeyMapping pinKey;
    private KeyMapping autopilotKey;
    private KeyMapping searchModeKey;
    private KeyMapping modeKey;
    private KeyMapping configKey;
    private int tickCounter = 0;
    private boolean wasAlive = true;
    private List<TargetScanner.Base> lastBases = List.of();

    @Override
    public void onInitializeClient() {
        toggleKey = reg("key.cyclea.toggle", GLFW.GLFW_KEY_LEFT_BRACKET);
        cycleKey = reg("key.cyclea.cycle", GLFW.GLFW_KEY_RIGHT_BRACKET);
        compactKey = reg("key.cyclea.compact", GLFW.GLFW_KEY_BACKSLASH);
        pinKey = reg("key.cyclea.pin", GLFW.GLFW_KEY_P);
        autopilotKey = reg("key.cyclea.autopilot", GLFW.GLFW_KEY_O);
        searchModeKey = reg("key.cyclea.searchmode", GLFW.GLFW_KEY_K);
        modeKey = reg("key.cyclea.mode", GLFW.GLFW_KEY_M);
        configKey = reg("key.cyclea.config", GLFW.GLFW_KEY_J);

        CycleaConfig.get().load();
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cyclea", "radar"), new CycleaHud());
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        loadPriorFinds();
    }

    /** Seed bases discovered in past sessions so they don't re-alert on rejoin. */
    private void loadPriorFinds() {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("cyclea-finds.txt");
            if (!Files.exists(file)) {
                return;
            }
            List<BlockPos> prior = new ArrayList<>();
            for (String ln : Files.readAllLines(file)) {
                String[] p = ln.split("\\s*,\\s*");
                if (p.length == 3) {
                    try {
                        prior.add(new BlockPos(Integer.parseInt(p[0].trim()),
                            Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())));
                    } catch (NumberFormatException ignored) {
                        // header/comment line
                    }
                }
            }
            CycleaState.get().seedSeen(prior);
        } catch (Exception ignored) {
            // non-fatal
        }
    }

    private static KeyMapping reg(String id, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(id, key, CATEGORY));
    }

    private boolean announcedReady = false;

    private void onTick(Minecraft mc) {
        if (mc.player == null) {
            announcedReady = false;   // re-arm for the next world join
            return;
        }
        // one-time startup self-check per world: confirm we loaded and whether Xaero is here
        if (!announcedReady) {
            announcedReady = true;
            boolean xaero = MinimapBridge.available();
            say(mc, "§b[Cyclea] ready §7— minimap: " + (xaero ? "§aXaero ✓" : "§estandalone")
                + " §7· O=auto, M=mode, J=config");
        }
        CycleaState st = CycleaState.get();

        // death → drop a death waypoint on the map
        boolean alive = mc.player.isAlive() && mc.player.getHealth() > 0f;
        if (wasAlive && !alive) {
            if (MinimapBridge.pushDeath(mc.player.blockPosition())) {
                say(mc, "§7☠ death point pinned to your map");
            }
        }
        wasAlive = alive;

        while (toggleKey.consumeClick()) {
            boolean on = st.toggle();
            say(mc, "§bCyclea " + (on ? "§aON" : "§7off") + "§b — " + st.getTarget().label);
        }
        while (cycleKey.consumeClick()) {
            st.cycleTarget();
            say(mc, "§bCyclea target → §f" + st.getTarget().label);
        }
        while (compactKey.consumeClick()) {
            st.toggleCompact();
        }
        while (pinKey.consumeClick()) {
            int n = pushToMinimap(lastBases);
            if (n > 0) {
                say(mc, "§d📍 Pinned §f" + n + "§d base" + (n > 1 ? "s" : "") + " to your minimap");
            } else if (!MinimapBridge.available()) {
                say(mc, "§7Cyclea: Xaero's Minimap not found — pins unavailable");
            } else {
                say(mc, "§7Cyclea: no new bases to pin");
            }
        }
        while (autopilotKey.consumeClick()) {
            boolean on = Autopilot.get().toggle(mc);
            if (on) {
                st.setActive(true);   // finder must run so the autopilot can halt on bases
                say(mc, "§6[Autopilot] §aENGAGED §7— heading toward "
                    + Autopilot.get().getTargetX() + "," + Autopilot.get().getTargetZ()
                    + " (spawn), mining/eating/dodging lava until a base pings");
            } else {
                say(mc, "§6[Autopilot] §7disengaged");
            }
        }

        while (configKey.consumeClick()) {
            mc.setScreenAndShow(new CycleaConfigScreen());
        }
        while (searchModeKey.consumeClick()) {
            String m = Autopilot.get().toggleSearchMode(mc);
            say(mc, "§6[Autopilot] §7search mode → §f" + m
                + ("SWEEP".equals(m) ? " §7(spiral-search this area)" : " §7(beeline to spawn)"));
        }
        while (modeKey.consumeClick()) {
            Autopilot.get().toggleMode(mc);   // Miner ⇄ Surface scout
        }

        // drive the bot every tick (it self-guards and no-ops when off)
        Autopilot.get().tick(mc);

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
        lastBases = scan.bases();

        BlockPos me = mc.player.blockPosition();

        List<BlockPos> baseCenters = new ArrayList<>();
        int richest = 0;
        for (TargetScanner.Base b : scan.bases()) {
            baseCenters.add(b.center());
            richest = Math.max(richest, b.total());
        }

        int fresh = st.recordBases(baseCenters);
        if (fresh > 0) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);
            say(mc, "§a✚ " + fresh + " new base" + (fresh > 1 ? "s" : "")
                + " found §7(session " + st.getSessionBaseTotal() + ")");
            exportFinds(st.getSessionBases());
            pushToMinimap(scan.bases());
        }

        String nearestLine = "";
        String vertical = "";
        String bearing = "";
        int nearestDist = -1;
        int nearestLoot = 0;
        List<int[]> blips = new ArrayList<>();

        if (!scan.hits().isEmpty()) {
            BlockPos best = nearest(me, scan.hits());
            nearestDist = (int) Math.sqrt(me.distSqr(best));
            vertical = vertical(best.getY() - me.getY());
            bearing = turnHint(mc, best.getX() - me.getX(), best.getZ() - me.getZ());

            if (st.getTarget() == CycleaState.Target.BASES) {
                TargetScanner.Base b = baseAt(scan.bases(), best);
                nearestLoot = b == null ? 0 : b.total();
                nearestLine = (b == null ? "" : b.chests() + "c " + b.shulkers() + "s  ")
                    + "Y" + best.getY() + "  " + nearestDist + "m "
                    + heading(best.getX() - me.getX(), best.getZ() - me.getZ())
                    + " (" + best.getX() + "," + best.getY() + "," + best.getZ() + ")";
            } else {
                nearestLine = nearestDist + "m " + heading(best.getX() - me.getX(), best.getZ() - me.getZ())
                    + " (" + best.getX() + "," + best.getY() + "," + best.getZ() + ")";
            }

            for (BlockPos p : scan.hits()) {
                blips.add(new int[]{p.getX() - me.getX(), p.getZ() - me.getZ()});
                if (blips.size() >= 48) {
                    break;
                }
            }
        }

        st.setSnapshot(scan.chestsTotal(), scan.shulkersTotal(), baseCenters.size(), 0,
            scan.players(), scan.hostiles(), richest, nearestLoot, nearestDist,
            nearestLine, vertical, bearing, blips);
    }

    private static TargetScanner.Base baseAt(List<TargetScanner.Base> bases, BlockPos center) {
        for (TargetScanner.Base b : bases) {
            if (b.center().equals(center)) {
                return b;
            }
        }
        return null;
    }

    private static String vertical(int dy) {
        return dy > 3 ? "▲ +" + dy : dy < -3 ? "▼ " + dy : "● level";
    }

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
        return rel > 0 ? "→ right " + (int) Math.abs(rel) + "°" : "← left " + (int) Math.abs(rel) + "°";
    }

    private static int pushToMinimap(List<TargetScanner.Base> bases) {
        try {
            return MinimapBridge.pushBases(bases);
        } catch (Throwable t) {
            return 0;
        }
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
            // export is a convenience; never fatal
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
