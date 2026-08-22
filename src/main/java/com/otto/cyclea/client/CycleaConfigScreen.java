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
        int y = this.height / 8;

        addRenderableWidget(Button.builder(presetMsg(), b -> {
            c.presetIdx = (c.presetIdx + 1) % CycleaConfig.PRESETS.length;
            c.applyPreset(c.presetIdx);
            this.rebuildWidgets();   // refresh every button to the new values
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(modeMsg(), b -> {
            c.cycleMode();
            b.setMessage(modeMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(stashMsg(), b -> {
            c.cycleStashLevel();
            b.setMessage(stashMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(stashPauseMsg(), b -> {
            c.cycleStashPause();
            b.setMessage(stashPauseMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

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
        y += 26;

        addRenderableWidget(Button.builder(oneByOneMsg(), b -> {
            c.cycleOneByOne();
            b.setMessage(oneByOneMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(paceMsg(), b -> {
            c.cyclePace();
            b.setMessage(paceMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(skipBasesMsg(), b -> {
            c.cycleSkipBases();
            b.setMessage(skipBasesMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(skipRaidedMsg(), b -> {
            c.cycleSkipRaided();
            b.setMessage(skipRaidedMsg());
        }).bounds(x, y, w, h).build());
        y += 26;

        addRenderableWidget(Button.builder(Component.literal("Clear base waypoints"), b -> {
            int n = com.otto.cyclea.feature.MinimapBridge.clearBaseWaypoints();
            b.setMessage(Component.literal("Cleared " + n + " base waypoint" + (n == 1 ? "" : "s")));
        }).bounds(x, y, w, h).build());
        y += 40;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(x, y, w, h).build());
    }

    private Component presetMsg() {
        return Component.literal("◆ Apply preset: " + CycleaConfig.get().presetLabel());
    }

    private Component modeMsg() {
        return Component.literal("Mode [M]: " + CycleaConfig.get().modeLabel());
    }

    private Component stashMsg() {
        return Component.literal("Stash alert size: " + CycleaConfig.get().stashLabel());
    }

    private Component stashPauseMsg() {
        return Component.literal("Pause at stash: " + CycleaConfig.get().stashPauseLabel());
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

    private Component oneByOneMsg() {
        return Component.literal("1×1 trapdoor lane: " + CycleaConfig.get().oneByOneLabel());
    }

    private Component paceMsg() {
        return Component.literal("Speed / server pace: " + CycleaConfig.get().paceLabel());
    }

    private Component skipBasesMsg() {
        return Component.literal("Skip all bases: " + CycleaConfig.get().skipBasesLabel());
    }

    private Component skipRaidedMsg() {
        return Component.literal("Skip raided bases: " + CycleaConfig.get().skipRaidedLabel());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
