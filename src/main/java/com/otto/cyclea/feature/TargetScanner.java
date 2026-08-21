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
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The finder brain. Reads loaded block entities (cheap, whole render distance).
 *
 * A BASE is simply a place with lots of storage: chests, barrels, ender chests
 * and shulker boxes bunched together. We gather all containers and flood-fill
 * the ones that sit near each other into clusters; a cluster of a few or more is
 * a base, and we report how many chests and shulkers it holds and its Y level.
 */
public final class TargetScanner {

    private TargetScanner() {
    }

    /** A detected base: where it is, and how much storage it has. */
    public record Base(BlockPos center, int chests, int shulkers) {
        public int total() {
            return chests + shulkers;
        }
    }

    public record Scan(List<BlockPos> hits, List<Base> bases,
                       int chestsTotal, int shulkersTotal, int players, int hostiles) {
    }

    /** Chests only count when this deep; shulkers count at any height. */
    public static final int CHEST_MAX_Y = 20;

    private record Container(BlockPos pos, boolean shulker) {
    }

    public static Scan scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return new Scan(List.of(), List.of(), 0, 0, 0, 0);
        }
        CycleaState state = CycleaState.get();
        List<BlockEntity> loaded = loadedBlockEntities(mc, level);

        List<Container> containers = new ArrayList<>();
        int chestsTotal = 0;
        int shulkersTotal = 0;
        for (BlockEntity be : loaded) {
            boolean shulker = be instanceof ShulkerBoxBlockEntity;                 // any level
            boolean chest = (be instanceof ChestBlockEntity                        // covers trapped
                || be instanceof BarrelBlockEntity
                || be instanceof EnderChestBlockEntity)
                && be.getBlockPos().getY() <= CHEST_MAX_Y;                         // below Y20
            if (shulker) {
                shulkersTotal++;
            }
            if (chest) {
                chestsTotal++;
            }
            if (shulker || chest) {
                containers.add(new Container(be.getBlockPos(), shulker));
            }
        }

        List<Base> bases = detectBases(containers, 12, 3);

        int[] entities = countEntities(mc, level);

        List<BlockPos> hits = switch (state.getTarget()) {
            case BASES -> {
                List<BlockPos> out = new ArrayList<>();
                for (Base b : bases) {
                    out.add(b.center());
                }
                yield out;
            }
            case CONTAINERS -> {
                List<BlockPos> out = new ArrayList<>();
                for (Container c : containers) {
                    out.add(c.pos());
                }
                yield out;
            }
            case SPAWNERS -> {
                List<BlockPos> out = new ArrayList<>();
                for (BlockEntity be : loaded) {
                    BlockState s = level.getBlockState(be.getBlockPos());
                    if (s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER) || s.is(Blocks.VAULT)) {
                        out.add(be.getBlockPos());
                    }
                }
                yield out;
            }
            case CAVES -> scanCaves(mc, level, state.getRadius());
        };

        return new Scan(hits, bases, chestsTotal, shulkersTotal, entities[0], entities[1]);
    }

    /** Flood-fill nearby containers into bases; count chests/shulkers in each. */
    private static List<Base> detectBases(List<Container> pts, int reach, int minSize) {
        List<Base> bases = new ArrayList<>();
        boolean[] used = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            if (used[i]) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(i);
            used[i] = true;
            for (int g = 0; g < group.size(); g++) {
                BlockPos a = pts.get(group.get(g)).pos();
                for (int j = 0; j < pts.size(); j++) {
                    if (!used[j] && a.distManhattan(pts.get(j).pos()) <= reach) {
                        used[j] = true;
                        group.add(j);
                    }
                }
            }
            if (group.size() < minSize) {
                continue;
            }
            int chests = 0;
            int shulkers = 0;
            long sx = 0;
            long sy = 0;
            long sz = 0;
            for (int idx : group) {
                Container c = pts.get(idx);
                if (c.shulker()) {
                    shulkers++;
                } else {
                    chests++;
                }
                sx += c.pos().getX();
                sy += c.pos().getY();
                sz += c.pos().getZ();
            }
            int n = group.size();
            // center = the container nearest the cluster's average position
            BlockPos avg = new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
            BlockPos center = pts.get(group.get(0)).pos();
            long bestD = Long.MAX_VALUE;
            for (int idx : group) {
                long d = pts.get(idx).pos().distManhattan(avg);
                if (d < bestD) {
                    bestD = d;
                    center = pts.get(idx).pos();
                }
            }
            bases.add(new Base(center, chests, shulkers));
        }
        return bases;
    }

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
