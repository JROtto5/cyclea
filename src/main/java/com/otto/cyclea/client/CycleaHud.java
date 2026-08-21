package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The on-screen radar panel (top-left). Always-on while Cyclea is active: shows
 * live base / chest / shulker tallies, the current target, and the nearest hit.
 * Uses 26.2's HudElement render-state hook and draws through GuiGraphicsExtractor.
 */
public class CycleaHud implements HudElement {

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        CycleaState state = CycleaState.get();
        if (!state.isActive() || mc.player == null) {
            return;
        }
        Font font = mc.font;
        int x = 4;
        int y = 4;

        g.fill(x - 3, y - 3, x + 210, y + 58, 0xAA000000);
        g.text(font, "CYCLEA ▶ base radar", x, y, 0xFF55FFFF, true);
        g.text(font,
            "Bases: " + state.getBaseCount()
                + "   Chests≤20: " + state.getChestCount()
                + "   Shulkers: " + state.getShulkerCount(),
            x, y + 12, 0xFFFFFFFF, true);
        g.text(font, "Target: " + state.getTarget().label,
            x, y + 24, 0xFF000000 | state.getTarget().color, true);
        String nearest = state.getNearestLine();
        g.text(font, nearest.isEmpty() ? "scanning…" : nearest, x, y + 36, 0xFFCFCFCF, true);
        g.text(font, "[ toggle    ] cycle target", x, y + 48, 0xFF808080, true);
    }
}
