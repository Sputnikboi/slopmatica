package com.sputnikboi.slopmatica.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sputnikboi.slopmatica.split.SplitAnalysis.Group;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Kind;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Result;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Type;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SplitAnalysisTest {
    private static final int SX = 48, SY = 24, SZ = 24;

    /** A made-up scene: flat ground with a hill, two houses, a wall with windows, a tree, a torch. */
    private static final class Scene implements SplitAnalysis.Source {
        final byte[] cls = new byte[SX * SY * SZ];
        final int[] state = new int[SX * SY * SZ];

        int index(int x, int y, int z) {
            return (y * SZ + z) * SX + x;
        }

        void set(int x, int y, int z, byte c, int s) {
            cls[index(x, y, z)] = c;
            state[index(x, y, z)] = s;
        }

        @Override
        public byte classAt(int x, int y, int z) {
            return cls[index(x, y, z)];
        }

        @Override
        public int stateAt(int x, int y, int z) {
            return state[index(x, y, z)];
        }

        Scene() {
            for (int x = 0; x < SX; x++) {
                for (int z = 0; z < SZ; z++) {
                    for (int y = 0; y < 4; y++) {
                        set(x, y, z, SplitAnalysis.NATURAL, 100);
                    }
                    // A cone of a hill around (38, 12), 14 high.
                    int top = 3 + Math.max(0, 14 - (Math.abs(x - 38) + Math.abs(z - 12)) * 2);
                    for (int y = 4; y <= top; y++) {
                        set(x, y, z, SplitAnalysis.NATURAL, 100);
                    }
                }
            }
            house(2);
            house(14);
            // A wall along x at z 20: pillar, window, window, again and again.
            for (int x = 0; x < 24; x++) {
                boolean pillar = x % 3 == 0;
                set(x, 4, 20, SplitAnalysis.FULL, 1);
                set(x, 5, 20, SplitAnalysis.FULL, pillar ? 1 : 2);
                set(x, 6, 20, SplitAnalysis.FULL, 1);
            }
            // A tree.
            for (int y = 4; y < 9; y++) {
                set(26, y, 4, SplitAnalysis.LOG, 50);
            }
            for (int x = 24; x <= 28; x++) {
                for (int z = 2; z <= 6; z++) {
                    for (int y = 8; y <= 10; y++) {
                        if (cls[index(x, y, z)] == SplitAnalysis.AIR) {
                            set(x, y, z, SplitAnalysis.LEAVES, 51);
                        }
                    }
                }
            }
            // A torch on its own, and a stone block floating inside the first house.
            set(11, 4, 14, SplitAnalysis.DETAIL, 60);
            set(5, 7, 5, SplitAnalysis.NATURAL, 100);
        }

        /** Hollow walls from x0 to x0+6 and z 2 to 8, y 4 to 8, under a roof of stairs. */
        void house(int x0) {
            for (int x = x0; x <= x0 + 6; x++) {
                for (int z = 2; z <= 8; z++) {
                    for (int y = 4; y <= 8; y++) {
                        if (x == x0 || x == x0 + 6 || z == 2 || z == 8) {
                            set(x, y, z, SplitAnalysis.FULL, 10);
                        }
                    }
                    set(x, 9, z, SplitAnalysis.SHAPE, 11);
                }
            }
        }
    }

    private static Result analyse(Scene scene) {
        return SplitAnalysis.analyse(SX, SY, SZ, scene, p -> { });
    }

    private static List<Group> of(Result r, Type type) {
        return r.groups().stream().filter(g -> g.type() == type).toList();
    }

    @Test
    void housesAndTheWallAreSeparateStructures() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        List<Group> structures = of(r, Type.STRUCTURE);
        assertEquals(3, structures.size(), "two houses and the wall: " + structures);
        int house1 = r.object(scene.index(2, 5, 5)), house2 = r.object(scene.index(14, 5, 5)), wall = r.object(scene.index(3, 5, 20));
        assertNotEquals(house1, house2);
        assertNotEquals(house1, wall);
        assertEquals(house1, r.object(scene.index(5, 9, 5)), "the roof is part of its house");
    }

    @Test
    void kinds() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        assertEquals(Kind.TERRAIN, r.kind(scene.index(0, 0, 0)));
        assertEquals(Kind.WALLS, r.kind(scene.index(2, 5, 5)));
        assertEquals(Kind.SHAPES, r.kind(scene.index(5, 9, 5)), "a roof of stairs");
        assertEquals(Kind.DETAIL, r.kind(scene.index(11, 4, 14)), "the torch");
        assertEquals(Kind.VEGETATION, r.kind(scene.index(26, 5, 4)), "a log with leaves is a tree");
        assertEquals(Kind.WALLS, r.kind(scene.index(5, 7, 5)), "stone up in a house is building material");
    }

    @Test
    void smallBitsGoTogether() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        int torch = r.object(scene.index(11, 4, 14));
        assertEquals(Type.SMALL, r.groups().stream().filter(g -> g.id() == torch).findFirst().orElseThrow().type());
        assertEquals(torch, r.object(scene.index(5, 7, 5)));
    }

    @Test
    void theHillIsAPeakAndTheFlatIsGround() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        List<Group> peaks = of(r, Type.PEAK);
        assertEquals(1, peaks.size(), peaks.toString());
        assertEquals(17, peaks.get(0).a(), "its top");
        assertEquals(peaks.get(0).id(), r.object(scene.index(38, 15, 12)));
        int ground = of(r, Type.GROUND).get(0).id();
        assertEquals(ground, r.object(scene.index(0, 2, 0)));
        assertEquals(ground, r.object(scene.index(38, 1, 12)), "under the hill, below its saddle");
    }

    @Test
    void theWindowsArePattern() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        List<Group> patterns = of(r, Type.PATTERN);
        assertEquals(1, patterns.size(), patterns.toString());
        Group p = patterns.get(0);
        assertEquals(3, p.a(), "module width");
        assertTrue(p.b() >= 7, "repeats: " + p.b());
        int col = 20 * SX + 4;
        assertTrue(r.patternX()[col] == p.id(), "the wall's columns are in it");
        assertEquals(-1, r.patternX()[5 * SX + 4], "a house wall isn't");
    }

    @Test
    void stoneWallsOnGrassAreBuiltAndAStoneMountainIsNot() {
        Scene scene = new Scene();
        // A stone tower ring on the ground (soil under it), and a solid stone block of a crag
        // standing straight from the bottom in the far corner, with no soil anywhere under it.
        for (int y = 4; y <= 10; y++) {
            for (int x = 30; x <= 33; x++) {
                for (int z = 18; z <= 21; z++) {
                    if (x == 30 || x == 33 || z == 18 || z == 21 || y == 4 || y == 10) { // with a floor and a roof
                        scene.set(x, y, z, SplitAnalysis.AMBIGUOUS, 200);
                    }
                }
            }
        }
        for (int y = 0; y <= 12; y++) {
            for (int x = 0; x <= 5; x++) {
                for (int z = 14; z <= 18; z++) {
                    scene.set(x, y, z, SplitAnalysis.AMBIGUOUS, 200);
                }
            }
        }
        // And a stepped stone hill on the grass, wide at its foot, with a one-block top.
        for (int k = 0; k <= 5; k++) {
            for (int x = 14 + k; x <= 24 - k; x++) {
                for (int z = 12 + k; z <= 22 - k; z++) {
                    if (scene.cls[scene.index(x, 4 + k, z)] == SplitAnalysis.AIR) {
                        scene.set(x, 4 + k, z, SplitAnalysis.AMBIGUOUS, 200);
                    }
                }
            }
        }
        Result r = analyse(scene);
        assertEquals(Kind.TERRAIN, r.kind(scene.index(19, 9, 17)), "the stone hill's top");
        assertEquals(Kind.WALLS, r.kind(scene.index(30, 8, 19)), "the tower");
        assertEquals(Type.STRUCTURE, typeOf(r, r.object(scene.index(30, 8, 19))));
        assertEquals(Kind.TERRAIN, r.kind(scene.index(2, 10, 16)), "the crag");
    }

    private static Type typeOf(Result r, int id) {
        return r.groups().stream().filter(g -> g.id() == id).findFirst().orElseThrow().type();
    }

    @Test
    void maskIsObjectsOfKinds() {
        Scene scene = new Scene();
        Result r = analyse(scene);
        boolean[] groups = new boolean[r.slots()];
        boolean[] kinds = new boolean[Kind.values().length];
        int house1 = r.object(scene.index(2, 5, 5));
        groups[house1] = true;
        kinds[Kind.SHAPES.ordinal()] = true;
        BitSet roof = r.mask(groups, kinds);
        assertEquals(49, roof.cardinality(), "just the first house's roof");
        assertTrue(roof.get(scene.index(5, 9, 5)));
        assertFalse(roof.get(scene.index(17, 9, 5)));

        Arrays.fill(kinds, true);
        Arrays.fill(groups, false);
        groups[of(r, Type.PATTERN).get(0).id()] = true;
        BitSet pattern = r.mask(groups, kinds);
        assertEquals(24 * 3, pattern.cardinality(), "the whole wall");
    }
}
