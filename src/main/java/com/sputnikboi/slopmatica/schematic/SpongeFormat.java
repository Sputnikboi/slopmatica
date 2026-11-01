package com.sputnikboi.slopmatica.schematic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.phys.Vec3;

/**
 * WorldEdit's {@code .schem} files (the Sponge schematic format, versions 1 to 3): gzip NBT with a
 * palette of block-state strings ({@code minecraft:oak_stairs[facing=east]}) and the blocks as
 * varints, x fastest, then z, then y. Version 3 nests blocks under {@code Blocks} and keeps block
 * entity and entity data under {@code Data}; 1 and 2 keep it flat. Written as version 3.
 */
public final class SpongeFormat {
    private SpongeFormat() {
    }

    public static RawSchematic read(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return parse(root, RawSchematic.stripExtension(path.getFileName().toString()));
    }

    public static RawSchematic parse(CompoundTag file, String name) throws IOException {
        // Version 3 wraps everything in a "Schematic" compound; 1 and 2 use the root directly.
        CompoundTag root = file.getCompound("Schematic").orElse(file);
        int version = root.getIntOr("Version", 1);
        int sx = root.getShortOr("Width", (short) 0) & 0xFFFF, sy = root.getShortOr("Height", (short) 0) & 0xFFFF, sz = root.getShortOr("Length", (short) 0) & 0xFFFF;
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("not a .schem file (size " + sx + " x " + sy + " x " + sz + ")");
        }
        CompoundTag blocksTag = version >= 3 ? root.getCompound("Blocks").orElseThrow(() -> new IOException("no Blocks in a version 3 .schem")) : root;
        CompoundTag paletteTag = blocksTag.getCompound("Palette").orElseThrow(() -> new IOException("no Palette in .schem"));
        byte[] data = (version >= 3 ? blocksTag.getByteArray("Data") : root.getByteArray("BlockData")).orElseThrow(() -> new IOException("no block data in .schem"));

        int max = 0;
        for (String key : paletteTag.keySet()) {
            max = Math.max(max, paletteTag.getIntOr(key, 0));
        }
        List<CompoundTag> palette = new ArrayList<>(max + 1);
        for (int i = 0; i <= max; i++) {
            palette.add(stateTag("minecraft:air"));
        }
        for (String key : paletteTag.keySet()) {
            palette.set(paletteTag.getIntOr(key, 0), stateTag(key));
        }

        long volume = (long) sx * sy * sz;
        PackedIntArray blocks = new PackedIntArray(PackedIntArray.bitsFor(palette.size()), volume);
        int at = 0;
        for (long i = 0; i < volume; i++) {
            int value = 0, shift = 0;
            while (true) {
                if (at >= data.length) {
                    throw new IOException("block data ends early in .schem");
                }
                byte b = data[at++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("bad varint in .schem block data");
                }
            }
            blocks.set(i, Math.min(value, palette.size() - 1));
        }

        Map<Long, CompoundTag> tileEntities = new HashMap<>();
        ListTag tiles = (version >= 3 ? blocksTag : root).getListOrEmpty(version == 1 ? "TileEntities" : "BlockEntities");
        for (int i = 0; i < tiles.size(); i++) {
            CompoundTag te = tiles.getCompoundOrEmpty(i);
            int[] pos = te.getIntArray("Pos").orElse(new int[0]);
            if (pos.length != 3) {
                continue;
            }
            CompoundTag nbt = version >= 3 ? te.getCompoundOrEmpty("Data").copy() : te.copy();
            nbt.remove("Pos");
            nbt.remove("Id");
            nbt.putString("id", te.getStringOr("Id", nbt.getStringOr("id", "")));
            nbt.putInt("x", pos[0]);
            nbt.putInt("y", pos[1]);
            nbt.putInt("z", pos[2]);
            tileEntities.put(BlockPos.asLong(pos[0], pos[1], pos[2]), nbt);
        }

