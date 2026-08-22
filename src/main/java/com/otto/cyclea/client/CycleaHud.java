package com.otto.cyclea.client;

import com.otto.cyclea.CycleaConfig;
import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.Autopilot;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The on-screen radar panel (top-left): live tallies, the active target and
 * deep-Y cutoff, the nearest hit with heading / vertical / turn hint, richest
 * base and session totals, an entity radar, and a little blip minimap.
 * Backslash toggles a compact version.
 */
public class CycleaHud implements HudElement {

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        CycleaState s = CycleaState.get();
        // see-through overlay + big alerts draw independent of the base-finder panel
        if (mc.player != null) {
            if (CycleaConfig.get().oreEsp || CycleaConfig.get().tracers) {
                renderWorldOverlay(g, mc, s);
            }
            renderBaseLog(g, mc, s);
            renderBigAlert(g, mc, s);
        }
        if (!s.isActive() || mc.player == null) {
            return;
        }
        Font font = mc.font;
        // Bottom-left, clear of a top-corner minimap.
        int x = 4;

        if (s.isCompact()) {
            int y = g.guiHeight() - 24;
            g.fill(x - 3, y - 3, x + 168, y + 22, 0xAA000000);
            g.text(font, "CYCLEA ▶ " + s.getTarget().label, x, y, 0xFF55FFFF, true);
            g.text(font, "B:" + s.getBaseCount() + "  C:" + s.getChestCount()
                + "  S:" + s.getShulkerCount() + "  " + proximity(s), x, y + 11, 0xFFFFFFFF, true);
            return;
        }

        int y = g.guiHeight() - 88;
        g.fill(x - 3, y - 3, x + 208, y + 84, 0xAA000000);

        g.text(font, "CYCLEA ▶ base finder", x, y, 0xFF55FFFF, true);
        g.text(font, "Chests≤20 " + s.getChestCount() + "   Shulkers " + s.getShulkerCount()
            + "   Bases " + s.getBaseCount(), x, y + 12, 0xFFFFFFFF, true);
        g.text(font, "Players " + s.getPlayerCount() + "   Hostiles " + s.getHostileCount()
            + "   Session " + s.getSessionBaseTotal(), x, y + 23, 0xFFB0B0B0, true);
        g.text(font, "Target: " + s.getTarget().label, x, y + 36,
            0xFF000000 | s.getTarget().color, true);

        int nearColor = s.getNearestDist() >= 0 && s.getNearestDist() < 16 ? 0xFFFF4040 : 0xFFE0E0E0;
        g.text(font, s.getNearestLine().isEmpty() ? "scanning…" : s.getNearestLine(),
            x, y + 48, nearColor, true);
        if (!s.getNearestLine().isEmpty()) {
            g.text(font, s.getBearing() + "   " + s.getVertical(), x, y + 60, 0xFFC0C0C0, true);
        }
        Autopilot ap = Autopilot.get();
        if (ap.isActive()) {
            long sec = ap.getSessionSeconds();
            g.text(font, "§6AUTO ▶ " + ap.getBlocksTraveled() + "m  " + (sec / 60) + "m"
                + (sec % 60) + "s   §7takeovers " + ap.getTakeovers(), x, y + 72, 0xFFFFC060, true);
            g.text(font, "§b⛏ " + ap.getOresMined() + " ores  §7(" + ap.getOresPerHour()
                + "/hr)" + (ap.getRestarts() > 0 ? "  §8self-fixed ×" + ap.getRestarts() : ""),
                x, y + 84, 0xFF80D0FF, true);
        } else if (ap.getTakeovers() > 0) {
            // assist metrics: how much you've had to step in
            g.text(font, "§7You: " + ap.getTakeovers() + " takeovers, "
                + ap.getHumanControlSeconds() + "s  §8[" + ap.topStopReasons() + "]",
                x, y + 72, 0xFFA0A0A0, true);
        } else {
            g.text(font, "[ on  ] cycle  \\ compact  O auto  J config", x, y + 72, 0xFF808080, true);
        }

