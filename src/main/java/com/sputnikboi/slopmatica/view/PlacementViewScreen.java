package com.sputnikboi.slopmatica.view;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.gui.MaterialPanels;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The placement view with a free cursor (right click while flying). The world stays in view, no
 * blur. Along the top: a tab per tool (move, select, crop; keys 1-3). On the
 * left: the tool's own buttons (one per selection tool), layer view and the list of builds (click a name to show or hide it, ☰ for its material panel, → to
 * work on it). Bottom right: rotate, mirror, nudge (Shift-click: 5 blocks), reset and paste.
 * Right click or Esc goes back to flying.
 */
public class PlacementViewScreen extends Screen {
    private static final int B = 20;
    private static final int GAP = 2;
    private static final int LEFT_W = 124;
    /** The tabs along the top (Blender-style): one per tool, keys 1-3. */
    private static final int TABS_Y = 4;
    private static final int PANEL_Y = TABS_Y + B + 4;

    /** Buttons drawn as chosen (an underline): the current tab, selection tool and mode. */
    private final List<Button> chosen = new java.util.ArrayList<>();

    /** Move tool: the height of the plane the build slides on, and origin minus grabbed block. */
    private double dragPlaneY;
    private @Nullable BlockPos dragOffset;
    /** Modify and crop tools: the face being dragged, and the last box applied. */
    private @Nullable BoxDrag boxDrag;
    private @Nullable BoundingBox lastBox;
    private int buildsTop;
    /** What a drag started from, so right click can put it back. */
    private @Nullable BlockPos dragStart;
    private Selection.@Nullable State dragSelection;
    private @Nullable BoundingBox dragCrop;
    /** Modify tool: the selection's parts and frame as a stretch or move began. */
    private @Nullable List<com.sputnikboi.slopmatica.build.SelectionShape.Part> reshapeParts;
    private @Nullable BoundingBox reshapeFrame;
    /** Modify tool, dragging the origin: the plane it slides on, and frame offset from the grabbed block. */
    private boolean originDrag;
    private int originDy;

    public PlacementViewScreen() {
        super(Component.translatable("slopmatica.view.title"));
    }

