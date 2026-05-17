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
import com.sk89q.worldedit.function.mask.AbstractMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.Mask2D;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Mask that excludes positions inside chunks already marked as completed. Used by the
 * paste-resume system as a {@code //gmask}-equivalent so a resumed paste skips chunks
 * that the previous attempt already wrote.
 *
 * <p>Returns {@code true} (= "this block should be modified") only when the position's
 * chunk is NOT in the completed set.
 */
public final class ChunkExclusionMask extends AbstractMask {

    private final Set<Long> excludedChunks;

    /**
     * Build a mask from a packed completed-chunk array {@code [x0, z0, x1, z1, ...]}
     * (matching {@link PasteResumeState#completedChunks}).
     */
    public ChunkExclusionMask(int[] packed) {
        Set<Long> set = new HashSet<>(Math.max(16, packed.length));
        for (int i = 0; i + 1 < packed.length; i += 2) {
            set.add(EditSession.packChunkKey(packed[i], packed[i + 1]));
        }
        this.excludedChunks = set;
    }

    private ChunkExclusionMask(Set<Long> excludedChunks) {
        this.excludedChunks = excludedChunks;
    }

    @Override
    public boolean test(BlockVector3 vector) {
        long key = EditSession.packChunkKey(vector.getBlockX() >> 4, vector.getBlockZ() >> 4);
        return !excludedChunks.contains(key);
    }

    @Override
    public Mask copy() {
        // The excluded chunk set is effectively immutable after construction (we don't
        // mutate it anywhere). Returning the same instance is safe and avoids copying
        // a potentially large set.
        return this;
    }

    @Nullable
    @Override
    public Mask2D toMask2D() {
        // Mask2D has TWO abstract methods (test + copy2D) so it isn't a functional
        // interface - return a real implementation rather than a lambda.
        final Set<Long> excl = this.excludedChunks;
        return new Mask2D() {
            @Override
            public boolean test(BlockVector2 vector) {
                long key = EditSession.packChunkKey(vector.x() >> 4, vector.z() >> 4);
                return !excl.contains(key);
            }

            @Override
            public Mask2D copy2D() {
                return this;
            }
        };
    }

    public int excludedChunkCount() {
        return excludedChunks.size();
    }
}
