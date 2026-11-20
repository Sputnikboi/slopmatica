package com.sputnikboi.slopmatica.verify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-side block changes and chunk loads, for counts kept up to date block by block (the
 * material panels, the inventory plan) instead of recounting a whole build each time. Render
 * thread only.
 */
public final class BlockChanges {
    public interface Listener {
        void blockChanged(BlockPos pos, BlockState old, BlockState now);

        /** A chunk column loaded or unloaded: what is known about its blocks changed wholesale. */
        void chunkChanged(int chunkX, int chunkZ);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private BlockChanges() {
    }

    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void blockChanged(BlockPos pos, BlockState old, BlockState now) {
        for (Listener l : LISTENERS) {
            l.blockChanged(pos, old, now);
        }
    }

    public static void chunkChanged(int chunkX, int chunkZ) {
        for (Listener l : LISTENERS) {
            l.chunkChanged(chunkX, chunkZ);
        }
    }
}
