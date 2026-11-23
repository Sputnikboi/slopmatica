package com.sputnikboi.slopmatica.build;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.SelectionShape.Part;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.render.VoxelOutline;
import com.sputnikboi.slopmatica.view.PlacementView;
import com.sputnikboi.slopmatica.view.UndoStack;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The selection: any set of blocks, made of shapes added to it or cut out of it (later ones win).
 * The selection key uses the chosen {@link SelectionTool} on the block it points at; the Box tool in
 * Replace mode is the original two-corner box. A plain box draws as a white box with a red first and
 * blue second corner; any other shape draws its outline.
 */
public final class Selection {
    public static final Selection INSTANCE = new Selection();

    private static final int BOX_COLOR = 0xFFFFFFFF;
    private static final int CORNER1_COLOR = 0xFFFF5050;
    private static final int CORNER2_COLOR = 0xFF5090FF;
    private static final int POINT_COLOR = 0xFFFFD040;
    private static final int ORIGIN_COLOR = 0xFFFFD040;
    private static final int FRAME_COLOR = 0x90FFD040;
    private static final int HANDLE_COLOR = 0xFFFFE080;
    /** The size of the frame's face handles, in blocks. */
    private static final double HANDLE = 0.8;
    /** Bigger shapes can't be cut down to surface blocks, or outlined block by block. */
    public static final long MAX_SCAN = 8_000_000;
    /** Magic select stops after this many blocks. */
    private static final int MAGIC_MAX = 250_000;
    /** A brush stroke stops growing after this many blocks. */
    private static final int BRUSH_MAX = 1_000_000;

    private List<Part> parts = List.of();
    private @Nullable BlockPos first, second;
    private boolean nextIsFirst = true;
    /** The sphere or cylinder tool's first click. */
    private @Nullable BlockPos center;
    /** The prism tool's points so far. */
    private List<BlockPos> points = List.of();
    private @Nullable String dimension;

    /** The brush stroke being painted (the last part), and where it last painted. */
    private SelectionShape.@Nullable Cells stroke;
    private @Nullable BlockPos lastBrush;

    private final VoxelOutline.Cache outline = new VoxelOutline.Cache(this::outlineCells);

    private Selection() {
    }

    // ---- Using the tools -------------------------------------------------------------------

    /** The selection key, in the world: the tool on the block the key points at. */
    public void setCorner(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        BlockPos pos = keyTarget(mc);
        if (pos != null) {
            use(pos, mc.level.dimension().identifier().toString(), mc.player.isShiftKeyDown());
        }
    }

    /** Where the selection key points: the corner rule (look, feet...), or the free camera's block. */
    private static @Nullable BlockPos keyTarget(Minecraft mc) {
        PlacementView view = PlacementView.INSTANCE;
        if (view.freecam()) {
            // In the build view your body is elsewhere: the corner is what the camera looks at.
            BlockPos pos = view.cameraPointed(mc);
            if (pos == null) {
                lookAtBlock();
            }
            return pos;
        }
        boolean looking = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
        if (SlopmaticaConfig.get().selectionTool != SelectionTool.BOX) {
            // The other tools reach further than an arm: anything you can see (ghosts too).
            BlockPos far = view.pointedBlock(mc, mc.player.getEyePosition(), mc.player.getLookAngle());
            if (far == null) {
                lookAtBlock();
            }
            return far;
        }
        return switch (SlopmaticaConfig.get().cornerMode) {
            case FEET -> mc.player.blockPosition();
            case LOOK -> {
                if (!looking) {
                    lookAtBlock();
                    yield null;
                }
                yield ((BlockHitResult) mc.hitResult).getBlockPos();
            }
            default -> looking ? ((BlockHitResult) mc.hitResult).getBlockPos() : mc.player.blockPosition();
        };
    }

    private static void lookAtBlock() {
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.selection.look_at_block").withColor(SlopmaticaActions.ERROR));
    }

