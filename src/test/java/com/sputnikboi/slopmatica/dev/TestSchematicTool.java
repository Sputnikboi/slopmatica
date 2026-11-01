package com.sputnikboi.slopmatica.dev;

import com.sputnikboi.slopmatica.schematic.BlockVolume;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes a schematic for testing in the dev client: beds, a door, player heads with a skin at
 * several rotations, stripped and waxed blocks. {@code -Dslopmatica.testschematic=<file>}.
 */
class TestSchematicTool {
    @Test
    @EnabledIfSystemProperty(named = "slopmatica.testschematic", matches = ".+")
    void write() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int sx = 11, sy = 2, sz = 5;
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        int[] indices = new int[sx * sy * sz];
        Map<Long, CompoundTag> tiles = new HashMap<>();
        // Floor of stone, then things on it.
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                set(palette, indices, sx, sz, x, 0, z, Blocks.STONE.defaultBlockState());
            }
        }
        BlockState bed = b("red_bed").setValue(BedBlock.FACING, Direction.EAST);
        set(palette, indices, sx, sz, 0, 1, 0, bed.setValue(BedBlock.PART, BedPart.FOOT));
        set(palette, indices, sx, sz, 1, 1, 0, bed.setValue(BedBlock.PART, BedPart.HEAD));
        BlockState bed2 = b("blue_bed").setValue(BedBlock.FACING, Direction.SOUTH);
        set(palette, indices, sx, sz, 0, 1, 2, bed2.setValue(BedBlock.PART, BedPart.FOOT));
        set(palette, indices, sx, sz, 0, 1, 3, bed2.setValue(BedBlock.PART, BedPart.HEAD));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        set(palette, indices, sx, sz, 3, 1, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        // (The upper half sits above the 2-high box; a door needs 3 layers.)
        for (int r = 0; r < 4; r++) {
            int x = 5 + r;
            set(palette, indices, sx, sz, x, 1, 0, Blocks.PLAYER_HEAD.defaultBlockState().setValue(SkullBlock.ROTATION, r * 4));
            CompoundTag head = new CompoundTag();
            head.putString("id", "minecraft:skull");
            CompoundTag profile = new CompoundTag();
            profile.putString("name", "Sputnikboi");
            head.put("profile", profile);
            tiles.put(BlockPos.asLong(x, 1, 0), head);
        }
        set(palette, indices, sx, sz, 5, 1, 2, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        set(palette, indices, sx, sz, 6, 1, 2, b("waxed_copper_block"));
        set(palette, indices, sx, sz, 7, 1, 2, b("weathered_copper"));
        BlockVolume v = new BlockVolume("part2 test", "Slopmatica", sx, sy, sz, palette, indices, tiles, List.of());
        Path out = Path.of(System.getProperty("slopmatica.testschematic"));
        SchematicFormats.SLOPMATIC.write(out, v);
        // Recommendations the build view should complain about.
        com.sputnikboi.slopmatica.schematic.SchematicMeta meta = new com.sputnikboi.slopmatica.schematic.SchematicMeta();
        meta.position = new BlockPos(10, -60, 10);
        meta.rotation = net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
        meta.biome = "minecraft:desert";
        meta.minVersion = "1.21";
        meta.maxVersion = "1.21";
        meta.write(out);
    }

    private static BlockState b(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id)).defaultBlockState();
    }

    private static void set(List<BlockState> palette, int[] indices, int sx, int sz, int x, int y, int z, BlockState state) {
        int id = palette.indexOf(state);
        if (id < 0) {
            id = palette.size();
            palette.add(state);
        }
        indices[(y * sz + z) * sx + x] = id;
    }
}
