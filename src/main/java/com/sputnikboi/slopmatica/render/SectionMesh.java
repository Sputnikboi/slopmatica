package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.jspecify.annotations.Nullable;

/** One section's geometry on the GPU. Indices come from the shared sequential index buffers. */
final class SectionMesh implements AutoCloseable {
    final @Nullable Part ghosts;
    final @Nullable Part lines;
    final @Nullable Part alerts;
    final @Nullable Part carried;

    record Part(GpuBuffer vertices, int indexCount) {
    }

    private SectionMesh(@Nullable Part ghosts, @Nullable Part lines, @Nullable Part alerts, @Nullable Part carried) {
        this.ghosts = ghosts;
        this.lines = lines;
        this.alerts = alerts;
        this.carried = carried;
    }

    /** Returns null when the section has nothing to draw. Render thread only. */
    static @Nullable SectionMesh upload(SectionMeshBuilder.Output output) {
        Part ghosts = upload("ghosts", output.ghosts());
        Part lines = upload("outlines", output.lines());
        Part alerts = upload("alerts", output.alerts());
        Part carried = upload("carried outlines", output.carried());
        if (ghosts == null && lines == null && alerts == null && carried == null) {
            return null;
        }
        return new SectionMesh(ghosts, lines, alerts, carried);
    }

    private static @Nullable Part upload(String what, @Nullable MeshData mesh) {
        if (mesh == null || mesh.drawState().indexCount() == 0) {
            return null;
        }
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Slopmatica " + what, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        return new Part(buffer, mesh.drawState().indexCount());
    }

    @Override
    public void close() {
        for (Part part : new Part[] {ghosts, lines, alerts, carried}) {
            if (part != null) {
                part.vertices.close();
            }
        }
    }
}
