package com.otto.cyclea.feature;

/**
 * Cyclea's operational telemetry — the "how the run is actually going" counters that the ore
 * ledger ({@link CycleaLedger}) doesn't cover. The ledger answers "how much money?"; this answers
 * "how healthy is the bot?" — meals eaten, hazards escaped (and from what), deaths, and near-broken
 * tool warnings. All per-session (cleared on a fresh run); printed by {@code /cyc stats}.
 *
 * <p>Single shared instance so both the mining loop (Autopilot) and the emergency watchdog
 * (CycleaClient.safety) can bump the same counters.
 */
public final class CycleaTelemetry {

    private static final CycleaTelemetry INSTANCE = new CycleaTelemetry();

    public static CycleaTelemetry get() {
        return INSTANCE;
    }

    private CycleaTelemetry() { }

    // ---- food -----------------------------------------------------------
    private int meals;          // real-food meals eaten to fight hunger
    private int goldenApples;   // golden/enchanted apples eaten to heal

    // ---- hazard escapes, by cause --------------------------------------
    private int escLava;
    private int escSuffocation;
    private int escFire;
    private int escLowHp;
    private int escBigHit;

    // ---- survival -------------------------------------------------------
    private int deaths;
    private int toolWarnings;   // times a pickaxe was about to break with no healthy spare

    // ---- recording ------------------------------------------------------

    /** Log one consumed item. golden=true → a golden/enchanted apple heal; false → a food meal. */
    public void ate(boolean golden) {
        if (golden) {
            goldenApples++;
        } else {
            meals++;
        }
    }

    /** Log an emergency escape, bucketed by the reason string fireHome() was called with. */
    public void escaped(String reason) {
        String r = reason == null ? "" : reason.toLowerCase();
        if (r.contains("lava")) {
            escLava++;
        } else if (r.contains("suffocat")) {
            escSuffocation++;
        } else if (r.contains("fire")) {
            escFire++;
        } else if (r.contains("low hp")) {
            escLowHp++;
        } else {
            escBigHit++;   // "big hit −N" and anything unclassified
        }
    }

    public void died() {
        deaths++;
    }

    public void toolWarning() {
        toolWarnings++;
    }

    /** New run — clear every operational counter (mirrors the ledger's session reset). */
    public void resetSession() {
        meals = 0;
        goldenApples = 0;
        escLava = 0;
        escSuffocation = 0;
        escFire = 0;
        escLowHp = 0;
        escBigHit = 0;
        deaths = 0;
        toolWarnings = 0;
    }

    // ---- readout --------------------------------------------------------

    public int totalEscapes() {
        return escLava + escSuffocation + escFire + escLowHp + escBigHit;
    }

    public int meals() {
        return meals;
    }

    public int goldenApples() {
        return goldenApples;
    }

    public int deaths() {
        return deaths;
    }

    public int toolWarnings() {
        return toolWarnings;
    }

    /** e.g. "lava ×2, suffocation ×1" — only the causes that actually fired, richest first. */
    public String escapeBreakdown() {
        StringBuilder sb = new StringBuilder();
        appendCause(sb, "lava", escLava);
        appendCause(sb, "suffocation", escSuffocation);
        appendCause(sb, "fire", escFire);
        appendCause(sb, "low-HP", escLowHp);
        appendCause(sb, "big-hit", escBigHit);
        return sb.toString();
    }

    private static void appendCause(StringBuilder sb, String label, int n) {
        if (n <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(label).append(" ×").append(n);
    }
}
