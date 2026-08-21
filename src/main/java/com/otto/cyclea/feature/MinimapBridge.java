package com.otto.cyclea.feature;

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
     * Returns how many new waypoints were placed, or 0 if Xaero is unavailable.
     */
    public static int pushBases(List<BlockPos> bases) {
        try {
            WaypointSet set = currentSet();
            if (set == null) {
                return 0;
            }
            int added = 0;
            for (BlockPos b : bases) {
                if (hasWaypointAt(set, b)) {
                    continue;
                }
                set.add(new Waypoint(b.getX(), b.getY(), b.getZ(),
                    "Cyclea Base " + b.getX() + "," + b.getZ(), "C", WaypointColor.RED));
                added++;
            }
            return added;
        } catch (Throwable t) {
            return 0;
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
