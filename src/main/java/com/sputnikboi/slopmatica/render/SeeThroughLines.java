package com.sputnikboi.slopmatica.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Lines drawn over everything, blocks included, and blended: for outlines that should show through
 * walls (the selection, the break list). Drawn faint, with a stronger depth-tested pass on top, so
 * the parts you can see directly stand out from the hidden ones.
 */
public final class SeeThroughLines {
    public static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("slopmatica", "pipeline/lines_see_through"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .build());

    /** The same for the game's order-independent transparency, which blended world geometry goes through. */
    public static final OitPipelineSet OIT = RenderPipelines.register(
        OitPipelineSet.builder("slopmatica_lines_see_through", RenderPipeline.builder(RenderPipelines.OIT_LINES_SNIPPET)).withoutDepthTest().build());

    /** Through blocks. */
    public static final RenderType HIDDEN = RenderType.create("slopmatica_lines_see_through",
        RenderSetup.builder(PIPELINE).setOitPipelines(OIT).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup());

    /**
     * Where nothing is in front: blended, depth-tested, and pulled a little towards the camera (as
     * vanilla's block outline is) so lines lying on block faces don't flicker against them.
     */
    public static final RenderPipeline VISIBLE_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("slopmatica", "pipeline/lines_translucent_depth_bias"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0F, 1.0F))
        .build());

    public static final RenderType VISIBLE = RenderType.create("slopmatica_lines_translucent_depth_bias",
        RenderSetup.builder(VISIBLE_PIPELINE).setOitPipelines(RenderPipelines.OIT_LINES_TRANSLUCENT)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup());

    /** Solid lines (out playing), with the same pull towards the camera. */
    public static final RenderType SOLID = RenderTypes.LINES_DEPTH_BIAS;

    /** How strong each pass is drawn, as a share of the colour's own alpha. */
    public static final float HIDDEN_ALPHA = 0.35F, VISIBLE_ALPHA = 0.8F;

    private SeeThroughLines() {
    }

    /** Loads the class, registering the pipeline with the others before the game compiles them. */
    public static void init() {
    }

    public static int fade(int color, float by) {
        return Math.round((color >>> 24) * by) << 24 | (color & 0xFFFFFF);
    }
}
