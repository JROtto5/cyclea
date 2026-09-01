package com.otto.cyclea.feature;

import com.otto.cyclea.CycleaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * TRUE X-ray. Not the HUD-projected ESP radar (that's {@link TargetScanner}) —
 * this actually hides every non-target block from the world render so the ores
 * float in the open. It works by a mixin ({@code BlockStateBaseMixin}) that
 * flips three vanilla block methods when X-ray is on:
 *   getRenderShape() -> INVISIBLE   (the block is not drawn)
 *   canOcclude()     -> false        (so revealed ores next to it aren't culled)
 *   isSolidRender()  -> false        (same, for the occlusion cache Sodium reads)
 *
 * It reuses the SAME ore whitelist as the radar ({@link CycleaConfig#wantsOre}),
 * so whatever ores you're seeking are exactly what X-ray leaves visible.
 *
 * Toggle with the [X] key. Best used with shaders off — with a shaderpack on you'll
 * still see the ores, just floating in the shader-lit void.
 */
public final class CycleaXray {

    private CycleaXray() {
    }

    /** Read by the mixin on every block-render query, so it stays a plain volatile flag. */
    public static volatile boolean ENABLED = false;

    /** Blocks to KEEP visible while X-ray is on. Rebuilt from the ore config on each toggle. */
    private static volatile Set<Block> reveal = new HashSet<>();

    /** Snapshot the current ore whitelist into a fast block-identity set. */
    public static void rebuild() {
        Set<Block> set = new HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (CycleaConfig.get().wantsOre(path)) {
                set.add(b);
            }
        }
        reveal = set;
    }

    /**
     * True if this block should be hidden right now. Deliberately CHEAP — a flag plus a
     * hash-set identity lookup — because it runs for every block the mesher touches.
     * MUST NOT call getRenderShape()/canOcclude()/isSolidRender(): those are the very
     * methods the mixin overrides, so touching them here would recurse.
     */
    public static boolean hidden(BlockState state) {
        return ENABLED && !reveal.contains(state.getBlock());
    }

    /**
     * Flip X-ray. We do NOT force a chunk reload from here: 26.2 removed the old
     * {@code LevelRenderer.allChanged()}, and its {@code resetLevelRenderData()} nulls
     * the renderer's viewArea WITHOUT rebuilding it — the next render frame then NPEs
     * (that was the [X] crash). There's no safe no-arg "rebuild all chunks" to call, so
     * the player refreshes with vanilla F3+A (Reload Chunks), which reallocates properly.
     * Chunks also pick up the change as they naturally re-mesh.
     */
    public static boolean toggle(Minecraft mc) {
        ENABLED = !ENABLED;
        if (ENABLED) {
            rebuild();
        }
        return ENABLED;
    }
}
