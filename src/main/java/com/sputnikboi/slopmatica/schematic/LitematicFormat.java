package com.sputnikboi.slopmatica.schematic;

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
 * Litematica's {@code .litematic}: gzip NBT, any number of regions, each with a palette and block
 * indices bit-packed across longs. Read so existing files keep working, and written on request
 * so copies can go back to Litematica users.
 */
public final class LitematicFormat {
    private LitematicFormat() {
    }

    public static RawSchematic read(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        return parse(root, RawSchematic.stripExtension(path.getFileName().toString()));
    }

    public static RawSchematic parse(CompoundTag root, String fallbackName) throws IOException {
        int dataVersion = root.getIntOr("MinecraftDataVersion", 0);
        CompoundTag meta = root.getCompoundOrEmpty("Metadata");
        String name = meta.getString("Name").filter(s -> !s.isBlank()).orElse(fallbackName);
        CompoundTag regionsTag = root.getCompound("Regions").orElseThrow(() -> new IOException("not a litematic: no Regions tag"));

        List<RawSchematic.Region> regions = new ArrayList<>();
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
            // Block entities are stored with their position relative to the region's min corner.
            Map<Long, CompoundTag> tileEntities = new HashMap<>();
            ListTag tiles = tag.getListOrEmpty("TileEntities");
            for (int i = 0; i < tiles.size(); i++) {
                CompoundTag te = tiles.getCompoundOrEmpty(i);
                tileEntities.put(BlockPos.asLong(te.getIntOr("x", 0), te.getIntOr("y", 0), te.getIntOr("z", 0)), te);
            }
            // Litematica stores entity Pos relative to the region's Position (not its min corner,
            // as for block entities); the two differ on axes with a negative size.
            List<EntityData> entities = new ArrayList<>();
            ListTag entityList = tag.getListOrEmpty("Entities");
            for (int i = 0; i < entityList.size(); i++) {
                entities.add(EntityData.fromRelativeNbt(entityList.getCompoundOrEmpty(i)).offset(pos[0] - minX, pos[1] - minY, pos[2] - minZ));
            }
            regions.add(new RawSchematic.Region(regionName, minX, minY, minZ, sx, sy, sz, palette, blocks, tileEntities, entities));
        }
        return new RawSchematic(name, meta.getStringOr("Author", ""), dataVersion, regions);
    }

    private static int[] readVec(CompoundTag parent, String key) throws IOException {
        CompoundTag v = parent.getCompound(key).orElseThrow(() -> new IOException("missing " + key));
        return new int[] {v.getIntOr("x", 0), v.getIntOr("y", 0), v.getIntOr("z", 0)};
    }

    /** A single-region file Litematica itself can open (version 7). */
    public static void write(Path file, BlockVolume v) throws IOException {
        long[] packed = PackedIntArray.pack(v.indices(), PackedIntArray.bitsFor(v.palette().size()));
        ListTag paletteTag = new ListTag();
        v.palette().forEach(state -> paletteTag.add(BlockVolume.stateTag(state)));
        ListTag tiles = new ListTag();
        v.tileEntities().forEach((key, data) -> {
            CompoundTag te = data.copy();
            BlockPos pos = BlockPos.of(key);
            te.putInt("x", pos.getX());
            te.putInt("y", pos.getY());
            te.putInt("z", pos.getZ());
            tiles.add(te);
        });

        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(v.sizeX(), v.sizeY(), v.sizeZ()));
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", packed);
        region.put("TileEntities", tiles);
        ListTag entities = new ListTag();
        v.entities().forEach(e -> entities.add(e.relativeNbt()));
        region.put("Entities", entities);
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put(v.name(), region);

        long now = System.currentTimeMillis();
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", v.name());
        meta.putString("Author", v.author());
        meta.putString("Description", "");
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", (int) Math.min(Integer.MAX_VALUE, v.nonAirBlocks()));
        meta.putInt("TotalVolume", v.sizeX() * v.sizeY() * v.sizeZ());
        meta.put("EnclosingSize", vec(v.sizeX(), v.sizeY(), v.sizeZ()));
        meta.putLong("TimeCreated", now);
        meta.putLong("TimeModified", now);

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.put("Metadata", meta);
        root.put("Regions", regions);
        SchematicFormats.writeAtomically(file, root);
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag v = new CompoundTag();
        v.putInt("x", x);
        v.putInt("y", y);
        v.putInt("z", z);
        return v;
    }
}
