package com.sputnikboi.slopmatica.verify;

import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockMatcher {
    private BlockMatcher() {
    }

    /** @param expected the schematic's block; must not be air */
    public static BlockStatus compare(BlockState expected, BlockState actual) {
        if (actual == expected) {
            return BlockStatus.MATCH;
        }
        Block want = expected.getBlock();
        Block have = actual.getBlock();
        if (actual.isAir() || (have instanceof LiquidBlock && have != want)) {
            return BlockStatus.MISSING;
        }
        return family(want) == family(have) ? BlockStatus.VARIANT : BlockStatus.WRONG;
    }

    /**
     * Collapses blocks that are "the same block" for building purposes onto one representative.
     * Several of these pairs render identically, so a mix-up is invisible without this check.
     */
    public static Block family(Block block) {
        Block unwaxed = HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block);
        if (unwaxed != null) {
            block = unwaxed;
        }
        block = WeatheringCopper.getFirst(block);
        if (block instanceof InfestedBlock infested) {
            block = infested.getHostBlock();
        }
        if (block == Blocks.PETRIFIED_OAK_SLAB) {
            block = Blocks.OAK_SLAB;
        }
        return block;
    }
}
