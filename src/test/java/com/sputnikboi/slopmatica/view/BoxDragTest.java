package com.sputnikboi.slopmatica.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoxDragTest {
    private static final BoundingBox BOX = new BoundingBox(0, 0, 0, 9, 4, 9);

    /** Looking at the east face from the side, then aiming further east, pulls that face out. */
    @Test
    void draggingTheEastFaceGrowsAndShrinksX() {
        Vec3 eye = new Vec3(20, 2.5, -10);
        BoxDrag drag = BoxDrag.grab(BOX, eye, new Vec3(10, 2.5, 5).subtract(eye).normalize());
        assertNotNull(drag);
        assertEquals(Direction.EAST, drag.face());
        // Aim at x = 13: the face follows to 13 (max block 12).
        BoundingBox grown = drag.follow(eye, new Vec3(13, 2.5, 5).subtract(eye).normalize());
        assertEquals(new BoundingBox(0, 0, 0, 12, 4, 9), grown);
        // Aim far inside: never thinner than one block.
        BoundingBox shrunk = drag.follow(eye, new Vec3(-30, 2.5, 5).subtract(eye).normalize());
        assertEquals(0, shrunk.maxX());
        assertEquals(0, shrunk.minX());
    }

    @Test
    void topFaceFromAbove() {
        Vec3 eye = new Vec3(5, 30, -20);
        BoxDrag drag = BoxDrag.grab(BOX, eye, new Vec3(5, 5, 5).subtract(eye).normalize());
        assertNotNull(drag);
        assertEquals(Direction.UP, drag.face());
    }

    @Test
    void missingTheBoxGrabsNothing() {
        assertNull(BoxDrag.grab(BOX, new Vec3(50, 50, 50), new Vec3(0, 1, 0)));
    }
}
