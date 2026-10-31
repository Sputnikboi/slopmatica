package com.sputnikboi.slopmatica.view;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The placement view with a free cursor (right click while flying). The world stays in view, no
 * blur. Drag the build with the left button to slide it along the ground; buttons in the bottom
 * right rotate, mirror, nudge (Shift-click: 5 blocks), reset and paste it. Right click or Esc goes
 * back to flying.
 */
public class PlacementViewScreen extends Screen {
    private static final int B = 20;
    private static final int GAP = 2;

    /** While dragging: the height of the plane the build slides on, and origin minus grabbed block. */
    private double dragPlaneY;
    private @Nullable BlockPos dragOffset;

    public PlacementViewScreen() {
        super(Component.translatable("slopmatica.view.title"));
    }

    @Override
    protected void init() {
        PlacementView view = PlacementView.INSTANCE;
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
            return;
        }
        PlacementView.drawPanel(graphics, minecraft, List.of(
            "Placement view: " + p.name() + "  (" + p.transform().origin().toShortString() + ")",
            "Drag the build to move it   Scroll: up/down",
            "Shift: buttons and keys move 5 blocks",
            "Right click or Esc: back to flying   " + PlacementView.key(SlopmaticaKeys.PLACEMENT_VIEW) + ": done"
        ), 4, 4);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == 1) {
            onClose();
            return true;
        }
        Placement p = PlacementView.INSTANCE.placement();
        if (event.button() != 0 || p == null) {
            return false;
        }
        Vec3 from = PlacementView.INSTANCE.eye();
        Vec3 dir = PlacementView.INSTANCE.rayThrough(minecraft, event.x(), event.y(), width, height);
        AABB box = AABB.of(p.transform().worldBounds());
        Vec3 hit = box.contains(from) ? from : box.clip(from, from.add(dir.scale(512))).orElse(null);
        if (hit == null) {
            return false;
        }
        dragPlaneY = hit.y;
        dragOffset = p.transform().origin().subtract(BlockPos.containing(hit.x, dragPlaneY, hit.z));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        Placement p = PlacementView.INSTANCE.placement();
        if (dragOffset == null || p == null) {
            return super.mouseDragged(event, dx, dy);
        }
        Vec3 from = PlacementView.INSTANCE.eye();
        Vec3 dir = PlacementView.INSTANCE.rayThrough(minecraft, event.x(), event.y(), width, height);
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
        if (dragOffset != null && event.button() == 0) {
            dragOffset = null;
            Placement p = PlacementView.INSTANCE.placement();
            if (p != null) {
                SlopmaticaActions.actionBar(Component.literal(p.name() + " at " + p.transform().origin().toShortString()).withColor(SlopmaticaActions.ACCENT));
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        PlacementView.INSTANCE.scroll(minecraft, scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        PlacementView view = PlacementView.INSTANCE;
        // Key mappings don't fire while a screen is open, so the view's keys are matched here.
        if (SlopmaticaKeys.PLACEMENT_VIEW.matches(event)) {
            view.exit(minecraft);
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
