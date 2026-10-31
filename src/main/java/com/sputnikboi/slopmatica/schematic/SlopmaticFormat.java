package com.sputnikboi.slopmatica.schematic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Slopmatica's own format, {@code .slopmatic}: gzip NBT whose blocks are a palette plus
 * run-length-coded palette indices, so the empty air around a build, solid walls and floors, and
 * other repeats cost a few bytes per run instead of bits per block. Block entities are stored
 * only where they exist.
 *
 * <pre>
 * {
 *   Format: "slopmatica", Version: 1, DataVersion: int,
 *   Name, Author: string, Created, Modified: long (ms),
 *   Size: int[3], BlockCount: long (non-air, non-void),
 *   Regions: [{
 *     Name: string, Pos: int[3], Size: int[3],
 *     Palette: [{Name, Properties}],   // vanilla block state form
 *     Blocks: byte[],                  // (index, run) pairs as unsigned varints, x fastest, then z, then y
 *     BlockEntities: [{Pos: int[3] (region-relative), ...block entity data}]
 *   }]
 * }
 * </pre>
 * A run longer than the region is invalid, as is an index outside the palette; both fail the load
 * rather than producing a half-read schematic.
 */
public final class SlopmaticFormat {
    public static final String MAGIC = "slopmatica";
    public static final int VERSION = 1;

    private SlopmaticFormat() {
    }

    public static RawSchematic read(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return parse(root, RawSchematic.stripExtension(path.getFileName().toString()));
    }

    public static RawSchematic parse(CompoundTag root, String fallbackName) throws IOException {
        if (!MAGIC.equals(root.getStringOr("Format", ""))) {
            throw new IOException("not a .slopmatic file");
        }
        int version = root.getIntOr("Version", 0);
        if (version < 1 || version > VERSION) {
            throw new IOException("made by a newer Slopmatica (format version " + version + "); update the mod");
        }
        String name = root.getString("Name").filter(s -> !s.isBlank()).orElse(fallbackName);
        List<RawSchematic.Region> regions = new ArrayList<>();
        ListTag regionList = root.getListOrEmpty("Regions");
        for (int r = 0; r < regionList.size(); r++) {
            CompoundTag tag = regionList.getCompoundOrEmpty(r);
            int[] pos = tag.getIntArray("Pos").filter(a -> a.length == 3).orElseThrow(() -> new IOException("region without Pos"));
            int[] size = tag.getIntArray("Size").filter(a -> a.length == 3 && a[0] > 0 && a[1] > 0 && a[2] > 0)
                .orElseThrow(() -> new IOException("region without a valid Size"));
            ListTag paletteTag = tag.getListOrEmpty("Palette");
            if (paletteTag.isEmpty()) {
                throw new IOException("region without a palette");
            }
            List<CompoundTag> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                palette.add(paletteTag.getCompoundOrEmpty(i));
            }
            long volume = (long) size[0] * size[1] * size[2];
            PackedIntArray blocks = decode(tag.getByteArray("Blocks").orElseThrow(() -> new IOException("region without Blocks")), palette.size(), volume);

            Map<Long, CompoundTag> tileEntities = new HashMap<>();
            ListTag tiles = tag.getListOrEmpty("BlockEntities");
            for (int i = 0; i < tiles.size(); i++) {
                CompoundTag te = tiles.getCompoundOrEmpty(i);
                int[] p = te.getIntArray("Pos").orElse(new int[0]);
                if (p.length == 3) {
                    CompoundTag data = te.copy();
                    data.remove("Pos");
                    tileEntities.put(BlockPos.asLong(p[0], p[1], p[2]), data);
                }
            }
            regions.add(new RawSchematic.Region(tag.getStringOr("Name", name), pos[0], pos[1], pos[2], size[0], size[1], size[2], palette, blocks, tileEntities));
        }
        if (regions.isEmpty()) {
            throw new IOException("no regions");
        }
        return new RawSchematic(name, root.getStringOr("Author", ""), root.getIntOr("DataVersion", 0), regions);
    }

    public static void write(Path file, BlockVolume v) throws IOException {
        ListTag paletteTag = new ListTag();
        v.palette().forEach(state -> paletteTag.add(BlockVolume.stateTag(state)));
        ListTag tiles = new ListTag();
        v.tileEntities().forEach((key, data) -> {
            CompoundTag te = data.copy();
            for (String k : new String[] {"x", "y", "z"}) {
                te.remove(k);
            }
            BlockPos pos = BlockPos.of(key);
            te.putIntArray("Pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
            tiles.add(te);
        });
        CompoundTag region = new CompoundTag();
        region.putString("Name", v.name());
        region.putIntArray("Pos", new int[] {0, 0, 0});
        region.putIntArray("Size", new int[] {v.sizeX(), v.sizeY(), v.sizeZ()});
        region.put("Palette", paletteTag);
        region.putByteArray("Blocks", encode(v.indices()));
        region.put("BlockEntities", tiles);
        ListTag regions = new ListTag();
        regions.add(region);

        long now = System.currentTimeMillis();
        CompoundTag root = new CompoundTag();
        root.putString("Format", MAGIC);
        root.putInt("Version", VERSION);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putString("Name", v.name());
        root.putString("Author", v.author());
        root.putLong("Created", now);
        root.putLong("Modified", now);
        root.putIntArray("Size", new int[] {v.sizeX(), v.sizeY(), v.sizeZ()});
        root.putLong("BlockCount", v.nonAirBlocks());
        root.put("Regions", regions);
        SchematicFormats.writeAtomically(file, root);
    }

    /** (index, run length) pairs as unsigned LEB128 varints. */
    static byte[] encode(int[] indices) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, indices.length / 8));
        int i = 0;
        while (i < indices.length) {
            int value = indices[i];
            int run = 1;
            while (i + run < indices.length && indices[i + run] == value) {
                run++;
            }
            varint(out, value);
            varint(out, run);
            i += run;
        }
        return out.toByteArray();
    }

    static PackedIntArray decode(byte[] data, int paletteSize, long volume) throws IOException {
        PackedIntArray blocks = new PackedIntArray(PackedIntArray.bitsFor(paletteSize), volume);
        long filled = 0;
        int[] cursor = {0};
        while (cursor[0] < data.length) {
            long value = readVarint(data, cursor);
            long run = readVarint(data, cursor);
            if (value >= paletteSize || run <= 0 || filled + run > volume) {
                throw new IOException("corrupt block data at byte " + cursor[0]);
            }
            for (long end = filled + run; filled < end; filled++) {
                blocks.set(filled, (int) value);
            }
        }
        if (filled != volume) {
            throw new IOException("block data covers " + filled + " of " + volume + " blocks");
        }
        return blocks;
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long readVarint(byte[] data, int[] cursor) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (cursor[0] >= data.length) {
                throw new IOException("truncated block data");
            }
            byte b = data[cursor[0]++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
