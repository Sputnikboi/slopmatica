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
 * blur. On the left: the tool dropdown (move, select, modify selection, crop; keys 1-4), layer
 * view and the list of builds (click a name to show or hide it, ☰ for its material panel, → to
 * work on it). Bottom right: rotate, mirror, nudge (Shift-click: 5 blocks), reset and paste.
 * Right click or Esc goes back to flying.
 */
public class PlacementViewScreen extends Screen {
    private static final int B = 20;
    private static final int GAP = 2;
    private static final int LEFT_W = 124;
    private static final int TOP = 62;

    private static boolean toolsOpen;

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

    public PlacementViewScreen() {
        super(Component.translatable("slopmatica.view.title"));
    }

    @Override
    protected void init() {
        PlacementView view = PlacementView.INSTANCE;
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

    /** The left column: tools, tool actions, layers, builds. Rebuilt whenever one of them changes. */
    private void initLeft(PlacementView view) {
        int x = 6, y = TOP;
        Placement onBuild = view.placement();
        if (onBuild != null) {
            Placement current = onBuild;
            List<Recommendations.Warning> warnings = Recommendations.of(minecraft, current);
            y += warnings.size() * 11;
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
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.dropdown", tool.label(), toolsOpen ? "▴" : "▾"), b -> {
            toolsOpen = !toolsOpen;
            rebuildWidgets();
        }).bounds(x, y, LEFT_W, B).tooltip(Tooltip.create(tool.tooltip())).build());
        y += B + GAP;
        if (toolsOpen) {
            for (Tool t : Tool.values()) {
                Component label = Component.literal((t.ordinal() + 1) + "  ").append(t.label());
                addRenderableWidget(Button.builder(t == tool ? label.copy().withStyle(ChatFormatting.YELLOW) : label, b -> pickTool(t))
                    .bounds(x + 8, y, LEFT_W - 8, B).tooltip(Tooltip.create(t.tooltip())).build());
                y += B;
            }
            y += GAP;
        }

