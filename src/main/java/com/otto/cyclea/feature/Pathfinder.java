package com.otto.cyclea.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A bounded A* over the loaded world. Nodes are "feet" positions the player can
 * stand at; edges step to the 4 horizontal neighbours at the same Y or ±1 (step
 * up / drop down). Blocks in the way can be mined (extra cost); lava, water, and
 * blocks with a lava/water neighbour (flood risk) are never crossed.
 *
 * Not a full Baritone — it's node-budgeted and gives up (returns null) on big or
 * impossible routes, at which point the autopilot falls back to dig-toward. But
 * within loaded chunks it finds real paths around walls, gaps, and ravines.
 */
public final class Pathfinder {

    private Pathfinder() {
    }

    private static final int MAX_NODES = 6000;

    private record Node(BlockPos pos, double g, double f) {
    }

    /** @return a list of feet positions from near start to near goal, or null. */
    public static List<BlockPos> find(Minecraft mc, BlockPos start, BlockPos goal) {
        return find(mc, start, goal, false);
    }

    /** Farm variant: carpets/cane/ladders are walk-through and carpet supports standing
     *  (even over the water channels), so it routes across a sugarcane farm's walkways. */
    public static List<BlockPos> findFarm(Minecraft mc, BlockPos start, BlockPos goal) {
        return find(mc, start, goal, true);
    }

    private static List<BlockPos> find(Minecraft mc, BlockPos start, BlockPos goal, boolean farm) {
        if (mc.level == null) {
            return null;
        }
        BlockPos from = grounded(mc, start, farm);
        if (from == null) {
            return null;
        }
        // Weighted A*: farms are big and open, so a plain heuristic flood-fills the floor
        // and burns the budget before reaching the far staircase. A heavy weight makes it
        // greedy — it beelines toward the goal, finds the stairs, and climbs within budget.
        double weight = farm ? 2.5 : 1.0;
        int budget = farm ? 30000 : MAX_NODES;

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, BlockPos> came = new HashMap<>();

        open.add(new Node(from, 0, weight * h(from, goal)));
        best.put(from.asLong(), 0.0);
        int expanded = 0;
        BlockPos closest = from;
        double closestH = h(from, goal);

        while (!open.isEmpty() && expanded++ < budget) {
            Node cur = open.poll();
            if (horizClose(cur.pos, goal, 2) && Math.abs(cur.pos.getY() - goal.getY()) <= 3) {
                return reconstruct(came, cur.pos);
            }
            double curH = h(cur.pos, goal);
            if (curH < closestH) {          // track the best-so-far in case we run out
                closestH = curH;
                closest = cur.pos;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = cur.pos.relative(d).above(dy);
                    Double cost = stepCost(mc, cur.pos, next, d, dy, farm);
                    if (cost == null) {
                        continue;
                    }
                    double ng = cur.g + cost;
                    long key = next.asLong();
                    if (ng < best.getOrDefault(key, Double.MAX_VALUE)) {
                        best.put(key, ng);
                        came.put(key, cur.pos);
                        open.add(new Node(next, ng, ng + weight * h(next, goal)));
                    }
                }
            }
        }
        // out of budget: hand back a partial route toward the goal so it keeps progressing
        // (walk to the closest point we found, then it re-plans from there)
        if (farm && closest != from) {
            return reconstruct(came, closest);
        }
        return null;
    }

    /** Move cost from a to b (b = feet of the destination), or null if illegal. */
    private static Double stepCost(Minecraft mc, BlockPos a, BlockPos b, Direction d, int dy, boolean farm) {
        if (!standable(mc, b, farm)) {
            return null;
        }
        // must be able to occupy feet+head at b (mine if solid, never through hazards)
        double mine = 0;
        Double f = enterCost(mc, b, farm);
        Double h = enterCost(mc, b.above(), farm);
        if (f == null || h == null) {
            return null;
        }
        mine += f + h;
        // stepping up requires clearing the block above the current head too
        if (dy > 0) {
            Double up = enterCost(mc, a.above(2), farm);
            if (up == null) {
                return null;
            }
            mine += up;
        }
        return 1.0 + mine + (dy != 0 ? 0.5 : 0);
    }

    /** Cost to occupy a block (0 if already open, >0 if we must mine it), null if impossible. */
    private static Double enterCost(Minecraft mc, BlockPos p, boolean farm) {
        BlockState st = mc.level.getBlockState(p);
        if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
            return null;
        }
        if (passable(st, farm)) {
            return 0.0;        // walk-through (incl. carpet/cane/ladder in farm mode) — no mining
        }
        if (st.is(Blocks.BEDROCK) || st.is(Blocks.BARRIER) || st.is(Blocks.REINFORCED_DEEPSLATE)) {
            return null;   // unbreakable — route around
        }
        if (farm) {
            return null;   // farms are non-destructive — never mine through structure
        }
        if (hasHazardNeighbor(mc, p)) {
            return null;   // mining it would flood
        }
        return 2.0;        // solid but safe to mine
    }

    private static boolean standable(Minecraft mc, BlockPos feet, boolean farm) {
        if (farm) {
            // carpet/slab/etc. in the feet block supports standing — even over water
            BlockState at = mc.level.getBlockState(feet);
            if (!at.is(Blocks.WATER) && !at.is(Blocks.LAVA)
                && !at.isCollisionShapeFullBlock(mc.level, feet)
                && !at.getCollisionShape(mc.level, feet).isEmpty()) {
                return true;
            }
        }
        BlockState below = mc.level.getBlockState(feet.below());
        return !passable(below, farm) && !below.is(Blocks.LAVA) && !below.is(Blocks.WATER);
    }

    /** Drop the start position down to the first solid floor so A* begins grounded. */
    private static BlockPos grounded(Minecraft mc, BlockPos p, boolean farm) {
        for (int i = 0; i < 4; i++) {
            if (standable(mc, p, farm)) {
                return p;
            }
            p = p.below();
        }
        return standable(mc, p, farm) ? p : null;
    }

    private static boolean passable(BlockState st, boolean farm) {
        if (st.isAir() || st.is(Blocks.CAVE_AIR) || st.is(Blocks.VOID_AIR)
            || st.is(Blocks.WATER) || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS)) {
            return true;
        }
        if (!farm) {
            return false;
        }
        // farm walkways: carpets, cane, ladders, snow are all walk-through
        if (st.is(Blocks.SUGAR_CANE) || st.is(Blocks.LADDER) || st.is(Blocks.SNOW)) {
            return true;
        }
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().endsWith("carpet");
    }

    private static boolean hasHazardNeighbor(Minecraft mc, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState st = mc.level.getBlockState(pos.relative(d));
            if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean horizClose(BlockPos a, BlockPos b, int r) {
        return Math.abs(a.getX() - b.getX()) <= r && Math.abs(a.getZ() - b.getZ()) <= r;
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.7;
    }

    private static List<BlockPos> reconstruct(Map<Long, BlockPos> came, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos c = end;
        while (c != null) {
            path.add(c);
            c = came.get(c.asLong());
        }
        Collections.reverse(path);
        return path;
    }
}
