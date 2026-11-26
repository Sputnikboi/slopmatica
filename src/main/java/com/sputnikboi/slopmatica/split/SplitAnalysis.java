package com.sputnikboi.slopmatica.split;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Splits a box of blocks into parts (experimental), with no Minecraft in it so it can be tested on
 * made-up grids. Every block gets a <b>kind</b> (terrain, vegetation, walls, roof shapes, detail)
 * and belongs to one <b>object</b> (a structure, the small loose bits, a terrain peak, the rest of
 * the ground, water, trees, plants). Separately, blocks of walls that repeat a module along a line
 * (pillar, window, window, pillar...) belong to a <b>pattern</b>. A part is then "these objects and
 * patterns, of these kinds".
 *
 * <ul>
 *   <li>Terrain: natural blocks (stone, dirt, sand, ores, water...) joined to the bottom of the
 *   box or in a big mass. A few stone blocks up in a house are building material instead, and
 *   so are stone (sandstone, terracotta...) walls standing on soil: see {@link #settleAmbiguous}.</li>
 *   <li>Trees: logs joined to leaves. Logs without leaves are building material.</li>
 *   <li>Structures: everything built, split where nothing built joins it to the rest (26
 *   neighbours), so separate houses come out separate. Tiny ones go together as small objects.</li>
 *   <li>Peaks: the terrain's height map, cut at its saddles: a peak is everything above the
 *   lowest point you'd have to go down to reach higher ground (its prominence). Peaks less
 *   prominent than a tenth of the terrain's relief (at least 5) are part of the ground.</li>
 *   <li>Patterns: each column's built blocks hashed; along every row and column of the box,
 *   stretches where the column sequence repeats a module of 2 to 16 columns at least three times.
 *   The same module anywhere (either way round) is one pattern.</li>
 * </ul>
 *
 * Grid cells are indexed {@code (y * sz + z) * sx + x}, as {@code BlockVolume} orders them.
 */
public final class SplitAnalysis {
    // What a block is, as the caller classifies it.
    public static final byte AIR = 0, NATURAL = 1, FLUID = 2, LEAVES = 3, LOG = 4, PLANT = 5, FULL = 6, SHAPE = 7, DETAIL = 8;
    /** Natural, but a common building material too (stone, sandstone, terracotta...): see {@link #settleAmbiguous}. */
    public static final byte AMBIGUOUS = 9;

    /** What a block is for, in the result. */
    public enum Kind {
        TERRAIN, VEGETATION, WALLS, SHAPES, DETAIL
    }

    public enum Type {
        STRUCTURE, SMALL, PEAK, GROUND, WATER, TREES, PLANTS, PATTERN
    }

    /**
     * An object or pattern.
     *
     * @param number  1, 2, 3... among its type (structures by size, peaks by height, patterns by size)
     * @param a       peak: its top (grid y); pattern: its module width
     * @param b       peak: its prominence; pattern: how many times it repeats, in all
     */
    public record Group(int id, Type type, int number, long count, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int a, int b) {
        public boolean pattern() {
            return type == Type.PATTERN;
        }
    }

    /**
     * @param cells     per cell: its kind ({@code ordinal + 1}, 0 for air) in the low 4 bits, its object's group id + 1 above
     * @param patternX  per column ({@code z * sx + x}): the pattern group its built blocks are in along x, or -1
     * @param patternZ  the same along z
     * @param slots     how many group ids there are (some may have no blocks and aren't listed)
     */
    public record Result(int sx, int sy, int sz, int[] cells, int[] patternX, int[] patternZ, List<Group> groups, int slots) {
        public Kind kind(int index) {
            int k = cells[index] & 15;
            return k == 0 ? null : Kind.values()[k - 1];
        }

        public int object(int index) {
            return (cells[index] >>> 4) - 1;
        }

        /**
         * The cells in the given objects or patterns ({@code groups} by id) and of the given kinds
         * ({@code kinds} by ordinal), as bits by cell index.
         */
        public BitSet mask(boolean[] groups, boolean[] kinds) {
            BitSet out = new BitSet(cells.length);
            boolean anyPattern = false;
            for (Group g : this.groups) {
                anyPattern |= g.pattern() && groups[g.id()];
            }
            int layer = sx * sz;
            for (int i = 0; i < cells.length; i++) {
                int c = cells[i];
                int k = c & 15;
                if (k == 0 || !kinds[k - 1]) {
                    continue;
                }
                boolean in = groups[(c >>> 4) - 1];
                if (!in && anyPattern && k >= 3) {
                    int col = i % layer;
                    in = patternX[col] >= 0 && groups[patternX[col]] || patternZ[col] >= 0 && groups[patternZ[col]];
                }
                if (in) {
                    out.set(i);
                }
            }
            return out;
        }
    }

    /** What the caller provides: each cell's class, and an id per block state (equal ids: the same block). */
    public interface Source {
        byte classAt(int x, int y, int z);

        int stateAt(int x, int y, int z);
    }

    static final int MIN_STRUCTURE = 24, MAX_STRUCTURES = 300, MAX_PEAKS = 40, MAX_PATTERNS = 60, MIN_PATTERN_CELLS = 24;
    static final int MAX_PERIOD = 16, MIN_REPEATS = 3;
    private static final int BIG_NATURAL = 512;

    private final int sx, sy, sz, layer;
    /** Class (low 4 bits) and a working label (above) per cell. */
    private final int[] g;
    private final DoubleConsumer progress;

    private SplitAnalysis(int sx, int sy, int sz, DoubleConsumer progress) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.layer = sx * sz;
        this.g = new int[Math.multiplyExact(layer, sy)];
        this.progress = progress;
    }

    public static Result analyse(int sx, int sy, int sz, Source source, DoubleConsumer progress) {
        return new SplitAnalysis(sx, sy, sz, progress).run(source);
    }

    private int cls(int i) {
        return g[i] & 15;
    }

    private void setCls(int i, int c) {
        g[i] = (g[i] & ~15) | c;
    }

    private int label(int i) {
        return g[i] >>> 4;
    }

    private void setLabel(int i, int l) {
        g[i] = (g[i] & 15) | (l << 4);
    }

    private void clearLabels() {
        for (int i = 0; i < g.length; i++) {
            g[i] &= 15;
        }
    }

    private static boolean built(int c) {
        return c == FULL || c == SHAPE || c == DETAIL;
    }

    private Result run(Source source) {
        for (int y = 0, i = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++, i++) {
                    g[i] = source.classAt(x, y, z);
                }
            }
            progress.accept(0.25 * (y + 1) / sy);
        }
        settleAmbiguous();
        settleNatural();
        settleTrees();
        progress.accept(0.35);

        List<Group> groups = new ArrayList<>();
        structures(groups);
        progress.accept(0.5);
        terrain(groups);
        progress.accept(0.6);
        int trees = addGroup(groups, Type.TREES, 1, 0, 0), plants = addGroup(groups, Type.PLANTS, 1, 0, 0);
        for (int i = 0; i < g.length; i++) {
            int c = cls(i);
            if (c == LEAVES || c == LOG) {
                setLabel(i, trees + 1);
            } else if (c == PLANT) {
                setLabel(i, plants + 1);
            }
        }
        int[] patternX = new int[layer], patternZ = new int[layer];
        patterns(source, groups, patternX, patternZ);
        progress.accept(0.85);
        kinds();
        progress.accept(0.95);
        List<Group> finished = measure(groups, patternX, patternZ);
        progress.accept(1);
        return new Result(sx, sy, sz, g, patternX, patternZ, finished, groups.size());
    }

    private static int addGroup(List<Group> groups, Type type, int number, int a, int b) {
        groups.add(new Group(groups.size(), type, number, 0, 0, 0, 0, 0, 0, 0, a, b));
        return groups.size() - 1;
    }

    // ---- Flood fill ------------------------------------------------------------------------------

    /** Cells joined to {@code start} (6 or 26 neighbours) whose class passes {@code member}, labelled {@code label}; returns them. */
    private IntArrayList flood(int start, int label, java.util.function.IntPredicate member, boolean diagonal, IntArrayList out) {
        out.clear();
        setLabel(start, label);
        out.add(start);
        for (int head = 0; head < out.size(); head++) {
            int i = out.getInt(head);
            int x = i % sx, z = (i / sx) % sz, y = i / layer;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < 0 || ny >= sy) {
                    continue;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= sz) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= sx || (dx == 0 && dy == 0 && dz == 0)) {
                            continue;
                        }
                        if (!diagonal && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
                            continue;
                        }
                        int n = (ny * sz + nz) * sx + nx;
                        if (label(n) == 0 && member.test(cls(n))) {
                            setLabel(n, label);
                            out.add(n);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Stone and the like: building material where it stands on soil (some plainly natural block
     * lower in its column) in a lump that is mostly thin, under a third of the 7x7 around each
     * block at its height natural (walls, towers); otherwise natural. A bare stone mountain has no
     * soil under it, and an outcrop or a stony peak on a grassy hill is mostly thick.
     */
    private void settleAmbiguous() {
        BitSet onSoil = new BitSet(g.length);
        boolean any = false;
        for (int col = 0; col < layer; col++) {
            boolean soil = false;
            for (int y = 0; y < sy; y++) {
                int i = y * layer + col, c = cls(i);
                if (c == NATURAL) {
                    soil = true;
                } else if (c == AMBIGUOUS) {
                    any = true;
                    if (soil) {
                        onSoil.set(i);
                    }
                }
            }
        }
        if (!any) {
            return;
        }
        // Lumps of it on soil (6 neighbours): a building if most of the lump is thin, terrain if
        // most is thick (a hill's rim and top are thin too, but they're the lesser part of it).
        BitSet built = new BitSet(g.length);
        IntArrayList cells = new IntArrayList();
        for (int i = onSoil.nextSetBit(0); i >= 0; i = onSoil.nextSetBit(i + 1)) {
            if (label(i) != 0) {
                continue;
            }
            flood(i, 1, c -> c == AMBIGUOUS, false, cells);
            int thin = 0, counted = 0;
            for (int k = 0; k < cells.size(); k++) {
                int c = cells.getInt(k);
                if (onSoil.get(c)) {
                    counted++;
                    if (thinAt(c)) {
                        thin++;
                    }
                }
            }
            if (thin >= 0.6 * counted) {
                for (int k = 0; k < cells.size(); k++) {
                    if (onSoil.get(cells.getInt(k))) {
                        built.set(cells.getInt(k));
                    }
                }
            }
        }
        clearLabels();
        for (int i = 0; i < g.length; i++) {
            if (cls(i) == AMBIGUOUS) {
                setCls(i, built.get(i) ? FULL : NATURAL);
            }
        }
    }

    private boolean thinAt(int i) {
        int x = i % sx, z = (i / sx) % sz, y = i / layer, solid = 0;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                int nx = x + dx, nz = z + dz;
                if (nx >= 0 && nz >= 0 && nx < sx && nz < sz) {
                    int c = cls((y * sz + nz) * sx + nx);
                    if (c == NATURAL || c == AMBIGUOUS || c == FLUID) {
                        solid++;
                    }
                }
            }
        }
        // Of the 7x7 around it: a wall up to two thick or a small tower fills under a third, a
        // cliff edge (over half) or a hill's corner (a third) more.
        return solid < 0.32 * 49;
    }

    /** Natural blocks not joined to the bottom or a big natural mass are building material (or, water, a detail). */
    private void settleNatural() {
        IntArrayList cells = new IntArrayList();
        for (int i = 0; i < g.length; i++) {
            if ((cls(i) == NATURAL || cls(i) == FLUID) && label(i) == 0) {
                flood(i, 1, c -> c == NATURAL || c == FLUID, false, cells);
                boolean ground = cells.size() >= BIG_NATURAL;
                for (int k = 0; k < cells.size() && !ground; k++) {
                    ground = cells.getInt(k) / layer <= 1;
                }
                if (!ground) {
                    for (int k = 0; k < cells.size(); k++) {
                        int c = cells.getInt(k);
                        setCls(c, cls(c) == FLUID ? DETAIL : FULL);
                    }
                }
            }
        }
        clearLabels();
    }

    /** Logs joined to (enough) leaves are trees; other logs are building material. */
    private void settleTrees() {
        IntArrayList cells = new IntArrayList();
        for (int i = 0; i < g.length; i++) {
            if (cls(i) == LOG && label(i) == 0) {
                flood(i, 1, c -> c == LOG || c == LEAVES, true, cells);
                int logs = 0, leaves = 0;
                for (int k = 0; k < cells.size(); k++) {
                    if (cls(cells.getInt(k)) == LOG) {
                        logs++;
                    } else {
                        leaves++;
                    }
                }
                if (leaves * 2 < logs) {
                    for (int k = 0; k < cells.size(); k++) {
                        int c = cells.getInt(k);
                        if (cls(c) == LOG) {
                            setCls(c, FULL);
                        }
                    }
                }
            }
        }
        clearLabels();
    }

    // ---- Objects -------------------------------------------------------------------------------

    /** Built blocks joined together (26 neighbours): the biggest are structures, the rest one group of small objects. */
    private void structures(List<Group> groups) {
        IntArrayList cells = new IntArrayList();
        IntArrayList sizes = new IntArrayList();
        sizes.add(0); // label 0: none
        for (int i = 0; i < g.length; i++) {
            if (built(cls(i)) && label(i) == 0) {
                flood(i, sizes.size(), SplitAnalysis::built, true, cells);
                sizes.add(cells.size());
            }
        }
        Integer[] order = new Integer[sizes.size() - 1];
        for (int k = 0; k < order.length; k++) {
            order[k] = k + 1;
        }
        Arrays.sort(order, Comparator.comparingInt((Integer l) -> sizes.getInt(l)).reversed());
        int[] remap = new int[sizes.size()];
        int small = -1, number = 0;
        for (Integer l : order) {
            if (sizes.getInt(l) >= MIN_STRUCTURE && number < MAX_STRUCTURES) {
                remap[l] = addGroup(groups, Type.STRUCTURE, ++number, 0, 0);
            } else {
                if (small < 0) {
                    small = addGroup(groups, Type.SMALL, 1, 0, 0);
                }
                remap[l] = small;
            }
        }
        for (int i = 0; i < g.length; i++) {
            int l = label(i);
            if (l != 0) {
                setLabel(i, remap[l] + 1);
            }
        }
    }

    /** Water, peaks (by prominence on the terrain's height map) and the rest of the ground. */
    private void terrain(List<Group> groups) {
        int[] height = new int[layer];
        Arrays.fill(height, -1);
        boolean anyWater = false, anyNatural = false;
        for (int i = 0; i < g.length; i++) {
            int c = cls(i);
            if (c == NATURAL) {
                height[i % layer] = i / layer; // Upwards: the last one is the top.
                anyNatural = true;
            } else if (c == FLUID) {
                anyWater = true;
            }
        }
        int water = anyWater ? addGroup(groups, Type.WATER, 1, 0, 0) : -1;
        int[] peakOf = new int[layer], saddleOf = new int[layer];
        Arrays.fill(peakOf, -1);
        List<int[]> peaks = anyNatural ? findPeaks(height, peakOf, saddleOf) : List.of();
        // Numbered from the highest.
        peaks.sort(Comparator.comparingInt((int[] p) -> -p[1]));
        int[] peakGroup = new int[peaks.size()];
        int number = 0;
        for (int[] p : peaks) {
            peakGroup[p[0]] = addGroup(groups, Type.PEAK, ++number, p[1], p[2]);
        }
        int ground = anyNatural ? addGroup(groups, Type.GROUND, 1, 0, 0) : -1;
        for (int i = 0; i < g.length; i++) {
            int c = cls(i);
            if (c == FLUID) {
                setLabel(i, water + 1);
            } else if (c == NATURAL) {
                int col = i % layer, peak = peakOf[col];
                setLabel(i, (peak >= 0 && i / layer > saddleOf[col] ? peakGroup[peak] : ground) + 1);
            }
        }
    }

    /**
     * Peaks on a height map by prominence: columns joined from the highest down (union-find);
     * where two areas meet, the one with the lower top is a peak if it rose far enough above
     * that saddle. Its columns are marked with the peak and the saddle height. Returns each peak
     * as {index, top height, prominence}.
     */
    private List<int[]> findPeaks(int[] height, int[] peakOf, int[] saddleOf) {
        int minH = Integer.MAX_VALUE, maxH = -1, n = 0;
        int[] counts = new int[sy + 1];
        for (int h : height) {
            if (h >= 0) {
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
                counts[h]++;
                n++;
            }
        }
        int prominence = Math.max(5, Math.round((maxH - minH) / 10F));
        // The ground's level: a quarter of the columns are at or below it.
        int base = minH;
        for (int h = 0, seen = 0; h <= sy; h++) {
            seen += counts[h];
            if (seen * 4 >= n) {
                base = h;
                break;
            }
        }
        // Columns from the highest down (counting sort by height).
        int[] order = new int[n];
        int[] fill = new int[sy + 1];
        int pos = 0;
        for (int h = maxH; h >= minH; h--) {
            fill[h] = pos;
            pos += counts[h];
        }
        for (int col = 0; col < layer; col++) {
            if (height[col] >= 0) {
                order[fill[height[col]]++] = col;
            }
        }
        int[] parent = new int[layer], next = new int[layer], tail = new int[layer], topH = new int[layer], lowH = new int[layer];
        boolean[] done = new boolean[layer];
        List<int[]> peaks = new ArrayList<>();
        for (int col : order) {
            int h = height[col];
            parent[col] = col;
            next[col] = -1;
            tail[col] = col;
            topH[col] = h;
            lowH[col] = h;
            done[col] = true;
            int x = col % sx, z = col / sx;
            int[] around = {x > 0 ? col - 1 : -1, x < sx - 1 ? col + 1 : -1, z > 0 ? col - sx : -1, z < sz - 1 ? col + sx : -1};
            for (int nb : around) {
                if (nb < 0 || !done[nb]) {
                    continue;
                }
                int a = find(parent, col), b = find(parent, nb);
                if (a == b) {
                    continue;
                }
                // a: the area with the higher top; b is cut off at this saddle.
                if (topH[b] > topH[a]) {
                    int t = a;
                    a = b;
                    b = t;
                }
                if (topH[b] - h >= prominence && peaks.size() < MAX_PEAKS) {
                    int index = peaks.size();
                    peaks.add(new int[] {index, topH[b], topH[b] - h});
                    for (int c = b; c >= 0; c = next[c]) {
                        if (peakOf[c] < 0) {
                            peakOf[c] = index;
                            saddleOf[c] = h;
                        }
                    }
                }
                parent[b] = a;
                next[tail[a]] = b;
                tail[a] = tail[b];
                lowH[a] = Math.min(lowH[a], lowH[b]);
            }
        }
        // What's left on top of each separate piece of terrain: a peak if high enough above the ground's level.
        for (int col : order) {
            if (find(parent, col) == col) {
                int saddle = Math.max(base, lowH[col]);
                if (topH[col] - saddle >= prominence && peaks.size() < MAX_PEAKS) {
                    int index = peaks.size();
                    peaks.add(new int[] {index, topH[col], topH[col] - saddle});
                    for (int c = col; c >= 0; c = next[c]) {
                        if (peakOf[c] < 0) {
                            peakOf[c] = index;
                            saddleOf[c] = saddle;
                        }
                    }
                }
            }
        }
        return peaks;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    // ---- Patterns ------------------------------------------------------------------------------

    private void patterns(Source source, List<Group> groups, int[] patternX, int[] patternZ) {
        Arrays.fill(patternX, -1);
        Arrays.fill(patternZ, -1);
        // Each column's built blocks, hashed (with their heights).
        long[] sig = new long[layer];
        for (int i = 0; i < g.length; i++) {
            if (built(cls(i))) {
                int col = i % layer, y = i / layer;
                sig[col] = mix(sig[col] * 31 + mix(((long) y << 32) ^ (source.stateAt(col % sx, y, col / sx) + 1L)));
            }
        }
        Long2IntOpenHashMap keys = new Long2IntOpenHashMap();
        keys.defaultReturnValue(-1);
        List<int[]> found = new ArrayList<>(); // per key: {period, repeats}
        long[] line = new long[Math.max(sx, sz)];
        int[] mark = new int[line.length];
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                line[x] = sig[z * sx + x];
            }
            scanLine(line, sx, mark, keys, found);
            for (int x = 0; x < sx; x++) {
                patternX[z * sx + x] = mark[x];
            }
        }
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                line[z] = sig[z * sx + x];
            }
            scanLine(line, sz, mark, keys, found);
            for (int z = 0; z < sz; z++) {
                patternZ[z * sx + x] = mark[z];
            }
        }
        // Count each pattern's blocks; keep the big ones, biggest first.
        long[] cells = new long[found.size()];
        for (int i = 0; i < g.length; i++) {
            if (built(cls(i))) {
                int col = i % layer;
                if (patternX[col] >= 0) {
                    cells[patternX[col]]++;
                }
                if (patternZ[col] >= 0 && patternZ[col] != patternX[col]) {
                    cells[patternZ[col]]++;
                }
            }
        }
        Integer[] order = new Integer[found.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        Arrays.sort(order, Comparator.comparingLong((Integer k) -> cells[k]).reversed());
        int[] remap = new int[found.size()];
        Arrays.fill(remap, -1);
        int number = 0;
        for (Integer k : order) {
            if (cells[k] >= MIN_PATTERN_CELLS && found.get(k)[1] >= MIN_REPEATS && number < MAX_PATTERNS) {
                remap[k] = addGroup(groups, Type.PATTERN, ++number, found.get(k)[0], found.get(k)[1]);
            }
        }
        for (int col = 0; col < layer; col++) {
            patternX[col] = patternX[col] >= 0 ? remap[patternX[col]] : -1;
            patternZ[col] = patternZ[col] >= 0 ? remap[patternZ[col]] : -1;
        }
    }

    /**
     * Marks stretches of {@code line} that repeat a module of 2..16 columns at least three times
     * (the shortest module that fits wins) with the module's key; -1 elsewhere.
     */
    private static void scanLine(long[] line, int n, int[] mark, Long2IntOpenHashMap keys, List<int[]> found) {
        Arrays.fill(mark, 0, n, -1);
        // run[p][x]: how many columns from x on equal the one p further.
        int maxP = Math.min(MAX_PERIOD, n / MIN_REPEATS);
        if (maxP < 2) {
            return;
        }
        int[][] run = new int[maxP + 1][n + 1];
        for (int p = 1; p <= maxP; p++) {
            for (int x = n - 1 - p; x >= 0; x--) {
                run[p][x] = line[x] != 0 && line[x] == line[x + p] ? run[p][x + 1] + 1 : 0;
            }
        }
        int x = 0;
        while (x < n) {
            int chosen = 0;
            for (int p = 2; p <= maxP && chosen == 0; p++) {
                // Periodic for run + p columns; a module that is all one column is just a plain wall.
                if (run[p][x] + p >= MIN_REPEATS * p && run[1][x] < p - 1 && nonEmpty(line, x, p)) {
                    chosen = p;
                }
            }
            if (chosen == 0) {
                x++;
                continue;
            }
            int span = run[chosen][x] + chosen;
            long key = moduleKey(line, x, chosen);
            int id = keys.get(key);
            if (id < 0) {
                id = found.size();
                keys.put(key, id);
                found.add(new int[] {chosen, 0});
            }
            found.get(id)[1] += span / chosen;
            for (int k = x; k < x + span; k++) {
                mark[k] = id;
            }
            x += span;
        }
    }

    private static boolean nonEmpty(long[] line, int x, int p) {
        for (int k = x; k < x + p; k++) {
            if (line[k] == 0) {
                return false;
            }
        }
        return true;
    }

    /** The same module whichever column it starts at and whichever way it runs. */
    private static long moduleKey(long[] line, int x, int p) {
        long best = Long.MAX_VALUE;
        for (int dir = 0; dir < 2; dir++) {
            for (int r = 0; r < p; r++) {
                long h = p;
                for (int k = 0; k < p; k++) {
                    int at = dir == 0 ? x + (r + k) % p : x + (r - k + p) % p;
                    h = mix(h * 1_000_003 + line[at]);
                }
                best = Math.min(best, h);
            }
        }
        return best;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // ---- Kinds and sizes -----------------------------------------------------------------------

    /** Classes become kinds. Stairs and slabs in a run (2+ of them around, diagonals too) are roofs and such; on their own, detail. */
    private void kinds() {
        BitSet loneShape = new BitSet(g.length);
        for (int i = 0; i < g.length; i++) {
            if (cls(i) == SHAPE && shapesAround(i) < 2) {
                loneShape.set(i);
            }
        }
        for (int i = 0; i < g.length; i++) {
            Kind kind = switch (cls(i)) {
                case NATURAL, FLUID -> Kind.TERRAIN;
                case LEAVES, LOG, PLANT -> Kind.VEGETATION;
                case FULL -> Kind.WALLS;
                case SHAPE -> loneShape.get(i) ? Kind.DETAIL : Kind.SHAPES;
                case DETAIL -> Kind.DETAIL;
                default -> null;
            };
            setCls(i, kind == null ? 0 : kind.ordinal() + 1);
        }
    }

    private int shapesAround(int i) {
        int x = i % sx, z = (i / sx) % sz, y = i / layer, n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if ((dx | dy | dz) != 0 && nx >= 0 && ny >= 0 && nz >= 0 && nx < sx && ny < sy && nz < sz && cls((ny * sz + nz) * sx + nx) == SHAPE) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Fills in each group's block count and bounds; drops the empty ones (ids stay as they are). */
    private List<Group> measure(List<Group> groups, int[] patternX, int[] patternZ) {
        int n = groups.size();
        long[] count = new long[n];
        int[][] box = new int[n][];
        for (int i = 0; i < g.length; i++) {
            int c = g[i];
            if ((c & 15) == 0) {
                continue;
            }
            int x = i % sx, z = (i / sx) % sz, y = i / layer;
            grow(box, count, (c >>> 4) - 1, x, y, z);
            if ((c & 15) >= Kind.WALLS.ordinal() + 1) {
                int col = i % layer;
                if (patternX[col] >= 0) {
                    grow(box, count, patternX[col], x, y, z);
                }
                if (patternZ[col] >= 0 && patternZ[col] != patternX[col]) {
                    grow(box, count, patternZ[col], x, y, z);
                }
            }
        }
        List<Group> out = new ArrayList<>();
        for (Group gr : groups) {
            int[] b = box[gr.id()];
            if (b != null) {
                out.add(new Group(gr.id(), gr.type(), gr.number(), count[gr.id()], b[0], b[1], b[2], b[3], b[4], b[5], gr.a(), gr.b()));
            }
        }
        return out;
    }

    private static void grow(int[][] box, long[] count, int id, int x, int y, int z) {
        count[id]++;
        int[] b = box[id];
        if (b == null) {
            box[id] = new int[] {x, y, z, x, y, z};
            return;
        }
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
    }
}
