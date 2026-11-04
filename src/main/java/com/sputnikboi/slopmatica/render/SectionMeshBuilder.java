package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.verify.BlockMatcher;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Builds the ghost-block and outline geometry for one world section. Runs on worker threads. */
final class SectionMeshBuilder {
    private static final int S = WorldSnapshot.SIZE;
    private static final int GHOST_BUFFER_START = 64 * 1024;
    private static final int LINE_BUFFER_START = 32 * 1024;

    /**
     * Cube edges as bit indices: 0-3 run along X (bit = y*2+z), 4-7 along Y (4 + x*2+z),
     * 8-11 along Z (8 + x*2+y). Each face owns the four edges around it, indexed by Direction.
     */
    private static final int[] FACE_EDGES = new int[6];

    static {
        FACE_EDGES[Direction.DOWN.ordinal()] = bits(0, 1, 8, 10);
        FACE_EDGES[Direction.UP.ordinal()] = bits(2, 3, 9, 11);
        FACE_EDGES[Direction.NORTH.ordinal()] = bits(0, 2, 4, 6);
        FACE_EDGES[Direction.SOUTH.ordinal()] = bits(1, 3, 5, 7);
        FACE_EDGES[Direction.WEST.ordinal()] = bits(4, 5, 8, 9);
        FACE_EDGES[Direction.EAST.ordinal()] = bits(6, 7, 10, 11);
    }

    record Input(
        long sectionKey,
        int version,
        SectionPos section,
        List<Placement.Transform> transforms,
        WorldSnapshot world,
        ClientLevel level,
        BlockStateModelSet blockModels,
        FluidStateModelSet fluidModels,
        BlockColors blockColors,
        float lineWidth,
        /** Indexed by BlockStatus ordinal: whether that status gets an outline at all. */
        boolean[] outlined
    ) {
    }

    /**
     * A missing block whose looks come (partly) from a block-entity renderer, drawn per frame.
     * {@code data} is the schematic's block entity data for it (sign text, banner patterns...), if any.
     */
    record SpecialGhost(BlockPos pos, BlockState state, @Nullable CompoundTag data) {
    }

    /**
     * Owns native memory until closed; must be uploaded or closed on the render thread.
     * {@code lines} holds missing/match outlines, which fade with distance; {@code alerts} holds
     * variant/wrong outlines, which stay visible from anywhere.
     */
    record Output(
        long sectionKey, int version,
        @Nullable MeshData ghosts, @Nullable MeshData lines, @Nullable MeshData alerts,
        List<SpecialGhost> specials,
        ByteBufferBuilder[] buffers
    ) implements AutoCloseable {
        static Output empty(long sectionKey, int version) {
            return new Output(sectionKey, version, null, null, null, List.of(), new ByteBufferBuilder[0]);
        }

        @Override
        public void close() {
            for (MeshData mesh : new MeshData[] {ghosts, lines, alerts}) {
                if (mesh != null) {
                    mesh.close();
                }
            }
            for (ByteBufferBuilder buffer : buffers) {
                buffer.close();
            }
        }
    }

    private final Input in;
    private final BlockState[] expected = new BlockState[S * S * S];
    /** As {@link #expected}, but with the schematic's air too: what ghost fluids flow against. */
    private final BlockState[] shown = new BlockState[S * S * S];
    private final BlockStatus[] status = new BlockStatus[S * S * S];
    private final Map<BlockState, Boolean> fullCubes = new IdentityHashMap<>();

    private SectionMeshBuilder(Input in) {
        this.in = in;
    }

    static Output build(Input in) {
        return new SectionMeshBuilder(in).run();
    }

