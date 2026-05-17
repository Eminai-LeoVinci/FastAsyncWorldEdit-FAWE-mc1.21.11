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

package com.sk89q.worldedit.fabric.internal;

import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.StateDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Raw, un-cached transformations.
 */
public class FabricTransmogrifier {
    public static Property<?> transmogToWorldEditProperty(net.minecraft.world.level.block.state.properties.Property<?> property) {
        // Property.getPossibleValues() return type changed Collection -> List between
        // 1.21.1 and 1.21.11. Use FabricAdapter.getPropertyPossibleValues for resilience.
        return switch (property) {
            case net.minecraft.world.level.block.state.properties.BooleanProperty booleanProperty ->
                new BooleanProperty(property.getName(), new ArrayList<>(FabricAdapter.getPropertyPossibleValues(booleanProperty)));
            case net.minecraft.world.level.block.state.properties.IntegerProperty integerProperty ->
                new IntegerProperty(property.getName(), new ArrayList<>(FabricAdapter.getPropertyPossibleValues(integerProperty)));
            case net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProperty -> {
                if (property.getValueClass() == net.minecraft.core.Direction.class) {
                    List<Direction> values = FabricAdapter.getPropertyPossibleValues(property).stream()
                        .map(v -> FabricAdapter.adaptEnumFacing((net.minecraft.core.Direction) v))
                        .collect(Collectors.toCollection(ArrayList::new));
                    yield new DirectionalProperty(property.getName(), values);
                }
                // Note: do not make x.asString a method reference.
                // It will cause runtime bootstrap exceptions.
                //noinspection Convert2MethodRef
                List<String> values = FabricAdapter.getPropertyPossibleValues(enumProperty).stream()
                    .map(x -> ((StringRepresentable) x).getSerializedName())
                    .collect(Collectors.toCollection(ArrayList::new));
                yield new EnumProperty(property.getName(), values);
            }
            default -> new PropertyAdapter<>(property);
        };
    }

    private static Map<Property<?>, Object> transmogToWorldEditProperties(BlockType block, Map<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> mcProps) {
        Map<Property<?>, Object> props = new TreeMap<>(Comparator.comparing(Property::getName));
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> prop : mcProps.entrySet()) {
            Object value = prop.getValue();
            if (prop.getKey() instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                if (prop.getKey().getValueClass() == net.minecraft.core.Direction.class) {
                    value = FabricAdapter.adaptEnumFacing((net.minecraft.core.Direction) value);
                } else {
                    value = ((StringRepresentable) value).getSerializedName();
                }
            }
            props.put(block.getProperty(prop.getKey().getName()), value);
        }
        return props;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static net.minecraft.world.level.block.state.BlockState transmogToMinecraftProperties(
        StateDefinition<Block, net.minecraft.world.level.block.state.BlockState> stateContainer,
        net.minecraft.world.level.block.state.BlockState newState,
        Map<Property<?>, Object> states
    ) {
        for (Map.Entry<Property<?>, Object> state : states.entrySet()) {
            net.minecraft.world.level.block.state.properties.Property property = stateContainer.getProperty(state.getKey().getName());
            Comparable value = (Comparable) state.getValue();
            // we may need to adapt this value, depending on the source prop
            if (property instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                if (property.getValueClass() == net.minecraft.core.Direction.class) {
                    Direction dir = (Direction) value;
                    value = FabricAdapter.adapt(dir);
                } else {
                    String enumName = (String) value;
                    value = ((net.minecraft.world.level.block.state.properties.EnumProperty<?>) property).getValue((String) value).orElseThrow(() ->
                        new IllegalStateException("Enum property " + property.getName() + " does not contain " + enumName)
                    );
                }
            }

            newState = newState.setValue(property, value);
        }
        return newState;
    }

    public static net.minecraft.world.level.block.state.BlockState transmogToMinecraft(BlockState blockState) {
        Block mcBlock = FabricAdapter.adapt(blockState.getBlockType());
        net.minecraft.world.level.block.state.BlockState newState = mcBlock.defaultBlockState();
        Map<Property<?>, Object> states = blockState.getStates();
        return transmogToMinecraftProperties(mcBlock.getStateDefinition(), newState, states);
    }

    public static com.sk89q.worldedit.world.block.BlockState transmogToWorldEdit(net.minecraft.world.level.block.state.BlockState blockState) {
        BlockType blockType = FabricAdapter.adapt(blockState.getBlock());
        com.sk89q.worldedit.world.block.BlockState weState = blockType.getDefaultState();
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> prop : blockState.getValues().entrySet()) {
            Property<?> weProp = blockType.getProperty(prop.getKey().getName());
            if (weProp == null) {
                continue;
            }
            Object value = prop.getValue();
            if (prop.getKey() instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                if (prop.getKey().getValueClass() == net.minecraft.core.Direction.class) {
                    value = FabricAdapter.adaptEnumFacing((net.minecraft.core.Direction) value);
                } else {
                    value = ((StringRepresentable) value).getSerializedName();
                }
            }
            weState = weState.with(weProp.getKey(), value);
        }
        return weState;
    }

    private FabricTransmogrifier() {
    }
}
