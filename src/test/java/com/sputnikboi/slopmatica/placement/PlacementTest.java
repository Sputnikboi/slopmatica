package com.sputnikboi.slopmatica.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.schematic.PackedIntArrayAccess;
import com.sputnikboi.slopmatica.schematic.LitematicFormat;
import com.sputnikboi.slopmatica.schematic.Schematic;
import com.sputnikboi.slopmatica.verify.BlockMatcher;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PlacementTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Rotating keeps the footprint's middle block in place, and four turns return exactly. */
    @Test
    void rotationPivotsOnTheMiddle() {
        Placement p = new Placement(asymmetric(), "t", new BlockPos(100, 64, 200));
        var b = p.schematic().bounds();
        BlockPos middle = p.transform().toWorld(Math.floorDiv(b.minX() + b.maxX(), 2), b.minY(), Math.floorDiv(b.minZ() + b.maxZ(), 2));
        SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, msg -> { });
        assertEquals(middle, p.transform().toWorld(Math.floorDiv(b.minX() + b.maxX(), 2), b.minY(), Math.floorDiv(b.minZ() + b.maxZ(), 2)));
        for (int i = 0; i < 3; i++) {
            SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, msg -> { });
        }
        assertEquals(new BlockPos(100, 64, 200), p.transform().origin());
        SlopmaticaActions.setMirror(p, Mirror.FRONT_BACK, msg -> { });
        SlopmaticaActions.setMirror(p, Mirror.NONE, msg -> { });
        assertEquals(new BlockPos(100, 64, 200), p.transform().origin());
    }

    /** An L of stone with an east-facing stair on the end: asymmetric under every transform. */
    private static Schematic asymmetric() {
        CompoundTag stair = new CompoundTag();
        stair.putString("Name", "minecraft:oak_stairs");
        CompoundTag props = new CompoundTag();
        props.putString("facing", "east");
        stair.put("Properties", props);

        ListTag palette = new ListTag();
        palette.add(name("minecraft:air"));
        palette.add(name("minecraft:stone"));
        palette.add(stair);

        int sx = 3, sy = 1, sz = 2;
        int[] ids = new int[sx * sy * sz];
        ids[0] = 1;           // (0,0,0) stone
        ids[1] = 1;           // (1,0,0) stone
        ids[2] = 2;           // (2,0,0) stair facing east
        ids[sx] = 1;          // (0,0,1) stone
        CompoundTag root = new CompoundTag();
        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(sx, sy, sz));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", PackedIntArrayAccess.pack(ids, 2));
        CompoundTag regions = new CompoundTag();
        regions.put("main", region);
        root.put("Regions", regions);
        try {
            return Schematic.fromRaw(LitematicFormat.parse(root, "asym"), Path.of("asym.litematic"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void everyTransformMapsSchematicBlocksToTheirWorldPositions() {
        Schematic schematic = asymmetric();
        assertEquals(4, schematic.blockCount());
        BlockPos origin = new BlockPos(100, 64, -50);
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                Placement placement = new Placement(schematic, "t", origin);
                placement.setMirror(mirror);
                placement.setRotation(rotation);
                Placement.Transform t = placement.transform();
                String label = mirror + "/" + rotation;

                List<BlockPos> covered = new ArrayList<>();
                for (int x = 0; x < 3; x++) {
                    for (int z = 0; z < 2; z++) {
                        BlockPos world = t.toWorld(x, 0, z);
                        covered.add(world);
                        BlockState want = x == 2 && z == 0
                            ? Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST).mirror(mirror).rotate(rotation)
                            : (x < 2 && z == 0) || (x == 0 && z == 1) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        assertEquals(want, t.stateAt(world.getX(), world.getY(), world.getZ()), label + " at schematic " + x + ",0," + z);
                    }
                }
                // The world bounds are exactly the transformed footprint, nothing outside it answers.
                assertEquals(6, covered.stream().distinct().count(), label);
                assertNull(t.stateAt(origin.getX(), origin.getY() + 1, origin.getZ()), label);
            }
        }
    }

    @Test
    void stairRotatesWithThePlacement() {
        Placement placement = new Placement(asymmetric(), "t", BlockPos.ZERO);
        placement.setRotation(Rotation.CLOCKWISE_90);
        BlockPos stairAt = placement.transform().toWorld(2, 0, 0);
        assertEquals(new BlockPos(0, 0, 2), stairAt);
        BlockState stair = placement.transform().stateAt(0, 0, 2);
        assertEquals(Direction.SOUTH, stair.getValue(StairBlock.FACING));
    }

    @Test
    void matcherSeparatesMissingVariantAndWrong() {
        BlockState stairEast = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        BlockState stairNorth = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        assertEquals(BlockStatus.MATCH, BlockMatcher.compare(stairEast, stairEast));
        assertEquals(BlockStatus.VARIANT, BlockMatcher.compare(stairEast, stairNorth));
        assertEquals(BlockStatus.MISSING, BlockMatcher.compare(stairEast, Blocks.AIR.defaultBlockState()));
        assertEquals(BlockStatus.MISSING, BlockMatcher.compare(stairEast, Blocks.WATER.defaultBlockState()));
        assertEquals(BlockStatus.WRONG, BlockMatcher.compare(stairEast, Blocks.STONE.defaultBlockState()));

        // Pairs that look identical in game must still be flagged.
        BlockState copper = Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState();
        assertEquals(BlockStatus.VARIANT, BlockMatcher.compare(copper, Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState()));
        assertEquals(BlockStatus.VARIANT, BlockMatcher.compare(Blocks.COPPER_BLOCK.waxed().oxidized().defaultBlockState(), copper));
        assertEquals(BlockStatus.VARIANT, BlockMatcher.compare(Blocks.STONE.defaultBlockState(), Blocks.INFESTED_STONE.defaultBlockState()));
        assertEquals(BlockStatus.VARIANT, BlockMatcher.compare(Blocks.OAK_SLAB.defaultBlockState(), Blocks.PETRIFIED_OAK_SLAB.defaultBlockState()));
        assertEquals(BlockStatus.WRONG, BlockMatcher.compare(copper, Blocks.IRON_BLOCK.defaultBlockState()));
    }

    @Test
    void oldDataVersionsAreUpgraded() throws java.io.IOException {
        // 1.20.2 (data 3578) still called short grass "grass"; 1.20.3 renamed it.
        ListTag palette = new ListTag();
        palette.add(name("minecraft:air"));
        palette.add(name("minecraft:grass"));
        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(1, 1, 1));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[] {1L});
        CompoundTag regions = new CompoundTag();
        regions.put("r", region);
        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", 3578);
        root.put("Regions", regions);

        Schematic schematic = Schematic.fromRaw(LitematicFormat.parse(root, "old"), Path.of("old.litematic"));
        assertEquals(Blocks.SHORT_GRASS, schematic.regions().getFirst().palette()[1].getBlock());
        assertEquals(0, schematic.unknownBlocks().size());
    }

    private static CompoundTag name(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", id);
        return tag;
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
