# FastAsyncWorldEdit — Fabric port for Minecraft 1.21.11

An **unofficial** Fabric build of FastAsyncWorldEdit, ported to Minecraft 1.21.11 and extended with a few quality-of-life features aimed at very large pastes.

> ⚠️ This is a personal fork. It is **not** affiliated with or supported by IntellectualSites, the upstream FAWE team, or the maintainers of the original Fabric port. Do not file bug reports against upstream for issues with this build.

---

## What this fork is

Branch `fawe-fabric-1.21.11` in this repo is built from:

- **sk89q WorldEdit** — the original world-editing library
- **[IntellectualSites / FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit)** — the FAWE rewrite (Bukkit/Paper focus)
- **[Plaaasma's FAWE Fabric port](https://github.com/Plaaasma/FastAsyncWorldEdit)** (`fabric-1.21.1` branch) — the Fabric module this is forked from
- **This repo** — 1.21.11 compatibility shims and a few extra commands

The Fabric module targets the Minecraft 1.21.11 runtime while staying on the 1.21.1 Yarn-mapped source tree. API drift between 1.21.1 and 1.21.11 is handled with reflective fallbacks inside `FabricAdapter` and friends, so the same source compiles against either runtime.

A second branch (`fabric-1.21.11`, parked) was an earlier attempt at retargeting the whole source tree at 1.21.11 mappings; it is kept for reference only — all active work happens on `fawe-fabric-1.21.11`.

---

## What this fork adds on top of upstream

### Large-paste progress reporting
Both `//copy` and `//paste` (and `//place`) now report progress as a percentage of expected block changes, with an ETA. Useful when copying or pasting tens of millions of blocks.

### `/fawestatus` / `/fs` command
A live status command that reports:
- FAWE blocking-executor active/queued/done counts
- ForkJoin pool stats (primary + secondary)
- The active `EditSession` for the calling player, with blocks changed, percent of expected total, and rate/ETA

Useful for sanity-checking whether a long-running paste is actually making progress or stuck.

### Paste-resume system
Long pastes can be resumed if the server crashes or the operation is cancelled mid-flight.

While a `//paste` runs, completed chunks are tracked in a per-player JSON file under the server's FAWE config directory. State is flushed every ~2 seconds. After a crash:

1. Reconnect, re-load the same schematic into your clipboard, and stand at the same paste origin.
2. Run `//pasteresume` (alias `/pres`).
3. The resume command verifies the clipboard matches what was being pasted, installs a chunk-exclusion mask that skips every chunk the previous attempt finished, and resumes the paste with the same flags.

On clean completion the state file is deleted automatically.

Source: `worldedit-core/src/main/java/com/fastasyncworldedit/core/paste/`

### Native chunk queue (experimental, opt-in)
A real FAWE fast-placement path for Fabric. Block data is staged off-thread, then all live-world changes
(section blocks, light, heightmaps, tile entities) and a single per-chunk client resend run on the server
thread — so it never trips C2ME/Lithium's async-chunk-modification guard. This removes the silent
dropped-block bug and the pause/unpause slowdown of the legacy per-block path; large pastes run several
times faster (measured ~1.2k → ~75k blocks/s unpaused).

Enable with the JVM flag `-Dfawe.fabric.nativeQueue=true` (default off — an un-flagged build behaves
exactly as before). See [`NATIVE_QUEUE.md`](NATIVE_QUEUE.md) for the design, test checklist, and current
v1 limitations (blocks/tiles/light are handled; biome and entity restoration via this path are not yet).

### 1.21.11 compatibility shims
- `BlockEntity.loadWithComponents` was removed in 1.21.11 — `FabricAdapter.loadBlockEntityNbt` walks the BE class hierarchy and calls `loadCustomOnly` / `loadAdditional` as appropriate.
- `BlockEntity.saveWithId` signature change handled reflectively.
- `Component.Serializer.fromJson` replacement handled via codec lookup.
- A handful of smaller arg-type and return-type changes are bridged the same way.

If you want to see the full set of touched files, the commit message on `0ef03671b` lists them.

---

## Building

Standard FAWE build. From the repo root:

```bash
./gradlew :worldedit-fabric:build
```

Output jar lands in `worldedit-fabric/build/libs/`. Drop the `-mc1.21.1-…-dist.jar` (the shadowed one, not the `-sources` or `-dev` jars) into your server's `mods/` folder.

Java 21 is required (Loom toolchain).

---

## Compatibility notes

- Built against Yarn mappings for **1.21.1**; runs against the **1.21.11** runtime. Other 1.21.x point releases probably work but are not tested.
- **Schematics created on newer Minecraft versions** (DataVersion > 4435) will fail to load. This is a forward-compatibility limit of the WorldEdit/FAWE schematic loader, not something this fork fixes. If you need to load a schematic from a newer MC version, edit its `DataVersion` NBT tag down before loading.
- This fork has only been tested in singleplayer against a CurseForge 1.21.11 modpack. Multiplayer and dedicated-server use cases are unverified.

---

## License

GPL-3.0 — preserved from the upstream WorldEdit / FAWE source. See `LICENSE`. All upstream copyright notices in source files are unchanged.

---

## Credits

- **sk89q** and contributors — original WorldEdit
- **[IntellectualSites](https://github.com/IntellectualSites)** — FastAsyncWorldEdit
- **[Plaaasma](https://github.com/Plaaasma)** — upstream Fabric port
- General FAWE features, brushes, masks, patterns, and command surface are all upstream's work; the Fabric module is Plaaasma's. This fork only adds the items listed under "What this fork adds" above.

For general FAWE documentation, commands, and the wider feature set, see the upstream project:
- [Wiki](https://intellectualsites.github.io/fastasyncworldedit-documentation/)
- [Javadocs](https://intellectualsites.github.io/fastasyncworldedit-javadocs/)
- [Discord](https://discord.gg/intellectualsites)
