package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * Ghosts for blocks whose looks come from a block-entity renderer rather than a baked model
 * (chests, shulker boxes, banners, heads, pots, bells...). These cannot live in the cached section
 * mesh, so they are re-submitted every frame through vanilla's own block-model resolver, the same
 * path an enderman uses to draw the block it carries.
 */
final class SpecialGhostRenderer {
    /** Beyond this, per-frame submits cost more than the detail is worth; the outline still shows. */
    private static final double MAX_DISTANCE = 64.0;
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();

    private final BlockModelRenderState renderState = new BlockModelRenderState();
    private BlockModelResolver resolver;

    void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera, List<List<SectionMeshBuilder.SpecialGhost>> sections, float opacity) {
        if (sections.isEmpty()) {
            return;
        }
        if (resolver == null) {
            resolver = new BlockModelResolver(Minecraft.getInstance().getModelManager());
        }
        GhostSubmitCollector ghosts = new GhostSubmitCollector(collector, opacity);
        double maxDistanceSq = MAX_DISTANCE * MAX_DISTANCE;
        for (List<SectionMeshBuilder.SpecialGhost> specials : sections) {
            for (SectionMeshBuilder.SpecialGhost ghost : specials) {
                double dx = ghost.pos().getX() - camera.x, dy = ghost.pos().getY() - camera.y, dz = ghost.pos().getZ() - camera.z;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                    continue;
                }
                renderState.clear();
                resolver.update(renderState, ghost.state(), DISPLAY_CONTEXT);
                poseStack.pushPose();
                poseStack.translate(dx, dy, dz);
                renderState.submit(poseStack, ghosts, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
            }
        }
    }
}
