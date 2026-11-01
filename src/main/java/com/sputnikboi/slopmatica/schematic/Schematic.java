package com.sputnikboi.slopmatica.schematic;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A loaded schematic in its own coordinate space (origin = the schematic's origin, unrotated). */
public final class Schematic {
    private final String name;
    private final Path source;
    private final List<Region> regions;
    private final BoundingBox bounds;
    private final long blockCount;
    private final Set<String> unknownBlocks;
    private final Map<String, Long> missingBlocks;
    private final Map<String, Integer> missingEntities;
    private final Set<String> modNamespaces;

    private Schematic(String name, Path source, List<Region> regions, Set<String> unknownBlocks,
                      Map<String, Long> missingBlocks, Map<String, Integer> missingEntities, Set<String> modNamespaces) {
        this.name = name;
        this.source = source;
        this.regions = List.copyOf(regions);
        this.unknownBlocks = Set.copyOf(unknownBlocks);
        this.missingBlocks = missingBlocks;
        this.missingEntities = missingEntities;
        this.modNamespaces = Set.copyOf(modNamespaces);
        this.bounds = BoundingBox.encapsulatingBoxes(regions.stream().map(Region::bounds).toList())
            .orElse(new BoundingBox(0, 0, 0, 0, 0, 0));
        this.blockCount = regions.stream().mapToLong(Region::blockCount).sum();
    }

    public static Schematic load(Path path) throws IOException {
        Schematic schematic = fromRaw(SchematicFormats.read(path), path);
        schematic.meta = SchematicMeta.read(path);
        return schematic;
    }

    /** Recommendations and origin point, from the file; edited in the schematic settings. */
    private volatile SchematicMeta meta = new SchematicMeta();

    public SchematicMeta meta() {
        return meta;
    }

    public void setMeta(SchematicMeta meta) {
        this.meta = meta;
    }

    /** The origin point in schematic coordinates: the file's, or the middle of the bottom layer. */
    public net.minecraft.core.BlockPos originPoint() {
        SchematicMeta m = meta;
        if (m.origin != null) {
            return m.origin;
        }
        return new net.minecraft.core.BlockPos(Math.floorDiv(bounds.minX() + bounds.maxX(), 2), bounds.minY(), Math.floorDiv(bounds.minZ() + bounds.maxZ(), 2));
    }

    public static Schematic fromRaw(RawSchematic raw, Path source) {
        BlockStateParser parser = new BlockStateParser(raw.dataVersion());
        List<Region> regions = new ArrayList<>();
        Map<String, Long> missing = new HashMap<>();
        Map<String, Integer> missingEntities = new HashMap<>();
        Set<String> namespaces = new TreeSet<>();
        for (RawSchematic.Region r : raw.regions()) {
            BlockState[] palette = new BlockState[r.palette().size()];
            String[] unknown = new String[palette.length];
            boolean anyUnknown = false;
            for (int i = 0; i < palette.length; i++) {
                String id = parser.nameOf(r.palette().get(i));
                namespaces.add(namespaceOf(id));
                palette[i] = parser.parse(r.palette().get(i));
                if (parser.unknownBlocks().contains(id)) {
                    unknown[i] = id;
                    anyUnknown = true;
                }
            }
            if (anyUnknown) {
                // How many blocks of each missing kind, so the warning can list the common ones first.
                for (long i = 0, n = r.blocks().size(); i < n; i++) {
                    int id = r.blocks().get(i);
                    if (id < unknown.length && unknown[id] != null) {
                        missing.merge(unknown[id], 1L, Long::sum);
                    }
                }
            }
            // Entities this game doesn't know can't be spawned or pasted: drop them, but say so.
            List<EntityData> entities = new ArrayList<>();
            for (EntityData e : r.entities()) {
                String id = e.nbt().getStringOr("id", "");
                namespaces.add(namespaceOf(id));
                Identifier parsed = Identifier.tryParse(id);
                if (parsed == null || BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).isEmpty()) {
                    missingEntities.merge(id.isEmpty() ? "?" : id, 1, Integer::sum);
                } else {
                    entities.add(e);
                }
            }
            RawSchematic.Region kept = entities.size() == r.entities().size() ? r
                : new RawSchematic.Region(r.name(), r.minX(), r.minY(), r.minZ(), r.sizeX(), r.sizeY(), r.sizeZ(), r.palette(), r.blocks(), r.tileEntities(), entities);
            regions.add(new Region(kept, palette));
        }
        namespaces.remove("minecraft");
        namespaces.remove("");
        return new Schematic(raw.name(), source, regions, parser.unknownBlocks(), sortedByCount(missing), sortedByCount(missingEntities), namespaces);
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static <N extends Number & Comparable<N>> Map<String, N> sortedByCount(Map<String, N> counts) {
        Map<String, N> out = new LinkedHashMap<>();
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, N>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(out);
    }

    public String name() {
        return name;
    }

    public Path source() {
        return source;
    }

    public List<Region> regions() {
        return regions;
    }

    public BoundingBox bounds() {
        return bounds;
    }

    public long blockCount() {
        return blockCount;
    }

    public Set<String> unknownBlocks() {
        return unknownBlocks;
    }

    /** Blocks this game doesn't have, most common first, with how many of each were left out. */
    public Map<String, Long> missingBlocks() {
        return missingBlocks;
    }

    /** Entity types this game doesn't have, most common first, with how many were left out. */
    public Map<String, Integer> missingEntities() {
        return missingEntities;
    }

    /** Whether anything had to be left out because this game doesn't have it. */
    public boolean incomplete() {
        return !missingBlocks.isEmpty() || !missingEntities.isEmpty();
    }

    /** Namespaces other than minecraft used by blocks or entities: the mods it needs. */
    public Set<String> modNamespaces() {
        return modNamespaces;
    }

    /** Uses anything from a mod (installed or not). Such schematics stay out of the cloud and the board. */
    public boolean modded() {
        return !modNamespaces.isEmpty();
    }

    public static final class Region {
        private final String name;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final BlockState[] palette;
        private final PackedIntArray blocks;
        private final Map<Long, CompoundTag> tileEntities;
        private final List<EntityData> entities;
        private final BoundingBox bounds;
        private final long blockCount;

        Region(RawSchematic.Region raw, BlockState[] palette) {
            this.name = raw.name();
            this.minX = raw.minX();
            this.minY = raw.minY();
            this.minZ = raw.minZ();
            this.sizeX = raw.sizeX();
            this.sizeY = raw.sizeY();
            this.sizeZ = raw.sizeZ();
            this.palette = palette;
            this.blocks = raw.blocks();
            this.tileEntities = raw.tileEntities();
            // Kept in schematic space, like the blocks.
            this.entities = raw.entities().stream().map(e -> e.offset(raw.minX(), raw.minY(), raw.minZ())).toList();
            this.bounds = new BoundingBox(minX, minY, minZ, minX + sizeX - 1, minY + sizeY - 1, minZ + sizeZ - 1);

            long count = 0;
            for (long i = 0, n = blocks.size(); i < n; i++) {
                int id = blocks.get(i);
                if (id < palette.length && !palette[id].isAir() && !palette[id].is(Blocks.STRUCTURE_VOID)) {
                    count++;
                }
            }
            this.blockCount = count;
        }

        /** Palette index at schematic-space coordinates, or -1 if the position is outside this region. */
        public int paletteIndexAt(int x, int y, int z) {
            int rx = x - minX, ry = y - minY, rz = z - minZ;
            if (rx < 0 || ry < 0 || rz < 0 || rx >= sizeX || ry >= sizeY || rz >= sizeZ) {
                return -1;
            }
            int id = blocks.get((long) (ry * sizeZ + rz) * sizeX + rx);
            return id < palette.length ? id : 0;
        }

        /** Block entity data at schematic-space coordinates (with region-relative x/y/z), or null. */
        public @Nullable CompoundTag tileEntityAt(int x, int y, int z) {
            return tileEntities.isEmpty() ? null : tileEntities.get(BlockPos.asLong(x - minX, y - minY, z - minZ));
        }

        /** Entities with schematic-space positions. */
        public List<EntityData> entities() {
            return entities;
        }

        public String name() {
            return name;
        }

        public BlockState[] palette() {
            return palette;
        }

        public BoundingBox bounds() {
            return bounds;
        }

        public long blockCount() {
            return blockCount;
        }
    }
}
