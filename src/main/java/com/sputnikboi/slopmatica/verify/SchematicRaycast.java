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

    /** What the placements show at a position (layer view and material selection applied): a block before air, null where none has an opinion. */
    public static @Nullable BlockState wantedAt(List<Placement.Transform> transforms, int x, int y, int z) {
        BlockState found = null;
        for (Placement.Transform transform : transforms) {
            BlockState s = transform.shownStateAt(x, y, z);
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
     * The first schematic block along the ray, or null if a real block without one is in the way.
     * Thin blocks (torches, fences, flowers) only count where the ray actually touches them, so
     * you can look past their edges at what is behind.
     */
    public static @Nullable Hit trace(ClientLevel level, List<Placement.Transform> transforms, Vec3 from, Vec3 dir, double reach) {
        List<Hit> hits = new ArrayList<>(1);
        walk(level, transforms, from, dir, reach, hits, 1);
        return hits.isEmpty() ? null : hits.getFirst();
    }

    /** Every schematic block along the ray, nearest first, up to the first real block in the way. */
    public static List<Hit> traceAll(ClientLevel level, List<Placement.Transform> transforms, Vec3 from, Vec3 dir, double reach) {
        List<Hit> hits = new ArrayList<>();
        walk(level, transforms, from, dir, reach, hits, Integer.MAX_VALUE);
        return hits;
    }

    /**
     * Steps through the cells along the ray (Amanatides and Woo). A cell counts where the ray
     * touches the shape of the schematic's block or the real one; a real block the ray touches
     * that the schematic doesn't want (or that is already right) ends the walk: nothing behind it
     * can be seen or reached.
     */
    private static void walk(ClientLevel level, List<Placement.Transform> transforms, Vec3 from, Vec3 dir, double reach, List<Hit> out, int max) {
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
        double tDeltaX = Math.abs(1 / dir.x), tDeltaY = Math.abs(1 / dir.y), tDeltaZ = Math.abs(1 / dir.z);
        // An axis the ray runs parallel to is never crossed (and 0 * infinity would be NaN).
        double tMaxX = dir.x == 0 ? Double.MAX_VALUE : (dir.x > 0 ? x + 1 - from.x : from.x - x) * tDeltaX;
        double tMaxY = dir.y == 0 ? Double.MAX_VALUE : (dir.y > 0 ? y + 1 - from.y : from.y - y) * tDeltaY;
        double tMaxZ = dir.z == 0 ? Double.MAX_VALUE : (dir.z > 0 ? z + 1 - from.z : from.z - z) * tDeltaZ;
        Vec3 to = from.add(dir.scale(reach));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double t = 0;
        while (t <= reach && out.size() < max) {
            pos.set(x, y, z);
            BlockState want = wantedAt(transforms, x, y, z);
            if (want != null && want.isAir()) {
                want = null;
            }
            BlockState actual = level.getBlockState(pos);
            boolean actualHit = !actual.isAir() && touches(actual, level, pos, from, to, true);
            if (want != null && (actualHit || touches(want, level, pos, from, to, false))) {
                BlockStatus status = BlockMatcher.compare(want, actual);
                out.add(new Hit(pos.immutable(), want, actual, status, t));
                if (actualHit && status != BlockStatus.MISSING) {
                    return; // A real block, right or wrong: it hides what is behind.
                }
            } else if (actualHit && actual.getFluidState().isEmpty()) {
                return;
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
    }

    /**
     * Whether the ray touches a block's outline shape in its cell. Shapeless blocks (fluids,
     * light) count as filling the cell when they are ghosts, and never when they are real.
     */
    private static boolean touches(BlockState state, ClientLevel level, BlockPos pos, Vec3 from, Vec3 to, boolean real) {
        var shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return !real;
        }
        return shape.clip(from, to, pos) != null;
    }
}