    @Override
    protected void init() {
        PlacementView view = PlacementView.INSTANCE;
        chosen.clear();
        icons.clear();
        initTabs(view);
        initLeft(view);

        // Top right, where the flying HUD draws it too: the main menu, coming back here when closed.
        addRenderableWidget(Button.builder(Component.literal(PlacementView.MENU_LABEL), b -> minecraft.gui.setScreen(new com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen(this)))
            .bounds(PlacementView.menuIconX(width), 4, PlacementView.MENU_ICON, PlacementView.MENU_ICON)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.view.menu", PlacementView.key(SlopmaticaKeys.openMenu())))).build());

        // Bottom-right panel, built right to left from the corner.
        int right = width - 6;
        int bottom = height - 6;

        int row3 = bottom - B;
        int row2 = row3 - B - GAP;
        int row1 = row2 - B - GAP;
        int row0 = row1 - B - 6;

        int col = right - 64;
        addRenderableWidget(button("slopmatica.view.done", col, row3, 64, b -> onClose(), null));
        addRenderableWidget(button("slopmatica.view.paste", col - 64 - GAP, row3, 64, b -> view.build(minecraft), "slopmatica.view.paste.tooltip"));
        addRenderableWidget(button("slopmatica.view.reset", col - 128 - 2 * GAP, row3, 64, b -> view.reset(minecraft), "slopmatica.view.reset.tooltip"));

        // Nudge cross: forward on top, left/back/right below; up/down beside it.
        int upDownW = 36;
        int crossX = right - (3 * B + 2 * GAP) - upDownW - 8;
        int upDownX = right - upDownW;
        // What they move: the selection while there is one (unless switched to the build), else the build.
        boolean sel = view.nudgesSelection(minecraft);
        Component targetLabel = Component.translatable(sel ? "slopmatica.view.nudge_target.selection" : "slopmatica.view.nudge_target.build");
        int targetW = Math.max(font.width(Component.translatable("slopmatica.view.nudge_target.selection")), font.width(Component.translatable("slopmatica.view.nudge_target.build"))) + 12;
        Button target = addRenderableWidget(Button.builder(targetLabel, b -> {
                view.nudgeBuild = !view.nudgeBuild;
                rebuildWidgets();
            }).bounds(crossX - targetW - 4, row2, targetW, B).tooltip(Tooltip.create(Component.translatable("slopmatica.view.nudge_target.tooltip"))).build());
        target.active = Selection.INSTANCE.frame() != null && view.placement() != null;
        addRenderableWidget(button("▲", crossX + B + GAP, row1, B, b -> view.nudge(minecraft, 0), "slopmatica.view.forward"));
        addRenderableWidget(button("◀", crossX, row2, B, b -> view.nudge(minecraft, 2), "slopmatica.view.left"));
        addRenderableWidget(button("▼", crossX + B + GAP, row2, B, b -> view.nudge(minecraft, 1), "slopmatica.view.back"));
        addRenderableWidget(button("▶", crossX + 2 * (B + GAP), row2, B, b -> view.nudge(minecraft, 3), "slopmatica.view.right"));
        addRenderableWidget(button("slopmatica.view.up.label", upDownX, row1, upDownW, b -> view.nudge(minecraft, 4), "slopmatica.view.up"));
        addRenderableWidget(button("slopmatica.view.down.label", upDownX, row2, upDownW, b -> view.nudge(minecraft, 5), "slopmatica.view.down"));

        // Plain words: the arrow glyphs vanilla's font has for rotation are too small to read.
        int w = 64;
        addRenderableWidget(button("slopmatica.view.rotate_ccw.label", right - 3 * w - 2 * GAP, row0, w, b -> view.rotate(minecraft, Rotation.COUNTERCLOCKWISE_90), "slopmatica.view.rotate_ccw"));
        addRenderableWidget(button("slopmatica.view.rotate_cw.label", right - 2 * w - GAP, row0, w, b -> view.rotate(minecraft, Rotation.CLOCKWISE_90), "slopmatica.view.rotate_cw"));
        addRenderableWidget(button("slopmatica.view.mirror.label", right - w, row0, w, b -> view.mirror(minecraft), "slopmatica.view.mirror"));
    }

    /** Move, Select, Modify, Crop along the top. */
    private void initTabs(PlacementView view) {
        int x = 4;
        for (Tool t : Tool.values()) {
            Component label = t.label();
            int w = font.width(label) + 14;
            Button tab = addRenderableWidget(Button.builder(t == view.tool() ? label.copy().withStyle(ChatFormatting.YELLOW) : label, b -> pickTool(t))
                .bounds(x, TABS_Y, w, B).tooltip(Tooltip.create(Component.translatable("slopmatica.tool.tab.tooltip", t.tooltip(), t.ordinal() + 1))).build());
            if (t == view.tool()) {
                chosen.add(tab);
            }
            x += w + GAP;
        }
    }

    /** Lines in the help panel under the tabs (the left column starts below it). */
    private List<String> panelLines(@Nullable Placement p) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(p == null ? "Build view: no build selected" : "Build view: " + p.name() + "  (" + p.worldOriginPoint().toShortString() + ")");
        if (p != null) {
            Recommendations.of(minecraft, p).forEach(w -> lines.add("⚠ " + w.text().getString()));
        }
        lines.add(switch (PlacementView.INSTANCE.tool()) {
            case MOVE -> "Drag the build to move it   Scroll: up/down";
            case SELECT -> Component.translatable("slopmatica.seltool." + com.sputnikboi.slopmatica.config.SlopmaticaConfig.get().selectionTool.name().toLowerCase(Locale.ROOT) + ".hint").getString();
            case CROP -> "Drag a face of the orange box to crop the build";
        });
        lines.add("Right click/Esc: fly   Shift: 5 blocks   " + PlacementView.key(SlopmaticaKeys.PLACEMENT_VIEW) + ": done");
        return lines;
    }

