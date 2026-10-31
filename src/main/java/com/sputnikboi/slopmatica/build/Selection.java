package com.sputnikboi.slopmatica.build;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The area to copy: two corner blocks, set one after the other with the corner key on the block
 * under the crosshair. Drawn as a white box with a red first and blue second corner.
 */
public final class Selection {
    public static final Selection INSTANCE = new Selection();

    private static final int BOX_COLOR = 0xFFFFFFFF;
    private static final int CORNER1_COLOR = 0xFFFF5050;
    private static final int CORNER2_COLOR = 0xFF5090FF;

    private @Nullable BlockPos first, second;
    private @Nullable String dimension;
    private boolean nextIsFirst = true;

    private Selection() {
    }

    public void setCorner(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        BlockPos pos;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            pos = hit.getBlockPos();
        } else {
            pos = mc.player.blockPosition();
        }
        String dim = mc.level.dimension().identifier().toString();
        if (!dim.equals(dimension)) {
            first = second = null;
            nextIsFirst = true;
            dimension = dim;
        }
        if (nextIsFirst) {
            first = pos;
        } else {
            second = pos;
        }
        nextIsFirst = !nextIsFirst;
        BoundingBox box = box();
        String size = box == null ? "" : String.format("  (%d x %d x %d)", box.getXSpan(), box.getYSpan(), box.getZSpan());
        SlopmaticaActions.actionBar(Component.literal("Corner " + (nextIsFirst ? 2 : 1) + " at " + pos.toShortString() + size).withColor(SlopmaticaActions.ACCENT));
    }

    public void clear() {
        first = second = null;
        nextIsFirst = true;
        SlopmaticaActions.actionBar(Component.literal("Selection cleared").withColor(SlopmaticaActions.ACCENT));
    }

    public @Nullable BoundingBox box() {
        return first != null && second != null ? BoundingBox.fromCorners(first, second) : null;
    }

    public @Nullable String dimension() {
        return dimension;
    }

    public void submit(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if ((first == null && second == null) || mc.level == null || !mc.level.dimension().identifier().toString().equals(dimension)) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth();
        PoseStack poseStack = context.poseStack();
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            BoundingBox box = box();
            if (box != null) {
                box(buffer, pose, cam, box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1, 0.002, BOX_COLOR, width);
            }
            if (first != null) {
                box(buffer, pose, cam, first.getX(), first.getY(), first.getZ(), first.getX() + 1, first.getY() + 1, first.getZ() + 1, 0.01, CORNER1_COLOR, width);
            }
            if (second != null) {
                box(buffer, pose, cam, second.getX(), second.getY(), second.getZ(), second.getX() + 1, second.getY() + 1, second.getZ() + 1, 0.01, CORNER2_COLOR, width);
            }
        });
    }

    private static void box(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, double x0, double y0, double z0, double x1, double y1, double z1,
                            double grow, int color, float width) {
        float ax = (float) (x0 - grow - cam.x), ay = (float) (y0 - grow - cam.y), az = (float) (z0 - grow - cam.z);
        float bx = (float) (x1 + grow - cam.x), by = (float) (y1 + grow - cam.y), bz = (float) (z1 + grow - cam.z);
        float[][] corners = {{ax, ay, az}, {bx, ay, az}, {bx, ay, bz}, {ax, ay, bz}, {ax, by, az}, {bx, by, az}, {bx, by, bz}, {ax, by, bz}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            float[] p = corners[e[0]], q = corners[e[1]];
            float nx = q[0] - p[0], ny = q[1] - p[1], nz = q[2] - p[2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= len;
            ny /= len;
            nz /= len;
            b.addVertex(pose, p[0], p[1], p[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
            b.addVertex(pose, q[0], q[1], q[2]).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }
}
