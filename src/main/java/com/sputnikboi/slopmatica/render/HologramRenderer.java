package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the hologram's section meshes: decides which world sections need geometry, rebuilds them
 * off-thread when the world or a placement changes, and draws them after vanilla's main pass.
 * Everything here except the worker tasks runs on the render thread.
 */
public final class HologramRenderer {
    public static final HologramRenderer INSTANCE = new HologramRenderer();
    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");

    private static final int MAX_SUBMITS_PER_FRAME = 8;
    private static final int MAX_UPLOADS_PER_FRAME = 24;
    /** 100% outline thickness, relative to vanilla's block outline (at 6 blocks away). */
    private static final float BASE_LINE_WIDTH = 0.5F;
    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    private static final double BLOCK_ENTITY_RANGE = 64;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<SectionMeshBuilder.Output> finished = new ConcurrentLinkedQueue<>();
    /** Mesh-building threads: half the cores, at most 4. {@code -Dslopmatica.workers=1} simulates a weak CPU. */
    private final int workerCount = Integer.getInteger("slopmatica.workers", Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4));
    private final ExecutorService workers = Executors.newFixedThreadPool(workerCount, r -> {
        Thread t = new Thread(r, "Slopmatica mesh builder");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger inFlight = new AtomicInteger();

    private boolean layoutDirty = true;
    /** Set when a layout refresh starts; cleared (and logged) once every section has caught up. */
    private long rebuildStartedNanos;
    /** How long the last full rebuild took, for the developer overlay; -1 before the first. */
    private long lastRebuildMs = -1;
    private BlockStateModelSet lastModels;
    private boolean hidden;
    private boolean renderFailureLogged;
    private final SpecialGhostRenderer specialGhosts = new SpecialGhostRenderer();

    private static final class Section {
        final long key;
        SectionMesh mesh;
        /** Bumped on every change; the mesh is current when builtVersion catches up. */
        int version;
        int builtVersion = -1;
        boolean building;
        List<SectionMeshBuilder.SpecialGhost> specials = List.of();

        Section(long key) {
            this.key = key;
        }

        void markDirty() {
            version++;
        }

        void dropMesh() {
            if (mesh != null) {
                mesh.close();
                mesh = null;
            }
            specials = List.of();
            builtVersion = -1;
        }
    }

    public boolean hidden() {
        return hidden;
    }

    /** Hides every placement without unloading anything. */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    /** An option that is baked into the meshes (outline width or which statuses get outlines) changed. */
    public void rebuildAll() {
        sections.values().forEach(Section::markDirty);
    }

    /** Placements were added, removed, moved or hidden. */
    /** Whether any of a section can be on screen; sections behind you cost nothing to skip. */
    private static boolean inView(Frustum frustum, long key) {
        double x = SectionPos.sectionToBlockCoord(SectionPos.x(key)), y = SectionPos.sectionToBlockCoord(SectionPos.y(key)), z = SectionPos.sectionToBlockCoord(SectionPos.z(key));
        // Grown a little: outlines sit just outside their blocks.
        return frustum.isVisible(new AABB(x - 0.5, y - 0.5, z - 0.5, x + 16.5, y + 16.5, z + 16.5));
    }

    /** Block-entity ghosts (chests, signs, banners) only within vanilla's block entity range. */
    private static boolean withinBlockEntityRange(long key, Vec3 camera) {
        double cx = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8, cy = SectionPos.sectionToBlockCoord(SectionPos.y(key)) + 8, cz = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8;
        double reach = BLOCK_ENTITY_RANGE + 14; // Section half-diagonal.
        return camera.distanceToSqr(cx, cy, cz) < reach * reach;
    }

    public int sectionCount() {
        return sections.size();
    }

    /** One line for the developer overlay: sections, builds in flight, last rebuild time, threads. */
    public String stats() {
        long building = rebuildStartedNanos != 0 ? (System.nanoTime() - rebuildStartedNanos) / 1_000_000 : -1;
        return String.format(java.util.Locale.ROOT, "Hologram: %d sections, %d building, last rebuild %s, %d threads",
            sections.size(), inFlight.get(), building >= 0 ? "running " + building + " ms" : lastRebuildMs >= 0 ? lastRebuildMs + " ms" : "-", workerCount);
    }

    public void onPlacementsChanged() {
        layoutDirty = true;
    }

    public void onBlockChanged(BlockPos pos) {
        // Outlines on a section's border depend on the neighbouring block's status, so a change on
        // the edge also dirties the section next door.
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int sx = SectionPos.blockToSectionCoord(x), sy = SectionPos.blockToSectionCoord(y), sz = SectionPos.blockToSectionCoord(z);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        for (int dy = ly == 0 ? -1 : 0; dy <= (ly == 15 ? 1 : 0); dy++) {
            for (int dz = lz == 0 ? -1 : 0; dz <= (lz == 15 ? 1 : 0); dz++) {
                for (int dx = lx == 0 ? -1 : 0; dx <= (lx == 15 ? 1 : 0); dx++) {
                    Section section = sections.get(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                    if (section != null) {
                        section.markDirty();
                    }
                }
            }
        }
    }

    public void onChunkLoaded(int chunkX, int chunkZ) {
        forColumns(chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1, Section::markDirty);
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        forColumns(chunkX, chunkZ, chunkX, chunkZ, Section::dropMesh);
    }

    /** New level or disconnect: every mesh describes a world that is gone. */
    public void reset() {
        for (Section section : sections.values()) {
            section.dropMesh();
        }
        sections.clear();
        SectionMeshBuilder.Output output;
        while ((output = finished.poll()) != null) {
            output.close();
        }
        layoutDirty = true;
    }

    private void forColumns(int minX, int minZ, int maxX, int maxZ, Consumer<Section> action) {
        for (Section section : sections.values()) {
            int x = SectionPos.x(section.key), z = SectionPos.z(section.key);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                action.accept(section);
            }
        }
    }

    /** Hooked to the end of vanilla's main level pass. */
    public void render(LevelRenderContext context) {
        try {
            renderUnchecked(context);
        } catch (RuntimeException e) {
            // A hologram bug must not take the game down with it; log it once and keep playing.
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("Hologram rendering failed; further failures are not logged", e);
            }
        }
    }

    /** Hooked to Fabric's submit collection: block-entity ghosts go through vanilla's feature renderers. */
    public void collectSubmits(LevelRenderContext context) {
        ClientLevel level = Minecraft.getInstance().level;
        if (hidden || level == null || !SlopmaticaConfig.get().showGhosts) {
            return;
        }
        try {
            Vec3 camera = context.levelState().cameraRenderState.pos;
            List<List<SectionMeshBuilder.SpecialGhost>> nearby = new ArrayList<>();
            Frustum frustum = Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
            for (Section section : sections.values()) {
                if (!section.specials.isEmpty() && section.mesh != null && level.hasChunk(SectionPos.x(section.key), SectionPos.z(section.key))
                    && withinBlockEntityRange(section.key, camera) && inView(frustum, section.key)) {
                    nearby.add(section.specials);
                }
            }
            specialGhosts.submit(context.submitNodeCollector(), context.poseStack(), camera, context.levelState().cameraRenderState,
                Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false), nearby, SlopmaticaConfig.get().effectiveGhostOpacity());
        } catch (RuntimeException e) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("Hologram rendering failed; further failures are not logged", e);
            }
        }
    }

    private void renderUnchecked(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        BlockStateModelSet models = mc.getModelManager().getBlockStateModelSet();
        if (models != lastModels) {
            // Resource reload: baked quads point into the old atlas.
            lastModels = models;
            sections.values().forEach(Section::markDirty);
        }
        if (layoutDirty) {
            layoutDirty = false;
            refreshLayout(level);
        }

        Vec3 camera = context.levelState().cameraRenderState.pos;
        int maxDistance = mc.options.getEffectiveRenderDistance() + 1;
        uploadFinished();
        scheduleBuilds(mc, level, camera, maxDistance);
        if (rebuildStartedNanos != 0 && inFlight.get() == 0 && finished.isEmpty() && !hasPendingWork(level, camera, maxDistance)) {
            lastRebuildMs = (System.nanoTime() - rebuildStartedNanos) / 1_000_000;
            LOGGER.info("Hologram rebuilt: {} sections in {} ms", sections.size(), lastRebuildMs);
            rebuildStartedNanos = 0;
        }
        if (!hidden) {
            draw(mc, level, camera, maxDistance);
        }
    }

    private void refreshLayout(ClientLevel level) {
        Long2ObjectOpenHashMap<Section> wanted = new Long2ObjectOpenHashMap<>();
        int minSectionY = level.getMinSectionY(), maxSectionY = level.getMaxSectionY();
        for (Placement placement : PlacementManager.INSTANCE.placements()) {
            if (!placement.visible() || !placement.inDimension(level)) {
                continue;
            }
            BoundingBox box = placement.transform().worldBounds();
            int y0 = Math.max(SectionPos.blockToSectionCoord(box.minY()), minSectionY);
            int y1 = Math.min(SectionPos.blockToSectionCoord(box.maxY()), maxSectionY);
            for (int sx = SectionPos.blockToSectionCoord(box.minX()); sx <= SectionPos.blockToSectionCoord(box.maxX()); sx++) {
                for (int sz = SectionPos.blockToSectionCoord(box.minZ()); sz <= SectionPos.blockToSectionCoord(box.maxZ()); sz++) {
                    for (int sy = y0; sy <= y1; sy++) {
                        long key = SectionPos.asLong(sx, sy, sz);
                        if (!wanted.containsKey(key)) {
                            Section existing = sections.remove(key);
                            wanted.put(key, existing != null ? existing : new Section(key));
                        }
                    }
                }
            }
        }
        // Whatever is left is no longer covered by any placement.
        sections.values().forEach(Section::dropMesh);
        sections.clear();
        sections.putAll(wanted);
        // Any placement may have moved, so every remaining section needs a rebuild. The old meshes
        // stay on screen until their replacements arrive, so nothing flickers.
        sections.values().forEach(Section::markDirty);
        rebuildStartedNanos = System.nanoTime();
    }

    private void uploadFinished() {
        for (int i = 0; i < MAX_UPLOADS_PER_FRAME; i++) {
            SectionMeshBuilder.Output output = finished.poll();
            if (output == null) {
                return;
            }
            try (output) {
                Section section = sections.get(output.sectionKey());
                if (section == null) {
                    continue;
                }
                section.building = false;
                if (section.mesh != null) {
                    section.mesh.close();
                }
                section.mesh = SectionMesh.upload(output);
                section.specials = output.specials();
                section.builtVersion = output.version();
            }
        }
    }

    private void scheduleBuilds(Minecraft mc, ClientLevel level, Vec3 camera, int maxDistance) {
        int capacity = workerCount * 2 - inFlight.get();
        if (capacity <= 0) {
            return;
        }
        int camX = SectionPos.posToSectionCoord(camera.x), camY = SectionPos.posToSectionCoord(camera.y), camZ = SectionPos.posToSectionCoord(camera.z);
        List<Section> dirty = new ArrayList<>();
        for (Section section : sections.values()) {
            if (section.building || section.builtVersion == section.version) {
                continue;
            }
            int x = SectionPos.x(section.key), z = SectionPos.z(section.key);
            if (Math.abs(x - camX) > maxDistance || Math.abs(z - camZ) > maxDistance || !level.hasChunk(x, z)) {
                continue;
            }
            dirty.add(section);
        }
        if (dirty.isEmpty()) {
            return;
        }
        dirty.sort(Comparator.comparingLong(s -> distanceSq(s.key, camX, camY, camZ)));

        List<Placement.Transform> transforms = PlacementManager.INSTANCE.placements().stream()
            .filter(p -> p.visible() && p.inDimension(level))
            .map(Placement::transform)
            .toList();
        SlopmaticaConfig config = SlopmaticaConfig.get();
        boolean[] outlined = new boolean[BlockStatus.values().length];
        for (BlockStatus status : BlockStatus.values()) {
            outlined[status.ordinal()] = config.showsOutline(status);
        }
        int submits = Math.min(Math.min(capacity, MAX_SUBMITS_PER_FRAME), dirty.size());
        for (int i = 0; i < submits; i++) {
            Section section = dirty.get(i);
            SectionPos pos = SectionPos.of(section.key);
            SectionMeshBuilder.Input input = new SectionMeshBuilder.Input(
                section.key, section.version, pos, transforms, WorldSnapshot.capture(level, pos), level,
                mc.getModelManager().getBlockStateModelSet(), mc.getModelManager().getFluidStateModelSet(), mc.getBlockColors(),
                mc.getWindow().getAppropriateLineWidth() * BASE_LINE_WIDTH * config.outlineWidth, outlined
            );
            section.building = true;
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    finished.add(SectionMeshBuilder.build(input));
                } catch (Throwable t) {
                    LOGGER.error("Failed to build hologram section {}", pos, t);
                    finished.add(SectionMeshBuilder.Output.empty(input.sectionKey(), input.version()));
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    private boolean hasPendingWork(ClientLevel level, Vec3 camera, int maxDistance) {
        int camX = SectionPos.posToSectionCoord(camera.x), camZ = SectionPos.posToSectionCoord(camera.z);
        for (Section section : sections.values()) {
            int x = SectionPos.x(section.key), z = SectionPos.z(section.key);
            if ((section.building || section.builtVersion != section.version)
                && Math.abs(x - camX) <= maxDistance && Math.abs(z - camZ) <= maxDistance && level.hasChunk(x, z)) {
                return true;
            }
        }
        return false;
    }

    private static long distanceSq(long key, int x, int y, int z) {
        long dx = SectionPos.x(key) - x, dy = SectionPos.y(key) - y, dz = SectionPos.z(key) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void draw(Minecraft mc, ClientLevel level, Vec3 camera, int maxDistance) {
        int camX = SectionPos.posToSectionCoord(camera.x), camZ = SectionPos.posToSectionCoord(camera.z);
        Frustum frustum = mc.gameRenderer.mainCamera().getCullFrustum();
        List<Section> visible = new ArrayList<>();
        int maxQuadIndices = 0, maxLineIndices = 0;
        for (Section section : sections.values()) {
            SectionMesh mesh = section.mesh;
            if (mesh == null) {
                continue;
            }
            int x = SectionPos.x(section.key), z = SectionPos.z(section.key);
            if (Math.abs(x - camX) > maxDistance || Math.abs(z - camZ) > maxDistance || !level.hasChunk(x, z) || !inView(frustum, section.key)) {
                continue;
            }
            visible.add(section);
            maxQuadIndices = Math.max(maxQuadIndices, indexCount(mesh.ghosts));
            maxLineIndices = Math.max(maxLineIndices, Math.max(indexCount(mesh.lines), indexCount(mesh.alerts)));
        }
        if (visible.isEmpty()) {
            return;
        }
        // Back to front, so overlapping translucent ghosts in different sections blend in order.
        int camY = SectionPos.posToSectionCoord(camera.y);
        visible.sort(Comparator.comparingLong((Section s) -> distanceSq(s.key, camX, camY, camZ)).reversed());

        // Uniforms and index buffers are written before the pass opens; nothing may allocate inside it.
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        SlopmaticaConfig config = SlopmaticaConfig.get();
        Vector4f ghostColor = new Vector4f(1.0F, 1.0F, 1.0F, config.effectiveGhostOpacity());
        // The line shader reads its settings from TextureMat[3] (lines have no texture): xy is the
        // fade range, where an end at or before the start means "never fade"; z = 1 keeps lines
        // solid when they shrink below a pixel. Wrong and variant outlines use both, so they stay
        // findable from any distance.
        Matrix4f fadeRange = new Matrix4f().m30(config.fadeOutlines ? config.fadeStartBlocks() : 0).m31(config.fadeOutlines ? config.fadeEndBlocks() : 0);
        Matrix4f alertStyle = new Matrix4f().m32(1);
        DynamicGpuData.Transform[] transforms = new DynamicGpuData.Transform[visible.size() * 3];
        for (int i = 0; i < visible.size(); i++) {
            long key = visible.get(i).key;
            float ox = (float) (SectionPos.sectionToBlockCoord(SectionPos.x(key)) - camera.x);
            float oy = (float) (SectionPos.sectionToBlockCoord(SectionPos.y(key)) - camera.y);
            float oz = (float) (SectionPos.sectionToBlockCoord(SectionPos.z(key)) - camera.z);
            // The block shader adds ModelOffset itself (and uses it for fog); the line shaders
            // ignore it, so outlines carry their offset in the model-view matrix instead.
            Matrix4f lineView = new Matrix4f(modelView).translate(ox, oy, oz);
            transforms[i * 3] = new DynamicGpuData.Transform(modelView, ghostColor, new Vector3f(ox, oy, oz), IDENTITY);
            transforms[i * 3 + 1] = new DynamicGpuData.Transform(lineView, WHITE, NO_OFFSET, fadeRange);
            transforms[i * 3 + 2] = new DynamicGpuData.Transform(lineView, WHITE, NO_OFFSET, alertStyle);
        }
        GpuBufferSlice[] uniforms = RenderSystem.getDynamicUniforms().writeTransforms(transforms);

        RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        RenderSystem.AutoStorageIndexBuffer lineIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
        GpuBuffer quadIndexBuffer = maxQuadIndices > 0 ? quadIndices.getBuffer(maxQuadIndices) : null;
        GpuBuffer lineIndexBuffer = maxLineIndices > 0 ? lineIndices.getBuffer(maxLineIndices) : null;

        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Slopmatica hologram", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(pass);

            if (lineIndexBuffer != null && config.showOutlines) {
                pass.setIndexBuffer(lineIndexBuffer, lineIndices.type());
                pass.setPipeline(RenderSystem.getCompiledPipeline(SlopmaticaPipelines.FADING_LINES));
                for (int i = 0; i < visible.size(); i++) {
                    drawPart(pass, visible.get(i).mesh.lines, uniforms[i * 3 + 1]);
                }
                // Wrong and variant blocks stay visible from any distance: those are the ones to find.
                // Drawn last so they win the edges they share with a neighbour's blue or green box.
                for (int i = 0; i < visible.size(); i++) {
                    drawPart(pass, visible.get(i).mesh.alerts, uniforms[i * 3 + 2]);
                }
            }

            if (quadIndexBuffer != null && config.showGhosts) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRANSLUCENT_BLOCK));
                pass.setUniform(
                    "Sampler0",
                    mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                    RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, true)
                );
                pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setIndexBuffer(quadIndexBuffer, quadIndices.type());
                for (int i = 0; i < visible.size(); i++) {
                    drawPart(pass, visible.get(i).mesh.ghosts, uniforms[i * 3]);
                }
            }
        }
    }

    private static int indexCount(SectionMesh.Part part) {
        return part == null ? 0 : part.indexCount();
    }

    private static void drawPart(RenderPass pass, SectionMesh.Part part, GpuBufferSlice transform) {
        if (part != null) {
            pass.setUniform("DynamicTransforms", transform);
            pass.setVertexBuffer(0, part.vertices().slice());
            pass.drawIndexed(part.indexCount(), 1, 0, 0, 0);
        }
    }
}
