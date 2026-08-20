package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the blocks (and entities) around the player and collects everything
 * matching the active target. Kept deliberately simple and bounded by the scan
 * radius so it can run on a background cadence without stalling the client.
 */
public final class TargetScanner {

    private TargetScanner() {
    }

    public static List<BlockPos> scan(Minecraft mc) {
        List<BlockPos> hits = new ArrayList<>();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return hits;
        }

        CycleaState state = CycleaState.get();
        BlockPos origin = mc.player.blockPosition();
        int r = state.getRadius();

        if (state.getTarget() == CycleaState.Target.VILLAGES) {
            for (Entity e : level.entitiesForRendering()) {
                if (e instanceof Villager) {
                    hits.add(e.blockPosition());
                }
            }
            return dedupeNearby(hits, 24);
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r; dy <= r; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.isOutsideBuildHeight(cursor.getY())) {
                        continue;
                    }
                    if (matches(level, cursor, state.getTarget())) {
                        hits.add(cursor.immutable());
                        if (hits.size() > 400) {
                            return hits;
                        }
                    }
                }
            }
        }
        return hits;
    }

    private static boolean matches(ClientLevel level, BlockPos pos, CycleaState.Target target) {
        BlockState s = level.getBlockState(pos);
        return switch (target) {
            case CHESTS -> s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST)
                || s.is(Blocks.ENDER_CHEST) || s.is(Blocks.BARREL);
            case SPAWNERS -> s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER) || s.is(Blocks.VAULT);
            case CAVES -> s.is(Blocks.CAVE_AIR);
            case VILLAGES -> false;
        };
    }

    /** Collapse clusters (e.g. many villagers or cave-air blocks) into markers. */
    private static List<BlockPos> dedupeNearby(List<BlockPos> in, int minDist) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : in) {
            boolean near = false;
            for (BlockPos q : out) {
                if (p.distManhattan(q) < minDist) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                out.add(p);
            }
        }
        return out;
    }
}
