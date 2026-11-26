package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.schematic.BlockVolume;
import com.sputnikboi.slopmatica.schematic.Schematic;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import com.sputnikboi.slopmatica.split.SchematicSplitter;
import com.sputnikboi.slopmatica.split.SplitAnalysis;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Group;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Kind;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Type;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Split a schematic into parts (experimental). On the left, what it found: structures, terrain
 * (peaks, ground, water), vegetation and repeating wall patterns; click to pick them. On top, the
 * kinds of block to keep (terrain, vegetation, walls, roofs and shapes, detailing). What's picked
 * is previewed on the placement itself, and can be saved as schematics or loaded as placements of
 * their own, right where they are.
 */
public class SplitScreen extends Screen {
    private static final int ROW = 13, HEADER = 16;
    private static final Type[][] SECTIONS = {
        {Type.STRUCTURE, Type.SMALL}, {Type.PEAK, Type.GROUND, Type.WATER}, {Type.TREES, Type.PLANTS}, {Type.PATTERN}
    };
    private static final String[] SECTION_KEYS = {"structures", "terrain", "vegetation", "patterns"};

    private final @Nullable Screen parent;
    private final Placement placement;
    private SchematicSplitter.@Nullable Split split;
    private @Nullable String problem;
    private volatile double progress;
    private boolean[] picked = new boolean[0];
    private final boolean[] kinds = new boolean[Kind.values().length];
    private boolean preview = true;
    private BitSet current = new BitSet();
    private int scroll;
    private int listX, listTop, listBottom, listW;

    /** A line of the list: a section heading (group null) or a group. */
    private record Line(int section, @Nullable Group group) {
    }

    /** What was picked last time, per placement, until the game closes. */
    private record Remembered(SchematicSplitter.Split split, boolean[] picked, boolean[] kinds, boolean preview) {
    }

    private static final java.util.Map<Placement, Remembered> REMEMBERED = new java.util.WeakHashMap<>();

    private List<Line> lines = List.of();

