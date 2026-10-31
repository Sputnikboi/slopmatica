package com.sputnikboi.slopmatica.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

class LitematicFormatTest {

    static long[] pack(int[] values, int bits) {
        return PackedIntArrayAccess.pack(values, bits);
    }

    @Test
    void packedArrayReadsEntriesThatStraddleLongs() {
        int bits = 5;
        int[] values = new int[200];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i * 7 + 3) % 17;
        }
        PackedIntArray array = new PackedIntArray(bits, values.length, pack(values, bits));
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], array.get(i), "index " + i);
        }
    }

    @Test
    void bitsForMatchesLitematica() {
        assertEquals(2, PackedIntArray.bitsFor(1));
        assertEquals(2, PackedIntArray.bitsFor(4));
        assertEquals(3, PackedIntArray.bitsFor(5));
        assertEquals(4, PackedIntArray.bitsFor(16));
        assertEquals(5, PackedIntArray.bitsFor(17));
    }

    @Test
    void negativeSizeRegionStartsAtMinCorner() throws IOException {
        // Region declared at (5,4,6) with size (-3,-2,2): it spans x 3..5, y 3..4, z 6..7.
        int sx = 3, sy = 2, sz = 2;
        int[] ids = new int[sx * sy * sz];
        ids[(1 * sz + 1) * sx + 2] = 1; // container (2,1,1) -> schematic (5,4,7)
        CompoundTag root = litematic("test", vec(5, 4, 6), vec(-3, -2, 2), List.of("minecraft:air", "minecraft:stone"), ids);

        RawSchematic raw = LitematicFormat.parse(root, "fallback");
        RawSchematic.Region region = raw.regions().getFirst();
        assertEquals(3, region.minX());
        assertEquals(3, region.minY());
        assertEquals(6, region.minZ());
        assertEquals(1, region.blocks().get(region.index(2, 1, 1)));
        assertEquals(0, region.blocks().get(region.index(0, 0, 0)));
    }

    /**
     * Cross-checks real files against the block count Litematica wrote into their metadata.
     * Point SLOPMATICA_SAMPLES at a folder of .litematic files to run it.
     */
    @Test
    void realFilesMatchTheirMetadataBlockCount() throws IOException {
        String dir = System.getenv("SLOPMATICA_SAMPLES");
        if (dir == null) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(dir))) {
            files = s.filter(p -> p.toString().endsWith(".litematic")).toList();
        }
        assertTrue(!files.isEmpty(), "no samples in " + dir);
        for (Path file : files) {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            int expected = root.getCompoundOrEmpty("Metadata").getIntOr("TotalBlocks", -1);
            RawSchematic raw = LitematicFormat.parse(root, "x");
            long counted = 0;
            for (RawSchematic.Region r : raw.regions()) {
                for (long i = 0; i < r.volume(); i++) {
                    String name = r.palette().get(r.blocks().get(i)).getStringOr("Name", "");
                    if (!name.equals("minecraft:air")) {
                        counted++;
                    }
                }
            }
            assertEquals(expected, counted, file.getFileName().toString());
        }
    }

    static CompoundTag litematic(String name, CompoundTag pos, CompoundTag size, List<String> palette, int[] ids) {
        ListTag paletteTag = new ListTag();
        for (String block : palette) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", block);
            paletteTag.add(entry);
        }
        CompoundTag region = new CompoundTag();
        region.put("Position", pos);
        region.put("Size", size);
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", pack(ids, PackedIntArray.bitsFor(palette.size())));
        CompoundTag regions = new CompoundTag();
        regions.put(name, region);
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.put("Regions", regions);
        return root;
    }

    static CompoundTag vec(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
