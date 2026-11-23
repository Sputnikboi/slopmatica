package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * Smooth shapes for the GUI, drawn as geometry rather than textures, so they stay sharp at any GUI
 * scale and size: rings, ring sectors (pie slices with a hole) and lines. Edges fade out over one
 * real pixel, which is the anti-aliasing. Collect shapes, then {@link #submit} them as one element.
 */
public final class GuiShapes {
    /** Corners as x, y pairs (four per quad), and a colour per corner. */
    private float[] xy = new float[256];
    private int[] colors = new int[128];
    private int quads;
    /** One real pixel, in GUI pixels: how wide edges fade. */
    private final float px;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

    public GuiShapes() {
        px = (float) (1.0 / Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()));
    }

    /** One real pixel in GUI pixels. */
    public float pixel() {
        return px;
    }

    private void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        // Wound the way vanilla's rectangles are (negative area on the y-down screen), whichever way it came.
        float area = x0 * y1 - x1 * y0 + x1 * y2 - x2 * y1 + x2 * y3 - x3 * y2 + x3 * y0 - x0 * y3;
        if (area > 0) {
            float tx = x1, ty = y1;
            int tc = c1;
            x1 = x3;
            y1 = y3;
            c1 = c3;
            x3 = tx;
            y3 = ty;
            c3 = tc;
        }
        if ((quads + 1) * 8 > xy.length) {
            xy = Arrays.copyOf(xy, xy.length * 2);
            colors = Arrays.copyOf(colors, colors.length * 2);
        }
        int o = quads * 8, c = quads * 4;
        xy[o] = x0;
        xy[o + 1] = y0;
        xy[o + 2] = x1;
        xy[o + 3] = y1;
        xy[o + 4] = x2;
        xy[o + 5] = y2;
        xy[o + 6] = x3;
        xy[o + 7] = y3;
        colors[c] = c0;
        colors[c + 1] = c1;
        colors[c + 2] = c2;
        colors[c + 3] = c3;
        quads++;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, xy[o + i]);
            maxX = Math.max(maxX, xy[o + i]);
            minY = Math.min(minY, xy[o + i + 1]);
            maxY = Math.max(maxY, xy[o + i + 1]);
        }
    }

    private static int clear(int color) {
        return color & 0x00FFFFFF;
    }

    /**
     * A band between two radii from angle {@code a0} to {@code a1} (radians, clockwise from the top),
     * one colour inside and another outside, without fading.
     */
    private void band(float cx, float cy, float r0, float r1, double a0, double a1, int inner, int outer) {
        int steps = Math.max(1, (int) Math.ceil((a1 - a0) / (2 * Math.PI) * Math.clamp(r1 / px / 2, 48, 360)));
        for (int i = 0; i < steps; i++) {
            double s = a0 + (a1 - a0) * i / steps, e = a0 + (a1 - a0) * (i + 1) / steps;
            float ss = (float) Math.sin(s), sc = (float) -Math.cos(s), es = (float) Math.sin(e), ec = (float) -Math.cos(e);
            // Counter-clockwise on screen (y down), as the GUI's quads are wound.
            quad(cx + ss * r0, cy + sc * r0, inner, cx + ss * r1, cy + sc * r1, outer, cx + es * r1, cy + ec * r1, outer, cx + es * r0, cy + ec * r0, inner);
        }
    }

    /** A ring sector from radius r0 to r1 and angle a0 to a1, its round edges smoothed (r0 0: a pie slice). */
    public void sector(float cx, float cy, float r0, float r1, double a0, double a1, int color) {
        if (r0 > 0) {
            band(cx, cy, Math.max(0, r0 - px), r0, a0, a1, clear(color), color);
        }
        band(cx, cy, r0, r1, a0, a1, color, color);
        band(cx, cy, r1, r1 + px, a0, a1, color, clear(color));
    }

    /** A whole ring (or a disc, with r0 0). */
    public void ring(float cx, float cy, float r0, float r1, int color) {
        sector(cx, cy, r0, r1, 0, 2 * Math.PI, color);
    }

    /** A straight line {@code width} GUI pixels wide, its long edges smoothed. */
    public void line(float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) {
            return;
        }
        float nx = -dy / len, ny = dx / len, h = width / 2;
        strip(x0, y0, x1, y1, nx, ny, -h, h, color, color);
        strip(x0, y0, x1, y1, nx, ny, h, h + px, color, clear(color));
        strip(x0, y0, x1, y1, nx, ny, -h - px, -h, clear(color), color);
    }

    private void strip(float x0, float y0, float x1, float y1, float nx, float ny, float o0, float o1, int c0, int c1) {
        quad(x0 + nx * o0, y0 + ny * o0, c0, x0 + nx * o1, y0 + ny * o1, c1, x1 + nx * o1, y1 + ny * o1, c1, x1 + nx * o0, y1 + ny * o0, c0);
    }

    /** Hands everything collected to the GUI, drawn where the graphics' pose and scissor are now. */
    public void submit(GuiGraphicsExtractor g) {
        if (quads == 0) {
            return;
        }
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        ScreenRectangle scissor = g.scissorStack.peek();
        ScreenRectangle bounds = new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
            (int) Math.ceil(maxX - minX) + 1, (int) Math.ceil(maxY - minY) + 1).transformMaxBounds(pose);
        g.guiRenderState.addGuiElement(new Shapes(Arrays.copyOf(xy, quads * 8), Arrays.copyOf(colors, quads * 4), quads, pose,
            scissor, scissor != null ? scissor.intersection(bounds) : bounds));
    }

    private record Shapes(float[] xy, int[] colors, int quads, Matrix3x2fc pose, @Nullable ScreenRectangle scissorArea,
                          @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (int i = 0; i < quads * 4; i++) {
                consumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(colors[i]);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }
}
