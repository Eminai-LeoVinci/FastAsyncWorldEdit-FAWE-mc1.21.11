package com.fastasyncworldedit.fabric;

import com.sk89q.worldedit.fabric.internal.FabricTransmogrifier;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional cache between FAWE block ordinals (the {@code char} ids used inside the
 * {@code CharBlocks} arrays) and native Minecraft {@link net.minecraft.world.level.block.state.BlockState}
 * instances.
 *
 * <p>This intentionally does <b>not</b> use the global block-state palette id ({@code Block.getId})
 * the way the Paper adapter does — those ids are not trustworthy on this Fabric port (see
 * {@code FabricAdapter}). Every conversion goes through {@link FabricTransmogrifier} property-by-property,
 * but the result is memoised so the cost is only paid once per distinct state.</p>
 */
public final class FabricBlockMapping {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    // Indexed by FAWE ordinal. Lazily populated; sized once to the ordinal count.
    private static volatile net.minecraft.world.level.block.state.BlockState[] ORDINAL_TO_NATIVE;
    // Native state -> FAWE ordinal. Native block states are interned singletons, so the map stays small.
    private static final ConcurrentHashMap<net.minecraft.world.level.block.state.BlockState, Character> NATIVE_TO_ORDINAL =
            new ConcurrentHashMap<>();

    private static volatile boolean warnedTransmogToNative = false;
    private static volatile boolean warnedTransmogToWe = false;

    private FabricBlockMapping() {
    }

    private static net.minecraft.world.level.block.state.BlockState[] table() {
        net.minecraft.world.level.block.state.BlockState[] table = ORDINAL_TO_NATIVE;
        if (table == null) {
            synchronized (FabricBlockMapping.class) {
                table = ORDINAL_TO_NATIVE;
                if (table == null) {
                    table = ORDINAL_TO_NATIVE = new net.minecraft.world.level.block.state.BlockState[BlockTypesCache.states.length];
                }
            }
        }
        return table;
    }

    /**
     * Convert a FAWE ordinal to a native block state, or {@code null} if the ordinal cannot be mapped
     * (e.g. an unmapped/renamed block id across the 1.21.1&lt;-&gt;1.21.11 boundary). A {@code null} return
     * means the caller should skip that cell rather than place a wrong block.
     */
    public static net.minecraft.world.level.block.state.BlockState ordinalToNative(char ordinal) {
        net.minecraft.world.level.block.state.BlockState[] table = table();
        if (ordinal >= table.length) {
            return null;
        }
        net.minecraft.world.level.block.state.BlockState state = table[ordinal];
        if (state != null) {
            return state;
        }
        try {
            state = FabricTransmogrifier.transmogToMinecraft(BlockTypesCache.states[ordinal]);
        } catch (Throwable t) {
            if (!warnedTransmogToNative) {
                warnedTransmogToNative = true;
                LOGGER.warn("[FAWE-FABRIC] Could not map FAWE ordinal {} to a native block state; cells using it will be skipped",
                        (int) ordinal, t);
            }
            return null;
        }
        table[ordinal] = state;
        return state;
    }

    /**
     * Convert a native block state to a FAWE ordinal, falling back to AIR on failure.
     */
    public static char nativeToOrdinal(net.minecraft.world.level.block.state.BlockState state) {
        Character cached = NATIVE_TO_ORDINAL.get(state);
        if (cached != null) {
            return cached;
        }
        char ordinal;
        try {
            ordinal = FabricTransmogrifier.transmogToWorldEdit(state).getOrdinalChar();
        } catch (Throwable t) {
            if (!warnedTransmogToWe) {
                warnedTransmogToWe = true;
                LOGGER.warn("[FAWE-FABRIC] Could not map a native block state to a FAWE ordinal; treating as air", t);
            }
            ordinal = (char) BlockTypesCache.ReservedIDs.AIR;
        }
        NATIVE_TO_ORDINAL.put(state, ordinal);
        return ordinal;
    }
}
