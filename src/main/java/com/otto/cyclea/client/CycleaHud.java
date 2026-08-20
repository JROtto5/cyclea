package com.otto.cyclea.client;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A tiny always-on panel in the top-left corner that doubles as the "button"
 * surface: it shows whether Cyclea is on, the current target, and how many were
 * found. Clicking is handled by the keybind; this is the readout beside it.
 */
public final class CycleaHud {

    private CycleaHud() {
    }

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) {
            return;
        }
        CycleaState state = CycleaState.get();

        int x = 6;
        int y = 6;
        int color = state.isActive() ? state.getTarget().color : 0x808080;

        String head = state.isActive() ? "Cyclea ▶ ON" : "Cyclea ■ off";
        g.drawString(mc.font, Component.literal(head), x, y, 0xFFFFFF, true);
        g.drawString(mc.font, Component.literal("Target: " + state.getTarget().label),
            x, y + 12, color | 0xFF000000, true);
        if (state.isActive()) {
            g.drawString(mc.font, Component.literal("Found: " + state.getFound().size()
                + "   (radius " + state.getRadius() + ")"), x, y + 24, 0xC0C0C0, true);
            g.drawString(mc.font, Component.literal("[G] toggle   [B] cycle target"),
                x, y + 36, 0x707070, true);
        }
    }
}
