package com.sputnikboi.slopmatica.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.DynamicGpuData;
import com.sputnikboi.slopmatica.schematic.Schematic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isometric 3D icons of schematic files, rendered with the real block models.
 *
 * <p>A schematic is meshed off-thread, drawn into an offscreen texture on the render thread (one
 * per tick, so opening a folder of hundreds doesn't stall a frame), read back, saved as a PNG in
 * {@code <gamedir>/slopmatica/thumbnails/} and registered as a texture for the GUI. The PNG is
 * keyed by the file's path, size and modification time, so later sessions just load it.
 */
public final class SchematicThumbnails {
    public static final SchematicThumbnails INSTANCE = new SchematicThumbnails();

    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    public static final int SIZE = 128;
    /** Bigger schematics get no icon: meshing them would take too long for a list entry. */
    private static final long MAX_VOLUME = 4L * 1024 * 1024;
    private static final int GRASS = 0xFF91BD59, FOLIAGE = 0xFF77AB2F, DRY_FOLIAGE = 0xFFA0815A, WATER = 0xFF3F76E4;

    private enum State { LOADING, READY, NONE }

    private record Entry(State state, @Nullable Identifier texture) {
    }

    /** A meshed schematic waiting for the render thread. */
    private record Job(Path file, String key, @Nullable MeshData solid, @Nullable MeshData translucent, ByteBufferBuilder[] buffers, BoundingBox bounds) {
        void close() {
            if (solid != null) {
                solid.close();
            }
            if (translucent != null) {
                translucent.close();
            }
            for (ByteBufferBuilder b : buffers) {
                b.close();
            }
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Queue<Job> ready = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> mainThread = new ArrayDeque<>();
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    private final Projection projection = new Projection();

    private SchematicThumbnails() {
    }

    public static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("slopmatica").resolve("thumbnails");
    }

    /** The icon's texture if ready; otherwise starts making it and returns null for now. */
    public @Nullable Identifier get(Path file) {
        String key;
        try {
            key = key(file);
        } catch (IOException e) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry != null) {
            return entry.texture;
        }
        entries.put(key, new Entry(State.LOADING, null));
        Path png = cacheDir().resolve(key + ".png");
        Util.ioPool().execute(() -> {
            if (Files.isRegularFile(png)) {
                try (var in = Files.newInputStream(png)) {
                    NativeImage image = NativeImage.read(in);
                    Minecraft.getInstance().execute(() -> register(key, image));
                    return;
                } catch (IOException e) {
                    LOGGER.warn("Bad cached icon {}", png, e);
                }
            }
            mesh(file, key);
        });
        return null;
    }

    /** The cached PNG for a file, if one has been rendered (used for cloud uploads). */
    public Optional<Path> cachedPng(Path file) {
        try {
            Path png = cacheDir().resolve(key(file) + ".png");
            return Files.isRegularFile(png) ? Optional.of(png) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String key(Path file) throws IOException {
        String id = file.toAbsolutePath().normalize() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void mesh(Path file, String key) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Schematic schematic = Schematic.load(file);
            BoundingBox b = schematic.bounds();
            long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
            if (volume > MAX_VOLUME || schematic.blockCount() == 0) {
                entries.put(key, new Entry(State.NONE, null));
                return;
            }
            ByteBufferBuilder solidBytes = new ByteBufferBuilder(256 * 1024);
            ByteBufferBuilder translucentBytes = new ByteBufferBuilder(16 * 1024);
            BufferBuilder solid = new BufferBuilder(solidBytes, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            BufferBuilder translucent = new BufferBuilder(translucentBytes, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            SchematicView view = new SchematicView(schematic);
            ModelBlockRenderer renderer = new ModelBlockRenderer(false, true, mc.getBlockColors());
            FluidRenderer fluids = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());
            var models = mc.getModelManager().getBlockStateModelSet();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int x = b.minX(); x <= b.maxX(); x++) {
                        BlockState state = view.getBlockState(pos.set(x, y, z));
                        if (state.isAir()) {
                            continue;
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            fluids.tesselate(view, pos, layer -> layer == ChunkSectionLayer.TRANSLUCENT ? translucent : solid, state, fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            renderer.tesselateBlock((qx, qy, qz, quad, instance) ->
                                    (quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT ? translucent : solid).putBlockBakedQuad(qx, qy, qz, quad, instance),
                                x, y, z, view, pos, state, models.get(state), state.getSeed(pos));
                        }
                    }
                }
            }
            ready.add(new Job(file, key, solid.build(), translucent.build(), new ByteBufferBuilder[] {solidBytes, translucentBytes}, b));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("No icon for {}", file, e);
            entries.put(key, new Entry(State.NONE, null));
        }
    }

    /** Client tick: draw at most one finished mesh. */
    public void tick(Minecraft mc) {
        Job job = ready.poll();
        if (job == null) {
            return;
        }
        try {
            render(mc, job);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not render icon for {}", job.file(), e);
            entries.put(job.key(), new Entry(State.NONE, null));
        } finally {
            job.close();
        }
    }

    private void render(Minecraft mc, Job job) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture color = device.createTexture(() -> "Slopmatica icon", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        GpuTexture depth = device.createTexture(() -> "Slopmatica icon depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1);
        GpuTextureView colorView = device.createTextureView(color);
        GpuTextureView depthView = device.createTextureView(depth);
        try {
            device.createCommandEncoder().clearColorAndDepthTextures(color, new Vector4f(0, 0, 0, 0), depth, 0.0);

            // Model space is squeezed into a unit cube: block-shader fog works in view-space blocks,
            // and at this scale nothing is ever far enough away to be fogged.
            BoundingBox b = job.bounds();
            float cx = (b.minX() + b.maxX() + 1) / 2.0F, cy = (b.minY() + b.maxY() + 1) / 2.0F, cz = (b.minZ() + b.maxZ() + 1) / 2.0F;
            Matrix4f rotation = new Matrix4f().rotateX((float) Math.toRadians(30)).rotateY((float) Math.toRadians(225));
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                Vector3f corner = new Vector3f(((i & 1) == 0 ? b.minX() : b.maxX() + 1) - cx, ((i & 2) == 0 ? b.minY() : b.maxY() + 1) - cy,
                    ((i & 4) == 0 ? b.minZ() : b.maxZ() + 1) - cz);
                rotation.transformPosition(corner);
                minX = Math.min(minX, corner.x);
                maxX = Math.max(maxX, corner.x);
                minY = Math.min(minY, corner.y);
                maxY = Math.max(maxY, corner.y);
            }
            float scale = 0.92F / Math.max(maxX - minX, maxY - minY);
            Matrix4f modelView = new Matrix4f()
                .translate(0.5F, 0.5F, 0.0F)
                .scale(scale, -scale, scale)
                .translate(-(minX + maxX) / 2, -(minY + maxY) / 2, 0)
                .mul(rotation)
                .translate(-cx, -cy, -cz);

            if (projectionBuffer == null) {
                projectionBuffer = new ProjectionMatrixBuffer("Slopmatica icons");
            }
            projection.setupOrtho(-2.0F, 2.0F, 1.0F, 1.0F, true);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransforms(
                new DynamicGpuData.Transform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f()))[0];
            int maxIndices = Math.max(indexCount(job.solid()), indexCount(job.translucent()));
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indexBuffer = maxIndices > 0 ? indices.getBuffer(maxIndices) : null;
            GpuBuffer solidVertices = upload(device, job.solid());
            GpuBuffer translucentVertices = upload(device, job.translucent());
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Slopmatica icon", colorView, Optional.empty(), depthView, OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                if (indexBuffer != null) {
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    draw(mc, pass, RenderPipelines.CUTOUT_BLOCK, solidVertices, indexCount(job.solid()), transform);
                    draw(mc, pass, RenderPipelines.TRANSLUCENT_BLOCK, translucentVertices, indexCount(job.translucent()), transform);
                }
            } finally {
                RenderSystem.restoreProjectionMatrix();
                if (solidVertices != null) {
                    solidVertices.close();
                }
                if (translucentVertices != null) {
                    translucentVertices.close();
                }
            }
            readBack(color, job.key());
        } finally {
            colorView.close();
            depthView.close();
            depth.close();
            // The color texture is closed once its pixels have been copied out.
        }
    }

    private static int indexCount(@Nullable MeshData mesh) {
        return mesh == null ? 0 : mesh.drawState().indexCount();
    }

    private static @Nullable GpuBuffer upload(GpuDevice device, @Nullable MeshData mesh) {
        if (mesh == null || mesh.drawState().indexCount() == 0) {
            return null;
        }
        return device.createBuffer(() -> "Slopmatica icon mesh", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
    }

    private static void draw(Minecraft mc, RenderPass pass, RenderPipeline pipeline, @Nullable GpuBuffer vertices, int indexCount, GpuBufferSlice transform) {
        if (vertices == null || indexCount == 0) {
            return;
        }
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        pass.setUniform("Sampler0", mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
            RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, false));
        pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setUniform("DynamicTransforms", transform);
        pass.setVertexBuffer(0, vertices.slice());
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
    }

    private void readBack(GpuTexture color, String key) {
        GpuDevice device = RenderSystem.getDevice();
        int bytes = color.getFormat().blockSize();
        GpuBuffer buffer = device.createBuffer(() -> "Slopmatica icon readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * bytes);
        device.createCommandEncoder().copyTextureToBuffer(color, buffer, 0L, () -> {
            NativeImage image = new NativeImage(SIZE, SIZE, false);
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        // Rows come back bottom-up.
                        image.setPixelABGR(x, SIZE - y - 1, read.data().getInt((x + y * SIZE) * bytes));
                    }
                }
            } finally {
                buffer.close();
                color.close();
            }
            Path png = cacheDir().resolve(key + ".png");
            NativeImage copy = new NativeImage(SIZE, SIZE, false);
            copy.copyFrom(image);
            Util.ioPool().execute(() -> {
                try {
                    Files.createDirectories(png.getParent());
                    copy.writeToFile(png);
                } catch (IOException e) {
                    LOGGER.warn("Could not save icon {}", png, e);
                } finally {
                    copy.close();
                }
            });
            register(key, image);
        }, 0);
    }

    private void register(String key, NativeImage image) {
        Identifier id = Identifier.fromNamespaceAndPath("slopmatica", "icons/" + key);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Slopmatica icon " + key, image));
        entries.put(key, new Entry(State.READY, id));
    }

    /**
     * The schematic as a block getter, so faces between its own blocks are culled. Lit fully, with
     * plains-like tints so icons look the same everywhere.
     */
    private static final class SchematicView implements BlockAndTintGetter {
        private final Schematic schematic;

        SchematicView(Schematic schematic) {
            this.schematic = schematic;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState found = Blocks.AIR.defaultBlockState();
            for (Schematic.Region region : schematic.regions()) {
                int id = region.paletteIndexAt(pos.getX(), pos.getY(), pos.getZ());
                if (id >= 0) {
                    BlockState state = region.palette()[id];
                    if (!state.isAir()) {
                        return state;
                    }
                }
            }
            return found;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
                return FOLIAGE;
            }
            if (resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER) {
                return DRY_FOLIAGE;
            }
            if (resolver == BiomeColors.WATER_COLOR_RESOLVER) {
                return WATER;
            }
            return GRASS;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            ClientLevel level = Minecraft.getInstance().level;
            return level != null ? level.cardinalLighting() : CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
        }

        @Override
        public int getHeight() {
            BoundingBox b = schematic.bounds();
            return b.getYSpan() + 2;
        }

        @Override
        public int getMinY() {
            return schematic.bounds().minY() - 1;
        }
    }
}
