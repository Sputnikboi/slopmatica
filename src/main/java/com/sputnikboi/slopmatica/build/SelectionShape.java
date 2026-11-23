package com.sputnikboi.slopmatica.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * One piece of a selection: a box, a ball, an upright cylinder, a prism over a floor outline, or a
 * set of single blocks (brush strokes, magic select, and any shape cut down to surface blocks).
 * Immutable once made, except a {@link Cells} still being painted by the brush.
 */
public sealed interface SelectionShape {
    BoundingBox bounds();

    boolean contains(int x, int y, int z);

    /** Each in-between copy for the outline thread: a brush stroke's cells change while it paints. */
    default SelectionShape snapshot() {
        return this;
    }

    record Box(BoundingBox box) implements SelectionShape {
        @Override
        public BoundingBox bounds() {
            return box;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return box.isInside(x, y, z);
        }
    }

    /** Blocks whose centre is within {@code radius} (+ half a block, so radius 0 is one block) of the centre block's. */
    record Ball(BlockPos center, double radius) implements SelectionShape {
        @Override
        public BoundingBox bounds() {
            int r = (int) Math.floor(radius + 0.5);
            return new BoundingBox(center.getX() - r, center.getY() - r, center.getZ() - r, center.getX() + r, center.getY() + r, center.getZ() + r);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            double dx = x - center.getX(), dy = y - center.getY(), dz = z - center.getZ();
            double r = radius + 0.5;
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
    }

    /** An upright cylinder around {@code center}'s column, from minY to maxY. */
    record Cylinder(BlockPos center, double radius, int minY, int maxY) implements SelectionShape {
        @Override
        public BoundingBox bounds() {
            int r = (int) Math.floor(radius + 0.5);
            return new BoundingBox(center.getX() - r, minY, center.getZ() - r, center.getX() + r, maxY, center.getZ() + r);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (y < minY || y > maxY) {
                return false;
            }
            double dx = x - center.getX(), dz = z - center.getZ();
            double r = radius + 0.5;
            return dx * dx + dz * dz <= r * r;
        }
    }

    /**
     * An upright prism: the floor outline through the points' blocks (in order, closed back to the
     * first), from minY to maxY. Blocks the outline passes through count, so the clicked ones do.
     */
    record Prism(List<BlockPos> points, int minY, int maxY, BoundingBox bounds) implements SelectionShape {
        public static Prism of(List<BlockPos> points) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : points) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
            return new Prism(List.copyOf(points), minY, maxY, new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (y < minY || y > maxY || !bounds.isInside(x, y, z)) {
                return false;
            }
            int n = points.size();
            boolean inside = false;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = points.get(i).getX(), zi = points.get(i).getZ();
                double xj = points.get(j).getX(), zj = points.get(j).getZ();
                if (nearSegment(x, z, xi, zi, xj, zj)) {
                    return true;
                }
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                    inside = !inside;
                }
            }
            return inside;
        }

        /** Whether the outline from (ax, az) to (bx, bz) passes through the block at (x, z). */
        private static boolean nearSegment(double x, double z, double ax, double az, double bx, double bz) {
            double dx = bx - ax, dz = bz - az;
            double len = dx * dx + dz * dz;
            double t = len == 0 ? 0 : Math.clamp(((x - ax) * dx + (z - az) * dz) / len, 0, 1);
            double px = ax + t * dx - x, pz = az + t * dz - z;
            return Math.abs(px) <= 0.5 && Math.abs(pz) <= 0.5;
        }
    }

    /** Single blocks, by {@link BlockPos#asLong}. */
    final class Cells implements SelectionShape {
        private final LongOpenHashSet cells;
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        public Cells() {
            cells = new LongOpenHashSet();
        }

        private Cells(Cells from) {
            cells = new LongOpenHashSet(from.cells);
            minX = from.minX;
            minY = from.minY;
            minZ = from.minZ;
            maxX = from.maxX;
            maxY = from.maxY;
            maxZ = from.maxZ;
        }

        public void add(int x, int y, int z) {
            if (cells.add(BlockPos.asLong(x, y, z))) {
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }

        public int size() {
            return cells.size();
        }

        public boolean isEmpty() {
            return cells.isEmpty();
        }

        public LongOpenHashSet cells() {
            return cells;
        }

        @Override
        public BoundingBox bounds() {
            return cells.isEmpty() ? new BoundingBox(0, 0, 0, 0, 0, 0) : new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ && cells.contains(BlockPos.asLong(x, y, z));
        }

        @Override
        public SelectionShape snapshot() {
            return new Cells(this);
        }
    }

    /** A shape added to the selection, or cut out of it. Later parts win over earlier ones. */
    record Part(SelectionShape shape, boolean subtract) {
    }
}
