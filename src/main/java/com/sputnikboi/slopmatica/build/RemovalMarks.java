package com.sputnikboi.slopmatica.build;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.placement.PlacementStore;
import com.sputnikboi.slopmatica.render.VoxelOutline;
import com.sputnikboi.slopmatica.verify.BlockChanges;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The break list: real blocks marked to be dug out, outlined in red with a count on screen. A
 * block leaves the list by itself once it's gone (air, or water or lava flowed in). Kept per world
 * or server, per dimension, in {@code slopmatica/marks/}.
 */
public final class RemovalMarks implements BlockChanges.Listener {
    public static final RemovalMarks INSTANCE = new RemovalMarks();

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Slopmatica");
    private static final int COLOR = 0xFFFF3A3A;
    /** Marking takes at most this many blocks at once. */
    private static final long MAX_MARK = 2_000_000;

    private final Map<String, LongOpenHashSet> marks = new HashMap<>();
    private @Nullable Path file;
    private boolean dirty;
    private int saveIn;
    private final VoxelOutline.Cache outline = new VoxelOutline.Cache(this::outlineCells);

    private RemovalMarks() {
    }

    public static void register() {
        BlockChanges.listen(INSTANCE);
    }

    private static @Nullable String dimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.dimension().identifier().toString();
    }

    private LongOpenHashSet here() {
        String dim = dimension();
        return dim == null ? new LongOpenHashSet() : marks.computeIfAbsent(dim, d -> new LongOpenHashSet());
    }

    public int count() {
        String dim = dimension();
        LongOpenHashSet set = dim == null ? null : marks.get(dim);
        return set == null ? 0 : set.size();
    }

    private static boolean gone(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /** Marks the selection's real blocks (air and liquids aren't anything to break). */
    public void markSelection(Minecraft mc) {
        ClientLevel level = mc.level;
        Selection sel = Selection.INSTANCE;
        if (level == null || sel.box() == null || !level.dimension().identifier().toString().equals(sel.dimension())) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.no_selection").withColor(SlopmaticaActions.ERROR));
            return;
        }
        LongOpenHashSet set = here();
        int[] added = {0};
        boolean ok = sel.forEach(MAX_MARK * 4, p -> {
            if (level.hasChunk(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ())) && !gone(level.getBlockState(p))
                && set.add(p.asLong())) {
                added[0]++;
            }
        });
        if (!ok) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.too_big").withColor(SlopmaticaActions.ERROR));
            return;
        }
        changed();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.marked", String.format("%,d", added[0]), String.format("%,d", set.size()))
            .withColor(SlopmaticaActions.ACCENT));
    }

    /** Takes the selection's blocks off the list. */
    public void unmarkSelection(Minecraft mc) {
        Selection sel = Selection.INSTANCE;
        LongOpenHashSet set = here();
        if (sel.box() == null || mc.level == null || !mc.level.dimension().identifier().toString().equals(sel.dimension())) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.no_selection").withColor(SlopmaticaActions.ERROR));
            return;
        }
        int before = set.size();
        if (!set.isEmpty()) {
            // Whichever is smaller to walk: the list, or the selection.
            var b = sel.box();
            long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
            if (volume > set.size()) {
                set.removeIf(k -> sel.contains(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)));
            } else {
                sel.forEach(Long.MAX_VALUE, p -> set.remove(p.asLong()));
            }
        }
        changed();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.unmarked", String.format("%,d", before - set.size()), String.format("%,d", set.size()))
            .withColor(SlopmaticaActions.ACCENT));
    }

    /** Empties this dimension's list. */
    public void clear() {
        String dim = dimension();
        if (dim != null) {
            marks.remove(dim);
        }
        changed();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.marks.cleared").withColor(SlopmaticaActions.ACCENT));
    }

    private void changed() {
        dirty = true;
        saveIn = 40;
        outline.changed();
    }

    // ---- Keeping it up to date -------------------------------------------------------------

    @Override
    public void blockChanged(BlockPos pos, BlockState old, BlockState now) {
        if (gone(now)) {
            String dim = dimension();
            LongOpenHashSet set = dim == null ? null : marks.get(dim);
            if (set != null && set.remove(pos.asLong())) {
                changed();
            }
        }
    }

    @Override
    public void chunkChanged(int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        String dim = dimension();
        LongOpenHashSet set = dim == null ? null : marks.get(dim);
        if (set == null || set.isEmpty() || mc.level == null || !mc.level.hasChunk(chunkX, chunkZ)) {
            return;
        }
        // Dug out while this chunk wasn't loaded (or by someone else): take those off.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        ClientLevel level = mc.level;
        if (set.removeIf(k -> SectionPos.blockToSectionCoord(BlockPos.getX(k)) == chunkX && SectionPos.blockToSectionCoord(BlockPos.getZ(k)) == chunkZ
            && gone(level.getBlockState(p.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)))))) {
            changed();
        }
    }

    public void tick(Minecraft mc) {
        if (dirty && --saveIn <= 0) {
            save();
        }
    }

    // ---- Saving ------------------------------------------------------------------------------

    public void onJoin(Minecraft mc) {
        marks.clear();
        dirty = false;
        file = PlacementStore.storeFile(mc, "marks");
        if (file != null && Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.entrySet()) {
                    LongOpenHashSet set = new LongOpenHashSet();
                    for (var v : e.getValue().getAsJsonArray()) {
                        set.add(v.getAsLong());
                    }
                    marks.put(e.getKey(), set);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read the break list {}", file, e);
            }
        }
        outline.changed();
    }

    public void onLeave() {
        if (dirty) {
            save();
        }
        marks.clear();
        file = null;
        outline.changed();
    }

    private void save() {
        dirty = false;
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        for (var e : marks.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            JsonArray list = new JsonArray();
            for (LongIterator it = e.getValue().iterator(); it.hasNext(); ) {
                list.add(it.nextLong());
            }
            root.add(e.getKey(), list);
        }
        try {
            Files.createDirectories(file.getParent());
            if (root.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.writeString(file, new Gson().toJson(root), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not save the break list {}", file, e);
        }
    }

    // ---- Drawing -----------------------------------------------------------------------------

    private VoxelOutline.@Nullable Cells outlineCells() {
        String dim = dimension();
        LongOpenHashSet set = dim == null ? null : marks.get(dim);
        if (set == null || set.isEmpty()) {
            return null;
        }
        LongOpenHashSet copy = new LongOpenHashSet(set);
        return new VoxelOutline.Cells() {
            @Override
            public LongIterator iterator() {
                return copy.iterator();
            }

            @Override
            public boolean contains(int x, int y, int z) {
                return copy.contains(BlockPos.asLong(x, y, z));
            }
        };
    }

    public void submit(LevelRenderContext context) {
        if (count() == 0) {
            return;
        }
        VoxelOutline shape = outline.get();
        if (shape == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth() * 1.5F;
        // Faint through blocks (so you can find them), stronger where you can see them.
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), com.sputnikboi.slopmatica.render.SeeThroughLines.HIDDEN,
            (pose, buffer) -> shape.draw(buffer, pose, cam, com.sputnikboi.slopmatica.render.SeeThroughLines.fade(COLOR, com.sputnikboi.slopmatica.render.SeeThroughLines.HIDDEN_ALPHA), width, 0.006));
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), com.sputnikboi.slopmatica.render.SeeThroughLines.VISIBLE,
            (pose, buffer) -> shape.draw(buffer, pose, cam, com.sputnikboi.slopmatica.render.SeeThroughLines.fade(COLOR, com.sputnikboi.slopmatica.render.SeeThroughLines.VISIBLE_ALPHA), width, 0.006));
    }

    /** HUD, bottom left (above Replace Block's box): how many marked blocks are left, and whether the lock is on. */
    public void extractHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) {
            return;
        }
        int y = g.guiHeight() - 24 - (ReplaceBlock.INSTANCE.enabled() ? 24 : 0);
        int n = count();
        if (n > 0) {
            box(g, mc, y, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE),
                Component.translatable("slopmatica.marks.hud", String.format("%,d", n)).getString(), 0xFFFF9090);
            y -= 24;
        }
        if (SelectionLock.active()) {
            box(g, mc, y, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BARS),
                Component.translatable("slopmatica.lock.hud").getString(), 0xFFFFE080);
        }
    }

    private static void box(GuiGraphicsExtractor g, Minecraft mc, int y, net.minecraft.world.item.ItemStack icon, String text, int color) {
        int x = 4, w = 20 + mc.font.width(text) + 6;
        g.fill(x, y, x + w, y + 20, 0x90000000);
        g.item(icon, x + 2, y + 2);
        g.text(mc.font, text, x + 21, y + 6, color);
    }
}
