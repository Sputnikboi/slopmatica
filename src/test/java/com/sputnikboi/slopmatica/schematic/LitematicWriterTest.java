package com.sputnikboi.slopmatica.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LitematicWriterTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** What copy writes, load reads back block for block, block entities included. */
    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        List<BlockState> palette = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), stair, Blocks.CHEST.defaultBlockState());
        int sx = 5, sy = 3, sz = 7;
        int[] indices = new int[sx * sy * sz];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = (i * 7) % palette.size();
        }
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("CustomName", "\"loot\"");
        Path file = dir.resolve("copy.litematic");
        LitematicWriter.write(file, "copy", "tester", sx, sy, sz, palette, indices, Map.of(BlockPos.asLong(1, 2, 3), chest));
        assertNotNull(Files.size(file));

        Schematic schematic = Schematic.load(file);
        Schematic.Region region = schematic.regions().getFirst();
        assertEquals(sx, schematic.bounds().getXSpan());
        assertEquals(sy, schematic.bounds().getYSpan());
        assertEquals(sz, schematic.bounds().getZSpan());
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState expected = palette.get(indices[(y * sz + z) * sx + x]);
                    assertEquals(expected, region.palette()[region.paletteIndexAt(x, y, z)], "at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals("\"loot\"", region.tileEntityAt(1, 2, 3).getStringOr("CustomName", ""));
        assertNull(region.tileEntityAt(0, 0, 0));
    }
}
