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
    // place a trapdoor ceiling in the tunnel (1×1 sprint-lane trick); needs trapdoors in the hotbar
    public boolean oneByOne = false;
    // 0 = fast (every tick), 1 = normal (every 2), 2 = slow (every 3) — pace vs the server
    public int paceLevel = 1;
    // operating mode: 0 = Miner (strip toward spawn), 1 = Surface scout (walk on top,
    // deep-scan for big underground stashes, alert you)
    public int mode = 0;
    // stash-alert sensitivity for surface mode: 0 = any, 1 = big (default), 2 = huge
    public int stashLevel = 1;
    // pause the scout when a big stash is found (default: keep scouting, just alert)
    public boolean stashPause = false;

    // one-click preset bundles
    public static final String[] PRESETS = {"Diamond Run", "Surface Scout", "All-Ore Miner", "Cautious"};
    public int presetIdx = 0;

    /** Apply a preset bundle of settings, then persist. */
    public void applyPreset(int p) {
        switch (p) {
            case 1 -> {                       // Surface Scout
                mode = 1;
                stashLevel = 1;
                stashPause = false;
            }
            case 2 -> {                       // All-Ore Miner
                mode = 0;
                oreSeekLevel = 3;
                paceLevel = 1;
                skipBases = true;
            }
            case 3 -> {                       // Cautious (slow, safe, XP ores)
                mode = 0;
                oreSeekLevel = 1;
                paceLevel = 2;
                skipBases = true;
                skipRaided = true;
            }
            default -> {                      // Diamond Run
                mode = 0;
                oreSeekLevel = 1;
                paceLevel = 1;
                skipBases = true;
                skipRaided = true;
                oneByOne = false;
            }
        }
        save();
    }

    public String presetLabel() {
        return PRESETS[presetIdx % PRESETS.length];
    }

    public String modeLabel() {
        return mode == 1 ? "Surface scout (find stashes)" : "Miner (strip to spawn)";
    }

    public void cycleMode() {
        mode = (mode + 1) % 2;
        save();
    }

    /** {chestCount, shulkerCount} thresholds for a "worth-alerting" stash. */
    public int[] stashThreshold() {
        return switch (stashLevel) {
            case 0 -> new int[]{6, 2};    // any decent cluster
            case 2 -> new int[]{24, 8};   // only monster hoards
            default -> new int[]{12, 4};  // big
        };
    }

    public String stashLabel() {
        return switch (stashLevel) {
            case 0 -> "Any (6c/2s)";
            case 2 -> "Huge (24c/8s)";
            default -> "Big (12c/4s)";
        };
    }

    public void cycleStashLevel() {
        stashLevel = (stashLevel + 1) % 3;
        save();
    }

    public void cycleStashPause() {
        stashPause = !stashPause;
        save();
    }

    public String stashPauseLabel() {
        return stashPause ? "On (stop at stash)" : "Off (keep scouting)";
    }

    // skip ALL bases — don't auto-navigate to any; just pin them, you go yourself
    public boolean skipBases = true;
    // (when NOT skipping all) skip raided/ruined ones — only go for looted-worthy
    public boolean skipRaided = true;

    public void cycleSkipBases() {
        skipBases = !skipBases;
        save();
    }

    public String skipBasesLabel() {
        return skipBases ? "On (just pin, I'll go)" : "Off (auto-navigate)";
    }

    public void cycleSkipRaided() {
        skipRaided = !skipRaided;
        save();
    }

    public String skipRaidedLabel() {
        return skipRaided ? "On (only looted-worthy)" : "Off (visit all)";
    }

    /** Act every N ticks. Default is full speed (every tick); only "Slow" eases off. */
    public int actEveryNTicks() {
        return paceLevel == 2 ? 2 : 1;
    }

    public String paceLabel() {
        return switch (paceLevel) {
            case 2 -> "Slow (server-friendly)";
            default -> "Full speed";
        };
    }

    public void cyclePace() {
        paceLevel = (paceLevel + 1) % 3;
        save();
    }

    public void cycleOneByOne() {
        oneByOne = !oneByOne;
        save();
    }

    public String oneByOneLabel() {
        return oneByOne ? "On (needs trapdoors)" : "Off";
    }

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
            // default: diamond + redstone — both drop XP (keeps tools mended)
            return diamond || path.contains("redstone");
        }
        // rare tier: also emerald/gold/lapis/quartz
        return diamond || path.contains("redstone") || path.contains("emerald")
            || path.contains("gold") || path.contains("lapis") || path.contains("quartz");
    }

    public String oreSeekLabel() {
        return switch (oreSeekLevel) {
            case 0 -> "Off";
            case 2 -> "Rare + XP ores";
            case 3 -> "All ores";
            default -> "Diamond + Redstone (XP)";
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
            case 0 -> 0.22f;
            case 2 -> 0.6f;
            default -> 0.4f;    // medium: quick but still eased (human, faster)
        };
    }

    public float turnMax() {
        return switch (turnLevel) {
            case 0 -> 10f;
            case 2 -> 34f;
            default -> 22f;     // medium: ~440°/s cap — snappy yet smooth
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
                oneByOne = Boolean.parseBoolean(p.getProperty("oneByOne", "false"));
                paceLevel = Integer.parseInt(p.getProperty("paceLevel", "1"));
                mode = Integer.parseInt(p.getProperty("mode", "0"));
                stashLevel = Integer.parseInt(p.getProperty("stashLevel", "1"));
                stashPause = Boolean.parseBoolean(p.getProperty("stashPause", "false"));
                skipBases = Boolean.parseBoolean(p.getProperty("skipBases", "true"));
                skipRaided = Boolean.parseBoolean(p.getProperty("skipRaided", "true"));
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
        p.setProperty("oneByOne", Boolean.toString(oneByOne));
        p.setProperty("paceLevel", Integer.toString(paceLevel));
        p.setProperty("mode", Integer.toString(mode));
        p.setProperty("stashLevel", Integer.toString(stashLevel));
        p.setProperty("stashPause", Boolean.toString(stashPause));
        p.setProperty("skipBases", Boolean.toString(skipBases));
        p.setProperty("skipRaided", Boolean.toString(skipRaided));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Cyclea config");
        } catch (IOException ignored) {
            // non-fatal
        }
    }
}
