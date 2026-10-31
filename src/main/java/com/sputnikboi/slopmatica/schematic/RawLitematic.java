package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * A .litematic file decoded down to palettes and packed indices, before any block state is resolved.
 * Kept free of registry access so the format layer can be tested without bootstrapping the game.
 */
public record RawLitematic(String name, int version, int dataVersion, List<Region> regions) {

    public record Region(
        String name,
        int minX, int minY, int minZ,
        int sizeX, int sizeY, int sizeZ,
        List<CompoundTag> palette,
        PackedIntArray blocks,
        /** Block entity data keyed by {@code BlockPos.asLong} of the region-relative position. */
        Map<Long, CompoundTag> tileEntities
    ) {
        public int index(int x, int y, int z) {
            return (y * sizeZ + z) * sizeX + x;
        }

        public long volume() {
            return (long) sizeX * sizeY * sizeZ;
        }
    }

    public static RawLitematic read(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return parse(root, stripExtension(path.getFileName().toString()));
    }

    public static RawLitematic parse(CompoundTag root, String fallbackName) throws IOException {
        int version = root.getIntOr("Version", 0);
        int dataVersion = root.getIntOr("MinecraftDataVersion", 0);
        String name = root.getCompound("Metadata").flatMap(m -> m.getString("Name")).filter(s -> !s.isBlank()).orElse(fallbackName);
        CompoundTag regionsTag = root.getCompound("Regions").orElseThrow(() -> new IOException("not a litematic: no Regions tag"));

        List<Region> regions = new ArrayList<>();
        for (String regionName : regionsTag.keySet()) {
            CompoundTag tag = regionsTag.getCompoundOrEmpty(regionName);
            int[] pos = readVec(tag, "Position");
            int[] size = readVec(tag, "Size");
            ListTag paletteTag = tag.getList("BlockStatePalette").orElseThrow(() -> new IOException("region " + regionName + " has no palette"));
            long[] packed = tag.getLongArray("BlockStates").orElseThrow(() -> new IOException("region " + regionName + " has no BlockStates"));

            List<CompoundTag> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                palette.add(paletteTag.getCompoundOrEmpty(i));
            }

            // Size may be negative on any axis: the region then extends from Position back
            // towards the schematic origin, and the stored array starts at the min corner.
            int sx = Math.abs(size[0]), sy = Math.abs(size[1]), sz = Math.abs(size[2]);
            int minX = size[0] < 0 ? pos[0] + size[0] + 1 : pos[0];
            int minY = size[1] < 0 ? pos[1] + size[1] + 1 : pos[1];
            int minZ = size[2] < 0 ? pos[2] + size[2] + 1 : pos[2];
            long volume = (long) sx * sy * sz;
            if (volume == 0 || palette.isEmpty()) {
                continue;
            }
            PackedIntArray blocks = new PackedIntArray(PackedIntArray.bitsFor(palette.size()), volume, packed);
            // Litematica stores block entities with their position relative to the region's min corner.
            Map<Long, CompoundTag> tileEntities = new HashMap<>();
            ListTag tiles = tag.getListOrEmpty("TileEntities");
            for (int i = 0; i < tiles.size(); i++) {
                CompoundTag te = tiles.getCompoundOrEmpty(i);
                tileEntities.put(BlockPos.asLong(te.getIntOr("x", 0), te.getIntOr("y", 0), te.getIntOr("z", 0)), te);
            }
            regions.add(new Region(regionName, minX, minY, minZ, sx, sy, sz, palette, blocks, tileEntities));
        }
        return new RawLitematic(name, version, dataVersion, regions);
    }

    private static int[] readVec(CompoundTag parent, String key) throws IOException {
        CompoundTag v = parent.getCompound(key).orElseThrow(() -> new IOException("missing " + key));
        return new int[] {v.getIntOr("x", 0), v.getIntOr("y", 0), v.getIntOr("z", 0)};
    }

    static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
