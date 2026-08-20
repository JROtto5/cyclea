package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds everything matching the active target.
 *
 * Chests and spawners are block entities, so we read the level's loaded
 * block-entity map directly — that covers your whole render distance for free,
 * no cube scan, no radius ceiling. Villages come from loaded villager entities.
 * Only caves (plain cave-air blocks) fall back to a bounded cube scan, since
 * those aren't block entities.
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

        switch (state.getTarget()) {
            case CHESTS -> {
                for (BlockEntity be : loadedBlockEntities(mc, level)) {
                    // ChestBlockEntity also covers trapped chests (its subclass)
                    if (be instanceof ChestBlockEntity
                        || be instanceof EnderChestBlockEntity
                        || be instanceof BarrelBlockEntity
                        || be instanceof ShulkerBoxBlockEntity) {
                        hits.add(be.getBlockPos());
                    }
                }
            }
            case SPAWNERS -> {
                for (BlockEntity be : loadedBlockEntities(mc, level)) {
                    BlockState s = level.getBlockState(be.getBlockPos());
                    if (s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER) || s.is(Blocks.VAULT)) {
                        hits.add(be.getBlockPos());
                    }
                }
            }
            case VILLAGES -> {
                for (Entity e : level.entitiesForRendering()) {
                    if (e instanceof Villager) {
                        hits.add(e.blockPosition());
                    }
                }
                return dedupeNearby(hits, 24);
            }
            case CAVES -> {
                BlockPos origin = mc.player.blockPosition();
                int r = state.getRadius();
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int dx = -r; dx <= r; dx += 2) {
                    for (int dz = -r; dz <= r; dz += 2) {
                        for (int dy = -r; dy <= r; dy += 2) {
                            cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                            if (level.isOutsideBuildHeight(cursor.getY())) {
                                continue;
                            }
                            if (level.getBlockState(cursor).is(Blocks.CAVE_AIR)) {
                                hits.add(cursor.immutable());
                                if (hits.size() > 200) {
                                    return dedupeNearby(hits, 12);
                                }
                            }
                        }
                    }
                }
                return dedupeNearby(hits, 12);
            }
        }
        return hits;
    }

    /** Every block entity in the currently loaded chunks around the player. */
    private static List<BlockEntity> loadedBlockEntities(Minecraft mc, ClientLevel level) {
        List<BlockEntity> out = new ArrayList<>();
        ClientChunkCache cache = level.getChunkSource();
        BlockPos pp = mc.player.blockPosition();
        int pcx = pp.getX() >> 4;
        int pcz = pp.getZ() >> 4;
        int rd = mc.options.renderDistance().get();
        for (int cx = pcx - rd; cx <= pcx + rd; cx++) {
            for (int cz = pcz - rd; cz <= pcz + rd; cz++) {
                LevelChunk chunk = cache.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    out.addAll(chunk.getBlockEntities().values());
                }
            }
        }
        return out;
    }

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
