package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.Fawe;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Resends a whole chunk (block + light data) to all tracking players. This is the Fabric analogue of
 * {@code PaperweightPlatformAdapter.sendChunk} and is the single per-chunk client update emitted by the
 * native queue (replacing the per-block packet spam of the legacy WorldNativeAccess path).
 *
 * <p>The actual packet build + send is always performed on the server thread; if called from a FAWE
 * worker it is marshalled via {@link net.minecraft.server.MinecraftServer#execute(Runnable)}.</p>
 */
public final class FabricChunkSender {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private static volatile Constructor<?> packetCtor;
    private static volatile int packetArgCount = -1;

    private FabricChunkSender() {
    }

    public static void sendChunk(ServerLevel level, int chunkX, int chunkZ) {
        Runnable task = () -> doSend(level, chunkX, chunkZ);
        if (Fawe.isMainThread()) {
            task.run();
        } else {
            level.getServer().execute(task);
        }
    }

    private static void doSend(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        Packet<?> packet = buildPacket(chunk, level.getLightEngine());
        if (packet == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(pos, false);
        for (ServerPlayer player : players) {
            try {
                player.connection.send(packet);
            } catch (Throwable ignored) {
                // a disconnecting player can throw; ignore and keep sending to the rest
            }
        }
    }

    private static Packet<?> buildPacket(LevelChunk chunk, LevelLightEngine lightEngine) {
        try {
            Constructor<?> ctor = packetCtor;
            if (ctor == null) {
                ctor = resolveCtor();
                if (ctor == null) {
                    LOGGER.error("[FAWE-FABRIC] Could not resolve a ClientboundLevelChunkWithLightPacket constructor");
                    return null;
                }
                packetCtor = ctor;
            }
            // BitSets are null (send full chunk); the trailing boolean (1.21.11) disables x-ray.
            if (packetArgCount >= 5) {
                return (Packet<?>) ctor.newInstance(chunk, lightEngine, null, null, false);
            }
            return (Packet<?>) ctor.newInstance(chunk, lightEngine, null, null);
        } catch (Throwable e) {
            LOGGER.error("[FAWE-FABRIC] Failed to build chunk packet", e);
            return null;
        }
    }

    private static Constructor<?> resolveCtor() {
        Constructor<?> best = null;
        int bestLen = -1;
        for (Constructor<?> c : ClientboundLevelChunkWithLightPacket.class.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            // Want (LevelChunk, LevelLightEngine, BitSet, BitSet[, boolean]) — prefer the longer (5-arg) form.
            if (params.length >= 4
                    && params[0] == LevelChunk.class
                    && params[1] == LevelLightEngine.class
                    && params.length > bestLen) {
                best = c;
                bestLen = params.length;
            }
        }
        if (best != null) {
            packetArgCount = bestLen;
        }
        return best;
    }
}
