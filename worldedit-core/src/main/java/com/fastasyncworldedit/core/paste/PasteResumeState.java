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

package com.fastasyncworldedit.core.paste;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Persistent state for an in-progress //paste operation, enabling resume after a crash.
 *
 * <p>One file per player at {@code config/worldedit/resume-state/<player-uuid>.json}.
 * Contains the clipboard fingerprint, target paste position, paste flags, the set of
 * chunks that have been (at least partially) written, and bookkeeping for UI.
 *
 * <p>Chunks are stored as a packed int array {@code [x0, z0, x1, z1, ...]} for compactness.
 * For pastes that touch ~100k chunks this is much smaller than an array of objects.
 */
public final class PasteResumeState {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String STATE_DIR_NAME = "resume-state";
    private static final String STATE_FILE_EXTENSION = ".json";

    // Schema version for forward-compat. Bump if the JSON shape changes.
    public int version = 1;

    public String playerUuid;

    // Clipboard fingerprint - we accept resume only if the current clipboard matches.
    public int clipboardVolume;
    public int clipboardMinX, clipboardMinY, clipboardMinZ;
    public int clipboardMaxX, clipboardMaxY, clipboardMaxZ;
    public int clipboardOriginX, clipboardOriginY, clipboardOriginZ;

    // Paste target position (player's stand point at paste time, or clipboard origin if -o).
    public int targetX, targetY, targetZ;

    // Paste flags (mirror of //paste switches; for re-issuing).
    public boolean ignoreAirBlocks;
    public boolean atOrigin;
    public boolean pasteEntities;
    public boolean pasteBiomes;
    public boolean removeEntities;
    public boolean ignoreStructureVoid;

    // Packed completed-chunk array: pairs of (chunkX, chunkZ).
    public int[] completedChunks = new int[0];

    // Bookkeeping for UI / staleness detection.
    public long savedAtMs;
    public long startedAtMs;
    public int blocksPlaced;
    public int totalBlocksExpected;

    // World id for sanity-check ("did the player change dimensions?")
    @Nullable
    public String worldId;

    public PasteResumeState() {
    }

    public static Path stateDir() {
        return WorldEdit.getInstance().getWorkingDirectoryPath(STATE_DIR_NAME);
    }

    public static Path stateFileFor(UUID playerUuid) {
        return stateDir().resolve(playerUuid.toString() + STATE_FILE_EXTENSION);
    }

    /**
     * Read the state file for a player, or null if no resume state exists / is unreadable.
     */
    @Nullable
    public static PasteResumeState load(UUID playerUuid) {
        Path file = stateFileFor(playerUuid);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = Files.readString(file);
            PasteResumeState s = GSON.fromJson(json, PasteResumeState.class);
            if (s == null || s.version != 1) {
                LOGGER.warn("[paste-resume] Ignoring state file with unsupported version: {}", file);
                return null;
            }
            return s;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[paste-resume] Failed to read state for {}: {}: {}",
                playerUuid, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Atomically write this state to the resume-state directory.
     * Writes to a temp file in the same directory, then renames over the target -
     * so a partial write never produces a corrupt main file.
     */
    public void save() {
        try {
            Path file = stateFileFor(UUID.fromString(playerUuid));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            String json = GSON.toJson(this);
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems (Windows on certain configs) don't allow ATOMIC_MOVE across edge cases.
                // Fall back to non-atomic replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            LOGGER.warn("[paste-resume] Failed to save state for {}: {}: {}",
                playerUuid, t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /**
     * Delete the persistent state file for this player. Called after a successful paste
     * completes (so we don't keep "resume me" hanging around for a finished operation).
     */
    public static void delete(UUID playerUuid) {
        try {
            Files.deleteIfExists(stateFileFor(playerUuid));
        } catch (IOException e) {
            LOGGER.warn("[paste-resume] Failed to delete state for {}: {}", playerUuid, e.getMessage());
        }
    }

    /** True if our fingerprint matches the supplied clipboard dimensions/origin. */
    public boolean clipboardMatches(int volume, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ,
                                    int originX, int originY, int originZ) {
        return clipboardVolume == volume
            && clipboardMinX == minX && clipboardMinY == minY && clipboardMinZ == minZ
            && clipboardMaxX == maxX && clipboardMaxY == maxY && clipboardMaxZ == maxZ
            && clipboardOriginX == originX && clipboardOriginY == originY && clipboardOriginZ == originZ;
    }

    public int completedChunkCount() {
        return completedChunks.length / 2;
    }
}
