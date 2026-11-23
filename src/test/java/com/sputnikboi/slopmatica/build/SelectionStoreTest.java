package com.sputnikboi.slopmatica.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class SelectionStoreTest {
    /** Every block of the shape's bounds (one wider each way), as in or out. */
    private static String blocks(SelectionShape s) {
        BoundingBox b = s.bounds().inflatedBy(1);
        StringBuilder out = new StringBuilder(s.bounds().toString());
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    out.append(s.contains(x, y, z) ? '#' : '.');
                }
            }
        }
        return out.toString();
    }

    @Test
    void everyShapeComesBackTheSame() {
        SelectionShape.Cells cells = new SelectionShape.Cells();
        cells.add(1, 2, 3);
        cells.add(2, 2, 3);
        cells.add(-5, 70, 9);
        List<SelectionShape> shapes = List.of(
            new SelectionShape.Box(new BoundingBox(0, -60, 0, 4, -58, 7)),
            new SelectionShape.Ball(new BlockPos(3, 64, -2), 2.4),
            new SelectionShape.Ellipsoid(new BoundingBox(0, 0, 0, 6, 9, 3)),
            new SelectionShape.Cylinder(new BlockPos(10, 5, 10), 3, 2, 8),
            new SelectionShape.Tube(new BoundingBox(-3, 0, -1, 3, 4, 1)),
            SelectionShape.Prism.of(List.of(new BlockPos(0, 0, 0), new BlockPos(6, 0, 0), new BlockPos(0, 3, 5))),
            cells,
            cells.fit(new BoundingBox(0, 0, 0, 13, 137, 1)));
        for (SelectionShape s : shapes) {
            assertEquals(blocks(s), blocks(SelectionStore.shape(SelectionStore.json(s))), s.toString());
        }
    }
}
