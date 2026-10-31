#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla's rendertype_lines fragment shader, faded out with distance and by sub-pixel coverage. The fade range rides in
// TextureMat[3].xy (lines have no texture); an end at or before the start disables the fade.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in float viewDistance;
layout(location = 4) in float coverage;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    color.a *= coverage;
    float fadeStart = TextureMat[3].x;
    float fadeEnd = TextureMat[3].y;
    if (fadeEnd > fadeStart) {
        color.a *= 1.0 - smoothstep(fadeStart, fadeEnd, viewDistance);
    }
    if (color.a < 0.01) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
