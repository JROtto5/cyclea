package com.otto.cyclea.client;

import com.otto.cyclea.CycleaConfig;
import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.Autopilot;
import com.otto.cyclea.feature.MinimapBridge;
import com.otto.cyclea.feature.TargetScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
    private KeyMapping panicKey;
    private KeyMapping vaultKey;
    private KeyMapping clearLogKey;
    private KeyMapping configKey;
    private KeyMapping freeCursorKey;
    private int watchCounter = 0;
    private int watchAlertCd = 0;
    private int tickCounter = 0;
    private int oreScanCounter = 0;
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
        panicKey = reg("key.cyclea.panic", GLFW.GLFW_KEY_G);
        vaultKey = reg("key.cyclea.vault", GLFW.GLFW_KEY_V);
        clearLogKey = reg("key.cyclea.clearlog", GLFW.GLFW_KEY_C);
        configKey = reg("key.cyclea.config", GLFW.GLFW_KEY_J);
        freeCursorKey = reg("key.cyclea.freecursor", GLFW.GLFW_KEY_U);

        CycleaConfig.get().load();
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cyclea", "radar"), new CycleaHud());
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // draw the see-through overlay + big alerts ON TOP of any open menu, so the polish
        // (and "BASE!" banners) stay visible while you're in the pause/inventory screen
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) ->
            ScreenEvents.afterForeground(screen).register((scr, gx, mx, my, dt) -> {
                Minecraft m = Minecraft.getInstance();
                CycleaState st = CycleaState.get();
                if (m.player == null) {
                    return;
                }
                if (CycleaConfig.get().oreEsp || CycleaConfig.get().tracers) {
                    CycleaHud.renderWorldOverlay(gx, m, st);
                }
                CycleaHud.renderBaseLog(gx, m, st);
                CycleaHud.renderBigAlert(gx, m, st);
            }));

        // /cyc goto <x> <z> | /cyc spawn | /cyc stop
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, reg) ->
            dispatcher.register(ClientCommands.literal("cyc")
                .then(ClientCommands.literal("goto")
                    .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                Autopilot.get().gotoCoord(Minecraft.getInstance(),
                                    IntegerArgumentType.getInteger(ctx, "x"),
                                    IntegerArgumentType.getInteger(ctx, "z"));
                                return 1;
                            }))))
                .then(ClientCommands.literal("mine")
                    .executes(ctx -> {
                        Autopilot.get().startStripMine(Minecraft.getInstance());
                        return 1;
                    }))
                .then(ClientCommands.literal("minemoney")
                    .executes(ctx -> {
                        Autopilot.get().startMineMoney(Minecraft.getInstance());
                        return 1;
                    }))
                .then(ClientCommands.literal("diamonds")
                    .executes(ctx -> {
                        Autopilot.get().startDiamonds(Minecraft.getInstance());
                        return 1;
                    }))
                .then(ClientCommands.literal("spawn")
                    .executes(ctx -> {
                        Autopilot.get().gotoSpawn(Minecraft.getInstance());
                        return 1;
                    }))
                .then(ClientCommands.literal("stop")
                    .executes(ctx -> {
                        Autopilot.get().stop(Minecraft.getInstance(), "§7stopped by /cyc stop");
                        return 1;
                    }))
                .then(ClientCommands.literal("stats")
                    .executes(ctx -> {
                        Autopilot.get().printStats(Minecraft.getInstance());
                        return 1;
                    }))));

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

    private float prevHp = Float.NaN;
    private int homeCd = 0;
    private int suffTicks = 0;   // consecutive ticks with head in a suffocating block
    private boolean deathLatched = false;   // so one death counts once, not every dead tick

    /** Emergency /home when about to die or hit hard. Fires regardless of the bot.
     *  Reacts to the HAZARD (lava / fire / suffocation), not just to HP already being low —
     *  4 HP/tick lava and 1 HP/tick suffocation never trip a "big hit", and waiting for HP≤8
     *  is too late when a server's /home warmup gets cancelled by the ongoing damage. */
    private void safety(Minecraft mc) {
        if (homeCd > 0) {
            homeCd--;
        }
        if (mc.player == null || mc.level == null) {
            prevHp = Float.NaN;
            suffTicks = 0;
            return;
        }
        float hp = mc.player.getHealth();

        // --- death telemetry: count each death exactly once -------------
        if (!mc.player.isAlive()) {
            if (!deathLatched) {
                com.otto.cyclea.feature.CycleaTelemetry.get().died();
                deathLatched = true;
            }
        } else {
            deathLatched = false;
        }

        // --- proactive hazard escape ------------------------------------
        boolean inLava = mc.player.isInLava();
        boolean onFire = mc.player.getRemainingFireTicks() > 0;
        BlockPos eye = BlockPos.containing(mc.player.getEyePosition());
        boolean headStuck = mc.level.getBlockState(eye).isSuffocating(mc.level, eye);
        suffTicks = headStuck ? suffTicks + 1 : 0;

        if (inLava) {
            // LAVA = drop everything and get out THIS instant. Swim up every tick, and re-issue
            // /home repeatedly — a single /home is useless when the server's teleport warmup gets
            // cancelled by the burn damage, so we keep re-issuing it (~every 12 ticks) in the hope
            // one lands, while the jump physically lifts us toward the surface as the real lifeline.
            mc.player.setJumping(true);
            if (CycleaConfig.get().escapeHome && mc.player.isAlive()) {
                if (homeCd == 0) {
                    fireHome(mc, "IN LAVA");
                } else if (homeCd % 12 == 0) {
                    mc.player.connection.sendCommand(
                        CycleaConfig.get().homeCommand.replaceFirst("^/", ""));
                }
            }
            prevHp = hp;
            return;   // nothing else matters while we're burning
        }

        if (CycleaConfig.get().escapeHome && homeCd == 0 && mc.player.isAlive()) {
            if (suffTicks >= 2) {
                fireHome(mc, "suffocating");
            } else if (onFire && hp <= 14f) {
                fireHome(mc, "on fire, HP " + (int) hp);
            } else if (hp <= 10f) {
                fireHome(mc, "low HP " + (int) hp);
            } else if (!Float.isNaN(prevHp) && prevHp - hp >= 5f) {
                fireHome(mc, "big hit −" + (int) (prevHp - hp));
            }
        }
        prevHp = hp;
    }

    /** Run the escape command with a loud alarm + banner, and stand the bot down. */
    private void fireHome(Minecraft mc, String reason) {
        com.otto.cyclea.feature.CycleaTelemetry.get().escaped(reason);
        homeCd = 200;   // ~10s guard so it fires once, not every tick
        String cmd = CycleaConfig.get().homeCommand.replaceFirst("^/", "");
        mc.player.connection.sendCommand(cmd);
        CycleaState.get().flashAlert("!! ESCAPING — /" + CycleaConfig.get().homeCommand + " !!",
            0xFFFF2020, 6000);
        for (float pitch : new float[]{0.4f, 0.7f, 1.1f}) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, pitch);   // alarm chord
        }
        say(mc, "§4§l⚠ EMERGENCY (" + reason + ") — running §f/" + CycleaConfig.get().homeCommand);
        if (Autopilot.get().isActive()) {
            Autopilot.get().stop(mc, "§cemergency /home — " + reason);
        }
    }

    /** Warn (chat + sound) when another player is within range — underground early warning. */
    private void watchman(Minecraft mc) {
        if (watchAlertCd > 0) {
            watchAlertCd--;
        }
        if (!CycleaConfig.get().watchman || mc.level == null || mc.player == null) {
            return;
        }
        if (++watchCounter < 20) {
            return;
        }
        watchCounter = 0;
        final double range = 96.0;
        net.minecraft.world.entity.player.Player nearest = null;
        double best = range;
        for (net.minecraft.world.entity.player.Player pl : mc.level.players()) {
            if (pl == mc.player || !pl.isAlive()) {
                continue;
            }
            double d = pl.distanceTo(mc.player);
            if (d < best) {
                best = d;
                nearest = pl;
            }
        }
        // player got close — bail out via /home if escape is on
        if (nearest != null && best <= 48 && CycleaConfig.get().escapeHome && homeCd == 0) {
            fireHome(mc, nearest.getName().getString() + " " + (int) best + "m");
            return;
        }
        if (nearest != null && watchAlertCd == 0) {
            BlockPos me = mc.player.blockPosition();
            BlockPos them = nearest.blockPosition();
            String dir = compass(them.getX() - me.getX(), them.getZ() - me.getZ());
            CycleaState.get().flashAlert("⚠ PLAYER NEARBY ⚠", 0xFFFF4040, 5000);
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 0.5f);
            say(mc, "§c⚠ PLAYER NEARBY: §f" + nearest.getName().getString()
                + " §7" + (int) best + "m " + dir + " §8(y" + them.getY() + ") — press §fG§8 to seal in");
            watchAlertCd = 100;   // ~5s between warnings
        }
    }

    private static String compass(int dx, int dz) {
        String v = dz < -4 ? "N" : dz > 4 ? "S" : "";
        String h = dx > 4 ? "E" : dx < -4 ? "W" : "";
        String s = v + h;
        return s.isEmpty() ? "here" : s;
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
        while (freeCursorKey.consumeClick()) {
            // OWN-MOUSE MODE: free the physical cursor for your other monitors/apps, but keep MC's
            // internal grab flag ON so mining never stops. Unlike ESC this opens no screen, so
            // singleplayer never pauses; unlike a plain releaseMouse() the game keeps breaking
            // blocks (it refuses to when it thinks it's ungrabbed). Toggle again to recapture.
            boolean on = Autopilot.get().toggleOwnMouse(mc);
            if (on) {
                say(mc, "§b🖱 cursor freed §7— use other screens; it keeps mining. §8[U] again to recapture");
            } else {
                say(mc, "§b🖱 cursor recaptured");
            }
        }
        while (searchModeKey.consumeClick()) {
            String m = Autopilot.get().toggleSearchMode(mc);
            say(mc, "§6[Autopilot] §7search mode → §f" + m
                + ("SWEEP".equals(m) ? " §7(spiral-search this area)" : " §7(beeline to spawn)"));
        }
        while (modeKey.consumeClick()) {
            Autopilot.get().toggleMode(mc);   // Miner ⇄ Surface scout
        }
        while (panicKey.consumeClick()) {
            Autopilot.get().panicSeal(mc);    // wall into a 1×1 safe-hole
        }
        while (vaultKey.consumeClick()) {
            Autopilot.get().buildVault(mc);   // hollow a room + sort loot into shulkers
        }
        while (clearLogKey.consumeClick()) {
            int n = st.clearFounds();         // wipe the top-right base log
            say(mc, "§7Base log cleared (" + n + ")");
        }

        // Emergency escape (about to die / big hit) + Watchman (player near, may also escape)
        safety(mc);
        watchman(mc);

        // drive the bot every tick (it self-guards and no-ops when off)
        Autopilot.get().tick(mc);

        // ore X-ray: whenever search/auto is on, plot selected ores on the radar (through walls)
        if ((st.isActive() || Autopilot.get().isActive()) && mc.level != null) {
            if (++oreScanCounter >= 30) {   // lighter cadence + smaller cube = less CPU/lag
                oreScanCounter = 0;
                st.setOreBlips(TargetScanner.scanOres(mc, 12));
                // In the End you're flying, not tunnelling — reach out to the whole
                // loaded area so End-city chests/shulkers ping from the air, not just
                // the 32-block bubble that suits underground mining.
                boolean end = mc.level.dimension() == net.minecraft.world.level.Level.END;
                st.setContainerBlips(TargetScanner.scanContainers(mc, end ? 160 : 32));
            }
        } else {
            st.setOreBlips(java.util.List.of());
            st.setContainerBlips(java.util.List.of());
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
        lastBases = scan.bases();

        BlockPos me = mc.player.blockPosition();

        List<BlockPos> baseCenters = new ArrayList<>();
        int richest = 0;
        for (TargetScanner.Base b : scan.bases()) {
            baseCenters.add(b.center());
            richest = Math.max(richest, b.total());
            int col = switch (b.status()) {
                case "LOADED" -> 0xFF5050;
                case "partial" -> 0xFFC020;
                default -> 0x9AA0AB;
            };
            st.addFound(b.center().getX(), b.center().getY(), b.center().getZ(),
                b.chests(), b.shulkers(), col);
        }

        int fresh = st.recordBases(baseCenters);
        if (fresh > 0) {
            st.flashAlert("★ BASE FOUND ★", 0xFF55FF66, 4000);
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
