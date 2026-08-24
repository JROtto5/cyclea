package com.otto.cyclea.feature;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Cyclea's profit ledger — Epic 1. Tracks what the bot digs, by type, and turns it into money:
 * live session value, $/hour, all-time totals (persisted), and the richest vein of the run.
 *
 * <p>Per-unit sell prices default to rough server values and are fully tunable: they live in
 * {@code cyclea-ledger.properties} in your game dir alongside the saved all-time counts, so you
 * can dial them to YOUR server's shop and the $/hr instantly reflects reality.
 */
public final class CycleaLedger {

    /** Default per-block sell value (emeralds / $). Tunable via cyclea-ledger.properties.
     *  MUST be declared + populated BEFORE {@link #INSTANCE}: the constructor copies it, so if
     *  INSTANCE initialized first it would copy a null map and the whole class fails to load
     *  (NoClassDefFoundError → /cyc minemoney crash). Order here is load-bearing. */
    private static final Map<String, Integer> DEFAULT_PRICES = new LinkedHashMap<>();
    static {
        DEFAULT_PRICES.put("netherite", 150);
        DEFAULT_PRICES.put("diamond", 40);
        DEFAULT_PRICES.put("emerald", 35);
        DEFAULT_PRICES.put("gold", 8);
        DEFAULT_PRICES.put("lapis", 6);
        DEFAULT_PRICES.put("iron", 6);
        DEFAULT_PRICES.put("redstone", 5);
        DEFAULT_PRICES.put("quartz", 4);
        DEFAULT_PRICES.put("copper", 3);
        DEFAULT_PRICES.put("coal", 2);
    }

    private static final CycleaLedger INSTANCE = new CycleaLedger();

    public static CycleaLedger get() {
        return INSTANCE;
    }

    private final Map<String, Integer> prices = new LinkedHashMap<>(DEFAULT_PRICES);
    private final Map<String, Integer> session = new TreeMap<>();   // this run, by ore key
    private final Map<String, Integer> allTime = new TreeMap<>();    // persisted across sessions

    private long sessionStartMs = 0;
    private String curVeinType = "";
    private int curVeinRun = 0;
    private String bestVeinType = "";
    private int bestVeinRun = 0;
    private long lastSaveMs = 0;
    private boolean loaded = false;

    private CycleaLedger() { }

    // ---- classification -------------------------------------------------

    /** Reduce a mined block's registry path to a canonical ore key (e.g. deepslate_diamond_ore → diamond). */
    public static String classify(String blockPath) {
        String p = blockPath.toLowerCase();
        if (p.contains("debris")) {
            return "netherite";
        }
        p = p.replace("deepslate_", "").replace("nether_", "")
             .replace("raw_", "").replace("_ore", "").replace("_block", "");
        if (p.equals("lapis_lazuli") || p.startsWith("lapis")) {
            return "lapis";
        }
        return p;
    }

    // ---- recording ------------------------------------------------------

    /** Log one mined ore block by its canonical key. Starts the session clock on first ore. */
    public void record(String oreKey) {
        if (oreKey == null || oreKey.isEmpty()) {
            return;
        }
        ensureLoaded();
        if (sessionStartMs == 0) {
            sessionStartMs = System.currentTimeMillis();
        }
        session.merge(oreKey, 1, Integer::sum);
        allTime.merge(oreKey, 1, Integer::sum);

        // vein tracking: longest run of the same ore type back-to-back
        if (oreKey.equals(curVeinType)) {
            curVeinRun++;
        } else {
            curVeinType = oreKey;
            curVeinRun = 1;
        }
        if (curVeinRun > bestVeinRun) {
            bestVeinRun = curVeinRun;
            bestVeinType = oreKey;
        }

        // persist all-time occasionally (not every block — cheap throttle)
        long now = System.currentTimeMillis();
        if (now - lastSaveMs > 15000) {
            save();
            lastSaveMs = now;
        }
    }

