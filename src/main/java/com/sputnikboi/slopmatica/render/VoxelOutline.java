package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The outline of a set of blocks, Axiom-style: the edges where its surface folds (outer corners
 * and creases), not every block's own box, with straight runs merged into one line. Built on a
 * worker thread, drawn as lines nudged a little outwards so they sit on top of block faces.
 */
public final class VoxelOutline {
    /** More lines than this aren't drawn (only the bounding box is): the selection is too ragged to read anyway. */
    public static final int MAX_LINES = 150_000;

    /** The blocks to outline: every one of them, and a test for neighbours. */
    public interface Cells {
        LongIterator iterator();

        boolean contains(int x, int y, int z);
    }

    /**
     * Lines as int runs of 6: start x, y, z, axis (0 x, 1 y, 2 z), length, and the outward normals
     * of the faces meeting there as a bitmask (bit 2*axis for +, 2*axis+1 for -).
     */
    private final int[] lines;
    private final int count;

    private VoxelOutline(int[] lines, int count) {
        this.lines = lines;
        this.count = count;
    }

    public int lines() {
        return count;
    }

    private static final int[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Builds the outline, or null when it has more than {@link #MAX_LINES} lines. */
    public static @Nullable VoxelOutline build(Cells cells) {
        // Edge key: the block-grid corner the unit edge starts at, per axis. Value: face normals there.
        Long2IntOpenHashMap[] edges = {new Long2IntOpenHashMap(), new Long2IntOpenHashMap(), new Long2IntOpenHashMap()};
        for (LongIterator it = cells.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            for (int f = 0; f < 6; f++) {
                int[] n = NORMALS[f];
                if (cells.contains(x + n[0], y + n[1], z + n[2])) {
                    continue; // Not a face: the neighbour is selected too.
                }
                int a = f / 2; // The face's normal axis; its edges run along the other two.
                for (int e = 0; e < 3; e++) {
                    if (e == a) {
                        continue;
                    }
                    int s = 3 - a - e; // The axis the edge is offset along.
                    for (int side = 0; side < 2; side++) {
                        int[] d = new int[3];
                        d[s] = side == 0 ? -1 : 1;
                        // The face goes on flat past this edge: no line.
                        boolean next = cells.contains(x + d[0], y + d[1], z + d[2]);
                        boolean nextCovered = cells.contains(x + d[0] + n[0], y + d[1] + n[1], z + d[2] + n[2]);
                        if (next && !nextCovered) {
                            continue;
                        }
                        // The edge's start corner: the block's corner on the face's side and this edge's side.
                        int[] c = {x, y, z};
                        if (n[a] > 0) {
                            c[a] += 1;
                        }
                        if (side == 1) {
                            c[s] += 1;
                        }
                        long k = BlockPos.asLong(c[0], c[1], c[2]);
                        edges[e].put(k, edges[e].get(k) | 1 << f);
                    }
                }
            }
            if (edges[0].size() + edges[1].size() + edges[2].size() > MAX_LINES * 8) {
                return null;
            }
        }
        // Merge straight runs (same line, same normals) into one line each.
        int[] out = new int[64 * 6];
        int count = 0;
        for (int axis = 0; axis < 3; axis++) {
            int n = edges[axis].size();
            long[][] sorted = new long[n][];
            int i = 0;
            for (var entry : edges[axis].long2IntEntrySet()) {
                long k = entry.getLongKey();
                int[] c = {BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)};
                int o1 = c[(axis + 1) % 3], o2 = c[(axis + 2) % 3];
                sorted[i++] = new long[] {o1, o2, entry.getIntValue(), c[axis], c[0], c[1], c[2]};
            }
            Arrays.sort(sorted, (p, q) -> {
                for (int j = 0; j < 4; j++) {
                    int cmp = Long.compare(p[j], q[j]);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                return 0;
            });
            for (int j = 0; j < n; ) {
                long[] first = sorted[j];
                int len = 1;
                while (j + len < n && sorted[j + len][0] == first[0] && sorted[j + len][1] == first[1] && sorted[j + len][2] == first[2]
                    && sorted[j + len][3] == first[3] + len) {
                    len++;
                }
                if (count == MAX_LINES) {
                    return null;
                }
                if ((count + 1) * 6 > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                int b = count * 6;
                out[b] = (int) first[4];
                out[b + 1] = (int) first[5];
                out[b + 2] = (int) first[6];
                out[b + 3] = axis;
                out[b + 4] = len;
                out[b + 5] = (int) first[2];
                count++;
                j += len;
            }
        }
        return new VoxelOutline(out, count);
    }

    public void draw(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, int color, float width, double grow) {
        for (int i = 0; i < count; i++) {
            int o = i * 6;
            int axis = lines[o + 3], len = lines[o + 4], mask = lines[o + 5];
            double gx = 0, gy = 0, gz = 0;
            for (int f = 0; f < 6; f++) {
                if ((mask & 1 << f) != 0) {
                    gx += NORMALS[f][0];
                    gy += NORMALS[f][1];
                    gz += NORMALS[f][2];
                }
            }
            float x0 = (float) (lines[o] + gx * grow - cam.x), y0 = (float) (lines[o + 1] + gy * grow - cam.y), z0 = (float) (lines[o + 2] + gz * grow - cam.z);
            float x1 = x0 + (axis == 0 ? len : 0), y1 = y0 + (axis == 1 ? len : 0), z1 = z0 + (axis == 2 ? len : 0);
            float nx = axis == 0 ? 1 : 0, ny = axis == 1 ? 1 : 0, nz = axis == 2 ? 1 : 0;
            b.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
            b.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }

    /**
     * An outline kept up to date off the render thread: {@link #changed()} when the blocks change,
     * and {@link #get()} gives the newest finished one (the old one until the new one is ready).
     */
    public static final class Cache {
        private final Supplier<@Nullable Cells> snapshot;
        private int version, built = -1;
        private boolean building;
        private @Nullable VoxelOutline outline;
        private boolean tooBig;

        /** @param snapshot copies the blocks as they are now (on the render thread), or null for none */
        public Cache(Supplier<@Nullable Cells> snapshot) {
            this.snapshot = snapshot;
        }

        public void changed() {
            version++;
        }

        /** The newest outline; null while there is none yet, or none to draw. */
        public @Nullable VoxelOutline get() {
            if (built != version && !building) {
                int v = version;
                Cells cells = snapshot.get();
                if (cells == null) {
                    outline = null;
                    tooBig = false;
                    built = v;
                } else {
                    building = true;
                    CompletableFuture.supplyAsync(() -> build(cells), Util.backgroundExecutor()).whenComplete((result, error) ->
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            building = false;
                            outline = result;
                            tooBig = error == null && result == null;
                            built = v;
                        }));
                }
            }
            return outline;
        }

        /** The last build gave up: more lines than are worth drawing. */
        public boolean tooBig() {
            return tooBig;
        }
    }
}