    /** Sets the next box corner (first, then second, then first again...): the Box tool, whatever tool is chosen. */
    public void setCorner(BlockPos pos, String dim) {
        newDimension(dim);
        boxClick(pos, SlopmaticaConfig.get().selectionMode);
    }

    /**
     * Uses the chosen tool on a block. {@code sneaking} cuts out instead of adding for the brush and
     * magic select (they don't need two clicks, so cutting a bit back out should be quick).
     */
    public void use(BlockPos pos, String dim, boolean sneaking) {
        newDimension(dim);
        SlopmaticaConfig c = SlopmaticaConfig.get();
        SelectionTool tool = c.selectionTool;
        SelectionTool.Mode mode = c.selectionMode;
        switch (tool) {
            case BOX -> boxClick(pos, mode);
            case SPHERE, CYLINDER -> {
                if (center == null) {
                    center = pos;
                    message("slopmatica.selection.center", pos.toShortString());
                    changed();
                    return;
                }
                BlockPos c0 = center;
                center = null;
                SelectionShape shape;
                if (tool == SelectionTool.SPHERE) {
                    shape = new SelectionShape.Ball(c0, Math.sqrt(c0.distSqr(pos)));
                } else {
                    double dx = pos.getX() - c0.getX(), dz = pos.getZ() - c0.getZ();
                    shape = new SelectionShape.Cylinder(c0, Math.sqrt(dx * dx + dz * dz), Math.min(c0.getY(), pos.getY()), Math.max(c0.getY(), pos.getY()));
                }
                commit(shape, mode, tool);
            }
            case PRISM -> {
                if (points.size() >= 3 && pos.getX() == points.getFirst().getX() && pos.getZ() == points.getFirst().getZ()) {
                    List<BlockPos> all = new ArrayList<>(points);
                    all.add(pos); // Its height counts: click the first point higher up for a taller prism.
                    points = List.of();
                    commit(SelectionShape.Prism.of(all), mode, tool);
                    return;
                }
                List<BlockPos> more = new ArrayList<>(points);
                more.add(pos);
                points = List.copyOf(more);
                message(points.size() < 3 ? "slopmatica.selection.point" : "slopmatica.selection.point_close", points.size(), pos.toShortString());
                changed();
            }
            case BRUSH -> {
                startStroke(dim, sneaking);
                paint(pos);
                endStroke();
            }
            case MAGIC -> {
                Minecraft mc = Minecraft.getInstance();
                SelectionShape.Cells cells = mc.level != null ? magic(mc.level, pos) : null;
                if (cells != null) {
                    SelectionTool.Mode m = sneaking ? SelectionTool.Mode.SUBTRACT : mode;
                    commit(cells, m, tool);
                }
            }
        }
    }

    private void boxClick(BlockPos pos, SelectionTool.Mode mode) {
        if (mode == SelectionTool.Mode.REPLACE) {
            // The original selection: two corners, either one moved again later.
            if (nextIsFirst) {
                first = pos;
            } else {
                second = pos;
            }
            nextIsFirst = !nextIsFirst;
            if (first != null && second != null) {
                SelectionShape whole = new SelectionShape.Box(BoundingBox.fromCorners(first, second));
                SelectionShape shape = surfaceOnly(whole, SelectionTool.BOX);
                parts = shape == null ? List.of() : List.of(new Part(shape, false, shape != whole ? whole : null));
            }
            changed();
            BoundingBox box = box();
            String size = box == null || first == null || second == null ? "" : String.format("  (%d x %d x %d)", box.getXSpan(), box.getYSpan(), box.getZSpan());
            SlopmaticaActions.actionBar(Component.literal("Corner " + (nextIsFirst ? 2 : 1) + " at " + pos.toShortString() + size).withColor(SlopmaticaActions.ACCENT));
            return;
        }
        if (nextIsFirst || first == null) {
            first = pos;
            second = null;
            nextIsFirst = false;
            changed();
            SlopmaticaActions.actionBar(Component.literal("Corner 1 at " + pos.toShortString()).withColor(SlopmaticaActions.ACCENT));
            return;
        }
        BlockPos a = first;
        first = second = null;
        nextIsFirst = true;
        commit(new SelectionShape.Box(BoundingBox.fromCorners(a, pos)), mode, SelectionTool.BOX);
    }

