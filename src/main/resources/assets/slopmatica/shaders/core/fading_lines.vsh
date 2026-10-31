#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla's rendertype_lines vertex shader, with two changes: it passes the view distance on for
// a per-pixel fade, and lines have a fixed thickness in the world rather than on screen, so a
// distant outline is as thin next to its block as a near one. LineWidth is the on-screen width at
// REFERENCE_DEPTH blocks away.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 Normal;
layout(location = 3) in float LineWidth;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out float viewDistance;
layout(location = 4) out float coverage;

const float REFERENCE_DEPTH = 6.0;
// Up close a world-space width would balloon; past this it stops growing.
const float MAX_WIDTH_SCALE = 4.0;

const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
const mat4 VIEW_SCALE = mat4(
    VIEW_SHRINK, 0.0, 0.0, 0.0,
    0.0, VIEW_SHRINK, 0.0, 0.0,
    0.0, 0.0, VIEW_SHRINK, 0.0,
    0.0, 0.0, 0.0, 1.0
);

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    vec4 linePosStart = ProjMat * VIEW_SCALE * viewPos;
    vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);
    // Narrower than a pixel the line can't get thinner, so it gets fainter instead (TextureMat[3].z
    // set skips that: wrong and variant outlines stay solid at any range).
    float width = LineWidth * min(REFERENCE_DEPTH / max(linePosStart.w, 0.05), MAX_WIDTH_SCALE);
    coverage = TextureMat[3].z > 0.5 ? 1.0 : clamp(width, 0.0, 1.0);
    width = max(width, 1.0);
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * width / ScreenSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    if (gl_VertexIndex % 2 == 0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    sphericalVertexDistance = fog_spherical_distance(viewPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPos.xyz);
    vertexColor = Color;
    viewDistance = length(viewPos.xyz);
}
