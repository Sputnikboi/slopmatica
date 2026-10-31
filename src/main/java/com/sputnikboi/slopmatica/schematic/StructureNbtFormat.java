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
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla structure files ({@code .nbt}, as structure blocks and {@code /place template} use):
 * gzip NBT with a palette and one compound per stored block. Positions with no block entry are
 * structure voids, which mean "leave whatever is there": they load as {@code structure_void},
 * which placements treat as having no opinion.
 *
 * <p>Fine for exchange, poor for storage: every block, air included, costs a compound with a
 * position list, which is why {@code .slopmatic} exists.
 */
public final class StructureNbtFormat {
    private static final CompoundTag VOID = stateName("minecraft:structure_void");

    private StructureNbtFormat() {
    }

    public static RawSchematic read(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return parse(root, RawSchematic.stripExtension(path.getFileName().toString()));
    }

    public static RawSchematic parse(CompoundTag root, String name) throws IOException {
        ListTag size = root.getList("size").orElseThrow(() -> new IOException("not a structure file: no size"));
        int sx = size.getIntOr(0, 0), sy = size.getIntOr(1, 0), sz = size.getIntOr(2, 0);
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("empty structure (" + sx + " x " + sy + " x " + sz + ")");
        }
        // Several palettes are random variants of one structure (shipwrecks); the first will do.
        ListTag paletteTag = root.getList("palette").orElseGet(() -> root.getListOrEmpty("palettes").getListOrEmpty(0));
        List<CompoundTag> palette = new ArrayList<>(paletteTag.size() + 1);
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(paletteTag.getCompoundOrEmpty(i));
        }
        int voidId = palette.size();
        palette.add(VOID);

        long volume = (long) sx * sy * sz;
        PackedIntArray blocks = new PackedIntArray(PackedIntArray.bitsFor(palette.size()), volume);
        for (long i = 0; i < volume; i++) {
            blocks.set(i, voidId);
        }
        Map<Long, CompoundTag> tileEntities = new HashMap<>();
        ListTag blockList = root.getListOrEmpty("blocks");
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag block = blockList.getCompoundOrEmpty(i);
            ListTag pos = block.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
            int state = block.getIntOr("state", -1);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || state < 0 || state >= voidId) {
                continue;
            }
            blocks.set((long) (y * sz + z) * sx + x, state);
            block.getCompound("nbt").ifPresent(nbt -> tileEntities.put(BlockPos.asLong(x, y, z), nbt));
        }
        List<EntityData> entities = new ArrayList<>();
        ListTag entityList = root.getListOrEmpty("entities");
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag e = entityList.getCompoundOrEmpty(i);
            ListTag pos = e.getListOrEmpty("pos");
            ListTag blockPos = e.getListOrEmpty("blockPos");
            Vec3 p = new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0));
            BlockPos bp = blockPos.size() == 3 ? new BlockPos(blockPos.getIntOr(0, 0), blockPos.getIntOr(1, 0), blockPos.getIntOr(2, 0)) : BlockPos.containing(p);
            e.getCompound("nbt").ifPresent(nbt -> entities.add(new EntityData(p, bp, nbt)));
        }
        RawSchematic.Region region = new RawSchematic.Region(name, 0, 0, 0, sx, sy, sz, palette, blocks, tileEntities, entities);
        return new RawSchematic(name, root.getStringOr("author", ""), root.getIntOr("DataVersion", 0), List.of(region));
    }

    /** Every block, air included (an exact copy), in the current vanilla layout. */
    public static void write(Path file, BlockVolume v) throws IOException {
        ListTag paletteTag = new ListTag();
        v.palette().forEach(state -> paletteTag.add(NbtUtils.writeBlockState(state)));
        ListTag blockList = new ListTag();
        int[] indices = v.indices();
        for (int i = 0; i < indices.length; i++) {
            BlockPos pos = v.pos(i);
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
            block.putInt("state", indices[i]);
            CompoundTag nbt = v.tileEntities().get(pos.asLong());
            if (nbt != null) {
                CompoundTag data = nbt.copy();
                for (String key : new String[] {"x", "y", "z"}) {
                    data.remove(key);
                }
                block.put("nbt", data);
            }
            blockList.add(block);
        }
        CompoundTag root = new CompoundTag();
        root.put("size", ints(v.sizeX(), v.sizeY(), v.sizeZ()));
        root.put("palette", paletteTag);
        root.put("blocks", blockList);
        ListTag entities = new ListTag();
        for (EntityData e : v.entities()) {
            CompoundTag entry = new CompoundTag();
            entry.put("pos", EntityData.doubles(e.pos().x, e.pos().y, e.pos().z));
            entry.put("blockPos", ints(e.blockPos().getX(), e.blockPos().getY(), e.blockPos().getZ()));
            entry.put("nbt", e.relativeNbt());
            entities.add(entry);
        }
        root.put("entities", entities);
        root.putString("author", v.author());
        SchematicFormats.writeAtomically(file, NbtUtils.addCurrentDataVersion(root));
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(net.minecraft.nbt.IntTag.valueOf(value));
        }
        return list;
    }

    private static CompoundTag stateName(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }
}
