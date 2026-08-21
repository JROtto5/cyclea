package com.otto.cyclea;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tunable settings, persisted to config/cyclea.properties. Exposed in the config
 * screen and read live by the autopilot / scanner so you can dial the feel
 * (camera speed, how much it looks around, sweep spacing) without recompiling.
 */
public final class CycleaConfig {
    private static final CycleaConfig INSTANCE = new CycleaConfig();

    public static CycleaConfig get() {
        return INSTANCE;
    }

    // 0 = slow, 1 = medium, 2 = fast
    public int turnLevel = 1;
    // 0 = off, 1 = subtle, 2 = active
    public int glanceLevel = 1;
    // blocks per sweep-spiral leg unit
    public int sweepStep = 48;
    // chest detection depth cap
    public int chestMaxY = 20;
    // 0 = off, 1 = diamonds+debris, 2 = + emerald/gold, 3 = all ores
    public int oreSeekLevel = 1;

    /** Should the autopilot detour to dig this ore (by block path)? */
    public boolean wantsOre(String path) {
        if (oreSeekLevel == 0) {
            return false;
        }
        boolean ore = path.endsWith("_ore") || path.equals("ancient_debris");
        if (!ore) {
            return false;
        }
        if (oreSeekLevel >= 3) {
            return true;
        }
        boolean diamond = path.contains("diamond") || path.equals("ancient_debris");
        if (oreSeekLevel == 1) {
            return diamond;
        }
        return diamond || path.contains("emerald") || path.contains("gold");
    }

    public String oreSeekLabel() {
        return switch (oreSeekLevel) {
            case 0 -> "Off";
            case 2 -> "Rare (diamond/emerald/gold)";
            case 3 -> "All ores";
            default -> "Diamonds + debris";
        };
    }

    public void cycleOreSeek() {
        oreSeekLevel = (oreSeekLevel + 1) % 4;
        save();
    }

    private CycleaConfig() {
    }

    public float turnEase() {
        return switch (turnLevel) {
            case 0 -> 0.12f;
            case 2 -> 0.28f;
            default -> 0.18f;
        };
    }

    public float turnMax() {
        return switch (turnLevel) {
            case 0 -> 5f;
            case 2 -> 12f;
            default -> 7.5f;
        };
    }

    public float glanceYawAmp() {
        return switch (glanceLevel) {
            case 0 -> 0f;
            case 2 -> 60f;
            default -> 34f;
        };
    }

    public float glancePitchAmp() {
        return switch (glanceLevel) {
            case 0 -> 0f;
            case 2 -> 34f;
            default -> 22f;
        };
    }

    public String turnLabel() {
        return switch (turnLevel) {
            case 0 -> "Slow";
            case 2 -> "Fast";
            default -> "Medium";
        };
    }

    public String glanceLabel() {
        return switch (glanceLevel) {
            case 0 -> "Off";
            case 2 -> "Active";
            default -> "Subtle";
        };
    }

    public void cycleTurn() {
        turnLevel = (turnLevel + 1) % 3;
        save();
    }

    public void cycleGlance() {
        glanceLevel = (glanceLevel + 1) % 3;
        save();
    }

    public void cycleSweepStep() {
        sweepStep = switch (sweepStep) {
            case 32 -> 48;
            case 48 -> 64;
            case 64 -> 96;
            default -> 32;
        };
        save();
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("cyclea.properties");
    }

    public void load() {
        Properties p = new Properties();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
                turnLevel = Integer.parseInt(p.getProperty("turnLevel", "1"));
                glanceLevel = Integer.parseInt(p.getProperty("glanceLevel", "1"));
                sweepStep = Integer.parseInt(p.getProperty("sweepStep", "48"));
                chestMaxY = Integer.parseInt(p.getProperty("chestMaxY", "20"));
                oreSeekLevel = Integer.parseInt(p.getProperty("oreSeekLevel", "1"));
            }
        } catch (Exception ignored) {
            // defaults are fine
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("turnLevel", Integer.toString(turnLevel));
        p.setProperty("glanceLevel", Integer.toString(glanceLevel));
        p.setProperty("sweepStep", Integer.toString(sweepStep));
        p.setProperty("chestMaxY", Integer.toString(chestMaxY));
        p.setProperty("oreSeekLevel", Integer.toString(oreSeekLevel));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Cyclea config");
        } catch (IOException ignored) {
            // non-fatal
        }
    }
}
