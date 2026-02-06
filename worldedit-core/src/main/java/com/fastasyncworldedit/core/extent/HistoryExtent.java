package com.fastasyncworldedit.core.extent;

import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet;
import com.fastasyncworldedit.core.math.MutableBlockVector3;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.RegionMaskingFilter;
import com.sk89q.worldedit.function.block.BlockReplace;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.visitor.RegionVisitor;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Stores changes to a {@link ChangeSet}.
 */
public class HistoryExtent extends AbstractDelegateExtent {

    private final MutableBlockVector3 mutable = new MutableBlockVector3();
    private AbstractChangeSet changeSet;

    /**
     * Create a new instance.
     *
     * @param extent    the extent
     * @param changeSet the change set
     */
    public HistoryExtent(Extent extent, AbstractChangeSet changeSet) {
        super(extent);
        checkNotNull(changeSet);
        this.changeSet = changeSet;
    }

    public AbstractChangeSet getChangeSet() {
        return changeSet;
    }

    @Override
    public void setChangeSet(AbstractChangeSet fcs) {
        this.changeSet = fcs;
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(int x, int y, int z, B block) throws WorldEditException {
        BaseBlock previous = getFullBlock(x, y, z);
        if (previous.getInternalId() == block.getInternalId()) {
            if (!previous.hasNbtData() && block instanceof BaseBlock && !block.hasNbtData()) {
                return false;
            }
        }
        this.changeSet.add(x, y, z, previous, block.toBaseBlock());
        return getExtent().setBlock(x, y, z, block);
    }


    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 location, B block) throws WorldEditException {
        return setBlock(location.x(), location.y(), location.z(), block);
    }

    @Override
    public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(block);

        int changes = 0;
        for (BlockVector3 pos : region) {
            if (setBlock(pos, block)) {
                changes++;
            }
        }
        return changes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        if (pattern instanceof BlockPattern) {
            return setBlocks(region, ((BlockPattern) pattern).getBlock());
        }

        int count = 0;
        for (BlockVector3 pos : region) {
            if (pattern.apply(this, pos, pos)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int setBlocks(Set<BlockVector3> vset, Pattern pattern) {
        if (vset instanceof Region) {
            return setBlocks((Region) vset, pattern);
        }
        int count = 0;
        for (BlockVector3 pos : vset) {
            if (pattern.apply(this, pos, pos)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public <B extends BlockStateHolder<B>> int replaceBlocks(Region region, Set<BaseBlock> filter, B replacement)
            throws MaxChangedBlocksException {
        return replaceBlocks(region, filter, (Pattern) replacement);
    }

    @Override
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) throws MaxChangedBlocksException {
        Mask mask = filter == null ? new ExistingBlockMask(this) : new BlockMask(this, filter);
        return replaceBlocks(region, mask, pattern);
    }

    @Override
    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(mask);
        checkNotNull(pattern);

        BlockReplace replace = new BlockReplace(this, pattern);
        RegionMaskingFilter filter = new RegionMaskingFilter(mask, replace);
        RegionVisitor visitor = new RegionVisitor(region, filter, this);
        Operations.completeLegacy(visitor);
        return visitor.getAffected();
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity state) {
        final Entity entity = super.createEntity(location, state);
        if (state != null) {
            this.changeSet.addEntityCreate(FaweCompoundTag.of(state.getNbt()));
        }
        return entity;
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity state, UUID uuid) {
        final Entity entity = super.createEntity(location, state, uuid);
        if (state != null) {
            this.changeSet.addEntityCreate(FaweCompoundTag.of(state.getNbt()));
        }
        return entity;
    }

    @Override
    public List<? extends Entity> getEntities() {
        return this.wrapEntities(super.getEntities());
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        return this.wrapEntities(super.getEntities(region));
    }

    private List<? extends Entity> wrapEntities(List<? extends Entity> entities) {
        final List<Entity> newList = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            newList.add(new TrackedEntity(entity));
        }
        return newList;
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType newBiome) {
        BiomeType oldBiome = this.getBiome(position);
        if (!oldBiome.id().equals(newBiome.id())) {
            this.changeSet.addBiomeChange(position.x(), position.y(), position.z(), oldBiome, newBiome);
            return getExtent().setBiome(position, newBiome);
        } else {
            return false;
        }
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType newBiome) {
        BiomeType oldBiome = this.getBiome(mutable.setComponents(x, y, z));
        if (!oldBiome.id().equals(newBiome.id())) {
            this.changeSet.addBiomeChange(x, y, z, oldBiome, newBiome);
            return getExtent().setBiome(x, y, z, newBiome);
        } else {
            return false;
        }
    }

    public class TrackedEntity implements Entity {

        private final Entity entity;

        private TrackedEntity(Entity entity) {
            this.entity = entity;
        }

        @Override
        public BaseEntity getState() {
            return this.entity.getState();
        }

        @Override
        public Location getLocation() {
            return this.entity.getLocation();
        }

        @Override
        public Extent getExtent() {
            return this.entity.getExtent();
        }

        @Override
        public boolean remove() {
            final BaseEntity state = this.entity.getState();
            final boolean success = this.entity.remove();
            if (state != null && success) {
                HistoryExtent.this.changeSet.addEntityRemove(FaweCompoundTag.of(state.getNbt()));
            }
            return success;
        }

        @Nullable
        @Override
        public <T> T getFacet(Class<? extends T> cls) {
            return this.entity.getFacet(cls);
        }

        @Override
        public boolean setLocation(Location location) {
            return this.entity.setLocation(location);
        }

    }

    @Override
    public Extent disableHistory() {
        return getExtent();
    }

}
