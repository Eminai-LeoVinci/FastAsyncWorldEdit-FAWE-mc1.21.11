package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

/**
 * Read-only history snapshot used for undo. Holds the pre-edit block ordinals captured before the
 * native queue applied a change. Reads are served directly from the {@code CharBlocks} arrays; this
 * copy never touches the live world.
 *
 * <p>v1 scope: blocks only. Biome/light/tile/entity restoration on undo is not captured here yet — the
 * block-level undo is the common case for schematic pastes.</p>
 */
public class FabricGetBlocks_Copy extends CharGetBlocks {

    private final int minY;
    private final int maxY;

    protected FabricGetBlocks_Copy(int chunkX, int chunkZ, int minY, int maxY) {
        super(minY >> 4, maxY >> 4);
        this.minY = minY;
        this.maxY = maxY;
        init(chunkX, chunkZ);
    }

    void storeSection(int index, char[] ordinals) {
        if (index >= 0 && index < this.blocks.length) {
            this.blocks[index] = ordinals;
        }
    }

    @Nullable
    @Override
    public <T extends Future<T>> T call(IQueueExtent<? extends IChunk> owner, IChunkSet set, Runnable finalize) {
        return null;
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
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
        return Collections.emptyMap();
    }

    @Nullable
    @Override
    public FaweCompoundTag tile(int x, int y, int z) {
        return null;
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
        return Collections.emptySet();
    }

    @Override
    public boolean isCreateCopy() {
        return false;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        return -1;
    }

    @Override
    public void setLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setSkyLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
