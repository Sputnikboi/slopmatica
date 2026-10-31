package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.schematic.BlockVolume;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import com.sputnikboi.slopmatica.schematic.Schematic;
import com.sputnikboi.slopmatica.view.PlacementView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Copy and paste. Copy saves the selection as a real schematic file in {@code schematics/copies/}
 * (format chosen in Options, .slopmatic by default),
 * so a copy survives restarts, shows up in the browser and syncs like any schematic. Paste loads
 * the newest copy as a placement under the crosshair and opens the placement view carrying it;
 * from there "Paste into world" builds it (creative only, see {@link PlacementBuilder}).
 */
public final class Clipboard {
    public static final Clipboard INSTANCE = new Clipboard();

    /** Selections bigger than this are refused rather than freezing the game while reading them. */
    private static final long MAX_VOLUME = 16L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private @Nullable Path last;

    private Clipboard() {
    }

    public static Path copiesDir() {
        return SlopmaticaActions.schematicsDir().resolve("copies");
    }

    public void copy(Minecraft mc) {
        ClientLevel level = mc.level;
        BoundingBox box = Selection.INSTANCE.box();
        if (level == null || mc.player == null) {
            return;
        }
        if (box == null || !level.dimension().identifier().toString().equals(Selection.INSTANCE.dimension())) {
            error("Select two corners first (" + PlacementViewKey.name(SlopmaticaKeys.SELECT_CORNER) + ")");
            return;
        }
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > MAX_VOLUME) {
            error(String.format("Selection too big to copy (%,d blocks, max %,d)", volume, MAX_VOLUME));
            return;
        }
        for (int cx = SectionPos.blockToSectionCoord(box.minX()); cx <= SectionPos.blockToSectionCoord(box.maxX()); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(box.minZ()); cz <= SectionPos.blockToSectionCoord(box.maxZ()); cz++) {
                if (!level.hasChunk(cx, cz)) {
                    error("Part of the selection isn't loaded; move closer and copy again");
                    return;
                }
            }
        }

        int sx = box.getXSpan(), sy = box.getYSpan(), sz = box.getZSpan();
        Map<BlockState, Integer> ids = new IdentityHashMap<>();
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        ids.put(Blocks.AIR.defaultBlockState(), 0);
        int[] indices = new int[(int) volume];
        List<BlockPos> blockEntities = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState state = level.getBlockState(pos.set(box.minX() + x, box.minY() + y, box.minZ() + z));
                    Integer id = ids.get(state);
                    if (id == null) {
                        id = palette.size();
                        palette.add(state);
                        ids.put(state, id);
                    }
                    indices[(y * sz + z) * sx + x] = id;
                    if (state.hasBlockEntity()) {
                        blockEntities.add(pos.immutable());
                    }
                }
            }
        }
        Map<Long, CompoundTag> tiles = blockEntityData(mc, level, blockEntities, box);

        String name = "copy " + LocalDateTime.now().format(STAMP);
        SchematicFormats format = SlopmaticaConfig.get().copyFormat;
        Path file = copiesDir().resolve(name.replace(' ', '_') + format.extension);
        BlockVolume blocks = new BlockVolume(name, mc.player.getGameProfile().name(), sx, sy, sz, palette, indices, tiles);
        CompletableFuture.runAsync(() -> {
            try {
                format.write(file, blocks);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                error("Could not save the copy: " + error.getMessage());
                return;
            }
            last = file;
            SlopmaticaActions.chat(SlopmaticaActions.prefix().append(String.format("Copied %d x %d x %d to schematics/copies/%s. Paste with %s",
                sx, sy, sz, file.getFileName(), PlacementViewKey.name(SlopmaticaKeys.PASTE))));
        }, mc);
    }

    /**
     * Block entity data (chest contents, sign text...). In singleplayer it comes from the server's
     * copy, which is complete; on a server only what the client was sent is available.
     */
    private static Map<Long, CompoundTag> blockEntityData(Minecraft mc, ClientLevel level, List<BlockPos> positions, BoundingBox box) {
        Map<Long, CompoundTag> tiles = new HashMap<>();
        if (positions.isEmpty()) {
            return tiles;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            ServerLevel serverLevel = server.getLevel(level.dimension());
            if (serverLevel != null) {
                tiles.putAll(server.submit(() -> collect(serverLevel, positions, box)).join());
                return tiles;
            }
        }
        tiles.putAll(collect(level, positions, box));
        return tiles;
    }

    private static Map<Long, CompoundTag> collect(net.minecraft.world.level.Level level, List<BlockPos> positions, BoundingBox box) {
        Map<Long, CompoundTag> tiles = new HashMap<>();
        for (BlockPos pos : positions) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                tiles.put(BlockPos.asLong(pos.getX() - box.minX(), pos.getY() - box.minY(), pos.getZ() - box.minZ()), tag);
            }
        }
        return tiles;
    }

    /** Loads the newest copy as a placement under the crosshair and starts carrying it. */
    public void paste(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Path file = last != null && Files.isRegularFile(last) ? last : newestCopy();
        if (file == null) {
            error("Nothing copied yet: select two corners and copy first");
            return;
        }
        BlockPos at;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            at = hit.getBlockPos().relative(hit.getDirection());
        } else {
            at = mc.player.blockPosition();
        }
        String dimension = mc.level.dimension().identifier().toString();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Schematic.load(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((schematic, error) -> {
            if (error != null) {
                error("Could not read " + file.getFileName() + ": " + error.getMessage());
                return;
            }
            Placement p = PlacementManager.INSTANCE.add(schematic, at, dimension);
            PlacementView.INSTANCE.enter(mc, p, true);
            SlopmaticaActions.chat(SlopmaticaActions.prefix().append("Pasted " + p.name() + " as a placement. Position it, then use Paste into world ("
                + PlacementViewKey.name(SlopmaticaKeys.VIEW_PASTE) + " or right click → Paste)"));
        }, mc);
    }

    private static @Nullable Path newestCopy() {
        try (Stream<Path> files = Files.list(copiesDir())) {
            return files.filter(SchematicFormats::isSchematic)
                .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static void error(String message) {
        SlopmaticaActions.actionBar(Component.literal(message).withColor(SlopmaticaActions.ERROR));
    }

    /** Key names for messages. */
    static final class PlacementViewKey {
        static String name(net.minecraft.client.KeyMapping mapping) {
            return mapping.isUnbound() ? "its key (unbound: set it in Key Binds)" : mapping.getTranslatedKeyMessage().getString();
        }
    }
}