        List<EntityData> entities = new ArrayList<>();
        ListTag entityList = root.getListOrEmpty("Entities");
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag e = entityList.getCompoundOrEmpty(i);
            ListTag pos = e.getListOrEmpty("Pos");
            Vec3 p = new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0));
            CompoundTag nbt = version >= 3 ? e.getCompoundOrEmpty("Data").copy() : e.copy();
            nbt.remove("Id");
            nbt.putString("id", e.getStringOr("Id", nbt.getStringOr("id", "")));
            // Hanging entities (frames, paintings) name their block; the rest stand in theirs.
            int[] hang = nbt.getIntArray(EntityData.BLOCK_POS).orElse(new int[0]);
            entities.add(new EntityData(p, hang.length == 3 ? new BlockPos(hang[0], hang[1], hang[2]) : BlockPos.containing(p), nbt));
        }

        CompoundTag meta = root.getCompoundOrEmpty("Metadata");
        String title = meta.getStringOr("Name", name);
        RawSchematic.Region region = new RawSchematic.Region(title, 0, 0, 0, sx, sy, sz, palette, blocks, tileEntities, entities);
        // Version 1 has no data version; WorldEdit wrote those around 1.13 (1631).
        return new RawSchematic(title, meta.getStringOr("Author", ""), root.getIntOr("DataVersion", version == 1 ? 1631 : 0), List.of(region));
    }

    /**
     * {@code minecraft:oak_stairs[facing=east,half=bottom]} as a {@code {Name, Properties}}
     * compound, the form the block-state parser reads.
     */
    static CompoundTag stateTag(String state) {
        CompoundTag tag = new CompoundTag();
        int open = state.indexOf('[');
        tag.putString("Name", open < 0 ? state : state.substring(0, open));
        if (open >= 0 && state.endsWith("]")) {
            CompoundTag props = new CompoundTag();
            for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            tag.put("Properties", props);
        }
        return tag;
    }

    /** Version 3, as current WorldEdit and FAWE read it. */
    public static void write(Path file, BlockVolume v) throws IOException {
        if (v.sizeX() > 0xFFFF || v.sizeY() > 0xFFFF || v.sizeZ() > 0xFFFF) {
            throw new IOException(".schem can't hold a side longer than 65535 blocks");
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        int[] remap = new int[v.palette().size()];
        for (int i = 0; i < remap.length; i++) {
            String key = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(v.palette().get(i));
            remap[i] = ids.computeIfAbsent(key, k -> ids.size());
        }
        CompoundTag palette = new CompoundTag();
        ids.forEach(palette::putInt);

        ByteArrayOutputStream data = new ByteArrayOutputStream(v.indices().length);
        for (int index : v.indices()) {
            int value = remap[index];
            while ((value & ~0x7F) != 0) {
                data.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            data.write(value);
        }

        ListTag tiles = new ListTag();
        v.tileEntities().forEach((key, nbt) -> {
            BlockPos pos = BlockPos.of(key);
            CompoundTag te = new CompoundTag();
            te.put("Pos", new IntArrayTag(new int[] {pos.getX(), pos.getY(), pos.getZ()}));
            te.putString("Id", nbt.getStringOr("id", ""));
            CompoundTag d = nbt.copy();
            for (String k : new String[] {"x", "y", "z", "id"}) {
                d.remove(k);
            }
            te.put("Data", d);
            tiles.add(te);
        });

        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data.toByteArray());
        blocks.put("BlockEntities", tiles);

        ListTag entities = new ListTag();
        for (EntityData e : v.entities()) {
            CompoundTag entry = new CompoundTag();
            entry.put("Pos", EntityData.doubles(e.pos().x, e.pos().y, e.pos().z));
            CompoundTag d = e.relativeNbt();
            entry.putString("Id", d.getStringOr("id", ""));
            d.remove("id");
            entry.put("Data", d);
            entities.add(entry);
        }

        CompoundTag meta = new CompoundTag();
        meta.putString("Name", v.name());
        meta.putString("Author", v.author());
        meta.putLong("Date", System.currentTimeMillis());

        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        schematic.putShort("Width", (short) v.sizeX());
        schematic.putShort("Height", (short) v.sizeY());
        schematic.putShort("Length", (short) v.sizeZ());
        schematic.put("Offset", new IntArrayTag(new int[3]));
        schematic.put("Metadata", meta);
        schematic.put("Blocks", blocks);
        schematic.put("Entities", entities);
        CompoundTag root = new CompoundTag();
        root.put("Schematic", schematic);
        SchematicFormats.writeAtomically(file, root);
    }
}
