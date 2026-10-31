package com.sputnikboi.slopmatica.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelOutlinesTest {
    private static final double P = 1 / 16.0;

    @Test
    void cubeHasTwelveEdges() {
        assertEquals(12, edgeCount(box(0, 0, 0, 16, 16, 16, "")));
    }

    @Test
    void duplicateOverlayLayerAddsNothing() {
        List<ModelOutlines.Quad> quads = box(0, 0, 0, 16, 16, 16, "");
        quads.addAll(box(0, 0, 0, 16, 16, 16, ""));
        assertEquals(12, edgeCount(quads));
    }

    /** Vanilla's stair: the lower box keeps its full top face, the upper box has no bottom. */
    @Test
    void stairIsAnLShapedPrismWithoutSeams() {
        List<ModelOutlines.Quad> quads = box(0, 0, 0, 16, 8, 16, "");
        quads.addAll(box(8, 8, 0, 16, 16, 16, "d"));
        float[] edges = ModelOutlines.trace(quads);
        assertEquals(18, edges.length / 6);
        // No line along the buried half of the lower box's top (y = 0.5, x > 0.5) on the sides.
        for (int i = 0; i < edges.length; i += 6) {
            boolean flat = Math.abs(edges[i + 1] - 0.5) < 1e-4 && Math.abs(edges[i + 4] - 0.5) < 1e-4;
            boolean buried = Math.min(edges[i], edges[i + 3]) > 0.5 + 1e-4 || (Math.abs(edges[i] - 1) < 1e-4 && Math.abs(edges[i + 3] - 1) < 1e-4);
            assertFalse(flat && buried, () -> "seam through the stair's side");
        }
    }

    /** A fence arm whose end is buried in the post: the post face gets the arm's footprint, nothing inside. */
    @Test
    void fenceArmJoinsPost() {
        List<ModelOutlines.Quad> quads = box(6, 0, 6, 10, 16, 10, "");
        quads.addAll(box(7, 12, 0, 9, 15, 8, ""));
        // Post: 12. Arm: 4 along its length, 4 round its free end, 4 where it meets the post.
        assertEquals(24, edgeCount(quads));
    }

    @Test
    void doubleSidedPlantQuadIsOneRectangle() {
        double[] diag = {0, 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0};
        double[] back = {0, 1, 0, 1, 1, 1, 1, 0, 1, 0, 0, 0};
        List<ModelOutlines.Quad> quads = new ArrayList<>();
        quads.add(ModelOutlines.Quad.of(diag, 1, 0, -1));
        quads.add(ModelOutlines.Quad.of(back, -1, 0, 1));
        assertEquals(4, edgeCount(quads));
    }

    private static int edgeCount(List<ModelOutlines.Quad> quads) {
        return ModelOutlines.trace(quads).length / 6;
    }

    /** An axis-aligned element in sixteenths; {@code omit} lists faces left out (d u n s w e). */
    private static List<ModelOutlines.Quad> box(double x0, double y0, double z0, double x1, double y1, double z1, String omit) {
        x0 *= P;
        y0 *= P;
        z0 *= P;
        x1 *= P;
        y1 *= P;
        z1 *= P;
        List<ModelOutlines.Quad> out = new ArrayList<>();
        if (!omit.contains("d")) out.add(ModelOutlines.Quad.of(new double[] {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, 0, -1, 0));
        if (!omit.contains("u")) out.add(ModelOutlines.Quad.of(new double[] {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}, 0, 1, 0));
        if (!omit.contains("n")) out.add(ModelOutlines.Quad.of(new double[] {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, 0, 0, -1));
        if (!omit.contains("s")) out.add(ModelOutlines.Quad.of(new double[] {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}, 0, 0, 1));
        if (!omit.contains("w")) out.add(ModelOutlines.Quad.of(new double[] {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, -1, 0, 0));
        if (!omit.contains("e")) out.add(ModelOutlines.Quad.of(new double[] {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}, 1, 0, 0));
        return out;
    }
}
