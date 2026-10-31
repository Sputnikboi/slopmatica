package com.sputnikboi.slopmatica.schematic;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A box of blocks to save, as every writer takes it: a palette (index 0 is air) and one palette
 * index per block, x fastest, then z, then y.
 *
 * @param tileEntities block entity data keyed by {@code BlockPos.asLong} of the relative position
 * @param entities entities with positions relative to the box
 */
public record BlockVolume(String name, String author, int sizeX, int sizeY, int sizeZ,
                          List<BlockState> palette, int[] indices, Map<Long, CompoundTag> tileEntities, List<EntityData> entities) {

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

    /**
     * Vanilla's block state form for the current data version ({@code {id, properties}} since data
     * version 5006, when vanilla renamed {@code Name}/{@code Properties}), which is what Litematica
     * and structure blocks on this version read back.
     */
    static CompoundTag stateTag(BlockState state) {
        return NbtUtils.writeBlockState(state);
    }
}
