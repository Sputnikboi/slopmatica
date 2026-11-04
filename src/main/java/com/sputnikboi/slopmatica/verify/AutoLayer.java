package com.sputnikboi.slopmatica.verify;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.placement.Placement;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Auto Next Layer: in a layer view, once the last missing block of the current layer is placed,
 * the view moves up a layer. Only the moment a layer gets finished counts, so picking a layer
 * that is already done (to look at it) doesn't send you on. Blocks of the wrong kind or facing
 * don't hold it back: they are placed, just wrong, and still show when you go back down.
 */
public final class AutoLayer {
    public static final AutoLayer INSTANCE = new AutoLayer();

    /** How often the layer is checked, in ticks. */
    private static final int EVERY = 5;

    private int ticks;
    /** The layer last checked, and whether it still had missing blocks then. */
    private int checkedLayer = Integer.MIN_VALUE;
    private Layers.Mode checkedMode;
    private boolean hadMissing;

    private AutoLayer() {
    }

    public void tick(Minecraft mc) {
        Layers layers = Layers.INSTANCE;
        if (!SlopmaticaConfig.get().autoLayer || layers.mode() == Layers.Mode.ALL || mc.level == null || ++ticks < EVERY) {
            return;
        }
        ticks = 0;
        List<Placement.Transform> transforms = SchematicRaycast.visibleTransforms(mc.level);
        int layer = layers.layer();
        int missing = missing(mc.level, transforms, layer);
        boolean same = layer == checkedLayer && layers.mode() == checkedMode;
        if (same && hadMissing && missing == 0 && transforms.stream().anyMatch(t -> t.worldBounds().maxY() > layer)) {
            layers.move(1);
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.layers.auto", layer).append(" · ").append(SlopmaticaKeys.layersMessage())
                .withColor(SlopmaticaActions.ACCENT));
            checkedLayer = Integer.MIN_VALUE;
            return;
        }
        checkedLayer = layer;
        checkedMode = layers.mode();
        hadMissing = missing > 0;
    }

    /** Schematic blocks at this height not placed yet, over every shown placement. */
    private static int missing(ClientLevel level, List<Placement.Transform> transforms, int y) {
        int missing = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Placement.Transform t : transforms) {
            BoundingBox box = t.worldBounds();
            if (y < box.minY() || y > box.maxY()) {
                continue;
            }
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockState want = t.shownStateAt(x, y, z);
                    if (want != null && !want.isAir() && BlockMatcher.compare(want, level.getBlockState(pos.set(x, y, z))) == BlockStatus.MISSING) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }
}
