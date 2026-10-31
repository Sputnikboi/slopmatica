package com.sputnikboi.slopmatica.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;

/**
 * Outline edges traced from a block's baked model rather than its hitbox, so a stair, a lantern or
 * a brewing stand is outlined along the geometry you actually see.
 *
 * <p>An edge is kept where visible face lies on one side of it and not the other, within the
 * plane of the face. That drops the seams between a model's elements (the two boxes of a stair,
 * overlay layers, the front and back of a plant) and the faces vanilla leaves buried inside the
 * model (the top of a stair's lower box, under the upper one). Collinear pieces are then merged,
 * which also removes the duplicate an edge gets from the two faces meeting at it.
 */
final class ModelOutlines {
    private static final double EPS = 1.0E-4;
    /** How far off an edge or face coverage is sampled. Model features are at least 1/32 block. */
    private static final double SIDE = 1.0E-3;
    /** Hidden-face culling costs quads^3; past this, every face counts as visible. */
    private static final int MAX_CULLED_QUADS = 256;
    private static final Direction[] CULL_FACES = {
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    /** Keyed by the chosen model parts, which baking shares between states with the same look. */
    private static final Map<PartsKey, float[]> CACHE = new ConcurrentHashMap<>();
    private static volatile BlockStateModelSet cacheOwner;

    private ModelOutlines() {
    }

    /**
     * Edges as {@code x1 y1 z1 x2 y2 z2} runs in block-local coordinates, without the block's
     * random offset; empty if the model has no geometry (fluids, chests and other block-entity
     * blocks). Thread-safe.
     */
    static float[] edges(BlockStateModelSet models, BlockState state, long seed) {
        if (cacheOwner != models) {
            // Resource reload: the old parts are gone for good.
            CACHE.clear();
            cacheOwner = models;
        }
        List<BlockStateModelPart> parts = new ArrayList<>();
        models.get(state).collectParts(RandomSource.create(seed), parts);
        if (parts.isEmpty()) {
            return new float[0];
        }
        return CACHE.computeIfAbsent(new PartsKey(parts.toArray(BlockStateModelPart[]::new)), key -> trace(key.parts));
    }

    static float[] trace(BlockStateModelPart[] parts) {
        List<Quad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction face : CULL_FACES) {
                for (BakedQuad quad : part.getQuads(face)) {
                    Quad q = Quad.of(quad);
                    if (q != null) {
                        quads.add(q);
                    }
                }
            }
        }
        return trace(quads);
    }

    static float[] trace(List<Quad> quads) {
        Map<PlaneKey, List<Quad>> planes = new HashMap<>();
        for (Quad q : quads) {
            planes.computeIfAbsent(q.plane, k -> new ArrayList<>()).add(q);
        }
        List<Quad> occluders = quads.size() <= MAX_CULLED_QUADS ? quads : List.of();
        List<double[]> boundary = new ArrayList<>();
        for (List<Quad> plane : planes.values()) {
            // Candidates: the quads' own edges, plus the lines where other faces pass through
            // them (a fence arm entering its post), which can bound the visible part too.
            List<double[]> candidates = new ArrayList<>();
            for (Quad q : plane) {
                for (int v = 0; v < 4; v++) {
                    int w = (v + 1) & 3;
                    candidates.add(new double[] {q.p[v * 3], q.p[v * 3 + 1], q.p[v * 3 + 2], q.p[w * 3], q.p[w * 3 + 1], q.p[w * 3 + 2]});
                }
                for (Quad o : occluders) {
                    double[] cut = o.plane.equals(q.plane) ? null : q.intersection(o);
                    if (cut != null) {
                        candidates.add(cut);
                    }
                }
            }
            Quad face = plane.getFirst();
            for (double[] segment : candidates) {
                boundaryPieces(segment, face, plane, candidates, occluders, boundary);
            }
        }
        return mergeCollinear(boundary);
    }

    /**
     * Splits a candidate edge wherever the coverage beside it can change, and keeps the pieces
     * with visible face on exactly one side. Coverage changes where another candidate in the plane
     * touches or crosses this one, or where another face pierces it.
     */
    private static void boundaryPieces(double[] seg, Quad face, List<Quad> plane, List<double[]> candidates, List<Quad> occluders, List<double[]> out) {
        double ax = seg[0], ay = seg[1], az = seg[2];
        double dx = seg[3] - ax, dy = seg[4] - ay, dz = seg[5] - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < EPS) {
            return;
        }
        List<Double> cuts = new ArrayList<>(List.of(0.0, 1.0));
        for (double[] other : candidates) {
            if (other != seg) {
                addPlanarCuts(cuts, ax, ay, az, dx, dy, dz, len, other);
            }
        }
        for (Quad o : occluders) {
            if (o.plane.equals(face.plane)) {
                continue;
            }
            double denom = o.nx * dx + o.ny * dy + o.nz * dz;
            if (Math.abs(denom) < 1.0E-9) {
                continue;
            }
            double t = (o.nx * (o.p[0] - ax) + o.ny * (o.p[1] - ay) + o.nz * (o.p[2] - az)) / denom;
            if (t > 0 && t < 1 && o.contains(ax + dx * t, ay + dy * t, az + dz * t, 1.0E-5)) {
                cuts.add(t);
            }
        }
        double[] ts = cuts.stream().mapToDouble(Double::doubleValue).filter(t -> t >= 0 && t <= 1).sorted().toArray();

        // In-plane direction perpendicular to the edge.
        double px = face.ny * dz - face.nz * dy, py = face.nz * dx - face.nx * dz, pz = face.nx * dy - face.ny * dx;
        double pl = Math.sqrt(px * px + py * py + pz * pz) / SIDE;
        px /= pl;
        py /= pl;
        pz /= pl;
        for (int k = 0; k + 1 < ts.length; k++) {
            double t0 = ts[k], t1 = ts[k + 1];
            if ((t1 - t0) * len < EPS) {
                continue;
            }
            double tm = (t0 + t1) / 2;
            double mx = ax + dx * tm, my = ay + dy * tm, mz = az + dz * tm;
            if (visible(plane, occluders, mx + px, my + py, mz + pz) != visible(plane, occluders, mx - px, my - py, mz - pz)) {
                out.add(new double[] {ax + dx * t0, ay + dy * t0, az + dz * t0, ax + dx * t1, ay + dy * t1, az + dz * t1});
            }
        }
    }

    /**
     * Cuts at the ends of {@code o} where they lie on the edge being split, and where {@code o}
     * crosses it.
     */
    private static void addPlanarCuts(List<Double> cuts, double ax, double ay, double az, double dx, double dy, double dz, double len, double[] o) {
        double len2 = len * len;
        for (int e = 0; e < 6; e += 3) {
            double rx = o[e] - ax, ry = o[e + 1] - ay, rz = o[e + 2] - az;
            double t = (rx * dx + ry * dy + rz * dz) / len2;
            double ox = rx - dx * t, oy = ry - dy * t, oz = rz - dz * t;
            if (ox * ox + oy * oy + oz * oz < EPS * EPS) {
                cuts.add(t);
            }
        }
        double ex = o[3] - o[0], ey = o[4] - o[1], ez = o[5] - o[2];
        double cx = dy * ez - dz * ey, cy = dz * ex - dx * ez, cz = dx * ey - dy * ex;
        double c2 = cx * cx + cy * cy + cz * cz;
        if (c2 < 1.0E-12) {
            return;
        }
        double rx = o[0] - ax, ry = o[1] - ay, rz = o[2] - az;
        double t = ((ry * ez - rz * ey) * cx + (rz * ex - rx * ez) * cy + (rx * ey - ry * ex) * cz) / c2;
        double u = ((ry * dz - rz * dy) * cx + (rz * dx - rx * dz) * cy + (rx * dy - ry * dx) * cz) / c2;
        if (u > -EPS && u < 1 + EPS) {
            cuts.add(t);
        }
    }

    /** Whether some face in the plane covers the point and that spot of it can be seen. */
    private static boolean visible(List<Quad> plane, List<Quad> occluders, double x, double y, double z) {
        for (Quad q : plane) {
            if (q.contains(x, y, z, 0) && !enclosed(occluders, q, x + q.nx * SIDE, y + q.ny * SIDE, z + q.nz * SIDE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a point just in front of {@code from} is inside the model: along every axis, the
     * nearest face is seen from behind. Faces in {@code from}'s own plane are skipped, since
     * vanilla leaves out the face between two touching boxes (a stair's upper box has no bottom).
     */
    private static boolean enclosed(List<Quad> occluders, Quad from, double x, double y, double z) {
        if (occluders.isEmpty()) {
            return false;
        }
        for (int axis = 0; axis < 6; axis++) {
            double rx = axis == 0 ? 1 : axis == 1 ? -1 : 0;
            double ry = axis == 2 ? 1 : axis == 3 ? -1 : 0;
            double rz = axis == 4 ? 1 : axis == 5 ? -1 : 0;
            double nearest = Double.MAX_VALUE;
            boolean behind = false;
            for (Quad o : occluders) {
                if (o.plane.equals(from.plane)) {
                    continue;
                }
                double denom = o.nx * rx + o.ny * ry + o.nz * rz;
                if (Math.abs(denom) < 1.0E-9) {
                    continue;
                }
                double t = (o.nx * (o.p[0] - x) + o.ny * (o.p[1] - y) + o.nz * (o.p[2] - z)) / denom;
                if (t <= 1.0E-7 || t > nearest + 1.0E-7 || !o.contains(x + rx * t, y + ry * t, z + rz * t, 1.0E-7)) {
                    continue;
                }
                if (t < nearest - 1.0E-7) {
                    nearest = t;
                    behind = denom > 0;
                } else {
                    behind |= denom > 0;
                }
            }
            if (!behind) {
                return false;
            }
        }
        return true;
    }

    /** Unions overlapping pieces on the same line into single segments. */
    private static float[] mergeCollinear(List<double[]> segments) {
        Map<LineKey, List<double[]>> lines = new HashMap<>();
        for (double[] s : segments) {
            double dx = s[3] - s[0], dy = s[4] - s[1], dz = s[5] - s[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len;
            dy /= len;
            dz /= len;
            if (dx < -EPS || (Math.abs(dx) < EPS && (dy < -EPS || (Math.abs(dy) < EPS && dz < 0)))) {
                dx = -dx;
                dy = -dy;
                dz = -dz;
            }
            double t0 = s[0] * dx + s[1] * dy + s[2] * dz;
            double t1 = s[3] * dx + s[4] * dy + s[5] * dz;
            // The point on the line closest to the origin identifies it.
            double cx = s[0] - t0 * dx, cy = s[1] - t0 * dy, cz = s[2] - t0 * dz;
            LineKey key = new LineKey(q(dx), q(dy), q(dz), q(cx), q(cy), q(cz));
            lines.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[] {Math.min(t0, t1), Math.max(t0, t1), dx, dy, dz, cx, cy, cz});
        }
        List<double[]> merged = new ArrayList<>();
        for (List<double[]> spans : lines.values()) {
            spans.sort((a, b) -> Double.compare(a[0], b[0]));
            double[] line = spans.getFirst();
            double start = line[0], end = line[1];
            for (int i = 1; i <= spans.size(); i++) {
                if (i < spans.size() && spans.get(i)[0] <= end + EPS) {
                    end = Math.max(end, spans.get(i)[1]);
                    continue;
                }
                merged.add(new double[] {
                    line[5] + start * line[2], line[6] + start * line[3], line[7] + start * line[4],
                    line[5] + end * line[2], line[6] + end * line[3], line[7] + end * line[4]
                });
                if (i < spans.size()) {
                    start = spans.get(i)[0];
                    end = spans.get(i)[1];
                }
            }
        }
        float[] result = new float[merged.size() * 6];
        for (int i = 0; i < merged.size(); i++) {
            for (int j = 0; j < 6; j++) {
                result[i * 6 + j] = (float) merged.get(i)[j];
            }
        }
        return result;
    }

    private static long q(double v) {
        return Math.round(v * 2048);
    }

    /** A model quad in block space with its outward unit normal. */
    static final class Quad {
        final double[] p;
        final double nx, ny, nz;
        final PlaneKey plane;
        /** The two axes the quad is projected onto for inside tests. */
        private final int ua, va;

        Quad(double[] p, double nx, double ny, double nz) {
            this.p = p;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.plane = PlaneKey.of(nx, ny, nz, p);
            int drop = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0 : Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
            this.ua = drop == 0 ? 1 : 0;
            this.va = drop == 2 ? 1 : 2;
        }

        static Quad of(BakedQuad quad) {
            double[] p = new double[12];
            for (int v = 0; v < 4; v++) {
                Vector3fc pos = quad.position(v);
                p[v * 3] = pos.x();
                p[v * 3 + 1] = pos.y();
                p[v * 3 + 2] = pos.z();
            }
            Direction d = quad.direction();
            return of(p, d.getStepX(), d.getStepY(), d.getStepZ());
        }

        /** {@code (fx, fy, fz)} is roughly the way the face points; only its sign matters. */
        static Quad of(double[] p, double fx, double fy, double fz) {
            // Newell's method: robust for any quad, including ones with a repeated vertex.
            double nx = 0, ny = 0, nz = 0;
            for (int v = 0; v < 4; v++) {
                int w = (v + 1) & 3;
                nx += (p[v * 3 + 1] - p[w * 3 + 1]) * (p[v * 3 + 2] + p[w * 3 + 2]);
                ny += (p[v * 3 + 2] - p[w * 3 + 2]) * (p[v * 3] + p[w * 3]);
                nz += (p[v * 3] - p[w * 3]) * (p[v * 3 + 1] + p[w * 3 + 1]);
            }
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0E-7) {
                return null;
            }
            double sign = nx * fx + ny * fy + nz * fz < 0 ? -1 : 1;
            return new Quad(p, nx / len * sign, ny / len * sign, nz / len * sign);
        }

        /** Where {@code o} passes through this quad, as a segment, or null if it doesn't. */
        double[] intersection(Quad o) {
            double[] dist = new double[4];
            for (int v = 0; v < 4; v++) {
                dist[v] = nx * (o.p[v * 3] - p[0]) + ny * (o.p[v * 3 + 1] - p[1]) + nz * (o.p[v * 3 + 2] - p[2]);
            }
            // Where o's outline meets this quad's plane: its two ends on that plane.
            List<double[]> hits = new ArrayList<>();
            for (int v = 0; v < 4; v++) {
                int w = (v + 1) & 3;
                if (Math.abs(dist[v]) < EPS) {
                    hits.add(new double[] {o.p[v * 3], o.p[v * 3 + 1], o.p[v * 3 + 2]});
                } else if (Math.abs(dist[w]) >= EPS && (dist[v] > 0) != (dist[w] > 0)) {
                    double t = dist[v] / (dist[v] - dist[w]);
                    hits.add(new double[] {
                        o.p[v * 3] + (o.p[w * 3] - o.p[v * 3]) * t,
                        o.p[v * 3 + 1] + (o.p[w * 3 + 1] - o.p[v * 3 + 1]) * t,
                        o.p[v * 3 + 2] + (o.p[w * 3 + 2] - o.p[v * 3 + 2]) * t
                    });
                }
            }
            double[] a = null, b = null;
            double best = EPS * EPS;
            for (int i = 0; i < hits.size(); i++) {
                for (int j = i + 1; j < hits.size(); j++) {
                    double[] h = hits.get(i), k = hits.get(j);
                    double d2 = (h[0] - k[0]) * (h[0] - k[0]) + (h[1] - k[1]) * (h[1] - k[1]) + (h[2] - k[2]) * (h[2] - k[2]);
                    if (d2 > best) {
                        best = d2;
                        a = h;
                        b = k;
                    }
                }
            }
            if (a == null) {
                return null;
            }
            // Clip to this quad (Cyrus-Beck against its four edges).
            double cx = (p[0] + p[3] + p[6] + p[9]) / 4, cy = (p[1] + p[4] + p[7] + p[10]) / 4, cz = (p[2] + p[5] + p[8] + p[11]) / 4;
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double t0 = 0, t1 = 1;
            for (int v = 0; v < 4; v++) {
                int w = (v + 1) & 3;
                double ex = p[w * 3] - p[v * 3], ey = p[w * 3 + 1] - p[v * 3 + 1], ez = p[w * 3 + 2] - p[v * 3 + 2];
                // In-plane normal of this edge, flipped to point inside.
                double mx = ny * ez - nz * ey, my = nz * ex - nx * ez, mz = nx * ey - ny * ex;
                if (mx * (cx - p[v * 3]) + my * (cy - p[v * 3 + 1]) + mz * (cz - p[v * 3 + 2]) < 0) {
                    mx = -mx;
                    my = -my;
                    mz = -mz;
                }
                double start = mx * (a[0] - p[v * 3]) + my * (a[1] - p[v * 3 + 1]) + mz * (a[2] - p[v * 3 + 2]);
                double rate = mx * dx + my * dy + mz * dz;
                if (Math.abs(rate) < 1.0E-12) {
                    if (start < 0) {
                        return null;
                    }
                } else if (rate > 0) {
                    t0 = Math.max(t0, -start / rate);
                } else {
                    t1 = Math.min(t1, -start / rate);
                }
            }
            if ((t1 - t0) * Math.sqrt(best) < EPS) {
                return null;
            }
            return new double[] {a[0] + dx * t0, a[1] + dy * t0, a[2] + dz * t0, a[0] + dx * t1, a[1] + dy * t1, a[2] + dz * t1};
        }

        /** Point-in-quad for a point on the quad's plane; model quads are convex. */
        boolean contains(double x, double y, double z, double tolerance) {
            double u = ua == 0 ? x : y;
            double v = va == 1 ? y : z;
            int pos = 0, neg = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                double eu = p[j * 3 + ua] - p[i * 3 + ua], ev = p[j * 3 + va] - p[i * 3 + va];
                double el = Math.sqrt(eu * eu + ev * ev);
                if (el < 1.0E-9) {
                    continue;
                }
                double c = (eu * (v - p[i * 3 + va]) - ev * (u - p[i * 3 + ua])) / el;
                if (c > tolerance) {
                    pos++;
                } else if (c < -tolerance) {
                    neg++;
                }
            }
            return pos == 0 || neg == 0;
        }
    }

    /** Both sides of a plane share a key, so a quad and its back face merge. */
    record PlaneKey(long nx, long ny, long nz, long d) {
        static PlaneKey of(double nx, double ny, double nz, double[] p) {
            if (nx < -EPS || (Math.abs(nx) < EPS && (ny < -EPS || (Math.abs(ny) < EPS && nz < 0)))) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            return new PlaneKey(q(nx), q(ny), q(nz), q(nx * p[0] + ny * p[1] + nz * p[2]));
        }
    }

    private record LineKey(long dx, long dy, long dz, long cx, long cy, long cz) {
    }

    /** Model parts compared by identity: equal keys are literally the same baked geometry. */
    private record PartsKey(BlockStateModelPart[] parts) {
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PartsKey other) || other.parts.length != parts.length) {
                return false;
            }
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] != other.parts[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int h = 1;
            for (BlockStateModelPart p : parts) {
                h = h * 31 + System.identityHashCode(p);
            }
            return h;
        }
    }
}
