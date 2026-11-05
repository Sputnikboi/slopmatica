package com.sputnikboi.slopmatica.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper.WeatherState;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ToolUsesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState s(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    @Test
    void copperStepsCountWaxAndScraping() {
        assertEquals(0, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))));
        assertEquals(1, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))));
        assertEquals(3, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.OXIDIZED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.UNAFFECTED))));
        assertEquals(2, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.waxed().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED)))); // wax off, scrape
        assertEquals(3, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.waxed().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.waxed().pick(WeatherState.EXPOSED)))); // off, scrape, on
        assertEquals(1, ToolUses.copperSteps(s(Blocks.CUT_COPPER.weathering().pick(WeatherState.UNAFFECTED)), s(Blocks.CUT_COPPER.waxed().pick(WeatherState.UNAFFECTED))));
        assertEquals(Integer.MAX_VALUE, ToolUses.copperSteps(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.UNAFFECTED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))), "copper doesn't age on demand");
        assertEquals(Integer.MAX_VALUE, ToolUses.copperSteps(s(Blocks.CUT_COPPER.weathering().pick(WeatherState.UNAFFECTED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.UNAFFECTED))));
        assertEquals(Integer.MAX_VALUE, ToolUses.copperSteps(s(Blocks.STONE), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.UNAFFECTED))));
    }

    @Test
    void onlyStepsTowardsTheWantedBlockCount() {
        // Waxed weathered towards plain exposed: taking the wax off is a step, waxing again is not.
        assertTrue(ToolUses.closer(s(Blocks.COPPER_BLOCK.waxed().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))));
        assertTrue(ToolUses.closer(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))));
        assertFalse(ToolUses.closer(s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.waxed().pick(WeatherState.WEATHERED)), s(Blocks.COPPER_BLOCK.weathering().pick(WeatherState.EXPOSED))));
        assertTrue(ToolUses.closer(s(Blocks.GRASS_BLOCK), s(Blocks.DIRT_PATH), s(Blocks.DIRT_PATH)));
        assertFalse(ToolUses.closer(s(Blocks.GRASS_BLOCK), s(Blocks.FARMLAND), s(Blocks.DIRT_PATH)));
        assertTrue(ToolUses.closer(s(Blocks.OAK_LOG), s(Blocks.STRIPPED_OAK_LOG), s(Blocks.STRIPPED_OAK_LOG)));
    }
}