    /** Puts a finished shape into the selection (cut down to its surface blocks if that's on for the tool). */
    private void commit(SelectionShape shape, SelectionTool.Mode mode, SelectionTool tool) {
        SelectionShape s = surfaceOnly(shape, tool);
        if (s == null) {
            changed();
            return;
        }
        Part part = new Part(s, mode == SelectionTool.Mode.SUBTRACT, s != shape ? shape : null);
        if (mode == SelectionTool.Mode.REPLACE) {
            parts = List.of(part);
        } else {
            List<Part> more = new ArrayList<>(parts);
            more.add(part);
            parts = List.copyOf(more);
        }
        first = second = null;
        nextIsFirst = true;
        changed();
        BoundingBox b = s.bounds();
        String what = s instanceof SelectionShape.Cells cells ? String.format("%,d blocks", cells.size()) : String.format("%d x %d x %d", b.getXSpan(), b.getYSpan(), b.getZSpan());
        message(mode == SelectionTool.Mode.SUBTRACT ? "slopmatica.selection.cut" : mode == SelectionTool.Mode.ADD ? "slopmatica.selection.added" : "slopmatica.selection.made",
            tool.label(), what);
    }

    // ---- Brush -------------------------------------------------------------------------------

    /** Every tick: paints while the selection key is held with the Brush chosen (in the world). */
    public void tickBrush(Minecraft mc, boolean held) {
        if (!held || mc.player == null || mc.level == null || SlopmaticaConfig.get().selectionTool != SelectionTool.BRUSH) {
            endStroke();
            return;
        }
        BlockPos pos = keyTarget(mc);
        if (pos == null) {
            return;
        }
        if (stroke == null) {
            UndoStack.INSTANCE.saveSelection();
            startStroke(mc.level.dimension().identifier().toString(), mc.player.isShiftKeyDown());
        }
        paint(pos);
    }

    /** Starts a brush stroke: a new part the brush adds blocks to until {@link #endStroke()}. */
    public void startStroke(String dim, boolean sneaking) {
        endStroke();
        newDimension(dim);
        boolean cut = (SlopmaticaConfig.get().selectionMode == SelectionTool.Mode.SUBTRACT) != sneaking;
        stroke = new SelectionShape.Cells();
        List<Part> more = new ArrayList<>(parts);
        more.add(new Part(stroke, cut));
        parts = List.copyOf(more);
        first = second = null;
        nextIsFirst = true;
        lastBrush = null;
    }

    public boolean painting() {
        return stroke != null;
    }

    /** Paints around a block, and along the way from the last one (a quick sweep leaves no gaps). */
    public void paint(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (stroke == null || mc.level == null || pos.equals(lastBrush)) {
            return;
        }
        SlopmaticaConfig c = SlopmaticaConfig.get();
        int r = Math.clamp(c.brushRadius, 0, 16);
        boolean surface = c.surfaceTools.contains(SelectionTool.BRUSH.name());
        if (lastBrush != null) {
            double dist = Math.sqrt(lastBrush.distSqr(pos));
            int steps = (int) Math.ceil(dist / Math.max(1, r));
            for (int i = 1; i < steps; i++) {
                double t = (double) i / steps;
                dab(mc.level, BlockPos.containing(lastBrush.getX() + (pos.getX() - lastBrush.getX()) * t + 0.5,
                    lastBrush.getY() + (pos.getY() - lastBrush.getY()) * t + 0.5, lastBrush.getZ() + (pos.getZ() - lastBrush.getZ()) * t + 0.5), r, surface, c.brushShape);
            }
        }
        dab(mc.level, pos, r, surface, c.brushShape);
        lastBrush = pos;
        changed();
    }

