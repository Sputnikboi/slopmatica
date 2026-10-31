package com.sputnikboi.slopmatica.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
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
}
