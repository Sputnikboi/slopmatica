package com.sputnikboi.slopmatica.placement;

import com.sputnikboi.slopmatica.schematic.Schematic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A schematic positioned in the world. Mutated only on the client thread; every change swaps in a
 * new immutable {@link Transform}, which is what mesh-building threads read.
 */
public final class Placement {
    private final Schematic schematic;
    private final String name;
    private volatile Transform transform;
    private boolean visible = true;

    public Placement(Schematic schematic, String name, BlockPos origin) {
        this.schematic = schematic;
        this.name = name;
        this.transform = new Transform(schematic, origin, Rotation.NONE, Mirror.NONE);
    }

    public Schematic schematic() {
        return schematic;
    }

    public String name() {
        return name;
    }

    public Transform transform() {
        return transform;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setOrigin(BlockPos origin) {
        Transform t = transform;
        transform = new Transform(schematic, origin.immutable(), t.rotation, t.mirror);
    }

    public void setRotation(Rotation rotation) {
        Transform t = transform;
        transform = new Transform(schematic, t.origin, rotation, t.mirror);
    }

    public void setMirror(Mirror mirror) {
        Transform t = transform;
        transform = new Transform(schematic, t.origin, t.rotation, mirror);
    }

    public static final class Transform {
        private final BlockPos origin;
        private final Rotation rotation;
        private final Mirror mirror;
        private final List<Schematic.Region> regions;
        /** Each region's palette with this transform's mirror and rotation already applied. */
        private final BlockState[][] palettes;
        private final BoundingBox worldBounds;

        Transform(Schematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
            this.origin = origin;
            this.rotation = rotation;
            this.mirror = mirror;
            this.regions = schematic.regions();
            this.palettes = new BlockState[regions.size()][];
            for (int i = 0; i < regions.size(); i++) {
                BlockState[] src = regions.get(i).palette();
                BlockState[] dst = new BlockState[src.length];
                for (int j = 0; j < src.length; j++) {
                    dst[j] = src[j].mirror(mirror).rotate(rotation);
                }
                palettes[i] = dst;
            }
            BoundingBox b = schematic.bounds();
            BlockPos a = toWorld(b.minX(), b.minY(), b.minZ());
            BlockPos c = toWorld(b.maxX(), b.maxY(), b.maxZ());
            this.worldBounds = BoundingBox.fromCorners(a, c);
        }

        public BlockPos origin() {
            return origin;
        }

        public Rotation rotation() {
            return rotation;
        }

        public Mirror mirror() {
            return mirror;
        }

        public BoundingBox worldBounds() {
            return worldBounds;
        }

        /**
         * The block the schematic wants at a world position, or null when no region covers it.
         * Air inside a region is returned as air (an intentional gap, not "no opinion").
         */
        public @Nullable BlockState stateAt(int wx, int wy, int wz) {
            if (!worldBounds.isInside(wx, wy, wz)) {
                return null;
            }
            int dx = wx - origin.getX();
            int dz = wz - origin.getZ();
            int y = wy - origin.getY();
            // Undo the rotation, then the mirror (a mirror is its own inverse).
            int x, z;
            switch (rotation) {
                case CLOCKWISE_90 -> { x = dz; z = -dx; }
                case COUNTERCLOCKWISE_90 -> { x = -dz; z = dx; }
                case CLOCKWISE_180 -> { x = -dx; z = -dz; }
                default -> { x = dx; z = dz; }
            }
            switch (mirror) {
                case LEFT_RIGHT -> z = -z;
                case FRONT_BACK -> x = -x;
                default -> { }
            }

            BlockState found = null;
            for (int i = 0; i < regions.size(); i++) {
                int id = regions.get(i).paletteIndexAt(x, y, z);
                if (id >= 0) {
                    BlockState state = palettes[i][id];
                    if (!state.isAir()) {
                        return state;
                    }
                    found = state;
                }
            }
            return found;
        }

        /** Schematic-space to world-space; the same convention as vanilla structure templates. */
        public BlockPos toWorld(int x, int y, int z) {
            switch (mirror) {
                case LEFT_RIGHT -> z = -z;
                case FRONT_BACK -> x = -x;
                default -> { }
            }
            int rx, rz;
            switch (rotation) {
                case CLOCKWISE_90 -> { rx = -z; rz = x; }
                case COUNTERCLOCKWISE_90 -> { rx = z; rz = -x; }
                case CLOCKWISE_180 -> { rx = -x; rz = -z; }
                default -> { rx = x; rz = z; }
            }
            return new BlockPos(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
        }
    }
}
