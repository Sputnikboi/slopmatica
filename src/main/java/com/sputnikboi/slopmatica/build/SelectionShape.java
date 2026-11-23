package com.sputnikboi.slopmatica.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * One piece of a selection: a box, a ball, an upright cylinder, a prism over a floor outline, or a
 * set of single blocks (brush strokes, magic select, and any shape cut down to surface blocks).
 * Every one can be moved and stretched into another box ({@link #fit}): balls become ellipsoids,
 * cylinders ovals, and single blocks are resampled.
 * Immutable once made, except a {@link Cells} still being painted by the brush.
 */
public sealed interface SelectionShape {
    BoundingBox bounds();

    boolean contains(int x, int y, int z);

    /** Each in-between copy for the outline thread: a brush stroke's cells change while it paints. */
    default SelectionShape snapshot() {
        return this;
    }

    /** The same shape moved and stretched so its bounds become {@code to}. */
    SelectionShape fit(BoundingBox to);

    /**
     * Where a box goes when the frame around it goes from {@code from} to {@code to}: each side keeps
     * its place along the frame, never thinner than one block.
     */
    static BoundingBox map(BoundingBox b, BoundingBox from, BoundingBox to) {
        int[] x = mapAxis(b.minX(), b.maxX(), from.minX(), from.getXSpan(), to.minX(), to.getXSpan());
        int[] y = mapAxis(b.minY(), b.maxY(), from.minY(), from.getYSpan(), to.minY(), to.getYSpan());
        int[] z = mapAxis(b.minZ(), b.maxZ(), from.minZ(), from.getZSpan(), to.minZ(), to.getZSpan());
        return new BoundingBox(x[0], y[0], z[0], x[1], y[1], z[1]);
    }

    private static int[] mapAxis(int min, int max, int fromMin, int fromSpan, int toMin, int toSpan) {
        if (fromSpan == toSpan) {
            return new int[] {min - fromMin + toMin, max - fromMin + toMin};
        }
        double k = (double) toSpan / fromSpan;
        int lo = toMin + (int) Math.round((min - fromMin) * k);
        int hi = toMin + (int) Math.round((max + 1 - fromMin) * k) - 1;
        return new int[] {lo, Math.max(lo, hi)};
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

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Box(to);
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

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Ellipsoid(to);
        }
    }

    /** The ball (or egg) that fills a box: a stretched sphere. */
    record Ellipsoid(BoundingBox bounds) implements SelectionShape {
        @Override
        public boolean contains(int x, int y, int z) {
            if (!bounds.isInside(x, y, z)) {
                return false;
            }
            double dx = along(x, bounds.minX(), bounds.getXSpan()), dy = along(y, bounds.minY(), bounds.getYSpan()), dz = along(z, bounds.minZ(), bounds.getZSpan());
            return dx * dx + dy * dy + dz * dz <= 1;
        }

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Ellipsoid(to);
        }
    }

    /** How far a block's centre is from the middle of a span, in half-spans (-1 to 1 at the ends). */
    private static double along(int v, int min, int span) {
        return (v + 0.5 - min - span / 2.0) / (span / 2.0);
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

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Tube(to);
        }
    }

    /** The upright cylinder (round or oval) that fills a box. */
    record Tube(BoundingBox bounds) implements SelectionShape {
        @Override
        public boolean contains(int x, int y, int z) {
            if (!bounds.isInside(x, y, z)) {
                return false;
            }
            double dx = along(x, bounds.minX(), bounds.getXSpan()), dz = along(z, bounds.minZ(), bounds.getZSpan());
            return dx * dx + dz * dz <= 1;
        }

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Tube(to);
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

        @Override
        public SelectionShape fit(BoundingBox to) {
            List<BlockPos> moved = new java.util.ArrayList<>(points.size());
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : points) {
                int x = scale(p.getX(), bounds.minX(), bounds.maxX(), to.minX(), to.maxX());
                int z = scale(p.getZ(), bounds.minZ(), bounds.maxZ(), to.minZ(), to.maxZ());
                moved.add(new BlockPos(x, to.minY(), z));
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
            }
            return new Prism(List.copyOf(moved), to.minY(), to.maxY(), new BoundingBox(minX, to.minY(), minZ, maxX, to.maxY(), maxZ));
        }

        /** A point's place from one min..max to another (both ends kept). */
        private static int scale(int v, int fromMin, int fromMax, int toMin, int toMax) {
            return fromMax == fromMin ? toMin : toMin + (int) Math.round((double) (v - fromMin) * (toMax - toMin) / (fromMax - fromMin));
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

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Stretched(this, to);
        }
    }

    /**
     * Blocks stretched or moved into another box: each block of it is the nearest one of the source
     * (so moving is exact, and growing repeats blocks). Always from the original, never compounding.
     */
    record Stretched(SelectionShape source, BoundingBox bounds) implements SelectionShape {
        @Override
        public boolean contains(int x, int y, int z) {
            if (!bounds.isInside(x, y, z)) {
                return false;
            }
            BoundingBox s = source.bounds();
            return source.contains(back(x, bounds.minX(), bounds.getXSpan(), s.minX(), s.getXSpan()),
                back(y, bounds.minY(), bounds.getYSpan(), s.minY(), s.getYSpan()), back(z, bounds.minZ(), bounds.getZSpan(), s.minZ(), s.getZSpan()));
        }

        private static int back(int v, int min, int span, int srcMin, int srcSpan) {
            return span == srcSpan ? v - min + srcMin : srcMin + (int) Math.floor((v - min + 0.5) * srcSpan / span);
        }

        @Override
        public SelectionShape snapshot() {
            SelectionShape s = source.snapshot();
            return s == source ? this : new Stretched(s, bounds);
        }

        @Override
        public SelectionShape fit(BoundingBox to) {
            return new Stretched(source, to);
        }
    }

    /**
     * A shape added to the selection, or cut out of it. Later parts win over earlier ones.
     * {@code whole}: when the shape was cut down to surface blocks, the shape before that (what
     * stretching and moving work on, cutting it down again where it lands); else null.
     */
    record Part(SelectionShape shape, boolean subtract, @Nullable SelectionShape whole) {
        public Part(SelectionShape shape, boolean subtract) {
            this(shape, subtract, null);
        }

        /** The shape as made: before any cut down to surface blocks. */
        public SelectionShape base() {
            return whole != null ? whole : shape;
        }
    }
}
