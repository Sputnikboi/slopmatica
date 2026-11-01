package com.sputnikboi.slopmatica.render;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * An orange box around every shown build that had to leave something out because this game
 * doesn't have it (a mod that isn't installed), so a half-there build isn't mistaken for the real thing.
 */
public final class IncompleteBoxes {
    private IncompleteBoxes() {
    }

    public static void submit(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || HologramRenderer.INSTANCE.hidden()) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth() * 2;
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (!p.visible() || !p.inDimension(mc.level) || !p.schematic().incomplete()) {
                continue;
            }
            BoundingBox b = p.transform().worldBounds();
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lines(), (pose, buffer) ->
                Selection.box(buffer, pose, cam, b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1, 0.006, SlopmaticaActions.WARNING, width));
        }
    }
}
