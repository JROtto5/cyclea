package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import com.otto.cyclea.feature.Autopilot;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF); // player
    }

    private static String proximity(CycleaState s) {
        if (s.getNearestDist() < 0) {
            return "—";
        }
        return s.getNearestDist() + "m";
    }
}