    private void dab(Level level, BlockPos at, int r, boolean surface, SelectionTool.BrushShape shape) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double rr = (r + 0.5) * (r + 0.5);
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (shape == SelectionTool.BrushShape.SPHERE && dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    if (stroke.size() >= BRUSH_MAX) {
                        return;
                    }
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!surface || isSurface(level, p)) {
                        stroke.add(p.getX(), p.getY(), p.getZ());
                    }
                }
            }
        }
    }

    public void endStroke() {
        if (stroke == null) {
            return;
        }
        SelectionShape.Cells done = stroke;
        stroke = null;
        lastBrush = null;
        if (done.isEmpty()) {
            parts = parts.stream().filter(part -> part.shape() != done).toList();
            changed();
        } else {
            message(parts.getLast().subtract() ? "slopmatica.selection.cut" : "slopmatica.selection.added", SelectionTool.BRUSH.label(),
                String.format("%,d blocks", done.size()));
        }
    }

    // ---- Magic select and surfaces ---------------------------------------------------------

    /** Touching blocks of the same kind as the one at pos (same block, or same state if set so). */
    private static SelectionShape.@Nullable Cells magic(Level level, BlockPos start) {
        BlockState kind = level.getBlockState(start);
        if (kind.isAir()) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.selection.magic_air").withColor(SlopmaticaActions.ERROR));
            return null;
        }
        boolean exact = SlopmaticaConfig.get().magicExact;
        SelectionShape.Cells cells = new SelectionShape.Cells();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean capped = false;
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            cells.add(x, y, z);
            if (cells.size() >= MAGIC_MAX) {
                capped = true;
                break;
            }
            for (var d : net.minecraft.core.Direction.values()) {
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                long n = BlockPos.asLong(nx, ny, nz);
                if (seen.contains(n) || level.isOutsideBuildHeight(ny)
                    || !level.hasChunk(SectionPos.blockToSectionCoord(nx), SectionPos.blockToSectionCoord(nz))) {
                    continue;
                }
                seen.add(n);
                BlockState s = level.getBlockState(p.set(nx, ny, nz));
                if (exact ? s == kind : s.getBlock() == kind.getBlock()) {
                    queue.enqueue(n);
                }
            }
        }
        if (capped) {
            SlopmaticaActions.chat(SlopmaticaActions.prefix().append(Component.translatable("slopmatica.selection.magic_capped", String.format("%,d", MAGIC_MAX))));
        }
        return cells;
    }

    /** A solid-ish block with at least one side open to air, water or see-through blocks. */
    public static boolean isSurface(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (var d : net.minecraft.core.Direction.values()) {
            if (!level.getBlockState(n.setWithOffset(pos, d)).isSolidRender()) {
                return true;
            }
        }
        return false;
    }

    /** The shape as it is, or cut down to its surface blocks when that's on for the tool; null if too big for that. */
    private static @Nullable SelectionShape surfaceOnly(SelectionShape shape, SelectionTool tool) {
        Minecraft mc = Minecraft.getInstance();
        if (!SlopmaticaConfig.get().surfaceTools.contains(tool.name()) || mc.level == null) {
            return shape;
        }
        return surfaceCut(mc.level, shape);
    }

    /** Just the shape's surface blocks; null (and a message) if it's too big to look through. */
    private static SelectionShape.@Nullable Cells surfaceCut(Level level, SelectionShape shape) {
        BoundingBox b = shape.bounds();
        long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
        if (volume > MAX_SCAN) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.selection.surface_too_big", String.format("%,d", MAX_SCAN)).withColor(SlopmaticaActions.ERROR));
            return null;
        }
        SelectionShape.Cells cells = new SelectionShape.Cells();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (shape.contains(x, y, z) && isSurface(level, p.set(x, y, z))) {
                        cells.add(x, y, z);
                    }
                }
            }
        }
        return cells;
    }

    // ---- What's selected -------------------------------------------------------------------

    private void newDimension(String dim) {
        if (!dim.equals(dimension)) {
            endStroke();
            parts = List.of();
            first = second = center = null;
            points = List.of();
            nextIsFirst = true;
            dimension = dim;
            changed();
        }
    }

    private void changed() {
        outline.changed();
    }

    public boolean contains(int x, int y, int z) {
        List<Part> ps = parts;
        for (int i = ps.size() - 1; i >= 0; i--) {
            Part part = ps.get(i);
            if (part.shape().contains(x, y, z)) {
                return !part.subtract();
            }
        }
        return false;
    }

    /** The box around everything selected (cut-outs not taken off), or null if nothing is. */
    public @Nullable BoundingBox box() {
        BoundingBox all = null;
        for (Part part : parts) {
            if (part.subtract() || part.shape() instanceof SelectionShape.Cells cells && cells.isEmpty()) {
                continue;
            }
            all = all == null ? part.shape().bounds() : BoundingBox.encapsulating(all, part.shape().bounds());
        }
        return all;
    }

    /** The selection when it's just one box (resizable with the Modify tool, fittable), else null. */
    public @Nullable BoundingBox plainBox() {
        return parts.size() == 1 && !parts.getFirst().subtract() && parts.getFirst().shape() instanceof SelectionShape.Box b ? b.box() : null;
    }

    public List<Part> parts() {
        return parts;
    }

    /**
     * The box the whole selection is stretched and moved by: around everything added, taking shapes
     * as made (a surface-only sphere's frame is the sphere's box). Null if nothing is selected.
     */
    public @Nullable BoundingBox frame() {
        return frameOf(parts);
    }

    private static @Nullable BoundingBox frameOf(List<Part> parts) {
        BoundingBox all = null;
        for (Part part : parts) {
            SelectionShape base = part.base();
            if (part.subtract() || base instanceof SelectionShape.Cells cells && cells.isEmpty()) {
                continue;
            }
            all = all == null ? base.bounds() : BoundingBox.encapsulating(all, base.bounds());
        }
        return all;
    }

    /** The selection's origin: the block in the middle of its frame's floor. What you grab to move it. */
    public @Nullable BlockPos origin() {
        BoundingBox f = frame();
        return f == null ? null : originOf(f);
    }

    public static BlockPos originOf(BoundingBox f) {
        return new BlockPos(Math.floorDiv(f.minX() + f.maxX(), 2), f.minY(), Math.floorDiv(f.minZ() + f.maxZ(), 2));
    }

    /**
     * Stretches and moves {@code start} (the parts as a drag began) so the frame goes from
     * {@code from} to {@code to}. Surface-only shapes show whole until {@link #finishReshape()} cuts
     * them down again where they are now.
     */
    public void reshape(List<Part> start, BoundingBox from, BoundingBox to) {
        endStroke();
        List<Part> moved = new ArrayList<>(start.size());
        for (Part part : start) {
            SelectionShape base = part.base();
            SelectionShape fitted = base.fit(SelectionShape.map(base.bounds(), from, to));
            moved.add(new Part(fitted, part.subtract(), part.whole() != null ? fitted : null));
        }
        parts = List.copyOf(moved);
        BoundingBox plain = plainBox();
        if (plain != null) {
            first = new BlockPos(plain.minX(), plain.minY(), plain.minZ());
            second = new BlockPos(plain.maxX(), plain.maxY(), plain.maxZ());
        } else {
            first = second = null;
        }
        nextIsFirst = true;
        center = null;
        points = List.of();
        changed();
    }

    /** After {@link #reshape}: surface-only shapes cut down to the surface where they landed. */
    public void finishReshape() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || parts.stream().noneMatch(p -> p.whole() != null && p.shape() == p.whole())) {
            return;
        }
        List<Part> cut = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if (part.whole() != null && part.shape() == part.whole()) {
                SelectionShape.Cells cells = surfaceCut(mc.level, part.whole());
                cut.add(cells == null ? new Part(part.whole(), part.subtract()) : new Part(cells, part.subtract(), part.whole()));
            } else {
                cut.add(part);
            }
        }
        parts = List.copyOf(cut);
        first = second = null;
        changed();
    }

    /** Moves the whole selection (undo saved by the caller). */
    public void move(int dx, int dy, int dz) {
        BoundingBox f = frame();
        if (f == null) {
            return;
        }
        reshape(parts, f, f.moved(dx, dy, dz));
        finishReshape();
    }

    public @Nullable String dimension() {
        return dimension;
    }

    /** Each selected block, in y, z, x order; false (nothing done) when the selection's box is bigger than {@code max}. */
    public boolean forEach(long max, java.util.function.Consumer<BlockPos.MutableBlockPos> each) {
        BoundingBox b = box();
        if (b == null) {
            return true;
        }
        if ((long) b.getXSpan() * b.getYSpan() * b.getZSpan() > max) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = b.minY(); y <= b.maxY(); y++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    if (contains(x, y, z)) {
                        each.accept(p.set(x, y, z));
                    }
                }
            }
        }
        return true;
    }

    /** Everything about the selection, for undo. */
    public record State(List<Part> parts, @Nullable BlockPos first, @Nullable BlockPos second, @Nullable BlockPos center,
                        List<BlockPos> points, @Nullable String dimension, boolean nextIsFirst) {
    }

    public State state() {
        return new State(stroke != null ? parts.subList(0, parts.size() - 1) : parts, first, second, center, points, dimension, nextIsFirst);
    }

    public void restore(State state) {
        stroke = null;
        lastBrush = null;
        parts = List.copyOf(state.parts());
        first = state.first();
        second = state.second();
        center = state.center();
        points = state.points();
        dimension = state.dimension();
        nextIsFirst = state.nextIsFirst();
        changed();
    }

    private static boolean ghostAt(List<com.sputnikboi.slopmatica.placement.Placement.Transform> ghosts, int x, int y, int z) {
        for (var t : ghosts) {
            BlockState state = t.stateAt(x, y, z);
            if (state != null && !state.isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shrinks a box selection to the blocks in it: all-air layers at any side are trimmed off.
     * Returns false if there is no box selection or nothing but air in it.
     */
    public boolean fitToBlocks(Level level) {
        BoundingBox box = plainBox();
        if (box == null) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Unbuilt parts of a shown build count too, so a selection fits a build before it exists.
        List<com.sputnikboi.slopmatica.placement.Placement.Transform> ghosts = level instanceof net.minecraft.client.multiplayer.ClientLevel client
            ? com.sputnikboi.slopmatica.verify.SchematicRaycast.visibleTransforms(client) : List.of();
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
        set(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ), dimension);
        return true;
    }

    /** Replaces the whole selection with a box (the Modify tool). */
    public void set(BoundingBox box, String dim) {
        endStroke();
        dimension = dim;
        first = new BlockPos(box.minX(), box.minY(), box.minZ());
        second = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
        nextIsFirst = true;
        center = null;
        points = List.of();
        parts = List.of(new Part(new SelectionShape.Box(box), false));
        changed();
    }

    public void clear() {
        endStroke();
        parts = List.of();
        first = second = center = null;
        points = List.of();
        nextIsFirst = true;
        changed();
        SlopmaticaActions.actionBar(Component.literal("Selection cleared").withColor(SlopmaticaActions.ACCENT));
    }

    private static void message(String key, Object... args) {
        SlopmaticaActions.actionBar(Component.translatable(key, args).withColor(SlopmaticaActions.ACCENT));
    }

    // ---- Drawing -----------------------------------------------------------------------------

    /** A copy of what's selected, for the outline thread; null when a box or nothing needs no outline. */
    private VoxelOutline.@Nullable Cells outlineCells() {
        if (parts.isEmpty() || plainBox() != null) {
            return null;
        }
        BoundingBox b = box();
        if (b == null || (long) b.getXSpan() * b.getYSpan() * b.getZSpan() > MAX_SCAN) {
            return null;
        }
        List<Part> copy = parts.stream().map(p -> new Part(p.shape().snapshot(), p.subtract())).toList();
        return new VoxelOutline.Cells() {
            @Override
            public LongIterator iterator() {
                LongArrayList all = new LongArrayList();
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        for (int x = b.minX(); x <= b.maxX(); x++) {
                            if (contains(x, y, z)) {
                                all.add(BlockPos.asLong(x, y, z));
                            }
                        }
                    }
                }
                return all.iterator();
            }

            @Override
            public boolean contains(int x, int y, int z) {
                for (int i = copy.size() - 1; i >= 0; i--) {
                    if (copy.get(i).shape().contains(x, y, z)) {
                        return !copy.get(i).subtract();
                    }
                }
                return false;
            }
        };
    }

    public void submit(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().identifier().toString().equals(dimension)) {
            return;
        }
        if (parts.isEmpty() && first == null && second == null && center == null && points.isEmpty()) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth();
        PoseStack poseStack = context.poseStack();
        BoundingBox plain = plainBox();
        VoxelOutline shape = plain == null ? outline.get() : null;
        BoundingBox all = box();
        BoundingBox frame = frame();
        boolean editing = PlacementView.INSTANCE.active() && PlacementView.INSTANCE.tool() == com.sputnikboi.slopmatica.view.Tool.MODIFY;
        context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            if (plain != null) {
                box(buffer, pose, cam, plain.minX(), plain.minY(), plain.minZ(), plain.maxX() + 1, plain.maxY() + 1, plain.maxZ() + 1, 0.002, BOX_COLOR, width);
            } else if (shape != null) {
                shape.draw(buffer, pose, cam, BOX_COLOR, width, 0.003);
            } else if (all != null) {
                // Too big to outline block by block (or not outlined yet): its box, dimmed.
                box(buffer, pose, cam, all.minX(), all.minY(), all.minZ(), all.maxX() + 1, all.maxY() + 1, all.maxZ() + 1, 0.002, 0x80FFFFFF, width);
            }
            if (first != null) {
                cube(buffer, pose, cam, first, CORNER1_COLOR, width);
            }
            if (second != null) {
                cube(buffer, pose, cam, second, CORNER2_COLOR, width);
            }
            if (center != null) {
                cube(buffer, pose, cam, center, CORNER1_COLOR, width);
            }
            if (frame != null) {
                if (editing) {
                    // The frame you stretch, with a handle on each face, and its middle.
                    box(buffer, pose, cam, frame.minX(), frame.minY(), frame.minZ(), frame.maxX() + 1, frame.maxY() + 1, frame.maxZ() + 1, 0.02, FRAME_COLOR, width);
                    handles(buffer, pose, cam, frame, width);
                }
                originMarker(buffer, pose, cam, originOf(frame), editing, width);
            }
            BlockPos prev = null;
            for (BlockPos p : points) {
                cube(buffer, pose, cam, p, POINT_COLOR, width);
                if (prev != null) {
                    line(buffer, pose, cam, prev, p, POINT_COLOR, width);
                }
                prev = p;
            }
        });
    }

    /** A small square on the middle of each face of the frame: what to drag to stretch it. */
    private static void handles(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, BoundingBox f, float width) {
        double cx = (f.minX() + f.maxX() + 1) / 2.0, cy = (f.minY() + f.maxY() + 1) / 2.0, cz = (f.minZ() + f.maxZ() + 1) / 2.0;
        double h = HANDLE / 2, g = 0.03;
        double x0 = f.minX() - g, x1 = f.maxX() + 1 + g, y0 = f.minY() - g, y1 = f.maxY() + 1 + g, z0 = f.minZ() - g, z1 = f.maxZ() + 1 + g;
        box(b, pose, cam, x0, cy - h, cz - h, x0, cy + h, cz + h, 0, HANDLE_COLOR, width * 2);
        box(b, pose, cam, x1, cy - h, cz - h, x1, cy + h, cz + h, 0, HANDLE_COLOR, width * 2);
        box(b, pose, cam, cx - h, y0, cz - h, cx + h, y0, cz + h, 0, HANDLE_COLOR, width * 2);
        box(b, pose, cam, cx - h, y1, cz - h, cx + h, y1, cz + h, 0, HANDLE_COLOR, width * 2);
        box(b, pose, cam, cx - h, cy - h, z0, cx + h, cy + h, z0, 0, HANDLE_COLOR, width * 2);
        box(b, pose, cam, cx - h, cy - h, z1, cx + h, cy + h, z1, 0, HANDLE_COLOR, width * 2);
    }

    /** The origin: a yellow block with an axis cross through it (thicker while it can be dragged). */
    private static void originMarker(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, BlockPos o, boolean grabbable, float width) {
        float w = grabbable ? width * 2.5F : width * 1.5F;
        box(b, pose, cam, o.getX() + 0.2, o.getY() + 0.2, o.getZ() + 0.2, o.getX() + 0.8, o.getY() + 0.8, o.getZ() + 0.8, 0, ORIGIN_COLOR, w);
        double cx = o.getX() + 0.5, cy = o.getY() + 0.5, cz = o.getZ() + 0.5, r = 0.9;
        segment(b, pose, cam, cx - r, cy, cz, cx + r, cy, cz, 0xFFFF5050, w);
        segment(b, pose, cam, cx, cy - r, cz, cx, cy + r, cz, 0xFF50FF50, w);
        segment(b, pose, cam, cx, cy, cz - r, cx, cy, cz + r, 0xFF5090FF, w);
    }

    /** Whether a ray from {@code from} along {@code dir} passes through the origin's grab box. */
    public static boolean hitsOrigin(BlockPos o, Vec3 from, Vec3 dir) {
        net.minecraft.world.phys.AABB grab = new net.minecraft.world.phys.AABB(o).inflate(0.35);
        return grab.contains(from) || grab.clip(from, from.add(dir.scale(512))).isPresent();
    }

    private static void segment(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, double x0, double y0, double z0, double x1, double y1, double z1, int color, float width) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        b.addVertex(pose, (float) (x0 - cam.x), (float) (y0 - cam.y), (float) (z0 - cam.z)).setColor(color).setNormal(pose, nx / len, ny / len, nz / len).setLineWidth(width);
        b.addVertex(pose, (float) (x1 - cam.x), (float) (y1 - cam.y), (float) (z1 - cam.z)).setColor(color).setNormal(pose, nx / len, ny / len, nz / len).setLineWidth(width);
    }

    private static void cube(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, BlockPos p, int color, float width) {
        box(b, pose, cam, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, 0.01, color, width);
    }

    private static void line(VertexConsumer b, PoseStack.Pose pose, Vec3 cam, BlockPos from, BlockPos to, int color, float width) {
        float x0 = (float) (from.getX() + 0.5 - cam.x), y0 = (float) (from.getY() + 0.5 - cam.y), z0 = (float) (from.getZ() + 0.5 - cam.z);
        float x1 = (float) (to.getX() + 0.5 - cam.x), y1 = (float) (to.getY() + 0.5 - cam.y), z1 = (float) (to.getZ() + 0.5 - cam.z);
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) {
            return;
        }
        b.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, nx / len, ny / len, nz / len).setLineWidth(width);
        b.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx / len, ny / len, nz / len).setLineWidth(width);
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
