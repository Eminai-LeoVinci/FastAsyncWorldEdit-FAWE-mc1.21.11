# Native Fabric chunk queue (experimental)

Re-enables FAWE's `FAST_PLACEMENT` fast path on the Fabric port by implementing the previously-stubbed
chunk seams, so large pastes no longer route through the slow per-block `WorldNativeAccess` path that
raced C2ME/Lithium and silently dropped blocks.

**Status:** v1, compiles + builds clean, **not yet runtime-tested in-game**. Off by default.

## Enable

Add the JVM flag to your launch arguments:

```
-Dfawe.fabric.nativeQueue=true
```

With the flag **unset/false**, the build behaves exactly as before (legacy WNA path, `FAST_PLACEMENT` off).
The flag gates every change together, so the default build is byte-for-byte the old behavior.

## How it works

A paste flows: FAWE stages all block/biome/light data into in-memory `char[]` arrays on a worker thread
(`CharSetBlocks`), then `ChunkHolder.call()` invokes `FabricGetBlocks.call(...)`. The key design point:

- **Async phase** (FAWE worker): snapshot the staged arrays; capture the pre-edit state for undo.
- **Sync phase** (server thread, via `QueueHandler.sync`): every live mutation — section block writes
  (`LevelChunkSection.setBlockState`), light (`queueSectionData`), heightmaps (`primeHeightmaps`),
  `setLightCorrect`/`setUnsaved`, tile entities — plus **one** whole-chunk resend
  (`ClientboundLevelChunkWithLightPacket`).

Because the live chunk mutation and the packet send happen on the **server thread**, C2ME/Lithium's
"Async chunk modification" guard never fires — eliminating the silent dropped-block bug and the
pause/unpause throughput cliff. Backpressure: `handleCallFinalizer` returns the real `sync()` future, so
the STQE's submission tracking blocks the producer when the server thread can't keep up (e.g. paused),
instead of building an unbounded backlog.

### Prerequisite fix (always on, even with the flag off)

`FabricWorldEdit.onStartServer` now calls `Fawe.instance().setMainThread()`. `SERVER_STARTED` fires on the
server thread, so this binds FAWE's "main thread" to it. Without this, `QueueHandler.run()` (which throws
if not on the FAWE main thread) and `QueueHandler.sync()` route incorrectly.

## Files

New (`com.fastasyncworldedit.fabric`):
- `FabricGetBlocks` — the real `IChunkGet`; async-stage / server-thread-apply split.
- `FabricGetBlocks_Copy` — history snapshot for undo (blocks only in v1).
- `FabricChunkSender` — single per-chunk resend to tracking players.
- `FabricBlockMapping` — cached FAWE-ordinal ⇄ native-BlockState conversion (via `FabricTransmogrifier`,
  not the untrustworthy global palette id).

Modified: `FabricWorld.get`, `FabricPlatformAdapter.sendChunk`, `FabricWorldEdit` (setMainThread +
`isNativeQueueEnabled` + `FAST_PLACEMENT` gate), `LocalSession.shouldForceWna`, `worldedit.accesswidener`
(+`ChunkMap.getPlayers`).

## v1 limitations (deliberate, documented)

- **Biomes are not applied** by the native path. Schematics that carry biome data, and `//setbiome`, will
  not change biomes while the flag is on (blocks only). Most schematic pastes are block-only.
- **Entities** are not copied or restored.
- **Light GET reads** return full-bright defaults (only affects `//copy` of light). Paste lighting comes
  from FAWE's relighter via the staged light arrays and should be correct on resend.
- **Undo** restores blocks but not tile-entity NBT or biomes.
- **Per-cell apply:** v1 writes each changed cell with `LevelChunkSection.setBlockState` on the server
  thread (correct, drift-safe). A v2 optimization can build the replacement `PalettedContainer` off-thread
  and pointer-swap it for higher throughput — deferred because the 1.21.11 container-construction API does
  not exist in the 1.21.1 compile mappings.

## Test checklist (in-game, flag ON, under your C2ME+Lithium modpack)

1. **Thread identity / startup:** load a world; confirm no `Not main thread` / `IllegalStateException`
   spam from `QueueHandler.run` in `latest.log`.
2. **Small set:** `//pos1`/`//pos2` a 16³ region, `//set stone`. Blocks appear; **no "Async chunk
   modification" CME** in the log; a second connected client sees the change.
3. **Undo:** `//undo` restores the original blocks.
4. **Copy/paste:** `//copy` then `//paste` a structure with chests/signs — tiles restored.
5. **Large paste:** a big schematic spanning many sections (incl. high-altitude/sparse). No freeze, no
   drops, steady throughput **whether the game is paused or not** (the pause/unpause dance should no longer
   matter).
6. **Save durability:** after a big paste, `/save-all`, quit, reload — the edit persists (validates
   `setUnsaved`). If it does **not** persist, check the log for the "Could not mark chunk ... unsaved"
   error and report — a reflective fallback for `setUnsaved` would be needed on this runtime.
7. **Pause/backpressure:** pause mid-large-paste for a while; confirm no OOM / no runaway `latest.log`
   growth; unpause drains cleanly.
8. **Lighting:** paste glowstone/torches; light looks correct after the chunk resends.

## Watch-items / things most likely to need a fix after first run

- `setUnsaved` name/signature drift on the 1.21.11 runtime (see test #6).
- `ClientboundLevelChunkWithLightPacket` constructor arity (reflective 4-arg vs 5-arg probe in
  `FabricChunkSender` — if resend silently does nothing, check the "Could not resolve constructor" log).
- Whether FAWE's relighter actually populates `set.getLight()` in this path (if light is wrong, that's the
  suspect).
