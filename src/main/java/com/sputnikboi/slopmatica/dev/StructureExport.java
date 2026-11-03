package com.sputnikboi.slopmatica.dev;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Dev tool: generates every structure in the game whole, as worldgen would, and saves each as a
 * vanilla structure file. Run in a singleplayer superflat world whose ground is 128 blocks of
 * sponge (surface at y 64): what a structure carves out stays air, untouched ground (sponge) is
 * left out, and so is open air above the ground, so the files say nothing about the terrain
 * around them. Mobs are removed first; containers keep their loot tables. Not reachable in game:
 * only the dev harness's {@code exportstructures <dir>} step runs it.
 */
public final class StructureExport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SURFACE = 64;
    private static final int SPACING = 1024;
    private static final Set<String> NETHER = Set.of("fortress", "bastion_remnant", "nether_fossil", "ruined_portal_nether");
    private static final Set<String> END = Set.of("end_city");

    private StructureExport() {
    }

    /** Off the render thread: one structure per server task, so the game keeps ticking between them. */
    /** @param only structure ids to export (all when empty) */
    public static void start(Minecraft mc, Path out, Set<String> only) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("exportstructures needs a singleplayer world");
            return;
        }
        Thread thread = new Thread(() -> {
            ServerLevel level = server.overworld();
            List<Holder.Reference<Structure>> all = server.submit(() -> level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements()
                .filter(h -> only.isEmpty() || only.contains(h.key().identifier().getPath())).toList()).join();
            int i = 0, ok = 0;
            for (Holder.Reference<Structure> holder : all) {
                BlockPos at = new BlockPos(20000 + i++ * SPACING, SURFACE, 20000);
                try {
                    if (server.submit(() -> export(level, holder, at, out)).join()) {
                        ok++;
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("export {} failed", holder.key().identifier(), e);
                }
            }
            LOGGER.info("exportstructures done: {} of {} structures in {}", ok, all.size(), out);
        }, "Slopmatica structure export");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean export(ServerLevel level, Holder.Reference<Structure> holder, BlockPos at, Path out) {
        String id = holder.key().identifier().getPath();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        StructureStart start = holder.value().generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
            randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED), randomState, level.getStructureTemplateManager(), level.getSeed(),
            ChunkPos.containing(at), 0, level, b -> true);
        if (!start.isValid()) {
            LOGGER.warn("export {}: did not generate here", id);
            return false;
        }
        BoundingBox box = start.getBoundingBox();
        ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
        ChunkPos.rangeClosed(min, max).forEach(c -> level.getChunk(c.x(), c.z()));
        ChunkPos.rangeClosed(min, max).forEach(c -> start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
            new BoundingBox(c.getMinBlockX(), level.getMinY(), c.getMinBlockZ(), c.getMaxBlockX(), level.getMaxY() + 1, c.getMaxBlockZ()), c));
        // Some structures (igloos, shipwrecks, pyramids, buried treasure...) settle onto the ground
        // while placing, so the box is taken from the pieces afterwards.
        box = BoundingBox.encapsulatingBoxes(start.getPieces().stream().map(net.minecraft.world.level.levelgen.structure.StructurePiece::getBoundingBox).toList()).orElse(box);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, AABB.of(box).inflate(4))) {
            mob.discard();
        }
        StructureTemplate template = new StructureTemplate();
        BlockPos corner = new BlockPos(box.minX(), box.minY(), box.minZ());
        template.fillFromWorld(level, corner, new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), true, List.of(Blocks.SPONGE));
        CompoundTag tag = template.save(new CompoundTag());
        dropOpenAir(tag, box.minY());
        String dimension = NETHER.contains(id) ? "Nether" : END.contains(id) ? "The End" : "Overworld";
        Path file = out.resolve(dimension).resolve(id + ".nbt");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
        } catch (IOException e) {
            LOGGER.warn("export {}: couldn't write {}", id, file, e);
            return false;
        }
        LOGGER.info("exported {} ({} x {} x {}) to {}", id, box.getXSpan(), box.getYSpan(), box.getZSpan(), file);
        return true;
    }

    /** Air at or above the original surface that the structure didn't place: open sky, not part of it. */
    private static void dropOpenAir(CompoundTag tag, int minY) {
        ListTag palette = tag.getListOrEmpty("palette");
        int air = -1;
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompoundOrEmpty(i);
            // Newer data versions write the block as "id", older ones as "Name".
            if (state.getStringOr("id", state.getStringOr("Name", "")).equals("minecraft:air")) {
                air = i;
            }
        }
        if (air < 0) {
            return;
        }
        ListTag blocks = tag.getListOrEmpty("blocks");
        ListTag kept = new ListTag();
        for (Tag t : blocks) {
            CompoundTag block = (CompoundTag) t;
            int y = block.getListOrEmpty("pos").getIntOr(1, 0); // a list of three ints
            if (block.getIntOr("state", -1) == air && y + minY >= SURFACE && !block.contains("nbt")) {
                continue;
            }
            kept.add(block);
        }
        tag.put("blocks", kept);
    }
}
