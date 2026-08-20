# Cyclea

A lightweight client-side **finder** for Minecraft (Fabric). Cyclea draws a bright
guide-line from your camera to nearby **chests, spawners/vaults, villages, and
caves**, with a matching HUD readout — a "mod-of-mods" companion that pairs with
any minimap.

> By **John Rydell** · MIT licensed · single-player / your-own-server use.

## Features

- **Guide-lines + beacons** rendered straight into the world toward every target.
- **Cycle targets** on a keypress: Chests & Loot → Spawners & Vaults → Villages → Caves.
- **HUD panel** (top-left) shows on/off, current target, and how many were found.
- **Adjustable scan radius** (16–128 blocks).
- Runs the scan a few times per second on a background cadence — cheap on FPS.

## Controls

| Key | Action |
|-----|--------|
| `G` | Toggle the finder on/off |
| `B` | Cycle to the next target type |

Both are rebindable in **Options → Controls → Cyclea**.

## Building

```bash
./gradlew build
# result: build/libs/cyclea-1.0.0.jar  ->  drop into .minecraft/mods
```

### Note on Minecraft 26.2

This targets **26.2** using official Mojang mappings. If your build fails with
*"Failed to find official mojang mappings for 26.2"*, it means Mojang has not yet
published the mapping file for that exact version; build once the mappings drop,
or point `build.gradle` at a version that has them. The source itself is written
against the stable mojmap API and does not otherwise depend on that version.

## License

MIT — see [LICENSE](LICENSE). Do what you like, keep the notice.

## Fair play

Cyclea is a client finder that reveals things through terrain. Keep it to
single-player and servers you own — most public servers treat finders as cheating.
