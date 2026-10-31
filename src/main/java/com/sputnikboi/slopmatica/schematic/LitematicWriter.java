package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Writes a single-region {@code .litematic} that Litematica itself can open: gzip NBT, version 7,
 * palette with index 0 = air, block indices bit-packed across longs, block entities with
 * region-relative x/y/z.
 */
public final class LitematicWriter {
    private LitematicWriter() {
    }

    /**
     * @param indices palette index per block, in Litematica order {@code (y * sizeZ + z) * sizeX + x}
     * @param tileEntities block entity NBT keyed by {@code BlockPos.asLong} of the relative position
     */
    public static void write(Path file, String name, String author, int sizeX, int sizeY, int sizeZ,
                             List<BlockState> palette, int[] indices, Map<Long, CompoundTag> tileEntities) throws IOException {
        int bits = PackedIntArray.bitsFor(palette.size());
        long[] packed = pack(indices, bits);
        long nonAir = 0;
        for (int id : indices) {
            if (!palette.get(id).isAir()) {
                nonAir++;
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(stateTag(state));
        }
        ListTag tiles = new ListTag();
        tileEntities.forEach((key, data) -> {
            CompoundTag te = data.copy();
            BlockPos pos = BlockPos.of(key);
            te.putInt("x", pos.getX());
            te.putInt("y", pos.getY());
            te.putInt("z", pos.getZ());
            tiles.add(te);
        });

        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(sizeX, sizeY, sizeZ));
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", packed);
        region.put("TileEntities", tiles);
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put(name, region);

        long now = System.currentTimeMillis();
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", name);
        meta.putString("Author", author);
        meta.putString("Description", "");
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", (int) Math.min(Integer.MAX_VALUE, nonAir));
        meta.putInt("TotalVolume", sizeX * sizeY * sizeZ);
        meta.put("EnclosingSize", vec(sizeX, sizeY, sizeZ));
        meta.putLong("TimeCreated", now);
        meta.putLong("TimeModified", now);

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("Metadata", meta);
        root.put("Regions", regions);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Litematica's packing: values laid end to end across longs, straddling boundaries. */
    static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) (((long) values.length * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long bit = (long) i * bits;
            int start = (int) (bit >> 6);
            int offset = (int) (bit & 63);
            long v = values[i] & mask;
            out[start] |= v << offset;
            if (offset + bits > 64) {
                out[start + 1] |= v >>> (64 - offset);
            }
        }
        return out;
    }

    private static CompoundTag stateTag(BlockState state) {
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

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag v = new CompoundTag();
        v.putInt("x", x);
        v.putInt("y", y);
        v.putInt("z", z);
        return v;
    }
}
