# Cyclea

A lightweight client-side **finder** for Minecraft (Fabric). Cyclea locates
nearby **chests, spawners/vaults, villages, and caves** and points you to the
nearest one — a "mod-of-mods" companion that pairs with any minimap.

> By **John Rydell** · MIT licensed · single-player / your-own-server use.

## Features (v2.0) — Base Radar

- **Base finder (the "cluster bomb"):** every container and workstation a player
  builds — chests, barrels, shulkers, ender chests, furnaces, hoppers, brewing
  stands, beacons, signs, lecterns, campfires, beehives, jukeboxes, enchanting
  tables, crafters — is a block entity. Cyclea gathers that whole fingerprint set
  across your render distance and flood-fills it into clusters. A dense cluster of
  them is a **base**. It counts them and points you at the nearest one.
- **On-screen HUD** (top-left): live tallies — Bases / Chests≤20 / Shulkers — plus
  the active target and the nearest hit with heading + coordinates.
- **Target modes** (cycle with `]`): Bases → Loot (chests≤Y20 + shulkers) →
  Shulkers (all levels) → Spawners & Vaults (≤Y20) → Caves.
- **Deep-loot filter:** chests/barrels/ender chests and spawners/vaults report only
  at **Y ≤ 20**. Shulkers are unfiltered (every level).
- **Red clump alert:** bunched chests/shulkers (double chests, shulker walls,
  storage rooms) flash `⚠ CLUMP found!` in red so a stash stands out.

## Controls

| Key | Action |
|-----|--------|
| `[` | Toggle the finder on/off |
| `]` | Cycle to the next target type |

Both are rebindable in **Options → Controls → Cyclea**.

## Building

Two ways — pick either.

### A) No Loom, no Gradle (what this repo was actually built with)

`build-noloom.sh` compiles straight against your installed game jars with
`javac` and packages the mod. No mappings download, no build plugin:

```bash
MCROOT=~/.minecraft ./build-noloom.sh
# -> cyclea-1.1.0.jar  (drop into ~/.minecraft/mods/)
```

### B) Conventional Gradle + Loom

```bash
./gradlew build   # build/libs/cyclea-1.1.0.jar
```

> Loom needs Mojang's official mapping file for your version. If it errors with
> *"Failed to find official mojang mappings for 26.2"*, that file isn't published
> yet for that exact version — use method **A** in the meantime (see below).

## For other devs: building a Fabric mod for 26.2 without Loom

If Loom can't fetch mappings for a bleeding-edge version, you're not stuck. The
key observation for **26.2**:

- The client jar at `versions/26.2/26.2.jar` ships **already de-obfuscated** —
  10k+ classes under real names like `net.minecraft.client.Camera`, zero
  obfuscated single-letter classes.
- Mods load in that same **official (mojmap)** namespace at runtime.

So you can skip Loom entirely and compile with plain `javac`:

1. **Classpath** = the client jar + everything under `.minecraft/libraries` +
   the extracted Fabric API modules in `.minecraft/.fabric/processedMods`.
2. `javac --release 21` your sources against that classpath.
3. Package classes + `fabric.mod.json` + assets into a jar. No remap step —
   you're already in the runtime namespace.

Watch for **26.2 API drift** when porting older code:

| Old (≤1.21) | 26.2 |
|-------------|------|
| `KeyBindingHelper.registerKeyBinding` | `KeyMappingHelper.registerKeyMapping` |
| `new KeyMapping(name, key, "category")` | `new KeyMapping(name, key, new KeyMapping.Category(id))` |
| `net.minecraft.world.entity.npc.Villager` | `...npc.villager.Villager` |
| `net.minecraft.client.renderer.RenderType` | `...renderer.rendertype.RenderType` |
| `HudRenderCallback` | `HudElementRegistry` + `HudElement` (extract/render states) |
| `WorldRenderEvents` / `WorldRenderContext` | `level.LevelRenderEvents` / `LevelRenderContext` |

`javap -p -cp <classpath> <FQCN>` against the de-obfuscated jar is your friend
for pinning exact signatures. See `build-noloom.sh` for the full recipe.

## License

MIT — see [LICENSE](LICENSE). Do what you like, keep the notice.

## Fair play

Cyclea is a client finder that reveals things through terrain. Keep it to
single-player and servers you own — most public servers treat finders as
cheating, and this project makes no attempt to hide from anti-cheat.