    /** The left column: tool options, layers, builds. Rebuilt whenever one of them changes. */
    private void initLeft(PlacementView view) {
        int x = 6, y = PANEL_Y + 4 + panelLines(view.placement()).size() * 11 + 6;
        Placement onBuild = view.placement();
        if (onBuild != null) {
            Placement current = onBuild;
            List<Recommendations.Warning> warnings = Recommendations.of(minecraft, current);
            for (Recommendations.Warning w : warnings) {
                if (w.fix() != null) {
                    addRenderableWidget(Button.builder(w.fixLabel(), b -> {
                        w.fix().run();
                        rebuildWidgets();
                    }).bounds(x, y, LEFT_W, B).build());
                    y += B + GAP;
                }
            }
            if (!current.schematic().meta().isEmpty()) {
                addRenderableWidget(Button.builder(Component.translatable(current.ignoreWarnings ? "slopmatica.recommend.show" : "slopmatica.recommend.ignore"), b -> {
                    current.ignoreWarnings = !current.ignoreWarnings;
                    com.sputnikboi.slopmatica.placement.PlacementStore.INSTANCE.markDirty();
                    rebuildWidgets();
                }).bounds(x, y, LEFT_W, B).tooltip(Tooltip.create(Component.translatable("slopmatica.recommend.ignore.tooltip"))).build());
                y += B + GAP;
            }
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.button"),
                b -> minecraft.gui.setScreen(new com.sputnikboi.slopmatica.gui.SchematicInfoScreen(this, current.schematic().source(), current)))
                .bounds(x, y, LEFT_W, B).build());
            y += B + GAP + 4;
        }
        Tool tool = view.tool();
        if (tool == Tool.CROP) {
            Placement p = view.placement();
            Button reset = addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.crop.reset"), b -> {
                if (p != null) {
                    p.setCrop(null);
                    PlacementManager.INSTANCE.changed();
                    rebuildWidgets();
                }
            }).bounds(x, y, LEFT_W, B).build());
            reset.active = p != null && p.crop() != null;
            y += B + GAP;
        } else if (tool == Tool.SELECT) {
            y = initSelectTools(x, y);
            boolean any = Selection.INSTANCE.box() != null;
            int third = (LEFT_W - 2 * GAP) / 3;
            Button copy = addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.selection.copy"), b -> Clipboard.INSTANCE.copy(minecraft))
                .bounds(x, y, third, B).tooltip(Tooltip.create(Component.translatable("slopmatica.tool.selection.copy.tooltip"))).build());
            Button clear = addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.selection.clear"), b -> {
                UndoStack.INSTANCE.saveSelection();
                Selection.INSTANCE.clear();
                rebuildWidgets();
            }).bounds(x + third + GAP, y, third, B).build());
            copy.active = clear.active = any;
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.seltools.more"), b -> minecraft.gui.setScreen(new com.sputnikboi.slopmatica.gui.SelectionToolsScreen(this)))
                .bounds(x + 2 * (third + GAP), y, LEFT_W - 2 * (third + GAP), B).tooltip(Tooltip.create(Component.translatable("slopmatica.seltools.more.tooltip"))).build());
            y += B + GAP;
        }

        // Undo, for every tool.
        int half = (LEFT_W - GAP) / 2;
        Button undo = addRenderableWidget(Button.builder(Component.translatable("slopmatica.view.undo"), b -> {
            view.undo(minecraft);
            rebuildWidgets();
        }).bounds(x, y, half, B).tooltip(Tooltip.create(Component.translatable("slopmatica.view.undo.tooltip"))).build());
        undo.active = !UndoStack.INSTANCE.isEmpty();
        if (tool == Tool.SELECT) {
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.view.fit"), b -> {
                view.fitSelection(minecraft);
                rebuildWidgets();
            }).bounds(x + half + GAP, y, LEFT_W - half - GAP, B).tooltip(Tooltip.create(Component.translatable("slopmatica.view.fit.tooltip"))).build())
                .active = Selection.INSTANCE.plainBox() != null;
        }
        y += B + GAP;

        // Layers.
        y += 4;
        Layers layers = Layers.INSTANCE;
        int here = Layers.startHeight(minecraft.player != null ? minecraft.player.getBlockY() : 0);
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.layers.button",
                Component.translatable("slopmatica.layers." + layers.mode().name().toLowerCase(Locale.ROOT))), b -> {
                layers.cycleMode(here);
                rebuildWidgets();
            }).bounds(x, y, LEFT_W, B).tooltip(Tooltip.create(Component.translatable("slopmatica.layers.tooltip"))).build());
        y += B + GAP;
        if (layers.mode() != Layers.Mode.ALL) {
            addRenderableWidget(button("▲", x, y, B, b -> layers.move(1), "slopmatica.layers.up"));
            addRenderableWidget(button("▼", x + B + GAP, y, B, b -> layers.move(-1), "slopmatica.layers.down"));
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.layers.here"), b -> layers.setLayer(here))
                .bounds(x + 2 * (B + GAP), y, LEFT_W - 2 * (B + GAP), B).tooltip(Tooltip.create(Component.translatable("slopmatica.layers.here.tooltip"))).build());
            y += B + GAP;
        }

        // Builds in this dimension.
        y += 14; // Room for the heading drawn in extractRenderState.
        buildsTop = y;
        int nameW = LEFT_W - 2 * (16 + GAP);
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (minecraft.level == null || !p.inDimension(minecraft.level) || y + B > height - 6) {
                continue;
            }
            boolean current = p == view.placement();
            boolean incomplete = p.schematic().incomplete();
            Component name = Component.literal((p.visible() ? "✔ " : "✖ ") + (incomplete ? "⚠ " : "") + p.name())
                .withStyle(current ? ChatFormatting.YELLOW : p.visible() ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
            Component tip = Component.translatable("slopmatica.builds.toggle");
            if (incomplete) {
                tip = tip.copy().append("\n\n").append(SlopmaticaActions.missingWarning(p.schematic()));
            }
            addRenderableWidget(Button.builder(name, b -> {
                p.setVisible(!p.visible());
                PlacementManager.INSTANCE.changed();
                rebuildWidgets();
            }).bounds(x, y, nameW, B).tooltip(Tooltip.create(tip)).build());
            addRenderableWidget(Button.builder(Component.literal("☰").withStyle(p.panelOpen ? ChatFormatting.AQUA : ChatFormatting.WHITE), b -> {
                p.panelOpen = !p.panelOpen;
                if (!p.panelOpen) {
                    p.panelPinned = false;
                }
                MaterialPanels.INSTANCE.invalidate();
                rebuildWidgets();
            }).bounds(x + nameW + GAP, y, 16, B).tooltip(Tooltip.create(Component.translatable("slopmatica.builds.panel"))).build());
            Button go = addRenderableWidget(Button.builder(Component.literal("→"), b -> {
                view.switchTo(p);
                rebuildWidgets();
            }).bounds(x + nameW + 16 + 2 * GAP, y, 16, B).tooltip(Tooltip.create(Component.translatable("slopmatica.builds.switch"))).build());
            go.active = !current && p.visible();
            y += B;
        }
        // Load another build: it lands where the camera looks, and the view switches to it.
        if (y + B <= height - 6) {
            addRenderableWidget(Button.builder(Component.literal("+"), b -> minecraft.gui.setScreen(
                    new com.sputnikboi.slopmatica.gui.SchematicBrowserScreen(this).onPick(file -> {
                        minecraft.gui.setScreen(this);
                        SlopmaticaActions.load(file, view.cameraTarget(minecraft), SlopmaticaActions::actionBar, loaded -> {
                            view.switchTo(loaded);
                            if (minecraft.gui.screen() == this) {
                                rebuildWidgets();
                            }
                        });
                    })))
                .bounds(x, y + GAP, B, B).tooltip(Tooltip.create(Component.translatable("slopmatica.builds.add"))).build());
        }
    }

    /** 11x11 icons on the tool buttons, in {@link com.sputnikboi.slopmatica.build.SelectionTool} order ('#' is a pixel). */
    private static final String[][] TOOL_ICONS = {
        {"#..........", "##.........", "#.#........", "#..#.......", "#...#......", "#....#.....", "#.....#....", "#..####....", "#.#.#......", "##...#.....", ".....#....."},
        {"...#######.", "..#.....##.", ".#.....#.#.", "#######..#.", "#.....#..#.", "#.....#..#.", "#.....#..#.", "#.....#.#..", "#.....##...", "#######....", "..........."},
        {"...#####...", ".##.....##.", ".#.......#.", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", ".#.......#.", ".##.....##.", "...#####..."},
        {".#########.", "#.........#", ".#########.", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", "#.........#", ".#########."},
        {"#####......", "#...#......", "#...#......", "#...#......", "#...######.", "#........#.", "#........#.", "#........#.", "##########.", "...........", "..........."},
        {".........##", "........###", ".......###.", "......###..", ".....###...", "....##.....", "..###......", ".####......", "#####......", "####.......", "##........."},
        {"..#......#.", ".###....###", "..#......#.", ".......##..", "......##...", ".....##....", "....##.....", "...##......", "..##.......", ".##........", "##........."},
    };
    private static final String[] BALL_ICON = {"..###..", ".#####.", "#######", "#######", "#######", ".#####.", "..###.."};
    private static final String[] CUBE_ICON = {"#######", "#######", "#######", "#######", "#######", "#######", "#######"};
    /** Buttons that show a pixel icon instead of text. */
    private final java.util.Map<Button, String[]> icons = new java.util.HashMap<>();

    /**
     * The Select tab's own buttons: one per selection tool (Blender's toolbar), how new shapes
     * combine (replace, add, cut), and the chosen tool's options. Returns the next free row.
     */
    private int initSelectTools(int x, int y) {
        var c = com.sputnikboi.slopmatica.config.SlopmaticaConfig.get();
        var tools = com.sputnikboi.slopmatica.build.SelectionTool.values();
        int w = (LEFT_W - (tools.length - 1) * GAP) / tools.length;
        for (int i = 0; i < tools.length; i++) {
            var t = tools[i];
            int bx = x + i * (w + GAP), bw = i == tools.length - 1 ? LEFT_W - i * (w + GAP) : w;
            Button button = addRenderableWidget(Button.builder(Component.empty(), b -> {
                    Selection.INSTANCE.endStroke();
                    c.selectionTool = t;
                    c.save();
                    rebuildWidgets();
                }).bounds(bx, y, bw, B)
                .tooltip(Tooltip.create(t.label().copy().withStyle(ChatFormatting.YELLOW).append("\n").append(t.tooltip()))).build());
            icons.put(button, TOOL_ICONS[i]);
            if (t == c.selectionTool) {
                chosen.add(button);
            }
        }
        y += B + GAP;

        var modes = com.sputnikboi.slopmatica.build.SelectionTool.Mode.values();
        // Wide enough for each word: Replace is longer than Add and Cut.
        int[] mw = new int[modes.length];
        int total = 0;
        for (int i = 0; i < modes.length; i++) {
            mw[i] = font.width(modes[i].label()) + 8;
            total += mw[i];
        }
        int spare = LEFT_W - (modes.length - 1) * GAP - total;
        for (int i = 0, bx = x; i < modes.length; i++) {
            var m = modes[i];
            int bw = mw[i] + (i == modes.length - 1 ? spare - spare / modes.length * (modes.length - 1) : spare / modes.length);
            Button button = addRenderableWidget(Button.builder(m == c.selectionMode ? m.label().copy().withStyle(ChatFormatting.YELLOW) : m.label(), b -> {
                    c.selectionMode = m;
                    c.save();
                    rebuildWidgets();
                }).bounds(bx, y, bw, B)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.selmode." + m.name().toLowerCase(Locale.ROOT) + ".tooltip"))).build());
            if (m == c.selectionMode) {
                chosen.add(button);
            }
            bx += bw + GAP;
        }
        y += B + GAP;

        // The tool's options: surface only (every tool), brush size and shape, magic's exact match.
        var tool = c.selectionTool;
        boolean surface = c.surfaceTools.contains(tool.name());
        if (tool == com.sputnikboi.slopmatica.build.SelectionTool.CURSOR) {
            return y; // Nothing to set: it changes the selection there is.
        }
        boolean brush = tool == com.sputnikboi.slopmatica.build.SelectionTool.BRUSH, magic = tool == com.sputnikboi.slopmatica.build.SelectionTool.MAGIC;
        int sw = brush || magic ? 56 : LEFT_W;
        addRenderableWidget(Button.builder(Component.translatable(surface ? "slopmatica.seltools.surface.on" : "slopmatica.seltools.surface.off"), b -> {
                if (c.surfaceTools.contains(tool.name())) {
                    c.surfaceTools.remove(tool.name());
                } else {
                    c.surfaceTools.add(tool.name());
                }
                c.save();
                rebuildWidgets();
            }).bounds(x, y, sw, B).tooltip(Tooltip.create(Component.translatable("slopmatica.seltools.surface.tooltip"))).build());
        if (brush) {
            int rx = x + sw + GAP;
            addRenderableWidget(button("-", rx, y, 14, b -> brushSize(-1), "slopmatica.seltools.brush_smaller"));
            addRenderableWidget(Button.builder(Component.literal(String.valueOf(c.brushRadius)), b -> { }).bounds(rx + 14, y, 18, B)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.seltools.brush_radius.tooltip"))).build()).active = false;
            addRenderableWidget(button("+", rx + 32, y, 14, b -> brushSize(1), "slopmatica.seltools.brush_bigger"));
            int shx = rx + 46 + GAP;
            boolean sphere = c.brushShape == com.sputnikboi.slopmatica.build.SelectionTool.BrushShape.SPHERE;
            icons.put(addRenderableWidget(Button.builder(Component.empty(), b -> {
                    c.brushShape = sphere ? com.sputnikboi.slopmatica.build.SelectionTool.BrushShape.CUBE : com.sputnikboi.slopmatica.build.SelectionTool.BrushShape.SPHERE;
                    c.save();
                    rebuildWidgets();
                }).bounds(shx, y, x + LEFT_W - shx, B)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.seltools.brush_shape." + c.brushShape.name().toLowerCase(Locale.ROOT) + ".tooltip"))).build()),
                sphere ? BALL_ICON : CUBE_ICON);
        } else if (magic) {
            addRenderableWidget(Button.builder(Component.translatable(c.magicExact ? "slopmatica.seltools.exact.on" : "slopmatica.seltools.exact.off"), b -> {
                    c.magicExact = !c.magicExact;
                    c.save();
                    rebuildWidgets();
                }).bounds(x + sw + GAP, y, LEFT_W - sw - GAP, B).tooltip(Tooltip.create(Component.translatable("slopmatica.seltools.magic_exact.tooltip"))).build());
        }
        return y + B + GAP;
    }

    private void brushSize(int by) {
        var c = com.sputnikboi.slopmatica.config.SlopmaticaConfig.get();
        c.brushRadius = Math.clamp(c.brushRadius + (minecraft.hasShiftDown() ? by * 4 : by), 0, 16);
        c.save();
        rebuildWidgets();
    }

    /** Right click during a drag: everything back to how it was when the drag started. */
    private boolean cancelDrag() {
        Placement p = PlacementView.INSTANCE.placement();
        if (dragOffset != null && p != null && dragStart != null) {
            dragOffset = null;
            p.setOrigin(dragStart);
            PlacementManager.INSTANCE.changed();
        } else if (boxDrag != null || originDrag) {
            boxDrag = null;
            originDrag = false;
            reshapeParts = null;
            if (PlacementView.INSTANCE.tool() == Tool.SELECT && dragSelection != null) {
                Selection.INSTANCE.restore(dragSelection);
            } else if (p != null) {
                p.setCrop(dragCrop);
                PlacementManager.INSTANCE.changed();
            }
        } else {
            return false;
        }
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.drag_cancelled").withColor(SlopmaticaActions.ACCENT));
        return true;
    }

    private void pickTool(Tool tool) {
        PlacementView.INSTANCE.setTool(tool);
        rebuildWidgets();
    }

    private Button button(String label, int x, int y, int w, Button.OnPress press, @Nullable String tooltip) {
        Component text = label.startsWith("slopmatica.") ? Component.translatable(label) : Component.literal(label);
        Button.Builder builder = Button.builder(text, press).bounds(x, y, w, B);
        if (tooltip != null) {
            builder.tooltip(Tooltip.create(Component.translatable(tooltip)));
        }
        return builder.build();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The build is the point of this screen: no dimming, no blur.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        for (Button b : chosen) {
            graphics.fill(b.getX() + 2, b.getY() + b.getHeight() - 2, b.getX() + b.getWidth() - 2, b.getY() + b.getHeight(), SlopmaticaActions.ACCENT);
        }
        icons.forEach((b, icon) -> {
            int color = chosen.contains(b) ? 0xFFFFFF55 : b.isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFD0D0D0;
            int ix = b.getX() + (b.getWidth() - icon[0].length()) / 2, iy = b.getY() + (b.getHeight() - icon.length) / 2 - (chosen.contains(b) ? 1 : 0);
            for (int r = 0; r < icon.length; r++) {
                for (int col = 0; col < icon[r].length(); col++) {
                    if (icon[r].charAt(col) == '#') {
                        graphics.fill(ix + col, iy + r, ix + col + 1, iy + r + 1, color);
                    }
                }
            }
        });
        double[] o = originOnScreen();
        if (o != null) {
            // The origin as a handle on screen: visible even inside blocks, and easy to grab.
            int ox = (int) Math.round(o[0]), oy = (int) Math.round(o[1]);
            boolean hot = originDrag || Math.hypot(mouseX - o[0], mouseY - o[1]) <= ORIGIN_GRAB;
            int color = hot ? 0xFFFFFFFF : 0xFFFFD040;
            for (int r = 0; r <= 5; r++) {
                int half = 5 - r;
                graphics.fill(ox - half - 1, oy - r - 1, ox + half + 2, oy - r, 0xC0000000);
                graphics.fill(ox - half - 1, oy + r, ox + half + 2, oy + r + 1, 0xC0000000);
            }
            for (int r = 0; r <= 4; r++) {
                int half = 4 - r;
                graphics.fill(ox - half, oy - r, ox + half + 1, oy - r + 1, color);
                graphics.fill(ox - half, oy + r, ox + half + 1, oy + r + 1, color);
            }
            if (hot && !originDrag) {
                graphics.text(font, Component.translatable("slopmatica.tool.modify.origin"), ox + 8, oy - 4, 0xFFFFD040);
            }
        }
        Placement p = PlacementView.INSTANCE.placement();
        List<String> lines = panelLines(p);
        PlacementView.drawPanel(graphics, minecraft, lines, 4, PANEL_Y, p == null ? 0 : Recommendations.of(minecraft, p).size());
        graphics.text(font, Component.translatable("slopmatica.builds.title").withStyle(ChatFormatting.BOLD), 6, buildsTop - 11, 0xFFFFFFFF);
        MaterialPanels.INSTANCE.draw(graphics, minecraft, mouseX, mouseY, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (MaterialPanels.INSTANCE.mouseClicked(minecraft, event.x(), event.y(), event.button(), width, height)) {
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        // 26.3 numbers mouse buttons the SDL way: left 1, middle 2, right 3.
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (cancelDrag()) {
                return true;
            }
            onClose();
            return true;
        }
        PlacementView view = PlacementView.INSTANCE;
        Placement p = view.placement();
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || minecraft.level == null) {
            return false;
        }
        if (p == null && (view.tool() == Tool.MOVE || view.tool() == Tool.CROP)) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.no_build").withColor(SlopmaticaActions.ERROR));
            return true;
        }
        Vec3 from = view.eye();
        Vec3 dir = view.rayThrough(minecraft, event.x(), event.y(), width, height);
        String dim = minecraft.level.dimension().identifier().toString();
        switch (view.tool()) {
            case MOVE -> {
                AABB box = AABB.of(p.transform().worldBounds());
                Vec3 hit = box.contains(from) ? from : box.clip(from, from.add(dir.scale(512))).orElse(null);
                if (hit == null) {
                    return false;
                }
                dragPlaneY = hit.y;
                UndoStack.INSTANCE.saveBuild(p);
                dragStart = p.transform().origin();
                dragOffset = p.transform().origin().subtract(BlockPos.containing(hit.x, dragPlaneY, hit.z));
            }
            case SELECT -> {
                if (view.editingSelection()) {
                    return grabSelection(from, dir, event.x(), event.y(), dim);
                }
                BlockPos block = view.pointedBlock(minecraft, from, dir);
                if (block == null) {
                    return false;
                }
                UndoStack.INSTANCE.saveSelection();
                if (com.sputnikboi.slopmatica.config.SlopmaticaConfig.get().selectionTool == com.sputnikboi.slopmatica.build.SelectionTool.BRUSH) {
                    // Drag to keep painting; it ends when the button comes up.
                    Selection.INSTANCE.startStroke(dim, event.hasShiftDown());
                    Selection.INSTANCE.paint(block);
                } else {
                    Selection.INSTANCE.use(block, dim, event.hasShiftDown());
                }
                rebuildWidgets(); // Fit and Undo may have become available.
            }
            case CROP -> {
                boxDrag = BoxDrag.grab(p.transform().worldBounds(), from, dir);
                lastBox = p.transform().worldBounds();
                if (boxDrag != null) {
                    UndoStack.INSTANCE.saveBuild(p);
                    dragCrop = p.crop();
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (MaterialPanels.INSTANCE.mouseDragged(event.x(), event.y())) {
            return true;
        }
        PlacementView view = PlacementView.INSTANCE;
        Placement p = view.placement();
        if (Selection.INSTANCE.painting() && minecraft.level != null) {
            BlockPos block = view.pointedBlock(minecraft, view.eye(), view.rayThrough(minecraft, event.x(), event.y(), width, height));
            if (block != null) {
                Selection.INSTANCE.paint(block);
            }
            return true;
        }
        if (minecraft.level == null || (p == null && boxDrag == null && !originDrag)) {
            return super.mouseDragged(event, dx, dy);
        }
        Vec3 from = view.eye();
        Vec3 dir = view.rayThrough(minecraft, event.x(), event.y(), width, height);
        if (originDrag) {
            Vec3 hit = planeHit(from, dir, dragPlaneY);
            if (hit != null && reshapeFrame != null && reshapeParts != null && dragOffset != null) {
                BlockPos to = BlockPos.containing(hit.x, dragPlaneY, hit.z).offset(dragOffset);
                BlockPos start = Selection.originOf(reshapeFrame);
                BoundingBox moved = reshapeFrame.moved(to.getX() - start.getX(), originDy, to.getZ() - start.getZ());
                if (!moved.equals(lastBox)) {
                    lastBox = moved;
                    Selection.INSTANCE.reshape(reshapeParts, reshapeFrame, moved);
                }
            }
            return true;
        }
        if (boxDrag != null) {
            BoundingBox box = boxDrag.follow(from, dir);
            if (!box.equals(lastBox)) {
                lastBox = box;
                if (view.tool() == Tool.SELECT && reshapeParts != null && reshapeFrame != null) {
                    Selection.INSTANCE.reshape(reshapeParts, reshapeFrame, box);
                } else if (p != null) {
                    Placement.Transform t = p.transform();
                    p.setCrop(BoundingBox.fromCorners(t.toLocal(box.minX(), box.minY(), box.minZ()), t.toLocal(box.maxX(), box.maxY(), box.maxZ())));
                    PlacementManager.INSTANCE.changed();
                }
            }
            return true;
        }
        if (dragOffset == null || p == null) {
            return super.mouseDragged(event, dx, dy);
        }
        Vec3 point = planeHit(from, dir, dragPlaneY);
        if (point == null) {
            return true; // Looking away from the plane (at the sky, say): nowhere to put it.
        }
        BlockPos grabbed = BlockPos.containing(point.x, dragPlaneY, point.z);
        BlockPos origin = grabbed.offset(dragOffset);
        BlockPos current = p.transform().origin();
        if (origin.getX() != current.getX() || origin.getZ() != current.getZ()) {
            p.setOrigin(new BlockPos(origin.getX(), current.getY(), origin.getZ()));
            PlacementManager.INSTANCE.changed();
        }
        return true;
    }

    /** The Cursor tool's click: the origin (to move the selection) or a face of its frame (to stretch it). */
    private boolean grabSelection(Vec3 from, Vec3 dir, double mx, double my, String dim) {
        BoundingBox frame = dim.equals(Selection.INSTANCE.dimension()) ? Selection.INSTANCE.frame() : null;
        if (frame == null) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.tool.modify.none").withColor(SlopmaticaActions.ERROR));
            return true;
        }
        BlockPos origin = Selection.originOf(frame);
        double[] onScreen = originOnScreen();
        // Only its on-screen diamond grabs the origin: it sits inside the frame, so a ray test would beat every face.
        if (onScreen != null && Math.hypot(mx - onScreen[0], my - onScreen[1]) <= ORIGIN_GRAB) {
            // The origin: drag it to move the whole selection along its floor.
            originDrag = true;
            dragPlaneY = origin.getY() + 0.5;
            Vec3 hit = planeHit(from, dir, dragPlaneY);
            dragOffset = hit == null ? BlockPos.ZERO : origin.subtract(BlockPos.containing(hit.x, dragPlaneY, hit.z));
        } else {
            boxDrag = BoxDrag.grab(frame, from, dir);
            if (boxDrag == null) {
                return true;
            }
        }
        lastBox = frame;
        reshapeFrame = frame;
        reshapeParts = Selection.INSTANCE.parts();
        UndoStack.INSTANCE.saveSelection();
        dragSelection = Selection.INSTANCE.state();
        return true;
    }

    /** How near (GUI pixels) a click must be to the origin's handle to grab it. */
    private static final int ORIGIN_GRAB = 7;

    /** The selection's origin on screen while the Cursor tool is chosen; null otherwise or off screen. */
    private double @Nullable [] originOnScreen() {
        if (!PlacementView.INSTANCE.editingSelection() || minecraft.level == null
            || !minecraft.level.dimension().identifier().toString().equals(Selection.INSTANCE.dimension())) {
            return null;
        }
        BlockPos o = Selection.INSTANCE.origin();
        return o == null ? null : PlacementView.INSTANCE.project(minecraft, Vec3.atCenterOf(o), width, height);
    }

    /** Where a ray meets the flat plane at height {@code y}; null if it never does (nearby). */
    private static @Nullable Vec3 planeHit(Vec3 from, Vec3 dir, double y) {
        if (Math.abs(dir.y) < 1.0E-4) {
            return null;
        }
        double t = (y - from.y) / dir.y;
        return t <= 0 || t > 1024 ? null : from.add(dir.scale(t));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (MaterialPanels.INSTANCE.mouseReleased()) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseReleased(event);
        }
        if (Selection.INSTANCE.painting()) {
            Selection.INSTANCE.endStroke();
            rebuildWidgets();
            return true;
        }
        Placement p = PlacementView.INSTANCE.placement();
        if (originDrag) {
            originDrag = false;
            dragOffset = null;
            originDy = 0;
            reshapeParts = null;
            Selection.INSTANCE.finishReshape();
            BlockPos o = Selection.INSTANCE.origin();
            if (o != null) {
                SlopmaticaActions.actionBar(Component.translatable("slopmatica.tool.modify.moved", o.toShortString()).withColor(SlopmaticaActions.ACCENT));
            }
            rebuildWidgets();
            return true;
        }
        if (boxDrag != null) {
            boxDrag = null;
            if (reshapeParts != null) {
                reshapeParts = null;
                Selection.INSTANCE.finishReshape();
            }
            if (lastBox != null) {
                String what = PlacementView.INSTANCE.tool() == Tool.CROP ? "Showing" : "Selection";
                SlopmaticaActions.actionBar(Component.literal(String.format("%s %d x %d x %d", what, lastBox.getXSpan(), lastBox.getYSpan(), lastBox.getZSpan()))
                    .withColor(SlopmaticaActions.ACCENT));
            }
            MaterialPanels.INSTANCE.invalidate();
            rebuildWidgets(); // "Reset crop" may have become available.
            return true;
        }
        if (dragOffset != null) {
            dragOffset = null;
            if (p != null) {
                SlopmaticaActions.actionBar(Component.literal(p.name() + " at " + p.transform().origin().toShortString()).withColor(SlopmaticaActions.ACCENT));
            }
            MaterialPanels.INSTANCE.invalidate();
            rebuildWidgets(); // Undo is available now.
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (MaterialPanels.INSTANCE.mouseScrolled(minecraft, x, y, scrollY, width, height)) {
            return true;
        }
        if (PlacementView.INSTANCE.editingSelection() && Selection.INSTANCE.frame() != null) {
            int by = (int) Math.signum(scrollY) * (minecraft.hasShiftDown() ? 5 : 1);
            if (originDrag) {
                originDy += by; // Up and down while sliding it about.
                if (reshapeFrame != null && reshapeParts != null && lastBox != null) {
                    BoundingBox moved = new BoundingBox(lastBox.minX(), reshapeFrame.minY() + originDy, lastBox.minZ(),
                        lastBox.maxX(), reshapeFrame.maxY() + originDy, lastBox.maxZ());
                    lastBox = moved;
                    Selection.INSTANCE.reshape(reshapeParts, reshapeFrame, moved);
                }
            } else if (by != 0) {
                UndoStack.INSTANCE.saveSelection();
                Selection.INSTANCE.move(0, by, 0);
            }
            return true;
        }
        PlacementView.INSTANCE.scroll(minecraft, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        PlacementView view = PlacementView.INSTANCE;
        if (event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
            cancelDrag();
            view.undo(minecraft);
            rebuildWidgets();
            return true;
        }
        if (SlopmaticaKeys.VIEW_SAVE.matches(event)) {
            minecraft.gui.setScreen(new com.sputnikboi.slopmatica.gui.SaveSelectionScreen(this));
            return true;
        }
        // Number keys pick a tool here; the hotbar doesn't matter while the cursor is free.
        int number = event.key() - InputConstants.KEY_1;
        if (number >= 0 && number < Tool.values().length) {
            pickTool(Tool.values()[number]);
            return true;
        }
        // Key mappings don't fire while a screen is open, so the view's keys are matched here.
        if (SlopmaticaKeys.PLACEMENT_VIEW.matches(event)) {
            view.exit(minecraft);
            return true;
        }
        if (SlopmaticaKeys.VIEW_TOOL.matches(event)) {
            pickTool(view.tool().next());
            return true;
        }
        if (SlopmaticaKeys.VIEW_ROTATE.matches(event)) {
            view.rotate(minecraft, Rotation.CLOCKWISE_90);
            return true;
        }
        if (SlopmaticaKeys.VIEW_MIRROR.matches(event)) {
            view.mirror(minecraft);
            return true;
        }
        if (SlopmaticaKeys.VIEW_PASTE.matches(event)) {
            view.build(minecraft);
            return true;
        }
        for (int i = 0; i < SlopmaticaKeys.VIEW_NUDGES.length; i++) {
            if (SlopmaticaKeys.VIEW_NUDGES[i].matches(event)) {
                view.nudge(minecraft, i);
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
