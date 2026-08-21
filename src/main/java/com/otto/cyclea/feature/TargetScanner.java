package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The finder brain. Reads loaded block entities (cheap, whole render distance),
 * clusters the base fingerprint into bases, counts loot, and sweeps entities for
 * players and hostiles.
 */
public final class TargetScanner {

    private TargetScanner() {
    }

    /** A detected base: where it is and how many signature blocks it holds. */
    public record Cluster(BlockPos center, int size) {
    }

    public record Scan(List<BlockPos> hits, List<Cluster> baseClusters,
                       int chests, int shulkers, int beacons,
                       int players, int hostiles) {
    }

    public static Scan scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return new Scan(List.of(), List.of(), 0, 0, 0, 0, 0);
        }
        CycleaState state = CycleaState.get();
        int maxY = state.getDeepMaxY();
        List<BlockEntity> loaded = loadedBlockEntities(mc, level);

        int chests = 0;
        int shulkers = 0;
        int beacons = 0;
        List<BlockPos> signatures = new ArrayList<>();
        for (BlockEntity be : loaded) {
            if (be instanceof ShulkerBoxBlockEntity) {
                shulkers++;
            }
            if (be instanceof BeaconBlockEntity) {
                beacons++;
            }
            if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof EnderChestBlockEntity) && be.getBlockPos().getY() <= maxY) {
                chests++;
            }
            if (isBaseSignature(be)) {
                signatures.add(be.getBlockPos());
            }
        }
        List<Cluster> clusters = clumpsDetailed(signatures, 10, 4);

        int[] entityCounts = countEntities(mc, level);

        List<BlockPos> hits = switch (state.getTarget()) {
            case BASES -> {
                List<BlockPos> out = new ArrayList<>();
                for (Cluster c : clusters) {
                    out.add(c.center());
                }
                yield out;
            }
            case LOOT -> collect(loaded, be ->
                be instanceof ShulkerBoxBlockEntity
                    || ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                    || be instanceof EnderChestBlockEntity) && be.getBlockPos().getY() <= maxY));
            case SHULKERS -> collect(loaded, be -> be instanceof ShulkerBoxBlockEntity);
            case SPAWNERS -> {
                List<BlockPos> out = new ArrayList<>();
                for (BlockEntity be : loaded) {
                    if (be.getBlockPos().getY() > maxY) {
                        continue;
                    }
                    BlockState s = level.getBlockState(be.getBlockPos());
                    if (s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER) || s.is(Blocks.VAULT)) {
                        out.add(be.getBlockPos());
                    }
                }
                yield out;
            }
            case CAVES -> scanCaves(mc, level, state.getRadius());
        };

        return new Scan(hits, clusters, chests, shulkers, beacons,
            entityCounts[0], entityCounts[1]);
    }

    private interface BePredicate {
        boolean test(BlockEntity be);
    }

    private static List<BlockPos> collect(List<BlockEntity> loaded, BePredicate p) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockEntity be : loaded) {
            if (p.test(be)) {
                out.add(be.getBlockPos());
            }
        }
        return out;
    }

    /** {players (excluding self), hostiles} within loaded entities. */
    private static int[] countEntities(Minecraft mc, ClientLevel level) {
        int players = 0;
        int hostiles = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (e == mc.player) {
                continue;
            }
            if (e instanceof Player) {
                players++;
            } else if (e instanceof Monster) {
                hostiles++;
            }
        }
        return new int[]{players, hostiles};
    }

    /** Any container or workstation a player places — the base fingerprint. */
    private static boolean isBaseSignature(BlockEntity be) {
        return be instanceof ChestBlockEntity
            || be instanceof BarrelBlockEntity
            || be instanceof ShulkerBoxBlockEntity
            || be instanceof EnderChestBlockEntity
            || be instanceof AbstractFurnaceBlockEntity
            || be instanceof HopperBlockEntity
            || be instanceof DispenserBlockEntity
            || be instanceof BrewingStandBlockEntity
            || be instanceof BeaconBlockEntity
            || be instanceof BellBlockEntity
            || be instanceof LecternBlockEntity
            || be instanceof SignBlockEntity
            || be instanceof CampfireBlockEntity
            || be instanceof BeehiveBlockEntity
            || be instanceof JukeboxBlockEntity
            || be instanceof EnchantingTableBlockEntity
            || be instanceof CrafterBlockEntity;
    }

    private static List<BlockPos> scanCaves(Minecraft mc, ClientLevel level, int r) {
        List<BlockPos> hits = new ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
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

    /** Flood-fill positions into clusters, returning center + member count each. */
    public static List<Cluster> clumpsDetailed(List<BlockPos> pts, int reach, int minSize) {
        List<Cluster> clusters = new ArrayList<>();
        boolean[] used = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(i);
            used[i] = true;
            for (int g = 0; g < group.size(); g++) {
                BlockPos a = pts.get(group.get(g));
                for (int j = 0; j < pts.size(); j++) {
                    if (!used[j] && a.distManhattan(pts.get(j)) <= reach) {
                        used[j] = true;
                        group.add(j);
                    }
                }
            }
            if (group.size() >= minSize) {
                clusters.add(new Cluster(pts.get(group.get(0)), group.size()));
            }
        }
        return clusters;
    }

    /** Simple center list for the clump alert (chests/shulkers). */
    public static List<BlockPos> clumps(List<BlockPos> pts, int reach, int minSize) {
        List<BlockPos> centers = new ArrayList<>();
        for (Cluster c : clumpsDetailed(pts, reach, minSize)) {
            centers.add(c.center());
        }
        return centers;
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
