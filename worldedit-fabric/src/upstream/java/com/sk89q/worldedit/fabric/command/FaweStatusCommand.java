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

package com.sk89q.worldedit.fabric.command;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sk89q.worldedit.EditSession;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Lightweight chat-based FAWE queue status command. Run /fawestatus (or /fs)
 * during long copy/paste operations to see what's flowing through FAWE's
 * various work queues.
 *
 * <p>FAWE has four distinct work paths inside {@link QueueHandler}:
 * <ul>
 *   <li>{@code blockingExecutor} - ThreadPoolExecutor for final chunk submission</li>
 *   <li>{@code forkJoinPoolPrimary} - CPU-bound chunk processing (paste, set, etc.)</li>
 *   <li>{@code forkJoinPoolSecondary} - IO-bound cleanup tasks</li>
 *   <li>{@code syncTasks}/{@code syncWhenFree} - main-thread tasks</li>
 * </ul>
 *
 * <p>We surface counters from all four so you can see where work is actually
 * happening regardless of which path FAWE chose for your edit.
 */
public final class FaweStatusCommand {

    private FaweStatusCommand() {
    }

    // Cached reflective accessors for QueueHandler's private fields.
    private static volatile Field FIELD_BLOCKING_EXEC;
    private static volatile Field FIELD_FJ_PRIMARY;
    private static volatile Field FIELD_FJ_SECONDARY;
    private static volatile Field FIELD_SYNC_TASKS;
    private static volatile Field FIELD_SYNC_WHEN_FREE;

    private static final ConcurrentHashMap<UUID, Sample> LAST_SAMPLE = new ConcurrentHashMap<>();

    /**
     * One snapshot of cumulative work-done counters at a given moment.
     * Used to compute deltas (and chunks-per-second) between successive /fs invocations.
     */
    private record Sample(long timestampMs, long blockingCompleted, long fjPrimarySteals, long fjSecondarySteals,
                          int editSessionBlocks) {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("fawestatus")
                .executes(ctx -> {
                    report(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                })
        );
        dispatcher.register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("fs")
                .executes(ctx -> {
                    report(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                })
        );
    }

