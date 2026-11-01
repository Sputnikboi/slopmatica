package com.sputnikboi.slopmatica.view;

import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.schematic.SchematicMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Where a placement differs from what its schematic recommends: position (with a fix), rotation
 * (with a fix), biome (an estimate of how much of the build is in it) and game version. Shown in
 * the build view under the build's name, unless warnings are off for it or for all builds.
 */
public final class Recommendations {
    /** One warning; {@code fix} puts it right, when that can be done for you. */
    public record Warning(Component text, @Nullable Component fixLabel, @Nullable Runnable fix) {
    }

    private record BiomeEstimate(String biome, BoundingBox box, int percent, long time) {
    }

    private static final Map<Placement, BiomeEstimate> BIOMES = new WeakHashMap<>();

    private Recommendations() {
    }

    public static List<Warning> of(Minecraft mc, Placement p) {
        List<Warning> out = new ArrayList<>();
        SchematicMeta meta = p.schematic().meta();
        if (meta.isEmpty() || p.ignoreWarnings || SlopmaticaConfig.get().ignoreRecommendations || mc.level == null) {
            return out;
        }
        String version = SharedConstants.getCurrentVersion().name();
        if ((meta.minVersion != null || meta.maxVersion != null) && !meta.versionOk(version)) {
            out.add(new Warning(Component.translatable("slopmatica.recommend.version",
                meta.minVersion == null ? "…" : meta.minVersion, meta.maxVersion == null ? "…" : meta.maxVersion, version), null, null));
        }
        if (meta.position != null) {
            BlockPos at = p.worldOriginPoint();
            if (!at.equals(meta.position)) {
                int distance = (int) Math.round(Math.sqrt(at.distSqr(meta.position)));
                BlockPos target = meta.position;
                out.add(new Warning(Component.translatable("slopmatica.recommend.position", target.getX(), target.getY(), target.getZ(), distance),
                    Component.translatable("slopmatica.recommend.fix_position"), () -> {
                        UndoStack.INSTANCE.saveBuild(p);
                        p.moveOriginPointTo(target);
                        PlacementManager.INSTANCE.changed();
                    }));
            }
        }
        if (meta.rotation != null && p.transform().rotation() != meta.rotation) {
            Rotation wanted = meta.rotation;
            out.add(new Warning(Component.translatable("slopmatica.recommend.rotation", rotationName(wanted)),
                Component.translatable("slopmatica.recommend.fix_rotation"), () -> {
                    // Turn in place: the origin point stays where it is.
                    UndoStack.INSTANCE.saveBuild(p);
                    BlockPos keep = p.worldOriginPoint();
                    p.setRotation(wanted);
                    p.moveOriginPointTo(keep);
                    PlacementManager.INSTANCE.changed();
                }));
        }
        if (meta.biome != null) {
            int percent = biomePercent(mc, p, meta.biome);
            if (percent < 100) {
                out.add(new Warning(Component.translatable("slopmatica.recommend.biome", meta.biome.replace("minecraft:", ""), percent), null, null));
            }
        }
        return out;
    }

    public static Component rotationName(Rotation r) {
        return Component.translatable("slopmatica.rotation." + r.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * How much of the build's footprint is in the biome, sampling up to 9x9 columns along its
     * bottom layer. Rough on purpose: recounted at most once a second, or when the build moves.
     */
    private static int biomePercent(Minecraft mc, Placement p, String biome) {
        BoundingBox box = p.transform().worldBounds();
        BiomeEstimate cached = BIOMES.get(p);
        long now = Util.getMillis();
        if (cached != null && cached.biome().equals(biome) && cached.box().equals(box) && now - cached.time() < 1000) {
            return cached.percent();
        }
        int stepsX = Math.min(9, box.getXSpan()), stepsZ = Math.min(9, box.getZSpan());
        int match = 0, total = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < stepsX; i++) {
            for (int j = 0; j < stepsZ; j++) {
                int x = box.minX() + (stepsX == 1 ? 0 : i * (box.getXSpan() - 1) / (stepsX - 1));
                int z = box.minZ() + (stepsZ == 1 ? 0 : j * (box.getZSpan() - 1) / (stepsZ - 1));
                pos.set(x, box.minY(), z);
                if (!mc.level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                total++;
                String here = mc.level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("");
                if (here.equals(biome) || here.equals("minecraft:" + biome)) {
                    match++;
                }
            }
        }
        int percent = total == 0 ? 100 : Math.round(100F * match / total);
        BIOMES.put(p, new BiomeEstimate(biome, box, percent, now));
        return percent;
    }
}
