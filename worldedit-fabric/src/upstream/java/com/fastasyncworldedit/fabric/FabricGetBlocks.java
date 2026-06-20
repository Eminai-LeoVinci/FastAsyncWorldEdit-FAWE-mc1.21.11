package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The real Fabric {@link IChunkGet}. Replaces {@code NullChunkGet} so that FAWE's {@code FAST_PLACEMENT}
 * chunk queue can drive edits.
 *
 * <p>Threading model (the key divergence from the Paper reference): block data is staged into FAWE's
 * in-memory {@code char[]} arrays on a FAWE worker thread, but every <b>live</b> chunk mutation
 * (section block writes, light, heightmaps, tile entities) and the single per-chunk client resend are
 * marshalled onto the <b>server thread</b> via {@link QueueHandler#sync}. Because the live mutation never
 * happens off-thread, C2ME/Lithium's "Async chunk modification" guard never fires — eliminating the
 * silent dropped-block bug and the pause/unpause throughput cliff in one move.</p>
 *
 * <p>v1 applies blocks per-cell with {@link LevelChunkSection#setBlockState}. A future optimisation can
 * build the replacement {@code PalettedContainer} off-thread and pointer-swap it; that is deferred here
 * because the 1.21.11-only container-construction API does not exist in the 1.21.1 compile mappings.</p>
 */
public class FabricGetBlocks extends CharGetBlocks {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private final ServerLevel serverLevel;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;

    private final ReentrantLock callLock = new ReentrantLock();
    private final Map<Integer, IChunkGet> copies = new ConcurrentHashMap<>();
    private boolean createCopy = false;
    private int copyKey = 0;

    private volatile LevelChunk levelChunk;

    public FabricGetBlocks(ServerLevel serverLevel, int chunkX, int chunkZ, int minY, int maxY) {
        super(minY >> 4, maxY >> 4);
        this.serverLevel = serverLevel;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.maxY = maxY;
        init(chunkX, chunkZ);
    }

    // ------------------------------------------------------------------
    // Chunk resolution
    // ------------------------------------------------------------------

    private LevelChunk getChunk() {
        LevelChunk chunk = this.levelChunk;
        if (chunk == null) {
            synchronized (this) {
                chunk = this.levelChunk;
                if (chunk == null) {
                    this.levelChunk = chunk = ensureLoaded(serverLevel).join();
                }
            }
        }
        return chunk;
    }

    protected CompletableFuture<LevelChunk> ensureLoaded(ServerLevel level) {
        LevelChunk now = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (now != null) {
            return CompletableFuture.completedFuture(now);
        }
        if (Fawe.isMainThread()) {
            return CompletableFuture.completedFuture((LevelChunk) level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true));
        }
        CompletableFuture<LevelChunk> future = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete((LevelChunk) level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    // ------------------------------------------------------------------
    // GET read side (may run on a FAWE worker; reads are tolerated off-thread)
    // ------------------------------------------------------------------

    @Override
    public char[] update(int layer, char[] data, boolean aggressive) {
        if (data == null || data.length != 4096) {
            data = new char[4096];
        }
        LevelChunkSection[] sections = getChunk().getSections();
        if (layer < 0 || layer >= sections.length) {
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
            return data;
        }
        LevelChunkSection section = sections[layer];
        if (section == null || section.hasOnlyAir()) {
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
            return data;
        }
        for (int j = 0; j < 4096; j++) {
            int x = j & 15;
            int z = (j >> 4) & 15;
            int y = (j >> 8) & 15;
            data[j] = FabricBlockMapping.nativeToOrdinal(section.getBlockState(x, y, z));
        }
        return data;
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        // v1: biome GET reads are only used by //copy of biomes; return a stable default.
        return BiomeTypes.FOREST;
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return 0;
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        return new int[256];
    }

    @Override
    public void removeSectionLighting(int layer, boolean sky) {
    }

    @Override
    public Map<BlockVector3, FaweCompoundTag> tiles() {
        Map<BlockPos, BlockEntity> nmsTiles = getChunk().getBlockEntities();
        if (nmsTiles.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<BlockVector3, FaweCompoundTag> out = new HashMap<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : nmsTiles.entrySet()) {
            FaweCompoundTag tag = toFaweTile(entry.getValue());
            if (tag != null) {
                BlockPos pos = entry.getKey();
                out.put(BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()), tag);
            }
        }
        return out;
    }

    @Nullable
    @Override
    public FaweCompoundTag tile(int x, int y, int z) {
        BlockEntity be = getChunk().getBlockEntity(new BlockPos((x & 15) + (chunkX << 4), y, (z & 15) + (chunkZ << 4)));
        if (be == null) {
            return null;
        }
        return toFaweTile(be);
    }

    @Nullable
    private FaweCompoundTag toFaweTile(BlockEntity be) {
        try {
            net.minecraft.nbt.CompoundTag nbt = FabricAdapter.saveBlockEntityWithId(be, serverLevel.registryAccess());
            return FaweCompoundTag.of(NBTConverter.fromNative(nbt));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Collection<FaweCompoundTag> entities() {
        return Collections.emptyList();
    }

    @Nullable
    @Override
    public FaweCompoundTag entity(UUID uuid) {
        return null;
    }

    @Override
    public Set<Entity> getFullEntities() {
        // v1: entity copy/restore not supported by the native queue.
        return Collections.emptySet();
    }

    // ------------------------------------------------------------------
    // Light push-back to GET — handled inline in the apply sync task instead.
    // ------------------------------------------------------------------

    @Override
    public void setLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setSkyLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
    }

    // ------------------------------------------------------------------
    // Copy / call-lock bookkeeping (mirrors AbstractBukkitGetBlocks)
    // ------------------------------------------------------------------

    @Override
    public boolean isCreateCopy() {
        return createCopy;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        if (!callLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Attempting to set createCopy but chunk GET is not call-locked.");
        }
        this.createCopy = createCopy;
        return ++this.copyKey;
    }

    @Nullable
    @Override
    public IChunkGet getCopy(int key) {
        return copies.remove(key);
    }

    @Override
    public void lockCall() {
        this.callLock.lock();
    }

    @Override
    public void unlockCall() {
        this.callLock.unlock();
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    // ------------------------------------------------------------------
    // The apply seam
    // ------------------------------------------------------------------

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized <T extends Future<T>> T call(IQueueExtent<? extends IChunk> owner, IChunkSet set, Runnable finalizer) {
        if (!callLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Attempted to call chunk GET but chunk was not call-locked.");
        }
        final int finalCopyKey = copyKey;
        try {
            CompletableFuture<LevelChunk> future = ensureLoaded(serverLevel);
            LevelChunk chunk = future.getNow(null);
            if (chunk != null) {
                return internalCall(set, finalizer, finalCopyKey, chunk);
            }
            // Not loaded yet: resubmit via the STQE so excessive queuing waits for the target size, then
            // return a completed future to avoid deadlocking the blocking executor on this submission.
            future.thenApply(loaded -> owner.submitTaskUnchecked(() -> (T) tryInternalCall(set, finalizer, finalCopyKey, loaded)));
            return (T) (Future) CompletableFuture.completedFuture(null);
        } catch (Throwable e) {
            LOGGER.error("[FAWE-FABRIC] Error performing chunk call at {},{}", chunkX, chunkZ, e);
            return null;
        }
    }

    private <T extends Future<T>> T tryInternalCall(IChunkSet set, Runnable finalizer, int copyKey, LevelChunk chunk) {
        try {
            return internalCall(set, finalizer, copyKey, chunk);
        } catch (Throwable e) {
            LOGGER.error("[FAWE-FABRIC] Error performing chunk call at {},{}", chunkX, chunkZ, e);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected <T extends Future<T>> T internalCall(IChunkSet set, Runnable finalizer, int copyKey, LevelChunk nmsChunk)
            throws Exception {
        final FabricGetBlocks_Copy copy = createCopy ? new FabricGetBlocks_Copy(chunkX, chunkZ, minY, maxY) : null;
        if (createCopy) {
            if (copies.containsKey(copyKey)) {
                throw new IllegalStateException("Copy key already used.");
            }
            copies.put(copyKey, copy);
        }

        final int minSection = getMinSectionPosition();
        final int maxSection = getMaxSectionPosition();
        final List<Runnable> syncTasks = new ArrayList<>();
        boolean anyBlocks = false;

        for (int layerNo = minSection; layerNo <= maxSection; layerNo++) {
            if (!set.hasSection(layerNo)) {
                continue;
            }
            final int sectionIndex = layerNo - minSection;
            char[] tmp = set.load(layerNo);
            final char[] setArr = new char[tmp.length];
            System.arraycopy(tmp, 0, setArr, 0, tmp.length);

            if (createCopy) {
                char[] before = update(sectionIndex, new char[4096], true);
                char[] beforeCopy = new char[4096];
                System.arraycopy(before, 0, beforeCopy, 0, 4096);
                copy.storeSection(sectionIndex, beforeCopy);
            }

            anyBlocks = true;
            syncTasks.add(() -> applySection(nmsChunk, sectionIndex, setArr));
        }

        final char[][] blockLight = set.getLight();
        final char[][] skyLight = set.getSkyLight();
        final int setMinSection = set.getMinSectionPosition();
        final int setMaxSection = set.getMaxSectionPosition();
        final boolean hasLight = blockLight != null || skyLight != null;
        final Map<BlockVector3, FaweCompoundTag> tiles = set.tiles();
        final boolean hasTiles = tiles != null && !tiles.isEmpty();
        final boolean finalAnyBlocks = anyBlocks;

        if (!anyBlocks && !hasLight && !hasTiles && set.getBiomes() == null) {
            if (finalizer != null) {
                finalizer.run();
            }
            return null;
        }

        // Single finalize task: lighting, heightmaps, dirty + light-correct flags.
        syncTasks.add(() -> {
            if (blockLight != null) {
                fillLightNibble(nmsChunk, blockLight, LightLayer.BLOCK, setMinSection, setMaxSection);
            }
            if (skyLight != null) {
                fillLightNibble(nmsChunk, skyLight, LightLayer.SKY, setMinSection, setMaxSection);
            }
            if (finalAnyBlocks) {
                try {
                    Heightmap.primeHeightmaps(nmsChunk, EnumSet.allOf(Heightmap.Types.class));
                } catch (Throwable t) {
                    LOGGER.warn("[FAWE-FABRIC] Failed to prime heightmaps at {},{}", chunkX, chunkZ, t);
                }
            }
            try {
                nmsChunk.setLightCorrect(true);
            } catch (Throwable ignored) {
            }
            markUnsaved(nmsChunk);
        });

        if (hasTiles) {
            syncTasks.add(() -> applyTiles(tiles));
        }

        Runnable callback = () -> {
            send();
            if (finalizer != null) {
                finalizer.run();
            }
        };
        return handleCallFinalizer(syncTasks, callback, finalizer);
    }

    /**
     * Apply one section's worth of staged ordinals to the live section. Runs on the server thread.
     */
    private void applySection(LevelChunk chunk, int sectionIndex, char[] setArr) {
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) {
            return;
        }
        LevelChunkSection section = sections[sectionIndex];
        if (section == null) {
            LOGGER.warn("[FAWE-FABRIC] Null section {} at chunk {},{}; skipping (v1 does not allocate sections)",
                    sectionIndex, chunkX, chunkZ);
            return;
        }
        for (int j = 0; j < 4096; j++) {
            char ordinal = setArr[j];
            if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = FabricBlockMapping.ordinalToNative(ordinal);
            if (state == null) {
                continue;
            }
            int x = j & 15;
            int z = (j >> 4) & 15;
            int y = (j >> 8) & 15;
            section.setBlockState(x, y, z, state, false);
        }
    }

    /**
     * Restore tile-entity NBT for the set's tiles. Runs on the server thread, after the blocks land.
     */
    private void applyTiles(Map<BlockVector3, FaweCompoundTag> tiles) {
        int bx = chunkX << 4;
        int bz = chunkZ << 4;
        for (Map.Entry<BlockVector3, FaweCompoundTag> entry : tiles.entrySet()) {
            BlockVector3 key = entry.getKey();
            int x = (key.x() & 15) + bx;
            int y = key.y();
            int z = (key.z() & 15) + bz;
            BlockPos pos = new BlockPos(x, y, z);
            try {
                net.minecraft.nbt.CompoundTag nbt = NBTConverter.toNative(entry.getValue().linTag());
                nbt.putInt("x", x);
                nbt.putInt("y", y);
                nbt.putInt("z", z);
                BlockEntity be = serverLevel.getBlockEntity(pos);
                if (be != null) {
                    FabricAdapter.loadBlockEntityNbt(be, nbt, serverLevel.registryAccess());
                }
            } catch (Throwable t) {
                LOGGER.warn("[FAWE-FABRIC] Failed to restore tile entity at {},{},{}", x, y, z, t);
            }
        }
    }

    private void fillLightNibble(LevelChunk chunk, char[][] light, LightLayer layer, int minSection, int maxSection) {
        LevelLightEngine lightEngine = serverLevel.getChunkSource().getLightEngine();
        for (int Y = 0; Y <= maxSection - minSection; Y++) {
            if (Y >= light.length || light[Y] == null) {
                continue;
            }
            SectionPos sectionPos = SectionPos.of(chunk.getPos(), Y + minSection);
            DataLayer dataLayer = lightEngine.getLayerListener(layer).getDataLayerData(sectionPos);
            if (dataLayer == null) {
                byte[] bytes = new byte[2048];
                Arrays.fill(bytes, layer == LightLayer.SKY ? (byte) 15 : (byte) 0);
                dataLayer = new DataLayer(bytes);
                lightEngine.queueSectionData(layer, sectionPos, dataLayer);
            }
            synchronized (dataLayer) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int i = y << 8 | z << 4 | x;
                            if (light[Y][i] < 16) {
                                dataLayer.set(x, y, z, light[Y][i]);
                            }
                        }
                    }
                }
            }
        }
    }

    private void markUnsaved(ChunkAccess chunk) {
        try {
            chunk.setUnsaved(true);
        } catch (Throwable t) {
            LOGGER.error("[FAWE-FABRIC] Could not mark chunk {},{} unsaved — the paste may not persist across save/reload. "
                    + "Report this; a reflective fallback for setUnsaved is needed on this runtime.", chunkX, chunkZ, t);
        }
    }

    protected void send() {
        FabricChunkSender.sendChunk(serverLevel, chunkX, chunkZ);
    }

    /**
     * Chain the server-thread sync tasks and the (off-thread) callback, returning the sync future so the
     * STQE tracks the in-flight apply for backpressure. Mirrors {@code AbstractBukkitGetBlocks#handleCallFinalizer}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected <T extends Future<T>> T handleCallFinalizer(List<Runnable> syncTasks, Runnable callback, Runnable finalizer)
            throws Exception {
        if (!syncTasks.isEmpty()) {
            QueueHandler queueHandler = Fawe.instance().getQueueHandler();
            java.util.concurrent.Callable<Future<?>> chain = () -> {
                try {
                    for (Runnable task : syncTasks) {
                        if (task != null) {
                            task.run();
                        }
                    }
                    if (callback != null) {
                        return queueHandler.async(callback, null);
                    } else if (finalizer != null) {
                        return queueHandler.async(finalizer, null);
                    }
                    return null;
                } catch (Throwable e) {
                    LOGGER.error("[FAWE-FABRIC] Error performing final chunk call at {},{}", chunkX, chunkZ, e);
                    throw e;
                }
            };
            return (T) (Future) queueHandler.sync(chain);
        }
        if (callback != null) {
            callback.run();
        } else if (finalizer != null) {
            finalizer.run();
        }
        return null;
    }
}
