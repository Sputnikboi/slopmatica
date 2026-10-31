package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Ghosts for blocks whose looks come from a block-entity renderer rather than a baked model
 * (chests, shulker boxes, banners, heads, pots, bells, signs...). These cannot live in the cached
 * section mesh, so they are re-submitted every frame. The block itself comes through vanilla's
 * block-model resolver (the path an enderman uses for the block it carries); when the schematic has
 * block entity data, a detached block entity built from it is drawn by its vanilla renderer on top,
 * which adds what only that data knows: sign text, banner patterns, player-head skins, pot sherds.
 */
final class SpecialGhostRenderer {
    /** Beyond this, per-frame submits cost more than the detail is worth; the outline still shows. */
    private static final double MAX_DISTANCE = 64.0;
    private static final BlockDisplayContext DISPLAY_CONTEXT = BlockDisplayContext.create();

    /** A block entity built from schematic data, kept while the ghost it belongs to stays the same. */
    private record Detached(BlockState state, CompoundTag data, BlockEntity entity) {
    }

    private final BlockModelRenderState renderState = new BlockModelRenderState();
    private final Long2ObjectOpenHashMap<Detached> detached = new Long2ObjectOpenHashMap<>();
    private @Nullable ClientLevel detachedLevel;
    private BlockModelResolver resolver;

    void submit(SubmitNodeCollector collector, PoseStack poseStack, Vec3 camera, CameraRenderState cameraState, float partialTicks,
                List<List<SectionMeshBuilder.SpecialGhost>> sections, float opacity) {
        if (sections.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (resolver == null) {
            resolver = new BlockModelResolver(mc.getModelManager());
        }
        if (detachedLevel != mc.level) {
            detached.clear();
            detachedLevel = mc.level;
        }
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        GhostSubmitCollector ghosts = new GhostSubmitCollector(collector, opacity);
        double maxDistanceSq = MAX_DISTANCE * MAX_DISTANCE;
        for (List<SectionMeshBuilder.SpecialGhost> specials : sections) {
            for (SectionMeshBuilder.SpecialGhost ghost : specials) {
                double dx = ghost.pos().getX() - camera.x, dy = ghost.pos().getY() - camera.y, dz = ghost.pos().getZ() - camera.z;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(dx, dy, dz);
                renderState.clear();
                resolver.update(renderState, ghost.state(), DISPLAY_CONTEXT);
                renderState.submit(poseStack, ghosts, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                if (ghost.data() != null) {
                    BlockEntity entity = detachedFor(ghost);
                    if (entity != null) {
                        BlockEntityRenderState state = dispatcher.tryExtractRenderState(entity, partialTicks, null, false);
                        if (state != null) {
                            state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
                            dispatcher.submit(state, poseStack, ghosts, cameraState);
                        }
                    }
                }
                poseStack.popPose();
            }
        }
    }

    private @Nullable BlockEntity detachedFor(SectionMeshBuilder.SpecialGhost ghost) {
        long key = ghost.pos().asLong();
        Detached cached = detached.get(key);
        if (cached != null && cached.state() == ghost.state() && cached.data() == ghost.data()) {
            return cached.entity();
        }
        if (detachedLevel == null) {
            return null;
        }
        CompoundTag tag = ghost.data().copy();
        tag.putInt("x", ghost.pos().getX());
        tag.putInt("y", ghost.pos().getY());
        tag.putInt("z", ghost.pos().getZ());
        BlockEntity entity;
        try {
            entity = BlockEntity.loadStatic(ghost.pos(), ghost.state(), tag, detachedLevel.registryAccess());
        } catch (RuntimeException e) {
            entity = null;
        }
        if (entity == null) {
            return null;
        }
        // Never added to the level: the level is only there for renderers that look things up.
        entity.setLevel(detachedLevel);
        detached.put(key, new Detached(ghost.state(), ghost.data(), entity));
        return entity;
    }

    /** New level or a rebuild: forget detached block entities. */
    void clear() {
        detached.clear();
    }
}
