package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;
import java.util.Set;
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

    private Schematic(String name, Path source, List<Region> regions, Set<String> unknownBlocks) {
        this.name = name;
        this.source = source;
        this.regions = List.copyOf(regions);
        this.unknownBlocks = Set.copyOf(unknownBlocks);
        this.bounds = BoundingBox.encapsulatingBoxes(regions.stream().map(Region::bounds).toList())
            .orElse(new BoundingBox(0, 0, 0, 0, 0, 0));
        this.blockCount = regions.stream().mapToLong(Region::blockCount).sum();
    }

    public static Schematic load(Path path) throws IOException {
        return fromRaw(RawLitematic.read(path), path);
    }

    public static Schematic fromRaw(RawLitematic raw, Path source) {
        BlockStateParser parser = new BlockStateParser(raw.dataVersion());
        List<Region> regions = new ArrayList<>();
        for (RawLitematic.Region r : raw.regions()) {
            BlockState[] palette = r.palette().stream().map(parser::parse).toArray(BlockState[]::new);
            regions.add(new Region(r, palette));
        }
        return new Schematic(raw.name(), source, regions, parser.unknownBlocks());
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

    public static final class Region {
        private final String name;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final BlockState[] palette;
        private final PackedIntArray blocks;
        private final Map<Long, CompoundTag> tileEntities;
        private final BoundingBox bounds;
        private final long blockCount;

        Region(RawLitematic.Region raw, BlockState[] palette) {
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
            this.bounds = new BoundingBox(minX, minY, minZ, minX + sizeX - 1, minY + sizeY - 1, minZ + sizeZ - 1);

            long count = 0;
            for (long i = 0, n = blocks.size(); i < n; i++) {
                int id = blocks.get(i);
                if (id < palette.length && !palette[id].isAir()) {
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
