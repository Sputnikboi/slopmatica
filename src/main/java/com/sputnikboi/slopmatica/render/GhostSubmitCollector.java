package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sputnikboi.slopmatica.mixin.RenderSetupAccessor;
import com.sputnikboi.slopmatica.mixin.RenderTypeAccessor;
import com.sputnikboi.slopmatica.mixin.TextureBindingAccessor;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Sits between a block-entity renderer and the real collector and turns its output into a ghost:
 * each model is redirected to the translucent entity render type on the same texture, with the
 * ghost opacity multiplied in. Baked block parts are dropped, because the section mesh already
 * holds them; anything that is not a model passes through untouched.
 */
final class GhostSubmitCollector implements SubmitNodeCollector {
    private static final Map<RenderType, RenderType> TRANSLUCENT = new IdentityHashMap<>();

    private final SubmitNodeCollector root;
    private final OrderedSubmitNodeCollector target;
    private final float opacity;

    GhostSubmitCollector(SubmitNodeCollector root, float opacity) {
        this(root, root, opacity);
    }

    private GhostSubmitCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector target, float opacity) {
        this.root = root;
        this.target = target;
        this.opacity = opacity;
    }

    /** Render thread only (render types are created and cached lazily). */
    static @Nullable RenderType translucentFor(RenderType type) {
        return TRANSLUCENT.computeIfAbsent(type, t -> {
            Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) t).slopmatica$state()).slopmatica$textures().get("Sampler0");
            if (binding == null) {
                return null;
            }
            Identifier texture = ((TextureBindingAccessor) binding).slopmatica$location();
            return RenderTypes.entityTranslucent(texture, false);
        });
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new GhostSubmitCollector(root, root.order(order), opacity);
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
        int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
        RenderType ghost = translucentFor(renderType);
        if (ghost != null) {
            target.submitModel(model, state, poseStack, ghost, lightCoords, overlayCoords, ARGB.multiplyAlpha(tintedColor, opacity), uvMapping, 0);
        }
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords,
        int overlayCoords, int outlineColor) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypes, boolean translucent, List<BlockStateModelPart> parts,
        Mesh mesh, int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough, int lightCoords,
        CameraRenderState camera) {
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode,
        int lightCoords, int color, int backgroundColor, int outlineColor) {
        target.submitText(poseStack, x, y, string, dropShadow, displayMode, lightCoords, ARGB.multiplyAlpha(color, opacity), backgroundColor, outlineColor);
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode, int lightCoords) {
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
        int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor, int[] tintLayers,
        ItemQuads quads, ItemStackRenderState.FoilType foilType) {
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer customGeometryRenderer) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
