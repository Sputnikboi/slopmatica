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
        var view = com.sputnikboi.slopmatica.view.PlacementView.INSTANCE;
        if (view.freecam()) {
            // In the build view your body is elsewhere: the corner is what the camera looks at.
            pos = view.cameraPointed(mc);
            if (pos == null) {
                SlopmaticaActions.actionBar(Component.translatable("slopmatica.selection.look_at_block").withColor(SlopmaticaActions.ERROR));
                return;
            }
            setCorner(pos, mc.level.dimension().identifier().toString());
            return;
        }
        boolean looking = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
        switch (com.sputnikboi.slopmatica.config.SlopmaticaConfig.get().cornerMode) {
            case FEET -> pos = mc.player.blockPosition();
            case LOOK -> {
                if (!looking) {
                    SlopmaticaActions.actionBar(Component.translatable("slopmatica.selection.look_at_block").withColor(SlopmaticaActions.ERROR));
                    return;
                }
                pos = ((BlockHitResult) mc.hitResult).getBlockPos();
            }
            default -> pos = looking ? ((BlockHitResult) mc.hitResult).getBlockPos() : mc.player.blockPosition();
        }
        setCorner(pos, mc.level.dimension().identifier().toString());
    }

    /** Sets the next corner (first, then second, then first again...) to a block. */
    public void setCorner(BlockPos pos, String dim) {
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

    /** Everything about the selection, for undo. */
    public record State(@Nullable BlockPos first, @Nullable BlockPos second, @Nullable String dimension, boolean nextIsFirst) {
    }

    public State state() {
        return new State(first, second, dimension, nextIsFirst);
    }

    public void restore(State state) {
        first = state.first();
        second = state.second();
        dimension = state.dimension();
        nextIsFirst = state.nextIsFirst();
    }

    /**
     * Shrinks the selection to the blocks in it: all-air layers at any side are trimmed off.
     * Returns false if there is no selection or nothing but air in it.
     */
    private static boolean ghostAt(java.util.List<com.sputnikboi.slopmatica.placement.Placement.Transform> ghosts, int x, int y, int z) {
        for (var t : ghosts) {
            net.minecraft.world.level.block.state.BlockState state = t.stateAt(x, y, z);
            if (state != null && !state.isAir()) {
                return true;
            }
        }
        return false;
    }

    public boolean fitToBlocks(net.minecraft.world.level.Level level) {
        BoundingBox box = box();
        if (box == null) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Unbuilt parts of a shown build count too, so a selection fits a build before it exists.
        java.util.List<com.sputnikboi.slopmatica.placement.Placement.Transform> ghosts = level instanceof net.minecraft.client.multiplayer.ClientLevel client
            ? com.sputnikboi.slopmatica.verify.SchematicRaycast.visibleTransforms(client) : java.util.List.of();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir() || ghostAt(ghosts, x, y, z)) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
        }
        if (minX == Integer.MAX_VALUE) {
            return false;
        }
        first = new BlockPos(minX, minY, minZ);
        second = new BlockPos(maxX, maxY, maxZ);
        nextIsFirst = true;
        return true;
    }

    /** Replaces the whole selection with a box (the Modify tool). */
    public void set(BoundingBox box, String dim) {
        dimension = dim;
        first = new BlockPos(box.minX(), box.minY(), box.minZ());
        second = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
        nextIsFirst = true;
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

    /** A line box from (x0, y0, z0) to (x1, y1, z1), grown by {@code grow} so it doesn't z-fight with block faces. */
    public static void box(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, double x0, double y0, double z0, double x1, double y1, double z1,
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
