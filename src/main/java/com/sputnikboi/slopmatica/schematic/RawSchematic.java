package com.sputnikboi.slopmatica.schematic;

import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * A schematic as read from disk, before its block states are resolved: whichever format it came
 * from (see {@link SchematicFormats}), it ends up as regions of palette indices.
 *
 * @param dataVersion the Minecraft data version it was saved with, for upgrading old block states
 */
public record RawSchematic(String name, String author, int dataVersion, List<Region> regions) {

    /**
     * @param palette block states as {@code {Name, Properties}} compounds (lower-case keys accepted too)
     * @param blocks palette index per position, x fastest, then z, then y
     * @param tileEntities block entity data keyed by {@code BlockPos.asLong} of the region-relative position
     */
    public record Region(
        String name,
        int minX, int minY, int minZ,
        int sizeX, int sizeY, int sizeZ,
        List<CompoundTag> palette,
        PackedIntArray blocks,
        Map<Long, CompoundTag> tileEntities
    ) {
        public int index(int x, int y, int z) {
            return (y * sizeZ + z) * sizeX + x;
        }

        public long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