    /** New run — clear the session tallies and vein, keep all-time. */
    public void resetSession() {
        session.clear();
        sessionStartMs = 0;
        curVeinType = "";
        curVeinRun = 0;
        bestVeinType = "";
        bestVeinRun = 0;
    }

    // ---- value + metrics ------------------------------------------------

    public int priceOf(String key) {
        return prices.getOrDefault(key, 0);
    }

    private int valueOf(Map<String, Integer> counts) {
        int v = 0;
        for (var e : counts.entrySet()) {
            v += e.getValue() * priceOf(e.getKey());
        }
        return v;
    }

    public int sessionValue() {
        return valueOf(session);
    }

    public int allTimeValue() {
        ensureLoaded();
        return valueOf(allTime);
    }

    public int sessionCount() {
        int n = 0;
        for (int c : session.values()) {
            n += c;
        }
        return n;
    }

    public long sessionSeconds() {
        return sessionStartMs == 0 ? 0 : (System.currentTimeMillis() - sessionStartMs) / 1000;
    }

    /** Money earned per hour this run. */
    public int valuePerHour() {
        long s = sessionSeconds();
        return s <= 0 ? 0 : (int) (sessionValue() * 3600L / s);
    }

    /** e.g. "§bdiamond ×12" for the ore that made the most money this run, or "" if none. */
    public String topEarner() {
        String best = null;
        int bestVal = 0;
        for (var e : session.entrySet()) {
            int v = e.getValue() * priceOf(e.getKey());
            if (v > bestVal) {
                bestVal = v;
                best = e.getKey();
            }
        }
        return best == null ? "" : best + " ×" + session.get(best);
    }

    /** e.g. "redstone ×9" — the longest same-ore streak this run. */
    public String bestVein() {
        return bestVeinRun <= 1 ? "" : bestVeinType + " ×" + bestVeinRun;
    }

    /** Session breakdown lines, richest first, for the /cyc stats readout. */
    public List<String> sessionBreakdown() {
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(session.entrySet());
        rows.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(
            e -> e.getValue() * priceOf(e.getKey())).reversed());
        List<String> out = new ArrayList<>();
        for (var e : rows) {
            int val = e.getValue() * priceOf(e.getKey());
            out.add(String.format("§f%-10s §7×%-4d §a$%d", e.getKey(), e.getValue(), val));
        }
        return out;
    }

    // ---- persistence ----------------------------------------------------

    private Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("cyclea-ledger.properties");
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) {
            save();   // write defaults so the user has a file to edit
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        for (String name : p.stringPropertyNames()) {
            String v = p.getProperty(name);
            try {
                if (name.startsWith("price.")) {
                    prices.put(name.substring(6), Integer.parseInt(v.trim()));
                } else if (name.startsWith("total.")) {
                    allTime.put(name.substring(6), Integer.parseInt(v.trim()));
                }
            } catch (NumberFormatException ignored) {
                // skip malformed lines, keep going
            }
        }
    }

    private void save() {
        Properties p = new Properties() {
            // keep keys in a stable, human-friendly order when written
            @Override
            public java.util.Set<Object> keySet() {
                return new java.util.TreeSet<>(super.keySet());
            }
            @Override
            public synchronized java.util.Enumeration<Object> keys() {
                return java.util.Collections.enumeration(new java.util.TreeSet<>(super.keySet()));
            }
        };
        for (var e : prices.entrySet()) {
            p.setProperty("price." + e.getKey(), Integer.toString(e.getValue()));
        }
        for (var e : allTime.entrySet()) {
            p.setProperty("total." + e.getKey(), Integer.toString(e.getValue()));
        }
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Cyclea ledger — edit price.<ore> to match your server's shop. total.<ore> = all-time mined.");
        } catch (IOException ignored) {
            // best-effort; a failed save just means all-time isn't persisted this tick
        }
    }

    /** Flush to disk now (call on stop so nothing is lost). */
    public void flush() {
        if (loaded) {
            save();
        }
    }
}