        // Tool actions.
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
        } else if (tool == Tool.SELECT || tool == Tool.MODIFY) {
            int half = (LEFT_W - GAP) / 2;
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.selection.copy"), b -> Clipboard.INSTANCE.copy(minecraft))
                .bounds(x, y, half, B).tooltip(Tooltip.create(Component.translatable("slopmatica.tool.selection.copy.tooltip"))).build());
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.tool.selection.clear"), b -> Selection.INSTANCE.clear())
                .bounds(x + half + GAP, y, half, B).build());
            y += B + GAP;
        }

        // Undo, for every tool.
        int third = (LEFT_W - 2 * GAP) / 2;
        Button undo = addRenderableWidget(Button.builder(Component.translatable("slopmatica.view.undo"), b -> view.undo(minecraft))
            .bounds(x, y, third, B).tooltip(Tooltip.create(Component.translatable("slopmatica.view.undo.tooltip"))).build());
        undo.active = !UndoStack.INSTANCE.isEmpty();
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.view.fit"), b -> {
            view.fitSelection(minecraft);
            rebuildWidgets();
        }).bounds(x + third + GAP, y, LEFT_W - third - GAP, B).tooltip(Tooltip.create(Component.translatable("slopmatica.view.fit.tooltip"))).build())
            .active = Selection.INSTANCE.box() != null;
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

    /** Right click during a drag: everything back to how it was when the drag started. */
    private boolean cancelDrag() {
        Placement p = PlacementView.INSTANCE.placement();
        if (dragOffset != null && p != null && dragStart != null) {
            dragOffset = null;
            p.setOrigin(dragStart);
            PlacementManager.INSTANCE.changed();
        } else if (boxDrag != null) {
            boxDrag = null;
            if (PlacementView.INSTANCE.tool() == Tool.MODIFY && dragSelection != null) {
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
        toolsOpen = false;
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
        Placement p = PlacementView.INSTANCE.placement();
        if (p == null) {
            PlacementView.drawPanel(graphics, minecraft, List.of(
                "Build view: no build selected",
                "Select: click two blocks   Modify: drag a face",
                PlacementView.key(SlopmaticaKeys.VIEW_SAVE) + ": save the selection   Ctrl+Z: undo",
                "Right click or Esc: back to flying   " + PlacementView.key(SlopmaticaKeys.PLACEMENT_VIEW) + ": done"
            ), 4, 4);
            graphics.text(font, Component.translatable("slopmatica.builds.title").withStyle(ChatFormatting.BOLD), 6, buildsTop - 11, 0xFFFFFFFF);
            MaterialPanels.INSTANCE.draw(graphics, minecraft, mouseX, mouseY, true);
            return;
        }
        String hint = switch (PlacementView.INSTANCE.tool()) {
            case MOVE -> "Drag the build to move it   Scroll: up/down";
            case SELECT -> "Click blocks to set the two corners";
            case MODIFY -> "Drag a face of the selection to resize it";
            case CROP -> "Drag a face of the orange box to crop the build";
        };
        List<String> lines = new java.util.ArrayList<>();
        lines.add("Build view: " + p.name() + "  (" + p.worldOriginPoint().toShortString() + ")");
        List<Recommendations.Warning> warnings = Recommendations.of(minecraft, p);
        warnings.forEach(w -> lines.add("⚠ " + w.text().getString()));
        lines.add(hint);
        lines.add("Shift: buttons and keys move 5 blocks");
        lines.add("Right click or Esc: back to flying   " + PlacementView.key(SlopmaticaKeys.PLACEMENT_VIEW) + ": done");
        PlacementView.drawPanel(graphics, minecraft, lines, 4, 4, warnings.size());
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
                BlockPos block = view.pointedBlock(minecraft, from, dir);
                if (block == null) {
                    return false;
                }
                UndoStack.INSTANCE.saveSelection();
                Selection.INSTANCE.setCorner(block, dim);
                rebuildWidgets(); // Fit and Undo may have become available.
            }
            case MODIFY -> {
                BoundingBox box = dim.equals(Selection.INSTANCE.dimension()) ? Selection.INSTANCE.box() : null;
                if (box == null) {
                    SlopmaticaActions.actionBar(Component.translatable("slopmatica.tool.modify.none").withColor(SlopmaticaActions.ERROR));
                    return true;
                }
                boxDrag = BoxDrag.grab(box, from, dir);
                lastBox = box;
                if (boxDrag != null) {
                    UndoStack.INSTANCE.saveSelection();
                    dragSelection = Selection.INSTANCE.state();
                }
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
        if (minecraft.level == null || (p == null && boxDrag == null)) {
            return super.mouseDragged(event, dx, dy);
        }
        Vec3 from = view.eye();
        Vec3 dir = view.rayThrough(minecraft, event.x(), event.y(), width, height);
        if (boxDrag != null) {
            BoundingBox box = boxDrag.follow(from, dir);
            if (!box.equals(lastBox)) {
                lastBox = box;
                if (view.tool() == Tool.MODIFY) {
                    Selection.INSTANCE.set(box, minecraft.level.dimension().identifier().toString());
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
        if (Math.abs(dir.y) < 1.0E-4) {
            return true;
        }
        double t = (dragPlaneY - from.y) / dir.y;
        if (t <= 0 || t > 1024) {
            return true; // Looking away from the plane (at the sky, say): nowhere to put it.
        }
        Vec3 point = from.add(dir.scale(t));
        BlockPos grabbed = BlockPos.containing(point.x, dragPlaneY, point.z);
        BlockPos origin = grabbed.offset(dragOffset);
        BlockPos current = p.transform().origin();
        if (origin.getX() != current.getX() || origin.getZ() != current.getZ()) {
            p.setOrigin(new BlockPos(origin.getX(), current.getY(), origin.getZ()));
            PlacementManager.INSTANCE.changed();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (MaterialPanels.INSTANCE.mouseReleased()) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseReleased(event);
        }
        Placement p = PlacementView.INSTANCE.placement();
        if (boxDrag != null) {
            boxDrag = null;
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