        drawRadar(g, s, x + 168, y + 44, 28);
    }

    /** Little blip radar: player at center, targets as dots, north up. */
    private void drawRadar(GuiGraphicsExtractor g, CycleaState s, int cx, int cy, int r) {
        g.fill(cx - r, cy - r, cx + r, cy + r, 0x66101820);
        // cross-hairs
        g.fill(cx - 1, cy - r, cx + 1, cy + r, 0x33FFFFFF);
        g.fill(cx - r, cy - 1, cx + r, cy + 1, 0x33FFFFFF);
        int maxRange = Math.max(64, Minecraft.getInstance().options.renderDistance().get() * 16);
        double scale = (double) (r - 2) / maxRange;
        int color = 0xFF000000 | s.getTarget().color;
        List<int[]> blips = s.getBlips();
        for (int[] b : blips) {
            int px = cx + (int) Math.round(b[0] * scale);
            int py = cy + (int) Math.round(b[1] * scale);
            px = Math.max(cx - r, Math.min(cx + r - 2, px));
            py = Math.max(cy - r, Math.min(cy + r - 2, py));
            g.fill(px, py, px + 2, py + 2, color);
        }
        // ore X-ray: selected ores nearby, color-coded (top-down, through walls)
        Minecraft mc = Minecraft.getInstance();
        double px0 = mc.player.getX();
        double pz0 = mc.player.getZ();
        for (int[] o : s.getOreBlips()) {          // {x, y, z, rgb} absolute
            int px = cx + (int) Math.round((o[0] + 0.5 - px0) * scale);
            int py = cy + (int) Math.round((o[2] + 0.5 - pz0) * scale);
            px = Math.max(cx - r, Math.min(cx + r - 1, px));
            py = Math.max(cy - r, Math.min(cy + r - 1, py));
            g.fill(px, py, px + 1, py + 1, 0xFF000000 | o[3]);
        }
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF); // player
    }

    /** Camera→screen projector in GUI space (no world-render hook needed). */
    private static final class Proj {
        final double cx;
        final double cy;
        final double cz;
        final double fX;
        final double fY;
        final double fZ;
        final double rX;
        final double rZ;
        final double uX;
        final double uY;
        final double uZ;
        final double f;
        final int gw;
        final int gh;
        boolean ok = true;

        Proj(Camera cam, int gw, int gh) {
            Vec3 cp = cam.position();
            cx = cp.x;
            cy = cp.y;
            cz = cp.z;
            double yaw = Math.toRadians(cam.yRot());
            double pitch = Math.toRadians(cam.xRot());
            fX = -Math.sin(yaw) * Math.cos(pitch);
            fY = -Math.sin(pitch);
            fZ = Math.cos(yaw) * Math.cos(pitch);
            double rl = Math.sqrt(fZ * fZ + fX * fX);
            ok = rl > 1e-4;
            rX = ok ? -fZ / rl : 0;
            rZ = ok ? fX / rl : 0;
            uX = -rZ * fY;
            uY = rZ * fX - rX * fZ;
            uZ = rX * fY;
            this.gw = gw;
            this.gh = gh;
            this.f = (gh / 2.0) / Math.tan(Math.toRadians(Math.max(30, cam.getFov())) / 2.0);
        }

        /** {sx, sy} in GUI space, or null if behind the camera. */
        int[] to(double x, double y, double z) {
            double dx = x - cx;
            double dy = y - cy;
            double dz = z - cz;
            double fwd = dx * fX + dy * fY + dz * fZ;
            if (fwd < 0.4) {
                return null;
            }
            double right = dx * rX + dz * rZ;
            double up = dx * uX + dy * uY + dz * uZ;
            return new int[]{
                (int) Math.round(gw / 2.0 + (right / fwd) * f),
                (int) Math.round(gh / 2.0 - (up / fwd) * f),
                (int) fwd};
        }
    }

    /**
     * See-through overlay: ore boxes, tracer lines to ores/chests, and the travel route —
     * all projected onto the screen so they read like X-ray/ESP without a world-render hook.
     */
    static void renderWorldOverlay(GuiGraphicsExtractor g, Minecraft mc, CycleaState s) {
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null || !cam.isInitialized()) {
            return;
        }
        Proj p = new Proj(cam, g.guiWidth(), g.guiHeight());
        if (!p.ok) {
            return;
        }
        boolean boxes = CycleaConfig.get().oreEsp;
        boolean tracers = CycleaConfig.get().tracers;
        int ox = p.gw / 2;
        int oy = p.gh - 2;   // tracers fan out from the bottom-center

        // ore boxes (+ optional tracer to each)
        int drawn = 0;
        for (int[] o : s.getOreBlips()) {
            int[] sp = p.to(o[0] + 0.5, o[1] + 0.5, o[2] + 0.5);
            if (sp == null || sp[0] < -20 || sp[0] > p.gw + 20 || sp[1] < -20 || sp[1] > p.gh + 20) {
                continue;
            }
            int col = 0xFF000000 | o[3];
            if (boxes) {
                int half = Math.max(2, Math.min(26, (int) (0.85 / Math.max(1, sp[2]) * p.f)));
                g.fill(sp[0] - half, sp[1] - half, sp[0] + half, sp[1] - half + 1, col);
                g.fill(sp[0] - half, sp[1] + half - 1, sp[0] + half, sp[1] + half, col);
                g.fill(sp[0] - half, sp[1] - half, sp[0] - half + 1, sp[1] + half, col);
                g.fill(sp[0] + half - 1, sp[1] - half, sp[0] + half, sp[1] + half, col);
            }
            if (tracers && drawn < 10) {
                line(g, ox, oy, sp[0], sp[1], (col & 0x00FFFFFF) | 0x77000000);
            }
            if (++drawn >= 120) {
                break;
            }
        }

        // chest/shulker tracers + a small marker
        if (tracers) {
            int c = 0;
            for (int[] b : s.getContainerBlips()) {
                int[] sp = p.to(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                if (sp == null) {
                    continue;
                }
                int col = 0xFF000000 | b[3];
                g.fill(sp[0] - 2, sp[1] - 2, sp[0] + 2, sp[1] + 2, col);
                if (c < 8) {
                    line(g, ox, oy, sp[0], sp[1], (col & 0x00FFFFFF) | 0x66000000);
                }
                if (++c >= 40) {
                    break;
                }
            }
        }

        // travel route: a green polyline laid ON THE FLOOR, tracing the tunnels the path
        // runs through (and rising with it up stairs). Starts at the player's feet.
        if (tracers && mc.player != null) {
            List<net.minecraft.core.BlockPos> path = Autopilot.get().getPath();
            int[] start = p.to(mc.player.getX(), mc.player.getY() + 0.1, mc.player.getZ());
            int prevX = start != null ? start[0] : ox;
            int prevY = start != null ? start[1] : oy;
            boolean any = false;
            for (net.minecraft.core.BlockPos n : path) {
                int[] sp = p.to(n.getX() + 0.5, n.getY() + 0.1, n.getZ() + 0.5);   // floor level
                if (sp == null) {
                    continue;
                }
                line(g, prevX, prevY, sp[0], sp[1], 0xBB33FF66);
                g.fill(sp[0] - 1, sp[1] - 1, sp[0] + 2, sp[1] + 2, 0xCC33FF66);   // node dot
                prevX = sp[0];
                prevY = sp[1];
                any = true;
            }
            if (!any && Autopilot.get().isActive()) {
                // no A* path (just sweeping) — lay a floor line to the current heading
                int[] sp = p.to(Autopilot.get().getTargetX() + 0.5, mc.player.getY() + 0.1,
                    Autopilot.get().getTargetZ() + 0.5);
                if (sp != null && start != null) {
                    line(g, start[0], start[1], sp[0], sp[1], 0xBB33FF66);
                }
            }
        }
    }

    /** Cheap 2D line via stepped 1-px fills (clipped to the screen). */
    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps <= 0 || steps > 2000) {
            return;
        }
        for (int i = 0; i <= steps; i += 2) {   // every 2px keeps it light
            int px = x1 + (x2 - x1) * i / steps;
            int py = y1 + (y2 - y1) * i / steps;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** Top-right base log: recent finds with coords, colored by quality (red = worth
     *  going back to). Drawn over the world and over open menus. */
    static void renderBaseLog(GuiGraphicsExtractor g, Minecraft mc, CycleaState s) {
        List<CycleaState.Found> founds = s.getFounds();
        if (founds.isEmpty()) {
            return;
        }
        Font f = mc.font;
        int right = g.guiWidth() - 5;
        int y = 5;
        String title = "BASES ↩ go back to  [C clear]";
        g.fill(right - f.width(title) - 10, 2, right + 2, 4 + 12 + founds.size() * 11, 0x88000000);
        g.text(f, title, right - f.width(title), y, 0xFFFFD24A, true);
        y += 13;
        for (CycleaState.Found fo : founds) {
            String line = fo.x() + ", " + fo.y() + ", " + fo.z()
                + "  " + fo.chests() + "c " + fo.shulkers() + "s";
            int tw = f.width(line);
            int col = 0xFF000000 | fo.color();
            g.fill(right - tw - 8, y + 1, right - tw - 4, y + 6, col);   // quality dot
            g.text(f, line, right - tw, y, col, true);
            y += 11;
        }
    }

    /** A big, centered, pulsing on-screen banner for finds — draws over the world AND
     *  over any open menu (called from both the HUD and the screen-render hook). */
    static void renderBigAlert(GuiGraphicsExtractor g, Minecraft mc, CycleaState s) {
        long left = s.getAlertUntilMs() - System.currentTimeMillis();
        if (left <= 0 || s.getAlertText().isEmpty()) {
            return;
        }
        String msg = s.getAlertText();
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int cx = gw / 2;
        int cy = gh / 3;
        // pulse the opacity so it grabs the eye
        double pulse = 0.55 + 0.45 * Math.abs(Math.sin(left / 180.0));
        int alpha = (int) (pulse * 255) & 0xFF;
        int col = (alpha << 24) | (s.getAlertColor() & 0x00FFFFFF);
        Font font = mc.font;
        int tw = font.width(msg);
        // translucent backing bar
        g.fill(cx - tw - 18, cy - 16, cx + tw + 18, cy + 20, (Math.min(alpha, 160) << 24));
        g.fill(cx - tw - 18, cy - 16, cx + tw + 18, cy - 14, col);   // top edge
        g.fill(cx - tw - 18, cy + 18, cx + tw + 18, cy + 20, col);   // bottom edge
        // BIG text: scale the GUI matrix around the center point
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(2.0f, 2.0f);
        g.text(font, msg, -tw / 2, -4, col, true);
        pose.popMatrix();
    }

    private static String proximity(CycleaState s) {
        if (s.getNearestDist() < 0) {
            return "—";
        }
        return s.getNearestDist() + "m";
    }
}
