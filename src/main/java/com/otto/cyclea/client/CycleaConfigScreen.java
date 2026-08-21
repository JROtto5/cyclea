package com.otto.cyclea.client;

import com.otto.cyclea.CycleaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cyclea's settings screen. Button-only so it renders cleanly on 26.2's new GUI
 * pipeline — each button shows a setting and cycles it on click, saving to disk.
 * Open it with the config key (default J).
 */
public class CycleaConfigScreen extends Screen {

    public CycleaConfigScreen() {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Cyclea Config"));
    }

    @Override
    protected void init() {
        CycleaConfig c = CycleaConfig.get();
        int w = 240;
        int h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 4;

        addRenderableWidget(Button.builder(turnMsg(), b -> {
            c.cycleTurn();
            b.setMessage(turnMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(glanceMsg(), b -> {
            c.cycleGlance();
            b.setMessage(glanceMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(sweepMsg(), b -> {
            c.cycleSweepStep();
            b.setMessage(sweepMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(oreMsg(), b -> {
            c.cycleOreSeek();
            b.setMessage(oreMsg());
        }).bounds(x, y, w, h).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(x, y, w, h).build());
    }

    private Component turnMsg() {
        return Component.literal("Camera turn speed: " + CycleaConfig.get().turnLabel());
    }

    private Component glanceMsg() {
        return Component.literal("Look-around: " + CycleaConfig.get().glanceLabel());
    }

    private Component sweepMsg() {
        return Component.literal("Sweep spacing: " + CycleaConfig.get().sweepStep + " blocks");
    }

    private Component oreMsg() {
        return Component.literal("Detour for ores: " + CycleaConfig.get().oreSeekLabel());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
