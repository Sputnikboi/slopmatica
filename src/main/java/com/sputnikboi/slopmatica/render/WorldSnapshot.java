package com.sputnikboi.slopmatica.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The world's blocks for one 16^3 section plus a one-block border, copied on the client thread so
 * mesh building can run on worker threads without touching the live level.
 */
final class WorldSnapshot {
    static final int SIZE = 18;

    private final BlockState[] states;
    final int baseX, baseY, baseZ;

    private WorldSnapshot(BlockState[] states, int baseX, int baseY, int baseZ) {
        this.states = states;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    static WorldSnapshot capture(ClientLevel level, SectionPos section) {
        int baseX = section.minBlockX() - 1, baseY = section.minBlockY() - 1, baseZ = section.minBlockZ() - 1;
        BlockState[] states = new BlockState[SIZE * SIZE * SIZE];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunk[] chunks = new LevelChunk[9];
        for (int cz = 0; cz < 3; cz++) {
            for (int cx = 0; cx < 3; cx++) {
                chunks[cz * 3 + cx] = level.getChunk(section.x() + cx - 1, section.z() + cz - 1);
            }
        }
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                int wz = baseZ + z;
                int cz = (wz >> 4) - section.z() + 1;
                for (int x = 0; x < SIZE; x++) {
                    int wx = baseX + x;
                    int cx = (wx >> 4) - section.x() + 1;
                    states[index(x, y, z)] = chunks[cz * 3 + cx].getBlockState(pos.set(wx, baseY + y, wz));
                }
            }
        }
        return new WorldSnapshot(states, baseX, baseY, baseZ);
    }

    static int index(int x, int y, int z) {
        return (y * SIZE + z) * SIZE + x;
    }

    BlockState get(int i) {
        return states[i];
    }

    BlockState getWorld(int wx, int wy, int wz) {
        int x = wx - baseX, y = wy - baseY, z = wz - baseZ;
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
            return Blocks.AIR.defaultBlockState();
        }
        return states[index(x, y, z)];
    }
}
