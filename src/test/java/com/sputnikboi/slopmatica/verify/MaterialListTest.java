package com.sputnikboi.slopmatica.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaterialListTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void countsItemsNotBlocks() {
        assertEquals(1, MaterialList.itemCount(Blocks.STONE.defaultBlockState()));
        assertEquals(2, MaterialList.itemCount(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)));
        assertEquals(1, MaterialList.itemCount(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
        assertEquals(3, MaterialList.itemCount(Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3)));
        assertEquals(1, MaterialList.itemCount(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)));
        assertEquals(0, MaterialList.itemCount(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
        assertEquals(0, MaterialList.itemCount(Blocks.STRAW_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)));
        assertEquals(1, MaterialList.itemCount(Blocks.STRAW_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT)));
    }

    @Test
    void fluidSourcesNeedTheirBucket() {
        assertEquals(Items.WATER_BUCKET, MaterialList.bucketFor(Blocks.WATER.defaultBlockState()));
        assertEquals(Items.LAVA_BUCKET, MaterialList.bucketFor(Blocks.LAVA.defaultBlockState()));
        assertNull(MaterialList.bucketFor(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)), "flowing water comes from the sources");
        assertEquals(Items.WATER_BUCKET, MaterialList.bucketFor(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertNull(MaterialList.bucketFor(Blocks.OAK_SLAB.defaultBlockState()));
        assertNull(MaterialList.bucketFor(Blocks.STONE.defaultBlockState()));

        var water = Blocks.WATER.defaultBlockState();
        assertFalse(MaterialList.fluidMissing(water, water));
        assertFalse(MaterialList.fluidMissing(water, Blocks.KELP.defaultBlockState()));
        assertTrue(MaterialList.fluidMissing(water, Blocks.AIR.defaultBlockState()));
        assertTrue(MaterialList.fluidMissing(water, water.setValue(LiquidBlock.LEVEL, 2)));
        assertTrue(MaterialList.fluidMissing(water, Blocks.LAVA.defaultBlockState()));
    }
}