    private static void report(CommandSourceStack src) {
        try {
            QueueHandler handler = (Fawe.instance() == null) ? null : Fawe.instance().getQueueHandler();
            if (handler == null) {
                src.sendSystemMessage(line("[FAWE] Queue not initialised yet.", ChatFormatting.YELLOW));
                return;
            }

            ThreadPoolExecutor blocking = (ThreadPoolExecutor) get(handler, fieldBlocking());
            ForkJoinPool fjPrimary = (ForkJoinPool) get(handler, fieldFjPrimary());
            ForkJoinPool fjSecondary = (ForkJoinPool) get(handler, fieldFjSecondary());
            Queue<?> syncTasks = (Queue<?>) get(handler, fieldSyncTasks());
            Queue<?> syncWhenFree = (Queue<?>) get(handler, fieldSyncWhenFree());

            // Snapshot all cumulative counters atomically-ish (best effort).
            long blockingCompleted = blocking.getCompletedTaskCount();
            long fjPrimarySteals = fjPrimary.getStealCount();
            long fjSecondarySteals = fjSecondary.getStealCount();
            long now = System.currentTimeMillis();

            UUID id = src.getEntity() != null ? src.getEntity().getUUID() : new UUID(0L, 0L);
            EditSession activeEdit = EditSession.getActiveFor(id);
            int editBlocks = activeEdit != null ? activeEdit.getBlockChangeCount() : -1;
            int expectedBlocks = activeEdit != null ? activeEdit.getExpectedBlockChanges() : -1;

            Sample prev = LAST_SAMPLE.put(id,
                new Sample(now, blockingCompleted, fjPrimarySteals, fjSecondarySteals, editBlocks));

            // Line 1: blocking executor (final chunk submission path)
            src.sendSystemMessage(line(
                String.format("[FAWE] BlockingExec   Active:%d/%d  Queued:%d  Done:%d  Pool:%d/%d",
                    blocking.getActiveCount(),
                    blocking.getMaximumPoolSize(),
                    blocking.getQueue().size(),
                    blockingCompleted,
                    blocking.getPoolSize(),
                    blocking.getMaximumPoolSize()),
                ChatFormatting.AQUA
            ));
            // Line 2: primary fork-join (CPU-bound chunk processing - this is where //paste usually lives)
            src.sendSystemMessage(line(
                String.format("[FAWE] ForkJoinPri   Active:%d/%d  Subm:%d  TaskQ:%d  Steals:%d",
                    fjPrimary.getActiveThreadCount(),
                    fjPrimary.getParallelism(),
                    fjPrimary.getQueuedSubmissionCount(),
                    fjPrimary.getQueuedTaskCount(),
                    fjPrimarySteals),
                ChatFormatting.GREEN
            ));
            // Line 3: secondary fork-join (IO-bound cleanup)
            src.sendSystemMessage(line(
                String.format("[FAWE] ForkJoinSec   Active:%d/%d  Subm:%d  TaskQ:%d  Steals:%d",
                    fjSecondary.getActiveThreadCount(),
                    fjSecondary.getParallelism(),
                    fjSecondary.getQueuedSubmissionCount(),
                    fjSecondary.getQueuedTaskCount(),
                    fjSecondarySteals),
                ChatFormatting.DARK_GREEN
            ));
            // Line 4: main-thread sync queues
            src.sendSystemMessage(line(
                String.format("[FAWE] SyncMain     Tasks:%d  WhenFree:%d",
                    syncTasks.size(), syncWhenFree.size()),
                ChatFormatting.GOLD
            ));
            // Line 5: live EditSession - this catches small/synchronous edits that bypass the executors
            if (editBlocks >= 0) {
                String pctStr;
                if (expectedBlocks > 0) {
                    double pct = Math.min(100.0, (editBlocks * 100.0) / expectedBlocks);
                    pctStr = String.format("  %.1f%% of %d", pct, expectedBlocks);
                } else {
                    pctStr = "  (no expected total set)";
                }
                src.sendSystemMessage(line(
                    String.format("[FAWE] EditSession  BlocksChanged:%d%s",
                        editBlocks, pctStr),
                    ChatFormatting.LIGHT_PURPLE
                ));
            } else {
                src.sendSystemMessage(line(
                    "[FAWE] EditSession  (no active edit registered for you)",
                    ChatFormatting.DARK_GRAY
                ));
            }

            // Delta line - only meaningful on second+ invocation
            if (prev != null) {
                double deltaSeconds = (now - prev.timestampMs()) / 1000.0;
                if (deltaSeconds > 0.05) {
                    long deltaBlocking = blockingCompleted - prev.blockingCompleted();
                    long deltaFjPrimary = fjPrimarySteals - prev.fjPrimarySteals();
                    long deltaFjSecondary = fjSecondarySteals - prev.fjSecondarySteals();
                    String editDelta = "";
                    if (editBlocks >= 0 && prev.editSessionBlocks() >= 0) {
                        long deltaBlocks = editBlocks - prev.editSessionBlocks();
                        double blockRate = deltaBlocks / deltaSeconds;
                        String etaStr = "";
                        if (expectedBlocks > 0 && blockRate > 0 && editBlocks < expectedBlocks) {
                            long etaSec = Math.round((expectedBlocks - editBlocks) / blockRate);
                            etaStr = String.format("   ETA ~%ds", etaSec);
                        }
                        editDelta = String.format("   edit +%d (%.0f blocks/s)%s",
                            deltaBlocks, blockRate, etaStr);
                    } else if (editBlocks >= 0) {
                        editDelta = String.format("   edit now at %d", editBlocks);
                    }
                    src.sendSystemMessage(line(
                        String.format("       Since last /fs (%.1fs):  blocking +%d   fjPri +%d   fjSec +%d%s",
                            deltaSeconds, deltaBlocking, deltaFjPrimary, deltaFjSecondary, editDelta),
                        ChatFormatting.GRAY
                    ));
                }
            } else {
                src.sendSystemMessage(line(
                    "       (baseline recorded - run /fs again for a delta reading)",
                    ChatFormatting.DARK_GRAY
                ));
            }
        } catch (Throwable t) {
            src.sendSystemMessage(line(
                "[FAWE] Status error: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                ChatFormatting.RED
            ));
        }
    }

    // --- private reflective helpers ---

    private static Object get(Object target, Field f) throws ReflectiveOperationException {
        return f.get(target);
    }

    private static Field fieldBlocking() throws NoSuchFieldException {
        Field f = FIELD_BLOCKING_EXEC;
        if (f == null) {
            f = QueueHandler.class.getDeclaredField("blockingExecutor");
            f.setAccessible(true);
            FIELD_BLOCKING_EXEC = f;
        }
        return f;
    }

    private static Field fieldFjPrimary() throws NoSuchFieldException {
        Field f = FIELD_FJ_PRIMARY;
        if (f == null) {
            f = QueueHandler.class.getDeclaredField("forkJoinPoolPrimary");
            f.setAccessible(true);
            FIELD_FJ_PRIMARY = f;
        }
        return f;
    }

    private static Field fieldFjSecondary() throws NoSuchFieldException {
        Field f = FIELD_FJ_SECONDARY;
        if (f == null) {
            f = QueueHandler.class.getDeclaredField("forkJoinPoolSecondary");
            f.setAccessible(true);
            FIELD_FJ_SECONDARY = f;
        }
        return f;
    }

    private static Field fieldSyncTasks() throws NoSuchFieldException {
        Field f = FIELD_SYNC_TASKS;
        if (f == null) {
            f = QueueHandler.class.getDeclaredField("syncTasks");
            f.setAccessible(true);
            FIELD_SYNC_TASKS = f;
        }
        return f;
    }

    private static Field fieldSyncWhenFree() throws NoSuchFieldException {
        Field f = FIELD_SYNC_WHEN_FREE;
        if (f == null) {
            f = QueueHandler.class.getDeclaredField("syncWhenFree");
            f.setAccessible(true);
            FIELD_SYNC_WHEN_FREE = f;
        }
        return f;
    }

    private static MutableComponent line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color));
    }
}
