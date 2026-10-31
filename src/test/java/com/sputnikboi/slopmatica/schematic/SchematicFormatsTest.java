package com.sputnikboi.slopmatica.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sputnikboi.slopmatica.placement.Placement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SchematicFormatsTest {
    // Before the block constants below: they need a bootstrapped registry, even when this class runs alone.
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockState STAIR = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
    private static final List<BlockState> PALETTE = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), STAIR, Blocks.CHEST.defaultBlockState());

    /** A build in a mostly empty box: a floor, a wall, a few odd blocks, a chest with a name. */
    private static BlockVolume sample(int sx, int sy, int sz) {
        int[] indices = new int[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int id = y == 0 || z == 0 ? 1 : (x * 7 + y * 3 + z) % 97 == 0 ? 2 : 0;
                    indices[(y * sz + z) * sx + x] = id;
                }
            }
        }
        indices[(2 * sz + 3) * sx + 1] = 3;
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "\"loot\"");
        return new BlockVolume("sample", "tester", sx, sy, sz, PALETTE, indices, Map.of(BlockPos.asLong(1, 2, 3), chest));
    }

    /** Every format reads back exactly what it wrote, block entities included. */
    @ParameterizedTest
    @EnumSource(SchematicFormats.class)
    void roundTrip(SchematicFormats format, @TempDir Path dir) throws Exception {
        BlockVolume v = sample(9, 5, 11);
        Path file = dir.resolve("sample" + format.extension);
        format.write(file, v);

        Schematic schematic = Schematic.load(file);
        Schematic.Region region = schematic.regions().getFirst();
        assertEquals(v.sizeX(), schematic.bounds().getXSpan());
        assertEquals(v.sizeY(), schematic.bounds().getYSpan());
        assertEquals(v.sizeZ(), schematic.bounds().getZSpan());
        for (int y = 0; y < v.sizeY(); y++) {
            for (int z = 0; z < v.sizeZ(); z++) {
                for (int x = 0; x < v.sizeX(); x++) {
                    BlockState expected = PALETTE.get(v.indices()[(y * v.sizeZ() + z) * v.sizeX() + x]);
                    assertEquals(expected, region.palette()[region.paletteIndexAt(x, y, z)], format + " at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals("\"loot\"", region.tileEntityAt(1, 2, 3).getStringOr("CustomName", ""));
        assertNull(region.tileEntityAt(0, 0, 0));
        assertEquals(v.nonAirBlocks(), schematic.blockCount());
    }

    /** The point of .slopmatic: a sparse 64^3 build is far smaller than as .nbt, and smaller than as .litematic. */
    @Test
    void slopmaticIsCompact(@TempDir Path dir) throws Exception {
        BlockVolume v = sample(64, 64, 64);
        long[] sizes = new long[SchematicFormats.values().length];
        for (SchematicFormats format : SchematicFormats.values()) {
            Path file = dir.resolve("sample" + format.extension);
            format.write(file, v);
            sizes[format.ordinal()] = Files.size(file);
        }
        System.out.printf("64^3 sample: slopmatic %d B, nbt %d B, litematic %d B%n", sizes[0], sizes[1], sizes[2]);
        long slop = sizes[SchematicFormats.SLOPMATIC.ordinal()];
        assertTrue(slop * 4 < sizes[SchematicFormats.NBT.ordinal()], () -> "slopmatic " + slop + " vs nbt " + sizes[SchematicFormats.NBT.ordinal()]);
        assertTrue(slop < sizes[SchematicFormats.LITEMATIC.ordinal()], () -> "slopmatic " + slop + " vs litematic " + sizes[SchematicFormats.LITEMATIC.ordinal()]);
    }

    /** Positions a vanilla structure doesn't list are structure voids: no opinion, not air. */
    @Test
    void nbtVoidsHaveNoOpinion(@TempDir Path dir) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(2));
        size.add(IntTag.valueOf(1));
        size.add(IntTag.valueOf(1));
        root.put("size", size);
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        pos.add(IntTag.valueOf(0));
        block.put("pos", pos);
        block.putInt("state", 0);
        blocks.add(block);
        root.put("blocks", blocks);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        Path file = dir.resolve("void.nbt");
        NbtIo.writeCompressed(root, file);

        Placement p = new Placement(Schematic.load(file), "void", new BlockPos(10, 64, 10));
        assertEquals(Blocks.STONE.defaultBlockState(), p.transform().stateAt(10, 64, 10));
        assertNull(p.transform().stateAt(11, 64, 10));
        assertEquals(1, p.schematic().blockCount());
    }

    @Test
    void corruptSlopmaticFailsCleanly() {
        assertThrows(IOException.class, () -> SlopmaticFormat.decode(new byte[] {5, 3}, 2, 3));   // index 5 not in a 2-entry palette
        assertThrows(IOException.class, () -> SlopmaticFormat.decode(new byte[] {0, 9}, 2, 3));   // run past the end
        assertThrows(IOException.class, () -> SlopmaticFormat.decode(new byte[] {0, 2}, 2, 3));   // too short
        assertThrows(IOException.class, () -> SlopmaticFormat.decode(new byte[] {0, (byte) 0x80}, 2, 3)); // truncated varint
    }
}
