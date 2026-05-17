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

import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.registry.ItemCategoryRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public class FabricItemCategoryRegistry implements ItemCategoryRegistry {
    @Override
    public Set<ItemType> getCategorisedByName(String category) {
        // Registry.getTag(TagKey) was removed in 1.21.11 -> use reflective tag stream helper.
        try {
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, ResourceLocation.parse(category));
            return FabricAdapter.registryStreamTagValues(FabricWorldEdit.getRegistry(Registries.ITEM), tagKey)
                .map(FabricAdapter::adapt)
                .collect(Collectors.toSet());
        } catch (LinkageError | RuntimeException e) {
            return Collections.emptySet();
        }
    }
}