    public SplitScreen(@Nullable Screen parent, Placement placement) {
        super(Component.translatable("slopmatica.split.title", placement.name()));
        this.parent = parent;
        this.placement = placement;
        Arrays.fill(kinds, true);
        Schematic schematic = placement.schematic();
        BoundingBox box = placement.crop() != null ? placement.crop() : schematic.bounds();
        if (SchematicSplitter.cells(box) > SchematicSplitter.MAX_CELLS) {
            problem = Component.translatable("slopmatica.split.too_big", String.format(Locale.ROOT, "%,d", SchematicSplitter.cells(box)),
                String.format(Locale.ROOT, "%,d", SchematicSplitter.MAX_CELLS)).getString();
            return;
        }
        SchematicSplitter.split(schematic, box, p -> progress = p).whenCompleteAsync((s, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                problem = cause instanceof OutOfMemoryError ? Component.translatable("slopmatica.split.memory").getString() : String.valueOf(cause.getMessage());
                return;
            }
            split = s;
            Remembered last = REMEMBERED.get(placement);
            if (last != null && last.split() == s) {
                // Back to what was picked last time.
                picked = last.picked().clone();
                System.arraycopy(last.kinds(), 0, kinds, 0, kinds.length);
                preview = last.preview();
            } else {
                picked = new boolean[s.result.slots()];
                for (Group g : s.result.groups()) {
                    picked[g.id()] = !g.pattern(); // Everything, to start with (patterns overlap the structures).
                }
            }
            buildLines();
            update();
            if (minecraft != null) {
                rebuildWidgets();
            }
        }, net.minecraft.client.Minecraft.getInstance());
    }

    private void buildLines() {
        List<Line> out = new ArrayList<>();
        for (int s = 0; s < SECTIONS.length; s++) {
            List<Group> in = new ArrayList<>();
            for (Type type : SECTIONS[s]) {
                split.result.groups().stream().filter(g -> g.type() == type).forEach(in::add);
            }
            if (!in.isEmpty()) {
                out.add(new Line(s, null));
                for (Group g : in) {
                    out.add(new Line(s, g));
                }
            }
        }
        lines = out;
    }

    @Override
    protected void init() {
        listX = 10;
        listW = Math.min(280, width / 2 - 10);
        listTop = 60;
        listBottom = height - 34;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 75, height - 26, 150, 20).build());
        if (split == null) {
            return;
        }
        // The kinds, in a row along the top.
        int kx = 10, kw = Math.min(104, (width - 20 - 4 * 4) / 5);
        for (Kind kind : Kind.values()) {
            String key = "slopmatica.split.kind." + kind.name().toLowerCase(Locale.ROOT);
            addRenderableWidget(Button.builder(Component.literal((kinds[kind.ordinal()] ? "✔ " : "  ")).append(Component.translatable(key))
                    .withStyle(kinds[kind.ordinal()] ? ChatFormatting.WHITE : ChatFormatting.GRAY), b -> {
                    kinds[kind.ordinal()] = !kinds[kind.ordinal()];
                    update();
                    rebuildWidgets();
                }).bounds(kx, 24, kw, 20).tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build());
            kx += kw + 4;
        }
        int x = listX + listW + 16, w = Math.min(200, width - x - 10), y = listTop + 24;
        addRenderableWidget(CycleButton.onOffBuilder(preview)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.split.preview.tooltip")))
            .create(x, y, w, 20, Component.translatable("slopmatica.split.preview"), (b, v) -> {
                preview = v;
                update();
            }));
        y += 24;
        int half = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.all"), b -> pickAll(true)).bounds(x, y, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.none"), b -> pickAll(false)).bounds(x + w - half, y, half, 20).build());
        y += 32;
        boolean any = !current.isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.load"), b -> export(false, true))
            .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.split.load.tooltip"))).build()).active = any;
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.load_each"), b -> export(true, true))
            .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.split.load_each.tooltip"))).build()).active = any;
        y += 32;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.save"), b -> export(false, false))
            .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.split.save.tooltip"))).build()).active = any;
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.split.save_each"), b -> export(true, false))
            .bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.split.save_each.tooltip"))).build()).active = any;
    }

    private void pickAll(boolean on) {
        for (Group g : split.result.groups()) {
            picked[g.id()] = on && !g.pattern();
        }
        update();
        rebuildWidgets();
    }

    /** Works out what's picked, and shows it on the placement while previewing. */
    private void update() {
        boolean wasEmpty = current.isEmpty();
        current = split.result.mask(picked, kinds);
        REMEMBERED.put(placement, new Remembered(split, picked.clone(), kinds.clone(), preview));
        placement.setMask(preview && !everything() ? split.mask(current) : null);
        PlacementManager.INSTANCE.changed();
        if (wasEmpty != current.isEmpty() && minecraft != null) {
            rebuildWidgets();
        }
    }

    /** Whether all of it is picked (then there's nothing to hide). */
    private boolean everything() {
        for (boolean k : kinds) {
            if (!k) {
                return false;
            }
        }
        for (Group g : split.result.groups()) {
            if (!g.pattern() && !picked[g.id()]) {
                return false;
            }
        }
        return true;
    }

    // ---- Saving and loading ----------------------------------------------------------------------

    /** Saves what's picked (each picked group on its own, or all together) and, if asked, loads it where it was. */
    private void export(boolean each, boolean load) {
        SchematicSplitter.Split s = split;
        List<BitSet> parts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String base = SchematicFormats.baseName(s.schematic.source());
        if (each) {
            boolean[] one = new boolean[picked.length];
            for (Group g : s.result.groups()) {
                if (picked[g.id()]) {
                    Arrays.fill(one, false);
                    one[g.id()] = true;
                    BitSet cells = s.result.mask(one, kinds);
                    if (!cells.isEmpty()) {
                        parts.add(cells);
                        names.add(base + "-" + s.fileName(g));
                    }
                }
            }
        } else {
            parts.add((BitSet) current.clone());
            List<Group> chosen = s.result.groups().stream().filter(g -> picked[g.id()]).toList();
            names.add(base + "-" + (chosen.size() == 1 ? s.fileName(chosen.get(0)) : "part"));
        }
        if (parts.isEmpty()) {
            return;
        }
        SchematicFormats format = SlopmaticaConfig.get().copyFormat;
        Path dir = SlopmaticaActions.schematicsDir().resolve("split").resolve(base);
        String author = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
        List<BlockPos> mins = parts.stream().map(s::partMin).toList();
        List<BlockVolume> volumes = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            volumes.add(s.volume(parts.get(i), names.get(i), author));
        }
        CompletableFuture.supplyAsync(() -> {
            List<Path> files = new ArrayList<>();
            try {
                Files.createDirectories(dir);
                for (int i = 0; i < volumes.size(); i++) {
                    Path file = unique(dir, names.get(i), format.extension);
                    format.write(file, volumes.get(i));
                    files.add(file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return files;
        }, Util.ioPool()).whenCompleteAsync((files, error) -> {
            if (error != null) {
                SlopmaticaActions.chat(Component.literal("[Slopmatica] Could not save the parts: " + error.getMessage()).withColor(SlopmaticaActions.ERROR));
                return;
            }
            String where = "schematics/" + SlopmaticaActions.schematicsDir().relativize(dir).toString().replace('\\', '/');
            if (!load) {
                SlopmaticaActions.chat(SlopmaticaActions.prefix().append(Component.translatable("slopmatica.split.saved", files.size(), where)));
                return;
            }
            for (int i = 0; i < files.size(); i++) {
                loadPart(files.get(i), mins.get(i));
            }
            // The parts take over: the original is hidden (Placements to show it again), its preview off.
            placement.setMask(null);
            placement.setVisible(false);
            PlacementManager.INSTANCE.changed();
            SlopmaticaActions.chat(SlopmaticaActions.prefix().append(Component.translatable("slopmatica.split.loaded", files.size(), placement.name(), where)));
            onClose();
        }, minecraft);
    }

    /** A saved part as a placement of its own, turned and mirrored like the original and exactly where it was in it. */
    private void loadPart(Path file, BlockPos partMin) {
        Placement original = placement;
        CompletableFuture.supplyAsync(() -> {
            try {
                return Schematic.load(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((schematic, error) -> {
            if (error != null) {
                SlopmaticaActions.chat(Component.literal("[Slopmatica] Could not load " + file.getFileName() + ": " + error.getMessage()).withColor(SlopmaticaActions.ERROR));
                return;
            }
            // The part's own corner is where that corner of the original is.
            BlockPos origin = original.transform().toWorld(partMin.getX(), partMin.getY(), partMin.getZ());
            Placement part = PlacementManager.INSTANCE.add(schematic, origin, original.dimension());
            part.setMirror(original.transform().mirror());
            part.setRotation(original.transform().rotation());
            part.setOrigin(origin);
            PlacementManager.INSTANCE.changed();
        }, minecraft);
    }

    private static Path unique(Path dir, String name, String extension) {
        Path file = dir.resolve(name + extension);
        for (int n = 2; Files.exists(file); n++) {
            file = dir.resolve(name + "-" + n + extension);
        }
        return file;
    }

    // ---- Drawing and clicking --------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title.copy().append(Component.translatable("slopmatica.split.experimental").withStyle(ChatFormatting.GOLD)), width / 2, 10, -1);
        if (problem != null) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = font.split(Component.literal(problem), Math.min(360, width - 40));
            for (int i = 0; i < wrapped.size(); i++) {
                g.centeredText(font, wrapped.get(i), width / 2, height / 2 - 10 + i * 10, 0xFFFF8080);
            }
            return;
        }
        if (split == null) {
            g.centeredText(font, Component.translatable("slopmatica.split.working", (int) (progress * 100)), width / 2, height / 2 - 4, 0xFFC0C0C0);
            return;
        }
        Component hover = null;
        int content = contentHeight();
        scroll = Math.clamp(scroll, 0, Math.max(0, content - (listBottom - listTop)));
        g.fill(listX - 2, listTop - 2, listX + listW + 2, listBottom + 2, 0x60000000);
        g.enableScissor(listX, listTop, listX + listW, listBottom);
        int y = listTop - scroll;
        for (Line line : lines) {
            int h = line.group() == null ? HEADER : ROW;
            if (y + h >= listTop && y <= listBottom) {
                if (line.group() == null) {
                    g.text(font, Component.translatable("slopmatica.split.section." + SECTION_KEYS[line.section()]).withStyle(ChatFormatting.BOLD), listX + 2, y + 5, 0xFFFFD040);
                } else {
                    Group gr = line.group();
                    boolean over = mouseX >= listX && mouseX < listX + listW && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + h, listBottom);
                    if (over) {
                        g.fill(listX, y, listX + listW, y + h, 0x30FFFFFF);
                        hover = Component.translatable("slopmatica.split.row.tooltip", gr.maxX() - gr.minX() + 1, gr.maxY() - gr.minY() + 1, gr.maxZ() - gr.minZ() + 1,
                            String.format(Locale.ROOT, "%,d", gr.count()));
                    }
                    boolean on = picked[gr.id()];
                    g.text(font, on ? "☑" : "☐", listX + 4, y + 2, on ? 0xFF80FF80 : 0xFF909090);
                    String count = String.format(Locale.ROOT, "%,d", gr.count());
                    int cw = font.width(count);
                    g.text(font, font.plainSubstrByWidth(split.name(gr).getString(), listW - 30 - cw), listX + 16, y + 2, on ? 0xFFFFFFFF : 0xFFA0A0A0);
                    g.text(font, count, listX + listW - 4 - cw, y + 2, 0xFF909090);
                }
            }
            y += h;
        }
        g.disableScissor();
        if (content > listBottom - listTop) {
            int shown = listBottom - listTop, thumb = Math.max(12, shown * shown / content);
            int ty = listTop + (shown - thumb) * scroll / (content - shown);
            g.fill(listX + listW + 3, listTop, listX + listW + 6, listBottom, 0x40FFFFFF);
            g.fill(listX + listW + 3, ty, listX + listW + 6, ty + thumb, 0xC0FFFFFF);
        }
        // What's picked, above the buttons.
        int x = listX + listW + 16;
        int[] b = boxOf(current);
        g.text(font, Component.translatable("slopmatica.split.picked", String.format(Locale.ROOT, "%,d", current.cardinality())), x, listTop, 0xFFFFFFFF);
        if (b != null) {
            g.text(font, Component.translatable("slopmatica.split.picked_size", b[0], b[1], b[2]), x, listTop + 11, 0xFFA0A0A0);
        }
        g.text(font, Component.translatable("slopmatica.split.help"), listX, listTop - 12, 0xFF909090);
        if (hover != null) {
            g.setTooltipForNextFrame(font, hover, mouseX, mouseY);
        }
    }

    private int @Nullable [] boxOf(BitSet cells) {
        BlockPos min = split.partMin(cells);
        if (min == null) {
            return null;
        }
        int sx = split.result.sx(), sz = split.result.sz(), layer = sx * sz;
        int maxX = 0, maxY = 0, maxZ = 0;
        for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
            maxX = Math.max(maxX, i % sx);
            maxZ = Math.max(maxZ, (i / sx) % sz);
            maxY = Math.max(maxY, i / layer);
        }
        return new int[] {split.box.minX() + maxX - min.getX() + 1, split.box.minY() + maxY - min.getY() + 1, split.box.minZ() + maxZ - min.getZ() + 1};
    }

    private int contentHeight() {
        int h = 0;
        for (Line line : lines) {
            h += line.group() == null ? HEADER : ROW;
        }
        return h;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x >= listX && x < listX + listW && y >= listTop && y < listBottom) {
            scroll -= (int) (scrollY * ROW * 3);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (split == null || event.x() < listX || event.x() >= listX + listW || event.y() < listTop || event.y() >= listBottom) {
            return false;
        }
        int y = listTop - scroll;
        for (Line line : lines) {
            int h = line.group() == null ? HEADER : ROW;
            if (event.y() >= y && event.y() < y + h) {
                if (line.group() == null) {
                    // A heading: all of that section on, or (if it all is) off.
                    List<Group> in = lines.stream().filter(l -> l.section() == line.section() && l.group() != null).map(Line::group).toList();
                    boolean allOn = in.stream().allMatch(gr -> picked[gr.id()]);
                    in.forEach(gr -> picked[gr.id()] = !allOn);
                } else {
                    picked[line.group().id()] = !picked[line.group().id()];
                }
                update();
                return true;
            }
            y += h;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (split != null && placement.mask() != null) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.split.still_preview", placement.name()));
        }
        minecraft.gui.setScreen(parent);
    }
}
