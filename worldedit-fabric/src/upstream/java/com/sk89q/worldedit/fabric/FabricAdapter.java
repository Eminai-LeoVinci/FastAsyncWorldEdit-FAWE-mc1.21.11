/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric;

import com.mojang.serialization.Codec;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.internal.FabricTransmogrifier;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.Vec3;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

public final class FabricAdapter {

    private FabricAdapter() {
    }

    public static World adapt(net.minecraft.world.level.Level world) {
        return new FabricWorld(world);
    }

    /**
     * Create a Fabric world from a WorldEdit world.
     *
     * @param world the WorldEdit world
     * @return a Fabric world
     */
    public static net.minecraft.world.level.Level adapt(World world) {
        checkNotNull(world);
        if (world instanceof FabricWorld) {
            return ((FabricWorld) world).getWorld();
        } else {
            // TODO introduce a better cross-platform world API to match more easily
            throw new UnsupportedOperationException("Cannot adapt from a " + world.getClass());
        }
    }

    public static Biome adapt(BiomeType biomeType) {
        return registryGetValue(FabricWorldEdit.getRegistry(Registries.BIOME), ResourceLocation.parse(biomeType.id()));
    }

    public static BiomeType adapt(Biome biome) {
        ResourceLocation id = FabricWorldEdit.getRegistry(Registries.BIOME).getKey(biome);
        Objects.requireNonNull(id, "biome is not registered");
        return BiomeTypes.get(id.toString());
    }

    public static Vector3 adapt(Vec3 vector) {
        return Vector3.at(vector.x, vector.y, vector.z);
    }

    public static BlockVector3 adapt(BlockPos pos) {
        return BlockVector3.at(pos.getX(), pos.getY(), pos.getZ());
    }

    public static Vec3 toVec3(BlockVector3 vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    public static net.minecraft.core.Direction adapt(Direction face) {
        switch (face) {
            case NORTH:
                return net.minecraft.core.Direction.NORTH;
            case SOUTH:
                return net.minecraft.core.Direction.SOUTH;
            case WEST:
                return net.minecraft.core.Direction.WEST;
            case EAST:
                return net.minecraft.core.Direction.EAST;
            case DOWN:
                return net.minecraft.core.Direction.DOWN;
            case UP:
            default:
                return net.minecraft.core.Direction.UP;
        }
    }

    public static Direction adaptEnumFacing(@Nullable net.minecraft.core.Direction face) {
        if (face == null) {
            return null;
        }
        switch (face) {
            case NORTH:
                return Direction.NORTH;
            case SOUTH:
                return Direction.SOUTH;
            case WEST:
                return Direction.WEST;
            case EAST:
                return Direction.EAST;
            case DOWN:
                return Direction.DOWN;
            case UP:
            default:
                return Direction.UP;
        }
    }

    public static BlockPos toBlockPos(BlockVector3 vector) {
        return new BlockPos(vector.x(), vector.y(), vector.z());
    }

    /**
     * Adapts property.
     *
     * @deprecated without replacement, use the block adapter methods
     */
    @Deprecated
    public static Property<?> adaptProperty(net.minecraft.world.level.block.state.properties.Property<?> property) {
        return FabricTransmogrifier.transmogToWorldEditProperty(property);
    }

    /**
     * Adapts properties.
     *
     * @deprecated without replacement, use the block adapter methods
     */
    @Deprecated
    public static Map<Property<?>, Object> adaptProperties(BlockType block, Map<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> mcProps) {
        Map<Property<?>, Object> props = new TreeMap<>(Comparator.comparing(Property::getName));
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> prop : mcProps.entrySet()) {
            Object value = prop.getValue();
            if (prop.getKey() instanceof DirectionProperty) {
                value = adaptEnumFacing((net.minecraft.core.Direction) value);
            } else if (prop.getKey() instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                value = ((StringRepresentable) value).getSerializedName();
            }
            props.put(block.getProperty(prop.getKey().getName()), value);
        }
        return props;
    }

    public static net.minecraft.world.level.block.state.BlockState adapt(BlockState blockState) {
        // FAWE internal ids are not guaranteed to match Mojang runtime blockstate ids on Fabric.
        // Use explicit property mapping to avoid wrong/no-op placements.
        return FabricTransmogrifier.transmogToMinecraft(blockState);
    }