    private Output run() {
        WorldSnapshot world = in.world();
        boolean any = false;
        for (int y = 0; y < S; y++) {
            for (int z = 0; z < S; z++) {
                for (int x = 0; x < S; x++) {
                    BlockState want = expectedAt(world.baseX + x, world.baseY + y, world.baseZ + z);
                    int i = WorldSnapshot.index(x, y, z);
                    shown[i] = want;
                    if (want == null || want.isAir()) {
                        continue;
                    }
                    expected[i] = want;
                    status[i] = BlockMatcher.compare(want, world.get(i));
                    any = true;
                }
            }
        }

        if (!any) {
            return Output.empty(in.sectionKey(), in.version());
        }

        ByteBufferBuilder ghostBuffer = new ByteBufferBuilder(GHOST_BUFFER_START);
        ByteBufferBuilder lineBuffer = new ByteBufferBuilder(LINE_BUFFER_START);
        ByteBufferBuilder alertBuffer = new ByteBufferBuilder(LINE_BUFFER_START);
        BufferBuilder ghosts = new BufferBuilder(ghostBuffer, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        BufferBuilder lines = new BufferBuilder(lineBuffer, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
        BufferBuilder alerts = new BufferBuilder(alertBuffer, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
        GhostBlockView view = new GhostBlockView(in.level(), world, expected, false);
        GhostBlockView fluidView = new GhostBlockView(in.level(), world, shown, true);
        // No ambient occlusion: ghosts are lit flat, which keeps the vanilla look without dark seams.
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, in.blockColors());
        FluidRenderer fluidRenderer = new FluidRenderer(in.fluidModels());
        BlockQuadOutput quadOutput = ghosts::putBlockBakedQuad;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<SpecialGhost> specials = new ArrayList<>();

        for (int y = 1; y <= 16; y++) {
            for (int z = 1; z <= 16; z++) {
                for (int x = 1; x <= 16; x++) {
                    int i = WorldSnapshot.index(x, y, z);
                    BlockStatus st = status[i];
                    if (st == null) {
                        continue;
                    }
                    BlockState want = expected[i];
                    pos.set(world.baseX + x, world.baseY + y, world.baseZ + z);
                    if (st == BlockStatus.MISSING) {
                        FluidState fluid = want.getFluidState();
                        if (!fluid.isEmpty()) {
                            fluidRenderer.tesselate(fluidView, pos, layer -> ghosts, want, fluid);
                        }
                        if (want.getRenderShape() == RenderShape.MODEL) {
                            blockRenderer.tesselateBlock(quadOutput, x - 1, y - 1, z - 1, view, pos, want, in.blockModels().get(want), want.getSeed(pos));
                        }
                        if (want.hasBlockEntity()) {
                            specials.add(new SpecialGhost(pos.immutable(), want, tileEntityAt(pos.getX(), pos.getY(), pos.getZ(), want)));
                        }
                    }
                    if (in.outlined()[st.ordinal()]) {
                        outline(st == BlockStatus.VARIANT || st == BlockStatus.WRONG ? alerts : lines, x, y, z, i, st, pos);
                    }
                }
            }
        }
        return new Output(in.sectionKey(), in.version(), ghosts.build(), lines.build(), alerts.build(), List.copyOf(specials),
            new ByteBufferBuilder[] {ghostBuffer, lineBuffer, alertBuffer});
    }

    private @Nullable BlockState expectedAt(int wx, int wy, int wz) {
        BlockState found = null;
        for (Placement.Transform t : in.transforms()) {
            BlockState s = t.shownStateAt(wx, wy, wz);
            if (s != null) {
                if (!s.isAir()) {
                    return s;
                }
                found = s;
            }
        }
        return found;
    }

    /** The block entity data of whichever placement supplied {@code want} at this position. */
    private @Nullable CompoundTag tileEntityAt(int wx, int wy, int wz, BlockState want) {
        for (Placement.Transform t : in.transforms()) {
            if (t.shownStateAt(wx, wy, wz) == want) {
                return t.tileEntityAt(wx, wy, wz);
            }
        }
        return null;
    }

    /**
     * Heads have no model and a standing head's hitbox doesn't turn, so they get their own: the
     * 8x8x8 head box turned the way the head faces (16 steps), and an arrow on top pointing
     * where the face looks.
     */
    private void skullOutline(BufferBuilder lines, float ox, float oy, float oz, BlockState state, int color) {
        double yaw;
        double cx = 0.5, cz = 0.5, y0 = 0, y1 = 0.5;
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)) {
            yaw = Math.toRadians(net.minecraft.world.level.block.state.properties.RotationSegment.convertToDegrees(
                state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16)));
        } else {
            // On a wall: against the block behind it, a quarter block up, facing away from it.
            Direction facing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            yaw = Math.toRadians(facing.toYRot());
            cx = 0.5 - facing.getStepX() * 0.25;
            cz = 0.5 - facing.getStepZ() * 0.25;
            y0 = 0.25;
            y1 = 0.75;
        }
        // Forward (where the face looks) and right, from the yaw: 0 is south, +z.
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -fz, rz = fx;
        double[][] corners = new double[4][];
        int k = 0;
        for (int a : new int[] {-1, 1}) {
            for (int b : new int[] {-1, 1}) {
                corners[k++] = new double[] {cx + 0.25 * (a * fx + b * rx), cz + 0.25 * (a * fz + b * rz)};
            }
        }
        int[][] ring = {{0, 1}, {1, 3}, {3, 2}, {2, 0}};
        for (int[] e : ring) {
            double[] p = corners[e[0]], q = corners[e[1]];
            line(lines, ox + (float) p[0], oy + (float) y0, oz + (float) p[1], ox + (float) q[0], oy + (float) y0, oz + (float) q[1], color);
            line(lines, ox + (float) p[0], oy + (float) y1, oz + (float) p[1], ox + (float) q[0], oy + (float) y1, oz + (float) q[1], color);
            line(lines, ox + (float) p[0], oy + (float) y0, oz + (float) p[1], ox + (float) p[0], oy + (float) y1, oz + (float) p[1], color);
        }
        // The arrow on top: a shaft from the middle to near the front, and two barbs.
        float ty = oy + (float) y1 + 0.01F;
        double tipX = cx + 0.2 * fx, tipZ = cz + 0.2 * fz;
        line(lines, ox + (float) (cx - 0.12 * fx), ty, oz + (float) (cz - 0.12 * fz), ox + (float) tipX, ty, oz + (float) tipZ, color);
        for (int side : new int[] {-1, 1}) {
            line(lines, ox + (float) tipX, ty, oz + (float) tipZ,
                ox + (float) (tipX - 0.1 * fx + side * 0.08 * rx), ty, oz + (float) (tipZ - 0.1 * fz + side * 0.08 * rz), color);
        }
    }

    private void outline(BufferBuilder lines, int x, int y, int z, int i, BlockStatus st, BlockPos pos) {
        int color = st.color();
        float ox = x - 1, oy = y - 1, oz = z - 1;
        BlockState shape = outlineState(i);
        if (!fullCube(i)) {
            // Everything that isn't a plain cube is outlined along its model, which is what tells
            // you which way a stair should face or where a lantern hangs. The random offset of
            // plants is looked up at the real position so the outline lines up with the block.
            Vec3 offset = shape.getOffset(pos);
            float[] edges = ModelOutlines.edges(in.blockModels(), shape, shape.getSeed(pos));
            if (edges.length > 0) {
                float bx = ox + (float) offset.x, by = oy + (float) offset.y, bz = oz + (float) offset.z;
                for (int e = 0; e < edges.length; e += 6) {
                    line(lines, bx + edges[e], by + edges[e + 1], bz + edges[e + 2], bx + edges[e + 3], by + edges[e + 4], bz + edges[e + 5], color);
                }
                return;
            }
            if (shape.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock) {
                skullOutline(lines, ox, oy, oz, shape, color);
                return;
            }
            // No model geometry (chests, signs and other block-entity blocks): use the hitbox.
            shape.getShape(EmptyBlockGetter.INSTANCE, pos).forAllEdges((x1, y1, z1, x2, y2, z2) ->
                line(lines, ox + (float) x1, oy + (float) y1, oz + (float) z1, ox + (float) x2, oy + (float) y2, oz + (float) z2, color));
            return;
        }

        // Full cubes: skip the faces buried against a neighbour with the same status that is also
        // a cube, so a solid wall reads as a grid on its surface rather than a lattice through its body.
        int edges = 0;
        for (Direction d : Direction.values()) {
            int n = WorldSnapshot.index(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (status[n] != st || !fullCube(n)) {
                edges |= FACE_EDGES[d.ordinal()];
            }
        }
        for (int e = 0; e < 12; e++) {
            if ((edges & (1 << e)) == 0) {
                continue;
            }
            int a = (e & 3) >> 1, b = e & 1;
            switch (e >> 2) {
                case 0 -> line(lines, ox, oy + a, oz + b, ox + 1, oy + a, oz + b, color);
                case 1 -> line(lines, ox + a, oy, oz + b, ox + a, oy + 1, oz + b, color);
                default -> line(lines, ox + a, oy + b, oz, ox + a, oy + b, oz + 1, color);
            }
        }
    }

    /**
     * The block whose shape the outline follows. Normally the schematic's: for a variant that
     * shows how the block should be turned, since its ghost isn't drawn. A wrong block outlines
     * what is actually there instead, the thing to remove; the intended shape is often buried
     * inside it (a lantern where a stone stands) and would never be seen.
     */
    private BlockState outlineState(int i) {
        return status[i] == BlockStatus.WRONG ? in.world().get(i) : expected[i];
    }

    /**
     * Whether a cell is outlined as a plain cube: its block's hitbox is a full block, or it has
     * neither a hitbox nor a model (fluids), in which case a box is all there is to draw.
     */
    private boolean fullCube(int i) {
        if (status[i] == null) {
            return false;
        }
        BlockState s = outlineState(i);
        return fullCubes.computeIfAbsent(s, state -> {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (!shape.isEmpty()) {
                return Block.isShapeFullBlock(shape);
            }
            return state.getRenderShape() != RenderShape.MODEL || ModelOutlines.edges(in.blockModels(), state, 0L).length == 0;
        });
    }

    private void line(BufferBuilder lines, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float width = in.lineWidth();
        lines.addVertex(x1, y1, z1).setColor(color).setNormal(dx, dy, dz).setLineWidth(width);
        lines.addVertex(x2, y2, z2).setColor(color).setNormal(dx, dy, dz).setLineWidth(width);
    }

    private static int bits(int... indices) {
        int mask = 0;
        for (int i : indices) {
            mask |= 1 << i;
        }
        return mask;
    }
}
