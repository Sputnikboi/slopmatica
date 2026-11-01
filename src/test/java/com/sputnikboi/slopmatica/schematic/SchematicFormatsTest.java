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
        CompoundTag stand = new CompoundTag();
        stand.putString("id", "minecraft:armor_stand");
        stand.putString("CustomName", "\"steve\"");
        stand.put("Pos", EntityData.doubles(1000, 64, 1000)); // absolute junk: the writer makes it relative
        CompoundTag frame = new CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.putIntArray(EntityData.BLOCK_POS, new int[] {1000, 64, 1000});
        List<EntityData> entities = List.of(
            new EntityData(new net.minecraft.world.phys.Vec3(2.5, 1, 4.25), new BlockPos(2, 1, 4), stand),
            new EntityData(new net.minecraft.world.phys.Vec3(3.5, 1.5, 0.97), new BlockPos(3, 1, 1), frame));
        return new BlockVolume("sample", "tester", sx, sy, sz, PALETTE, indices, Map.of(BlockPos.asLong(1, 2, 3), chest), entities);
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

        List<EntityData> entities = region.entities();
        assertEquals(2, entities.size(), format + " entities");
        EntityData stand = entities.stream().filter(e -> e.nbt().getStringOr("id", "").equals("minecraft:armor_stand")).findFirst().orElseThrow();
        assertEquals(new net.minecraft.world.phys.Vec3(2.5, 1, 4.25), stand.pos());
        assertEquals("\"steve\"", stand.nbt().getStringOr("CustomName", ""));
        EntityData frame = entities.stream().filter(e -> e.nbt().getStringOr("id", "").equals("minecraft:item_frame")).findFirst().orElseThrow();
        assertEquals(new BlockPos(3, 1, 1), frame.blockPos(), format + " hanging block");
    }

    /** A version 2 file as older WorldEdit writes it: flat layout, palette strings, varint data. */
    @Test
    void readsWorldEditVersion2() throws Exception {
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", 1);
        palette.putInt("minecraft:stone", 200); // A big id, so its varint takes two bytes.
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.putShort("Width", (short) 3);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[] {1, (byte) (200 & 0x7F | 0x80), (byte) (200 >> 7), 0});
        Schematic s = Schematic.fromRaw(SpongeFormat.parse(root, "we"), Path.of("we.schem"));
        Schematic.Region r = s.regions().getFirst();
        assertEquals(STAIR, r.palette()[r.paletteIndexAt(0, 0, 0)]);
        assertEquals(Blocks.STONE.defaultBlockState(), r.palette()[r.paletteIndexAt(1, 0, 0)]);
        assertEquals(Blocks.AIR.defaultBlockState(), r.palette()[r.paletteIndexAt(2, 0, 0)]);
        assertEquals(2, s.blockCount());
    }

    /** An entity inside a block ends up inside that block's new place, for every rotation and mirror. */
    @Test
    void entitiesFollowTheirBlock(@TempDir Path dir) throws Exception {
        BlockVolume v = sample(5, 3, 6);
        Path file = dir.resolve("e.slopmatic");
        SchematicFormats.SLOPMATIC.write(file, v);
        Schematic schematic = Schematic.load(file);
        for (net.minecraft.world.level.block.Mirror mirror : net.minecraft.world.level.block.Mirror.values()) {
            for (net.minecraft.world.level.block.Rotation rotation : net.minecraft.world.level.block.Rotation.values()) {
                Placement p = new Placement(schematic, "e", new BlockPos(100, 64, -40));
                p.setMirror(mirror);
                p.setRotation(rotation);
                List<EntityData> world = p.transform().worldEntities();
                List<EntityData> local = schematic.regions().getFirst().entities();
                for (int i = 0; i < local.size(); i++) {
                    BlockPos block = BlockPos.containing(local.get(i).pos());
                    BlockPos expected = p.transform().toWorld(block.getX(), block.getY(), block.getZ());
                    assertEquals(expected, BlockPos.containing(world.get(i).pos()), mirror + " " + rotation + " entity " + i);
                    BlockPos hang = local.get(i).blockPos();
                    assertEquals(p.transform().toWorld(hang.getX(), hang.getY(), hang.getZ()), world.get(i).blockPos());
                }
            }
        }
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
