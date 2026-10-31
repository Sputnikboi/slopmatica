package com.sputnikboi.slopmatica.schematic;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A box of blocks to save, as every writer takes it: a palette (index 0 is air) and one palette
 * index per block, x fastest, then z, then y.
 *
 * @param tileEntities block entity data keyed by {@code BlockPos.asLong} of the relative position
 */
public record BlockVolume(String name, String author, int sizeX, int sizeY, int sizeZ,
                          List<BlockState> palette, int[] indices, Map<Long, CompoundTag> tileEntities) {

    public long nonAirBlocks() {
        long n = 0;
        for (int id : indices) {
            if (!palette.get(id).isAir()) {
                n++;
            }
        }
        return n;
    }

    public BlockPos pos(int index) {
        int x = index % sizeX;
        int rest = index / sizeX;
        return new BlockPos(x, rest / sizeZ, rest % sizeZ);
    }

    /** {@code {Name, Properties}}, the block state form every format here uses. */
    static CompoundTag stateTag(BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getProperties().isEmpty()) {
            CompoundTag props = new CompoundTag();
            for (Property<?> property : state.getProperties()) {
                props.putString(property.getName(), valueName(state, property));
            }
            tag.put("Properties", props);
        }
        return tag;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