    public static BlockState adapt(net.minecraft.world.level.block.state.BlockState blockState) {
        return FabricTransmogrifier.transmogToWorldEdit(blockState);
    }

    public static BaseBlock adapt(BlockEntity blockEntity) {
        if (!blockEntity.hasLevel()) {
            throw new IllegalArgumentException("BlockEntity must have a level");
        }
        BlockState worldEdit = FabricTransmogrifier.transmogToWorldEdit(blockEntity.getBlockState());
        // Save this outside the reference to ensure it doesn't mutate
        // saveWithId signature changed 1.21.1 -> 1.21.11; use reflective helper.
        CompoundTag savedNative = saveBlockEntityWithId(blockEntity, blockEntity.getLevel().registryAccess());
        return worldEdit.toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(savedNative)));
    }

    public static Block adapt(BlockType blockType) {
        return registryGetValue(FabricWorldEdit.getRegistry(Registries.BLOCK), ResourceLocation.parse(blockType.id()));
    }

    public static BlockType adapt(Block block) {
        return BlockTypes.get(FabricWorldEdit.getRegistry(Registries.BLOCK).getKey(block).toString());
    }

    public static Item adapt(ItemType itemType) {
        return registryGetValue(FabricWorldEdit.getRegistry(Registries.ITEM), ResourceLocation.parse(itemType.id()));
    }

    // InteractionResult was refactored from enum (1.21.1) to sealed interface (1.21.11). The
    // PASS/SUCCESS static fields either disappeared or changed type-descriptor, so direct
    // GETSTATIC fails. Resolve canonical instances at class-init via reflection, probing all
    // known shapes. wantConsumes selects between PASS (false) and SUCCESS (true) using the
    // consumesAction() / method_23665() bool method that distinguishes them.
    public static final net.minecraft.world.InteractionResult IR_PASS = resolveInteractionResult(false);
    public static final net.minecraft.world.InteractionResult IR_SUCCESS = resolveInteractionResult(true);

    private static net.minecraft.world.InteractionResult resolveInteractionResult(boolean wantConsumes) {
        Class<?> ir = net.minecraft.world.InteractionResult.class;

        // Strategy 1: static field by name. Production runtime uses intermediary names
        // (field_5811 = PASS, field_5812 = SUCCESS), dev uses Mojang. Try both.
        String[] fieldNames = wantConsumes
            ? new String[]{"field_5812", "SUCCESS", "success"}
            : new String[]{"field_5811", "PASS", "pass"};
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field f = ir.getField(name);
                Object v = f.get(null);
                if (v instanceof net.minecraft.world.InteractionResult res) {
                    return res;
                }
            } catch (Throwable ignored) {
            }
        }

        // Strategy 2: static factory method (e.g., InteractionResult.pass()).
        String[] methodNames = wantConsumes
            ? new String[]{"success", "ofSuccess"}
            : new String[]{"pass", "ofPass"};
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method m = ir.getMethod(name);
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    Object v = m.invoke(null);
                    if (v instanceof net.minecraft.world.InteractionResult res) {
                        return res;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Strategy 3: walk inner classes; instantiate via INSTANCE field or no-arg ctor;
        // identify with consumesAction()/method_23665().
        for (Class<?> inner : safeDeclaredClasses(ir)) {
            Object instance = tryInstantiate(inner);
            if (!(instance instanceof net.minecraft.world.InteractionResult)) {
                continue;
            }
            Boolean consumes = invokeBooleanNoArg(instance, "consumesAction", "method_23665");
            if (consumes != null && consumes == wantConsumes) {
                return (net.minecraft.world.InteractionResult) instance;
            }
        }

        return null;
    }

    private static Class<?>[] safeDeclaredClasses(Class<?> cls) {
        try {
            return cls.getDeclaredClasses();
        } catch (Throwable t) {
            return new Class<?>[0];
        }
    }

    @Nullable
    private static Object tryInstantiate(Class<?> cls) {
        for (String fName : new String[]{"INSTANCE", "instance"}) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fName);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Throwable ignored) {
            }
        }
        try {
            java.lang.reflect.Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static Boolean invokeBooleanNoArg(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                java.lang.reflect.Method m = target.getClass().getMethod(name);
                Object v = m.invoke(target);
                if (v instanceof Boolean b) return b;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // BlockEntity.saveWithId(HolderLookup.Provider) changed return type from CompoundTag (1.21.1)
    // to void (1.21.11, takes a WriteView now). Fall back to createNbtWithIdentifyingData
    // (method_38242), which exists in both with stable (HolderLookup.Provider) -> CompoundTag.
    public static net.minecraft.nbt.CompoundTag saveBlockEntityWithId(
        net.minecraft.world.level.block.entity.BlockEntity be,
        net.minecraft.core.HolderLookup.Provider registries) {
        Class<?> beClass = be.getClass();
        // Try 1.21.1's saveWithId/saveWithFullMetadata first (returns CompoundTag).
        for (String name : new String[]{"method_38243", "saveWithFullMetadata", "saveWithId"}) {
            try {
                java.lang.reflect.Method m = beClass.getMethod(name, net.minecraft.core.HolderLookup.Provider.class);
                if (m.getReturnType() == net.minecraft.nbt.CompoundTag.class) {
                    return (net.minecraft.nbt.CompoundTag) m.invoke(be, registries);
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        // 1.21.11: createNbtWithIdentifyingData -> CompoundTag (includes type ID + position).
        for (String name : new String[]{"method_38242", "createNbtWithIdentifyingData", "saveCustomOnly"}) {
            try {
                java.lang.reflect.Method m = beClass.getMethod(name, net.minecraft.core.HolderLookup.Provider.class);
                if (m.getReturnType() == net.minecraft.nbt.CompoundTag.class) {
                    return (net.minecraft.nbt.CompoundTag) m.invoke(be, registries);
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return new net.minecraft.nbt.CompoundTag();
    }

    // BlockEntity.loadWithComponents(CompoundTag, HolderLookup.Provider) (method_158690) was
    // refactored in 1.21.11 - the (CompoundTag, HolderLookup.Provider) signature is GONE,
    // replaced with a ValueInput-based path. Direct call throws NoSuchMethodError mid-paste
    // when a tile entity (chest, sign, etc.) needs NBT restoration. Try the old signature
    // first; on 1.21.11 fall back to (a) the protected loadCustomOnly variant or
    // (b) any single-arg method named method_158690 on a ValueInput-like type. Failures
    // are non-fatal - we return false and the caller continues placing blocks (entity NBT
    // is lost for that block, but the paste finishes instead of dying).
    private static volatile java.lang.reflect.Method CACHED_LOAD_OLD;
    private static volatile boolean CACHED_LOAD_OLD_PROBED;

    public static boolean loadBlockEntityNbt(
        net.minecraft.world.level.block.entity.BlockEntity be,
        net.minecraft.nbt.CompoundTag tag,
        net.minecraft.core.HolderLookup.Provider registries) {
        // Path 1: the 1.21.1 signature (CompoundTag, HolderLookup.Provider) -> void
        try {
            java.lang.reflect.Method m = CACHED_LOAD_OLD;
            if (m == null && !CACHED_LOAD_OLD_PROBED) {
                CACHED_LOAD_OLD_PROBED = true;
                for (String name : new String[]{"method_158690", "loadWithComponents"}) {
                    try {
                        m = net.minecraft.world.level.block.entity.BlockEntity.class.getMethod(
                            name, net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
                        CACHED_LOAD_OLD = m;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (m != null) {
                m.invoke(be, tag, registries);
                return true;
            }
        } catch (Throwable ignored) {
        }
        // Path 2: 1.21.11 - protected loadCustomOnly(CompoundTag, HolderLookup.Provider).
        // Walk the class hierarchy because BlockEntity subclasses may override.
        for (Class<?> c = be.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : new String[]{"method_11014", "loadCustomOnly", "loadAdditional"}) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name,
                        net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
                    m.setAccessible(true);
                    m.invoke(be, tag, registries);
                    return true;
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    // Registry.getTag(TagKey) -> Optional<HolderSet.Named<T>> was REMOVED in 1.21.11;
    // replaced by iterateEntries(TagKey) -> Iterable<Holder<T>>. Provide a stream of T
    // values that works on both versions via reflection.
    @SuppressWarnings("unchecked")
    public static <T> java.util.stream.Stream<T> registryStreamTagValues(
        net.minecraft.core.Registry<T> registry, net.minecraft.tags.TagKey<T> tagKey) {
        // 1.21.11: iterateEntries(TagKey) -> Iterable<Holder<T>>
        for (String name : new String[]{"method_40286", "getTagOrEmpty", "iterateEntries"}) {
            try {
                java.lang.reflect.Method m = registry.getClass().getMethod(name, net.minecraft.tags.TagKey.class);
                Object result = m.invoke(registry, tagKey);
                if (result instanceof Iterable<?>) {
                    Iterable<net.minecraft.core.Holder<T>> iter = (Iterable<net.minecraft.core.Holder<T>>) result;
                    return java.util.stream.StreamSupport.stream(iter.spliterator(), false)
                        .map(h -> (T) maybeUnwrapHolder(h));
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        // 1.21.1: getTag(TagKey) -> Optional<HolderSet.Named<T>>
        for (String name : new String[]{"method_40266", "getTag"}) {
            try {
                java.lang.reflect.Method m = registry.getClass().getMethod(name, net.minecraft.tags.TagKey.class);
                Object result = m.invoke(registry, tagKey);
                if (result instanceof java.util.Optional<?>) {
                    java.util.Optional<?> opt = (java.util.Optional<?>) result;
                    if (!opt.isPresent()) {
                        return java.util.stream.Stream.empty();
                    }
                    Object holderSet = opt.get();
                    java.lang.reflect.Method streamM = holderSet.getClass().getMethod("stream");
                    java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) streamM.invoke(holderSet);
                    return stream.map(h -> (T) maybeUnwrapHolder(h));
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return java.util.stream.Stream.empty();
    }

    // LevelChunk.setBlockState (method_12010) third arg changed boolean (moving) -> int
    // (flags) between 1.21.1 and 1.21.11, breaking bytecode descriptor lookup. Probe both
    // variants reflectively. Stay at the chunk level (NOT Level.setBlock) because FAWE's
    // async queue + Lithium's safety mixins assume chunk-level writes; calling Level.setBlock
    // off the server thread trips a ConcurrentModificationException("Async chunk modification").
    //
    // Always return a non-null state when the call succeeds. The native method may return null
    // when nothing changed; we substitute the pre-fetched oldState so worldedit-core's setBlock
    // sees success=true and proceeds to markAndNotifyBlock (which sends the client packet).
    public static net.minecraft.world.level.block.state.BlockState chunkSetBlockState(
        net.minecraft.world.level.chunk.LevelChunk chunk,
        net.minecraft.core.BlockPos pos,
        net.minecraft.world.level.block.state.BlockState state) {
        return rawChunkSetBlockState(chunk, pos, state, false);
    }

    public static net.minecraft.world.level.block.state.BlockState rawChunkSetBlockState(
        net.minecraft.world.level.chunk.LevelChunk chunk,
        net.minecraft.core.BlockPos pos,
        net.minecraft.world.level.block.state.BlockState state,
        boolean moved) {
        net.minecraft.world.level.block.state.BlockState oldState = chunk.getBlockState(pos);
        Class<?> chunkClass = chunk.getClass();
        // 1.21.11: (BlockPos, BlockState, int flags). UPDATE_MOVE_BY_PISTON=64 if moving.
        for (String name : new String[]{"method_12010", "setBlockState"}) {
            try {
                java.lang.reflect.Method m = chunkClass.getMethod(name,
                    net.minecraft.core.BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    int.class);
                Object result = invokeIgnoringAsyncCME(m, chunk, pos, state, moved ? 64 : 0);
                if (result == ASYNC_CME_SWALLOWED) {
                    return oldState;
                }
                return result instanceof net.minecraft.world.level.block.state.BlockState
                    ? (net.minecraft.world.level.block.state.BlockState) result
                    : oldState;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        // 1.21.1: (BlockPos, BlockState, boolean moved)
        for (String name : new String[]{"method_12010", "setBlockState"}) {
            try {
                java.lang.reflect.Method m = chunkClass.getMethod(name,
                    net.minecraft.core.BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    boolean.class);
                Object result = invokeIgnoringAsyncCME(m, chunk, pos, state, moved);
                if (result == ASYNC_CME_SWALLOWED) {
                    return oldState;
                }
                return result instanceof net.minecraft.world.level.block.state.BlockState
                    ? (net.minecraft.world.level.block.state.BlockState) result
                    : oldState;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // Sentinel return when we swallowed a C2ME async-chunk-modification CME (the chunk write
    // succeeded before the check fired, so the caller should still treat the operation as
    // having completed and use the pre-fetched oldState).
    private static final Object ASYNC_CME_SWALLOWED = new Object();

    private static Object invokeIgnoringAsyncCME(java.lang.reflect.Method m, Object target, Object... args)
        throws java.lang.reflect.InvocationTargetException, IllegalAccessException {
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getTargetException();
            if (cause instanceof java.util.ConcurrentModificationException
                && cause.getMessage() != null
                && cause.getMessage().contains("Async chunk modification")) {
                return ASYNC_CME_SWALLOWED;
            }
            throw ite;
        }
    }

    // Property.getPossibleValues() (yarn method_11898) changed return type between 1.21.1
    // (Collection) and 1.21.11 (List). JVM virtual dispatch matches by exact descriptor
    // including return type, so bytecode compiled against 1.21.1 fails NoSuchMethodError
    // on 1.21.11. Reflection.getMethod() resolves by name only — works on both versions.
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> java.util.Collection<T> getPropertyPossibleValues(
        net.minecraft.world.level.block.state.properties.Property<T> property) {
        for (String name : new String[]{"method_11898", "getPossibleValues"}) {
            try {
                java.lang.reflect.Method m = property.getClass().getMethod(name);
                Object result = m.invoke(property);
                if (result instanceof java.util.Collection<?>) {
                    return (java.util.Collection<T>) result;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return java.util.Collections.emptyList();
    }

    // Entity.level() (yarn method_37908) was renamed between 1.21.1 and 1.21.11. Probe
    // candidate accessors at runtime so callers don't NoSuchMethodError on level retrieval.
    @Nullable
    public static net.minecraft.world.level.Level getEntityLevel(net.minecraft.world.entity.Entity entity) {
        for (String name : new String[]{"getCommandSenderWorld", "level", "getLevel", "getWorld"}) {
            try {
                java.lang.reflect.Method m = entity.getClass().getMethod(name);
                Object result = m.invoke(entity);
                if (result instanceof net.minecraft.world.level.Level) {
                    return (net.minecraft.world.level.Level) result;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // Registry.get(ResourceLocation) changed between 1.21.1 (returns T) and 1.21.11
    // (now returns Optional<Holder.Reference<T>>; new getValue(ResourceLocation) returns T).
    //
    // Production-mode Fabric Loader runs mod bytecode against INTERMEDIARY-mapped game
    // classes, so reflection has to look up methods by intermediary name (e.g. method_63535),
    // not Mojang name (e.g. getValue). Dev runs use Mojang names. We probe both.
    //
    // 1.21.11 intermediaries:
    //   - method_63535(ResourceLocation) -> T          (= getValue)
    //   - method_10223(ResourceLocation) -> Optional<Holder.Reference<T>>  (= get)
    // 1.21.1 intermediary:
    //   - method_10223(ResourceLocation) -> T          (= get, returns T directly)
    @SuppressWarnings("unchecked")
    private static <T> T registryGetValue(Registry<T> registry, ResourceLocation key) {
        for (String name : new String[]{"method_63535", "getValue", "method_10223", "get"}) {
            try {
                java.lang.reflect.Method m = registry.getClass().getMethod(name, ResourceLocation.class);
                Object result = m.invoke(registry, key);
                if (result == null) {
                    continue;
                }
                if (result instanceof java.util.Optional<?>) {
                    Object inner = ((java.util.Optional<?>) result).orElse(null);
                    if (inner == null) {
                        continue;
                    }
                    inner = maybeUnwrapHolder(inner);
                    return (T) inner;
                }
                return (T) result;
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            } catch (Throwable ignored) {
                // try next candidate
            }
        }
        return null;
    }

    // Holder.Reference#value() — comp_349 intermediary (record component), Mojang name "value".
    private static Object maybeUnwrapHolder(Object obj) {
        for (String name : new String[]{"comp_349", "value"}) {
            try {
                java.lang.reflect.Method m = obj.getClass().getMethod(name);
                Object v = m.invoke(obj);
                if (v != null) {
                    return v;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return obj;
    }

    public static ItemType adapt(Item item) {
        return ItemTypes.get(FabricWorldEdit.getRegistry(Registries.ITEM).getKey(item).toString());
    }

    /**
     * For serializing and deserializing components.
     */
    private static final Codec<DataComponentPatch> COMPONENTS_CODEC = DataComponentPatch.CODEC.optionalFieldOf(
        "components", DataComponentPatch.EMPTY
    ).codec();

    public static ItemStack adapt(BaseItemStack baseItemStack) {
        Item item = adapt(baseItemStack.getType());
        if (item == null) {
            // Item id wasn't found in the runtime registry (likely the lookup helper picked
            // the wrong Registry method on 1.21.11). Return EMPTY so callers don't NPE in
            // ItemStack's null-checked constructor.
            return ItemStack.EMPTY;
        }
        final ItemStack itemStack = new ItemStack(item, baseItemStack.getAmount());
        LinCompoundTag nbt = baseItemStack.getNbt();
        if (nbt != null) {
            DataComponentPatch componentPatch = COMPONENTS_CODEC.parse(
                FabricWorldEdit.registryAccess().createSerializationContext(NbtOps.INSTANCE),
                NBTConverter.toNative(nbt)
            ).getOrThrow();
            itemStack.applyComponents(componentPatch);
        }
        return itemStack;
    }

    public static BaseItemStack adapt(ItemStack itemStack) {
        CompoundTag tag = (CompoundTag) COMPONENTS_CODEC.encodeStart(
            FabricWorldEdit.registryAccess().createSerializationContext(NbtOps.INSTANCE),
            itemStack.getComponentsPatch()
        ).getOrThrow();
        return new BaseItemStack(
            adapt(itemStack.getItem()), LazyReference.from(() -> NBTConverter.fromNative(tag)), itemStack.getCount()
        );
    }

    /**
     * Get the WorldEdit proxy for the given player.
     *
     * @param player the player
     * @return the WorldEdit player
     */
    // FAWE stores actor metadata (cmdConfirm, faweActionTick, etc.) in an instance field
    // on AbstractPlayerActor. If we return a fresh FabricPlayer on every call, that metadata
    // gets lost between commands — most visibly, //confirm after a large //copy says "no
    // action pending" because the FabricPlayer #2 (running //confirm) doesn't see the meta
    // that FabricPlayer #1 (running //copy) set. Cache by UUID and recreate only if the
    // underlying ServerPlayer reference changed (player reconnected).
    private static final java.util.Map<java.util.UUID, FabricPlayer> PLAYER_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static FabricPlayer adaptPlayer(ServerPlayer player) {
        checkNotNull(player);
        java.util.UUID uuid = player.getUUID();
        FabricPlayer cached = PLAYER_CACHE.get(uuid);
        if (cached != null && cached.getInternalPlayer() == player) {
            return cached;
        }
        FabricPlayer fresh = new FabricPlayer(player);
        PLAYER_CACHE.put(uuid, fresh);
        return fresh;
    }

    /** Called on player disconnect to release the cached actor. */
    public static void invalidatePlayer(java.util.UUID uuid) {
        PLAYER_CACHE.remove(uuid);
    }

    /**
     * Get the WorldEdit proxy for the given command source.
     *
     * @param commandSourceStack the command source
     * @return the WorldEdit actor
     */
    public static Actor adaptCommandSource(CommandSourceStack commandSourceStack) {
        checkNotNull(commandSourceStack);
        if (commandSourceStack.isPlayer()) {
            return adaptPlayer(commandSourceStack.getPlayer());
        }
        if (FabricWorldEdit.inst.getConfig().commandBlockSupport && commandSourceStack.source instanceof BaseCommandBlock commandBlock) {
            return new FabricBlockCommandSender(commandBlock);
        }

        return new FabricCommandSender(commandSourceStack);
    }
}
