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

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates paste-resume state across active paste operations.
 *
 * <p>Tracks one in-flight {@link PasteResumeState} per player UUID. A single background
 * scheduler flushes accumulated completed-chunk sets to disk every {@code SAVE_INTERVAL_MS}
 * so a hard JVM crash loses at most that much progress.
 *
 * <p>Lifecycle (called from the //paste command path):
 * <ol>
 *     <li>{@link #startTracking} - record initial state, enable chunk tracking on the EditSession</li>
 *     <li>(paste runs - the EditSession's setBlock hooks fill the chunk set)</li>
 *     <li>{@link #finishSuccess} - delete the state file on clean completion</li>
 *     <li>{@link #finishCrashed} - flush final state synchronously and stop tracking, but
 *         leave the state file in place for {@code //pasteresume}</li>
 * </ol>
 */
public final class PasteResumeManager {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final long SAVE_INTERVAL_MS = 2_000;

    private static final PasteResumeManager INSTANCE = new PasteResumeManager();

    public static PasteResumeManager instance() {
        return INSTANCE;
    }

    /** Active tracking entries, one per player UUID. */
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FAWE-PasteResume-Saver");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("FieldCanBeLocal")
    private final ScheduledFuture<?> saveTask;

    private PasteResumeManager() {
        this.saveTask = saver.scheduleAtFixedRate(this::flushAll,
            SAVE_INTERVAL_MS, SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** One active paste's tracking state. */
    private static final class Entry {
        final EditSession editSession;
        final PasteResumeState state;
        volatile boolean finished = false;

        Entry(EditSession editSession, PasteResumeState state) {
            this.editSession = editSession;
            this.state = state;
        }
    }

    /**
     * Begin tracking a paste operation. The supplied {@link PasteResumeState} should
     * already have fingerprint + flags filled in by the caller. We enable chunk
     * tracking on the EditSession and write the initial state file.
     */
    public void startTracking(UUID playerUuid, EditSession editSession, PasteResumeState state) {
        editSession.enableResumeChunkTracking();
        state.savedAtMs = System.currentTimeMillis();
        state.startedAtMs = state.savedAtMs;
        state.save();
        entries.put(playerUuid, new Entry(editSession, state));
    }

    /**
     * Paste finished successfully - remove our state file. The user has no use for resume
     * info on a paste that already completed.
     */
    public void finishSuccess(UUID playerUuid) {
        Entry e = entries.remove(playerUuid);
        if (e == null) {
            return;
        }
        e.finished = true;
        PasteResumeState.delete(playerUuid);
    }

    /**
     * Paste crashed or was cancelled mid-flight - flush latest progress to disk synchronously
     * and stop tracking. The state file stays so {@code //pasteresume} can find it.
     */
    public void finishCrashed(UUID playerUuid) {
        Entry e = entries.remove(playerUuid);
        if (e == null) {
            return;
        }
        e.finished = true;
        flushEntry(e);
    }

    /** Periodic flush task. Walks every active entry and writes its state if it has changed. */
    private void flushAll() {
        try {
            Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Entry> me = it.next();
                Entry e = me.getValue();
                if (e.finished) {
                    it.remove();
                    continue;
                }
                flushEntry(e);
            }
        } catch (Throwable t) {
            LOGGER.warn("[paste-resume] flushAll error: {}: {}", t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Copy current chunk-set + counters out of the EditSession into the state and write it. */
    private void flushEntry(Entry e) {
        try {
            Set<Long> chunks = e.editSession.getResumeChunks();
            if (chunks == null) {
                // tracking somehow got disabled - nothing to write
                return;
            }
            // Snapshot the set to avoid concurrent mutation during pack.
            long[] keys = chunks.stream().mapToLong(Long::longValue).toArray();
            int[] packed = new int[keys.length * 2];
            for (int i = 0; i < keys.length; i++) {
                packed[i * 2] = EditSession.unpackChunkX(keys[i]);
                packed[i * 2 + 1] = EditSession.unpackChunkZ(keys[i]);
            }
            e.state.completedChunks = packed;
            e.state.savedAtMs = System.currentTimeMillis();
            e.state.blocksPlaced = e.editSession.getBlockChangeCount();
            e.state.save();
        } catch (Throwable t) {
            LOGGER.warn("[paste-resume] flushEntry error: {}: {}", t.getClass().getSimpleName(), t.getMessage());
        }
    }
}
