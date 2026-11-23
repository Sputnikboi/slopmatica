package com.sputnikboi.slopmatica.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sputnikboi.slopmatica.render.VoxelOutline;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class SelectionShapeTest {
    private static int count(SelectionShape s) {
        BoundingBox b = s.bounds();
        int n = 0;
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (s.contains(x, y, z)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @Test
    void ballsAndCylinders() {
        assertEquals(1, count(new SelectionShape.Ball(BlockPos.ZERO, 0)));
        assertEquals(19, count(new SelectionShape.Ball(BlockPos.ZERO, 1))); // A 3x3x3 cube without its 8 corners.
        var cyl = new SelectionShape.Cylinder(new BlockPos(5, 10, 5), 1, 10, 13);
        assertEquals(9 * 4, count(cyl));
        assertFalse(cyl.contains(5, 9, 5));
        assertTrue(cyl.contains(6, 13, 5));
    }

    @Test
    void prismTakesItsOutlineAndInside() {
        // An L: (0,0) (4,0) (4,2) (2,2) (2,4) (0,4), two high.
        var prism = SelectionShape.Prism.of(List.of(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 2),
            new BlockPos(2, 0, 2), new BlockPos(2, 0, 4), new BlockPos(0, 1, 4)));
        assertTrue(prism.contains(0, 0, 0));
        assertTrue(prism.contains(4, 1, 2));
        assertTrue(prism.contains(1, 1, 1));
        assertTrue(prism.contains(2, 0, 3)); // On the outline.
        assertFalse(prism.contains(3, 0, 3)); // In the L's notch.
        assertFalse(prism.contains(4, 0, 4));
        assertFalse(prism.contains(1, 2, 1));
        assertEquals(2 * (5 * 3 + 3 * 2), count(prism));
    }

    @Test
    void laterPartsWin() {
        // A 5x1x5 slab with its middle 3x1x3 cut out, then the very centre added back.
        List<SelectionShape.Part> parts = List.of(
            new SelectionShape.Part(new SelectionShape.Box(new BoundingBox(0, 0, 0, 4, 0, 4)), false),
            new SelectionShape.Part(new SelectionShape.Box(new BoundingBox(1, 0, 1, 3, 0, 3)), true),
            new SelectionShape.Part(new SelectionShape.Ball(new BlockPos(2, 0, 2), 0), false));
        Predicate<int[]> in = p -> {
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (parts.get(i).shape().contains(p[0], p[1], p[2])) {
                    return !parts.get(i).subtract();
                }
            }
            return false;
        };
        assertTrue(in.test(new int[] {0, 0, 0}));
        assertFalse(in.test(new int[] {1, 0, 1}));
        assertTrue(in.test(new int[] {2, 0, 2}));
    }

    private static VoxelOutline.Cells cells(SelectionShape s) {
        return new VoxelOutline.Cells() {
            @Override
            public LongIterator iterator() {
                LongArrayList all = new LongArrayList();
                BoundingBox b = s.bounds();
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        for (int x = b.minX(); x <= b.maxX(); x++) {
                            if (s.contains(x, y, z)) {
                                all.add(BlockPos.asLong(x, y, z));
                            }
                        }
                    }
                }
                return all.iterator();
            }

            @Override
            public boolean contains(int x, int y, int z) {
                return s.contains(x, y, z);
            }
        };
    }

    @Test
    void aBoxOutlinesAsTwelveLines() {
        VoxelOutline o = VoxelOutline.build(cells(new SelectionShape.Box(new BoundingBox(0, 0, 0, 9, 4, 6))));
        assertNotNull(o);
        assertEquals(12, o.lines());
    }

    @Test
    void anLShapeOutlinesItsCrease() {
        // Two boxes in an L (one block high): 6 outer corners and 1 inner one, each a vertical
        // edge, plus 6 + 6 horizontal runs top and bottom.
        SelectionShape.Cells l = new SelectionShape.Cells();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                if (x < 2 || z < 2) {
                    l.add(x, 0, z);
                }
            }
        }
        VoxelOutline o = VoxelOutline.build(cells(l));
        assertNotNull(o);
        assertEquals(6 + 6 + 6, o.lines());
    }
}
