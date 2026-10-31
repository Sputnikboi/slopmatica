package com.sputnikboi.slopmatica.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

final class SlopmaticaPipelines {
    /**
     * Vanilla lines with a per-pixel distance fade. Up close every block gets an outline; at range
     * thousands of one-pixel lines would merge into a solid sheet and hide the ghosts. The fade
     * range is a per-draw uniform (see fading_lines.fsh) so the options screen can change it live.
     */
    static final RenderPipeline FADING_LINES = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
        .withLocation(id("pipeline/fading_lines"))
        .withVertexShader(id("core/fading_lines"))
        .withFragmentShader(id("core/fading_lines"))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .build();

    private SlopmaticaPipelines() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("slopmatica", path);
    }
}
