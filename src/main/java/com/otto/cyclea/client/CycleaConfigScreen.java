package com.otto.cyclea.client;

import com.otto.cyclea.CycleaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Cyclea's settings screen. Button-only so it renders cleanly on 26.2's new GUI
 * pipeline. The settings list now scrolls with the mouse wheel between a fixed
 * header and a fixed Done button, so it never runs off the screen. Open with J.
 */
public class CycleaConfigScreen extends Screen {

    private final List<Button> rows = new ArrayList<>();
    private int scroll = 0;
    private int viewTop;
    private int viewBottom;
    private int rowX;
    private static final int ROW_W = 240;
    private static final int ROW_H = 24;
    private static final int BTN_H = 20;

    public CycleaConfigScreen() {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Cyclea Config"));
    }

    @Override
    protected void init() {
        CycleaConfig c = CycleaConfig.get();
        rows.clear();
        rowX = this.width / 2 - ROW_W / 2;
        viewTop = 34;
        int doneY = this.height - 28;
        viewBottom = doneY - 8;

        // fixed header (non-interactive label)
        Button header = Button.builder(Component.literal("Cyclea Config  —  scroll for more"), b -> { })
            .bounds(rowX, 8, ROW_W, BTN_H).build();
        header.active = false;
        addRenderableWidget(header);

        // scrolling settings rows
        addRow(presetMsg(), b -> {
            c.presetIdx = (c.presetIdx + 1) % CycleaConfig.PRESETS.length;
            c.applyPreset(c.presetIdx);
            this.rebuildWidgets();   // refresh all labels; scroll is preserved (field)
        });
        addRow(modeMsg(), b -> { c.cycleMode(); b.setMessage(modeMsg()); });
        addRow(stashMsg(), b -> { c.cycleStashLevel(); b.setMessage(stashMsg()); });
        addRow(oreEspMsg(), b -> { c.cycleOreEsp(); b.setMessage(oreEspMsg()); });
        addRow(tracersMsg(), b -> { c.cycleTracers(); b.setMessage(tracersMsg()); });
        addRow(watchmanMsg(), b -> { c.cycleWatchman(); b.setMessage(watchmanMsg()); });
        addRow(stashPauseMsg(), b -> { c.cycleStashPause(); b.setMessage(stashPauseMsg()); });
        addRow(turnMsg(), b -> { c.cycleTurn(); b.setMessage(turnMsg()); });
        addRow(glanceMsg(), b -> { c.cycleGlance(); b.setMessage(glanceMsg()); });
        addRow(sweepMsg(), b -> { c.cycleSweepStep(); b.setMessage(sweepMsg()); });
        addRow(oreMsg(), b -> { c.cycleOreSeek(); b.setMessage(oreMsg()); });
        addRow(oneByOneMsg(), b -> { c.cycleOneByOne(); b.setMessage(oneByOneMsg()); });
        addRow(paceMsg(), b -> { c.cyclePace(); b.setMessage(paceMsg()); });
        addRow(skipBasesMsg(), b -> { c.cycleSkipBases(); b.setMessage(skipBasesMsg()); });
        addRow(autoSellMsg(), b -> { c.cycleAutoSell(); b.setMessage(autoSellMsg()); });
        addRow(skipRaidedMsg(), b -> { c.cycleSkipRaided(); b.setMessage(skipRaidedMsg()); });
        addRow(Component.literal("Clear base waypoints"), b -> {
            int n = com.otto.cyclea.feature.MinimapBridge.clearBaseWaypoints();
            b.setMessage(Component.literal("Cleared " + n + " base waypoint" + (n == 1 ? "" : "s")));
        });

        // fixed Done
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(rowX, doneY, ROW_W, BTN_H).build());

        scroll = Mth.clamp(scroll, 0, maxScroll());
        relayout();
    }

    private void addRow(Component msg, Button.OnPress onPress) {
        Button btn = Button.builder(msg, onPress).bounds(rowX, viewTop, ROW_W, BTN_H).build();
        rows.add(btn);
        addRenderableWidget(btn);
    }

    private int maxScroll() {
        return Math.max(0, rows.size() * ROW_H - (viewBottom - viewTop));
    }

    /** Position each row by the scroll offset; hide any that fall outside the viewport. */
    private void relayout() {
        for (int i = 0; i < rows.size(); i++) {
            Button b = rows.get(i);
            int y = viewTop - scroll + i * ROW_H;
            b.setY(y);
            boolean vis = y >= viewTop - 1 && y + BTN_H <= viewBottom + 1;
            b.visible = vis;
            b.active = vis;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - (int) Math.round(scrollY) * ROW_H, 0, maxScroll());
        relayout();
        return true;
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

    private Component oreEspMsg() {
        return Component.literal("Ore X-ray overlay: " + CycleaConfig.get().oreEspLabel());
    }

    private Component tracersMsg() {
        return Component.literal("Tracer lines: " + CycleaConfig.get().tracersLabel());
    }

    private Component watchmanMsg() {
        return Component.literal("Watchman [G=seal]: " + CycleaConfig.get().watchmanLabel());
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

    private Component autoSellMsg() {
        return Component.literal("Auto-sell when full: " + CycleaConfig.get().autoSellLabel());
    }

    private Component skipRaidedMsg() {
        return Component.literal("Skip raided bases: " + CycleaConfig.get().skipRaidedLabel());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
