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
        // see-through ore ESP draws whenever ores are being scanned (search OR auto on),
        // independent of the base-finder panel below
        if (mc.player != null && CycleaConfig.get().oreEsp) {
            drawOreEsp(g, mc, s);
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

    /**
     * See-through ore overlay: project each nearby selected ore's world position onto the
     * screen (manual camera projection in GUI space) and draw a colored box outline there —
     * an ESP that shows ores through walls, without needing a world-render hook.
     */
    private void drawOreEsp(GuiGraphicsExtractor g, Minecraft mc, CycleaState s) {
        List<int[]> ores = s.getOreBlips();
        if (ores.isEmpty()) {
            return;
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (cam == null || !cam.isInitialized()) {
            return;
        }
        Vec3 cp = cam.position();
        double yaw = Math.toRadians(cam.yRot());
        double pitch = Math.toRadians(cam.xRot());
        double cyaw = Math.cos(yaw);
        double syaw = Math.sin(yaw);
        double cpit = Math.cos(pitch);
        double spit = Math.sin(pitch);
        // forward, right, up basis (Minecraft convention)
        double fX = -syaw * cpit;
        double fY = -spit;
        double fZ = cyaw * cpit;
        double rLen = Math.sqrt(fZ * fZ + fX * fX);
        if (rLen < 1e-4) {
            return;
        }
        double rX = -fZ / rLen;
        double rZ = fX / rLen;
        double uX = -rZ * fY;
        double uY = rZ * fX - rX * fZ;
        double uZ = rX * fY;

        int gw = g.guiWidth();
        int gh = g.guiHeight();
        double fov = Math.toRadians(Math.max(30, cam.getFov()));
        double f = (gh / 2.0) / Math.tan(fov / 2.0);

        int drawn = 0;
        for (int[] o : ores) {
            double relX = o[0] + 0.5 - cp.x;
            double relY = o[1] + 0.5 - cp.y;
            double relZ = o[2] + 0.5 - cp.z;
            double camFwd = relX * fX + relY * fY + relZ * fZ;
            if (camFwd < 0.4) {
                continue;   // behind us
            }
            double camRight = relX * rX + relZ * rZ;
            double camUp = relX * uX + relY * uY + relZ * uZ;
            int sx = (int) Math.round(gw / 2.0 + (camRight / camFwd) * f);
            int sy = (int) Math.round(gh / 2.0 - (camUp / camFwd) * f);
            if (sx < -20 || sx > gw + 20 || sy < -20 || sy > gh + 20) {
                continue;
            }
            int half = (int) Math.max(2, Math.min(26, (0.85 / camFwd) * f));
            int col = 0xFF000000 | o[3];
            // box outline (4 thin edges)
            g.fill(sx - half, sy - half, sx + half, sy - half + 1, col);   // top
            g.fill(sx - half, sy + half - 1, sx + half, sy + half, col);   // bottom
            g.fill(sx - half, sy - half, sx - half + 1, sy + half, col);   // left
            g.fill(sx + half - 1, sy - half, sx + half, sy + half, col);   // right
            if (++drawn >= 120) {
                break;
            }
        }
    }

    private static String proximity(CycleaState s) {
        if (s.getNearestDist() < 0) {
            return "—";
        }
        return s.getNearestDist() + "m";
    }
}
