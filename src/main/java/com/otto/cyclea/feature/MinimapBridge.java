package com.otto.cyclea.feature;

import com.otto.cyclea.feature.TargetScanner;
import net.minecraft.core.BlockPos;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.util.List;

/**
 * Soft integration with Xaero's Minimap: pushes Cyclea's detected bases onto the
 * minimap/world-map as red waypoints. Every entry point is guarded — if Xaero
 * isn't installed (or its internals move), the calls throw and we swallow it, so
 * Cyclea keeps working standalone. The whole class is only ever touched from
 * inside a try/catch(Throwable) in the caller, so a missing Xaero jar can't
 * break class loading of the rest of the mod.
 */
public final class MinimapBridge {

    private MinimapBridge() {
    }

    /** True once we've confirmed Xaero is present and reachable. */
    public static boolean available() {
        try {
            return currentSet() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Add each base as a waypoint (skipping any already present at that spot).
     * The waypoint name carries the Y level and the chest/shulker counts so it
     * reads clearly on the map. Returns how many new waypoints were placed.
     */
    public static int pushBases(List<TargetScanner.Base> bases) {
        try {
            WaypointSet set = currentSet();
            if (set == null) {
                return 0;
            }
            int added = 0;
            for (TargetScanner.Base base : bases) {
                BlockPos b = base.center();
                if (hasWaypointAt(set, b)) {
                    continue;
                }
                String name = "Base Y" + b.getY() + " (" + base.chests() + "c "
                    + base.shulkers() + "s)";
                set.add(new Waypoint(b.getX(), b.getY(), b.getZ(), name, "B", colorFor(base)));
                added++;
            }
            return added;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Red = loaded, gold = some loot, gray = likely raided. */
    private static WaypointColor colorFor(TargetScanner.Base b) {
        return switch (b.status()) {
            case "LOADED" -> WaypointColor.RED;      // lots of chests/shulkers
            case "partial" -> WaypointColor.GOLD;    // some loot left
            default -> WaypointColor.GRAY;           // raided ruins
        };
    }

    /** Drop a death-point waypoint where the player died. Returns true if placed. */
    public static boolean pushDeath(BlockPos p) {
        try {
            WaypointSet set = currentSet();
            if (set == null) {
                return false;
            }
            set.add(new Waypoint(p.getX(), p.getY(), p.getZ(),
                "Death " + p.getX() + "," + p.getZ(), "☠", WaypointColor.WHITE));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static WaypointSet currentSet() {
        XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
        if (session == null) {
            return null;
        }
        MinimapSession minimap = session.getSession(BuiltInHudModules.MINIMAP);
        if (minimap == null) {
            return null;
        }
        MinimapWorld world = minimap.getWorldManager().getCurrentWorld();
        return world == null ? null : world.getCurrentWaypointSet();
    }

    private static boolean hasWaypointAt(WaypointSet set, BlockPos b) {
        for (Waypoint w : set.getWaypoints()) {
            if (w.getX() == b.getX() && w.getY() == b.getY() && w.getZ() == b.getZ()) {
                return true;
            }
        }
        return false;
    }
}
