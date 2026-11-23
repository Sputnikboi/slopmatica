package com.sputnikboi.slopmatica.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class SlopmaticaPipelines {
    /**
     * Vanilla lines with a world-space width and a per-pixel distance fade. Vanilla lines are a
     * fixed number of pixels wide, so they look ever thicker next to blocks that shrink with
     * distance; these thin out with their block instead. Up close every block gets an outline; at
     * range thousands of lines would still merge into a solid sheet and hide the ghosts, hence the
     * fade. Its range is a per-draw uniform (see fading_lines.fsh) so the options screen can
     * change it live.
     */
    static final RenderPipeline FADING_LINES = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(id("pipeline/fading_lines"))
        .withVertexShader(id("core/fading_lines"))
        .withFragmentShader(id("core/fading_lines"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        // Pulled a little towards the camera (as vanilla's block outline is), so outlines lying on
        // real blocks' edges don't flicker against them.
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 1.0F, 1.0F))
        .build();

    private SlopmaticaPipelines() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("slopmatica", path);
    }
}
