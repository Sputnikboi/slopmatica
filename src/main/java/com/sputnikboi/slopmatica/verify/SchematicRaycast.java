package com.sputnikboi.slopmatica.verify;

import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Finding the schematic block along a ray, ghost blocks included (they aren't in the world). */
public final class SchematicRaycast {
    /** A schematic block hit at {@code distance} along the ray. */
    public record Hit(BlockPos pos, BlockState want, BlockState actual, BlockStatus status, double distance) {
    }

    private SchematicRaycast() {
    }

    /** Placements shown in this level. */
    public static List<Placement.Transform> visibleTransforms(ClientLevel level) {
        List<Placement.Transform> out = new ArrayList<>();
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (p.visible() && p.inDimension(level)) {
                out.add(p.transform());
            }
        }
        return out;
    }

    /** What the placements want at a position: a block before air, null where none has an opinion. */
    public static @Nullable BlockState wantedAt(List<Placement.Transform> transforms, int x, int y, int z) {
        BlockState found = null;
        for (Placement.Transform transform : transforms) {
            BlockState s = transform.stateAt(x, y, z);
            if (s != null) {
                if (!s.isAir()) {
                    return s;
                }
                found = s;
            }
        }
        return found;
    }

    /**
     * Steps through the cells along the ray (Amanatides and Woo) until one holds a schematic block,
     * or a real block with no schematic block, which hides anything behind it.
     */
    public static @Nullable Hit trace(ClientLevel level, List<Placement.Transform> transforms, Vec3 from, Vec3 dir, double reach) {
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
        double tDeltaX = Math.abs(1 / dir.x), tDeltaY = Math.abs(1 / dir.y), tDeltaZ = Math.abs(1 / dir.z);
        // An axis the ray runs parallel to is never crossed (and 0 * infinity would be NaN).
        double tMaxX = dir.x == 0 ? Double.MAX_VALUE : (dir.x > 0 ? x + 1 - from.x : from.x - x) * tDeltaX;
        double tMaxY = dir.y == 0 ? Double.MAX_VALUE : (dir.y > 0 ? y + 1 - from.y : from.y - y) * tDeltaY;
        double tMaxZ = dir.z == 0 ? Double.MAX_VALUE : (dir.z > 0 ? z + 1 - from.z : from.z - z) * tDeltaZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double t = 0;
        while (t <= reach) {
            pos.set(x, y, z);
            BlockState want = wantedAt(transforms, x, y, z);
            if (want != null && want.isAir()) {
                want = null;
            }
            BlockState actual = level.getBlockState(pos);
            if (want != null) {
                return new Hit(pos.immutable(), want, actual, BlockMatcher.compare(want, actual), t);
            }
            if (!actual.isAir() && actual.getFluidState().isEmpty()) {
                return null;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

}
