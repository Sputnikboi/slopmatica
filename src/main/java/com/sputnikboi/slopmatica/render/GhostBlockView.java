package com.sputnikboi.slopmatica.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * What the block tessellator sees while drawing ghosts: real blocks where something is placed,
 * schematic blocks where nothing is, so ghost faces are culled against both. Light is always full,
 * so a hologram inside a dark cave is still readable; tint comes from the real biome.
 */
final class GhostBlockView implements BlockAndTintGetter {
    private final ClientLevel level;
    private final WorldSnapshot world;
    private final BlockState[] expected;

    GhostBlockView(ClientLevel level, WorldSnapshot world, BlockState[] expected) {
        this.level = level;
        this.world = world;
        this.expected = expected;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX() - world.baseX, y = pos.getY() - world.baseY, z = pos.getZ() - world.baseZ;
        if (x < 0 || y < 0 || z < 0 || x >= WorldSnapshot.SIZE || y >= WorldSnapshot.SIZE || z >= WorldSnapshot.SIZE) {
            return Blocks.AIR.defaultBlockState();
        }
        int i = WorldSnapshot.index(x, y, z);
        BlockState real = world.get(i);
        if (!real.isAir()) {
            return real;
        }
        BlockState ghost = expected[i];
        return ghost != null ? ghost : real;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return level.getBlockTint(pos, resolver);
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return level.cardinalLighting();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return 15;
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }
}
