package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaConfig;
import com.otto.cyclea.CycleaState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.level.block.entity.LecternBlockEntity;
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

    /** A detected base: where it is, how much storage it has, and (single-player
     *  only) a peek at the notable contents. On servers {@code loot} is empty. */
    public record Base(BlockPos center, int chests, int shulkers, int stations, String loot) {
        public int total() {
            return chests + shulkers;
        }

        /** Intact/loaded vs picked-over vs gutted ruins. */
        public String status() {
            if (shulkers >= 1 || chests >= 6) {
                return "LOADED";
            }
            if (chests >= 1) {
                return "partial";
            }
            return "RAIDED";   // only workstations left, loot gone
        }
    }

    public record Scan(List<BlockPos> hits, List<Base> bases,
                       int chestsTotal, int shulkersTotal, int players, int hostiles) {
    }

    /** Chests only count when this deep; shulkers count at any height. */
    public static final int CHEST_MAX_Y = 20;

    private record ContainerHit(BlockPos pos, boolean shulker, boolean station, BlockEntity be) {
    }

    /** Workstations that are block entities — signs a base exists even if looted. */
    private static boolean isStation(BlockEntity be) {
        return be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity
            || be instanceof BeaconBlockEntity || be instanceof HopperBlockEntity
            || be instanceof DispenserBlockEntity || be instanceof LecternBlockEntity
            || be instanceof BellBlockEntity || be instanceof CampfireBlockEntity
            || be instanceof BeehiveBlockEntity || be instanceof EnchantingTableBlockEntity
            || be instanceof CrafterBlockEntity;
    }

    /** X-ray scan: every ore we're configured to seek, within {@code radius}, even
     *  through walls. Returns {dx, dz, rgb} relative to the player for the radar.
     *  Capped so it never lags. */
    public static List<int[]> scanOres(Minecraft mc, int radius) {
        List<int[]> out = new ArrayList<>();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || CycleaConfig.get().oreSeekLevel == 0) {
            return out;
        }
        CycleaConfig cfg = CycleaConfig.get();
        BlockPos p = mc.player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    BlockState st = level.getBlockState(m);
                    if (st.isAir()) {
                        continue;
                    }
                    String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    if (!cfg.wantsOre(path)) {
                        continue;
                    }
                    out.add(new int[]{dx, dz, oreColor(path)});
                    if (out.size() >= 250) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private static int oreColor(String path) {
        if (path.contains("diamond")) {
            return 0x33E5FF;
        }
        if (path.contains("emerald")) {
            return 0x2BE04A;
        }
        if (path.contains("redstone")) {
            return 0xFF3030;
        }
        if (path.contains("gold") || path.equals("ancient_debris")) {
            return 0xFFC020;
        }
        if (path.contains("lapis")) {
            return 0x2A5BFF;
        }
        if (path.contains("copper")) {
            return 0xE08040;
        }
        if (path.contains("iron")) {
            return 0xE8D0B0;
        }
        if (path.contains("coal")) {
            return 0x606060;
        }
        if (path.contains("quartz")) {
            return 0xF0E8E0;
        }
        return 0xC0FFC0;
    }

    public static Scan scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return new Scan(List.of(), List.of(), 0, 0, 0, 0);
        }
        CycleaState state = CycleaState.get();
        List<BlockEntity> loaded = loadedBlockEntities(mc, level);

        List<ContainerHit> containers = new ArrayList<>();
        int chestsTotal = 0;
        int shulkersTotal = 0;
        for (BlockEntity be : loaded) {
            boolean shulker = be instanceof ShulkerBoxBlockEntity;                 // any level
            boolean chest = (be instanceof ChestBlockEntity                        // covers trapped
                || be instanceof BarrelBlockEntity
                || be instanceof EnderChestBlockEntity)
                && be.getBlockPos().getY() <= CHEST_MAX_Y;                         // below Y20
            boolean station = isStation(be);                                       // furnaces etc. (ruins signal)
            if (shulker) {
                shulkersTotal++;
            }
            if (chest) {
                chestsTotal++;
            }
            if (shulker || chest || station) {
                containers.add(new ContainerHit(be.getBlockPos(), shulker, station, be));
            }
        }

        // storage minecarts & chest boats are strong "active base" signals too
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer
                || e instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat) {
                chestsTotal++;
                containers.add(new ContainerHit(e.blockPosition(), false, false, null));
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
                for (ContainerHit c : containers) {
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

    /**
     * Flood-fill nearby containers into bases. Uses a spatial hash grid (cell =
     * reach) so each container only compares against its ~27 neighbour cells —
     * near-linear instead of O(n²), so a container-dense world never lags.
     */
    private static List<Base> detectBases(List<ContainerHit> pts, int reach, int minSize) {
        List<Base> bases = new ArrayList<>();
        // bucket container indices into grid cells of size `reach`
        java.util.Map<Long, List<Integer>> grid = new java.util.HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            grid.computeIfAbsent(cell(pts.get(i).pos(), reach), k -> new ArrayList<>()).add(i);
        }
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
                int cx = Math.floorDiv(a.getX(), reach);
                int cy = Math.floorDiv(a.getY(), reach);
                int cz = Math.floorDiv(a.getZ(), reach);
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            List<Integer> cellList = grid.get(packCell(cx + ox, cy + oy, cz + oz));
                            if (cellList == null) {
                                continue;
                            }
                            for (int j : cellList) {
                                if (!used[j] && a.distManhattan(pts.get(j).pos()) <= reach) {
                                    used[j] = true;
                                    group.add(j);
                                }
                            }
                        }
                    }
                }
            }
            if (group.size() < minSize) {
                continue;
            }
            int chests = 0;
            int shulkers = 0;
            int stations = 0;
            long sx = 0;
            long sy = 0;
            long sz = 0;
            for (int idx : group) {
                ContainerHit c = pts.get(idx);
                if (c.shulker()) {
                    shulkers++;
                } else if (c.station()) {
                    stations++;
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
            bases.add(new Base(center, chests, shulkers, stations, peekLoot(group, pts)));
        }
        return bases;
    }

    private static long cell(BlockPos p, int reach) {
        return packCell(Math.floorDiv(p.getX(), reach), Math.floorDiv(p.getY(), reach),
            Math.floorDiv(p.getZ(), reach));
    }

    private static long packCell(int cx, int cy, int cz) {
        return ((long) (cx & 0x1FFFFF) << 42) | ((long) (cy & 0xFFFFF) << 22) | (cz & 0x3FFFFF);
    }

    /**
     * Peek at container contents — only works in single-player (on a server the
     * client never receives them). Returns a short "top items" summary, or "".
     */
    private static String peekLoot(List<Integer> group, List<ContainerHit> pts) {
        java.util.Map<String, Integer> tally = new java.util.HashMap<>();
        for (int idx : group) {
            if (!(pts.get(idx).be() instanceof Container c)) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) {
                    tally.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
                }
            }
        }
        if (tally.isEmpty()) {
            return "";
        }
        return tally.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(4)
            .map(e -> e.getValue() + "× " + e.getKey())
            .reduce((a, b) -> a + ", " + b).orElse("");
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
