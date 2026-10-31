package com.sputnikboi.slopmatica.placement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.schematic.Schematic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps each world's placements across relogs and crashes: {@code <gamedir>/slopmatica/placements/
 * <world>.json}, one file per singleplayer save or server address. Written within a second of any
 * change (atomically, so a crash mid-write can't leave half a file), and read back on joining.
 *
 * <p>The file records which schematic file each placement came from, so it only stays valid
 * while that file exists. Entries whose file is missing, or still loading, are carried over
 * unchanged on every save, so a temporarily missing file doesn't lose the placement.
 */
public final class PlacementStore {
    public static final PlacementStore INSTANCE = new PlacementStore();

    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DELAY_MS = 1000;

    private @Nullable Path file;
    /** Saved entries not (yet) backed by a live placement: loading, or their schematic is missing. */
    private final List<JsonObject> pending = new ArrayList<>();
    private boolean dirty;
    private long dirtySince;
    /** Set while restoring, so the placements being re-added don't each trigger a save. */
    private boolean restoring;

    private PlacementStore() {
    }

    public void onJoin(Minecraft mc) {
        file = storeFile(mc);
        pending.clear();
        PlacementManager.INSTANCE.clear();
        dirty = false;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        JsonArray entries;
        try {
            entries = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("placements");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not read {}", file, e);
            return;
        }
        for (JsonElement element : entries) {
            if (element.isJsonObject()) {
                restore(element.getAsJsonObject());
            }
        }
    }

    public void onDisconnect() {
        saveNow();
        file = null;
        pending.clear();
        restoring = true;
        PlacementManager.INSTANCE.clear();
        restoring = false;
    }

    /** PlacementManager listener: something changed, save soon. */
    public void markDirty() {
        if (file != null && !restoring && !dirty) {
            dirty = true;
            dirtySince = Util.getMillis();
        }
    }

    public void tick() {
        if (dirty && Util.getMillis() - dirtySince >= SAVE_DELAY_MS) {
            saveNow();
        }
    }

    private void restore(JsonObject entry) {
        Path source;
        try {
            source = resolve(entry.get("file").getAsString());
        } catch (RuntimeException e) {
            pending.add(entry);
            return;
        }
        pending.add(entry);
        CompletableFuture.supplyAsync(() -> {
            try {
                return Schematic.load(source);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((schematic, error) -> {
            if (!pending.contains(entry)) {
                return; // Left the world meanwhile.
            }
            if (error != null) {
                // Keep the entry so it comes back once the file does.
                SlopmaticaActions.chat(Component.literal("[Slopmatica] Placement " + entry.get("name").getAsString()
                    + " not restored: " + source.getFileName() + " is missing or unreadable").withColor(SlopmaticaActions.ERROR));
                return;
            }
            pending.remove(entry);
            JsonArray o = entry.getAsJsonArray("origin");
            restoring = true;
            Placement before = PlacementManager.INSTANCE.selected().orElse(null);
            try {
                Placement p = PlacementManager.INSTANCE.add(schematic, new BlockPos(o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt()),
                    entry.get("dimension").getAsString());
                p.setRotation(Rotation.valueOf(entry.get("rotation").getAsString()));
                p.setMirror(Mirror.valueOf(entry.get("mirror").getAsString()));
                p.setVisible(!entry.has("visible") || entry.get("visible").getAsBoolean());
                // add() selects the new placement; keep the saved selection instead.
                if (before != null && !(entry.has("selected") && entry.get("selected").getAsBoolean())) {
                    PlacementManager.INSTANCE.select(before);
                }
                PlacementManager.INSTANCE.changed();
            } catch (RuntimeException e) {
                LOGGER.error("Bad placement entry {}", entry, e);
                pending.add(entry);
            } finally {
                restoring = false;
            }
        }, Minecraft.getInstance());
    }

    private void saveNow() {
        dirty = false;
        if (file == null) {
            return;
        }
        JsonArray entries = new JsonArray();
        Placement selected = PlacementManager.INSTANCE.selected().orElse(null);
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            Placement.Transform t = p.transform();
            JsonObject e = new JsonObject();
            e.addProperty("name", p.name());
            e.addProperty("file", relativize(p.schematic().source()));
            e.addProperty("dimension", p.dimension());
            JsonArray origin = new JsonArray();
            origin.add(t.origin().getX());
            origin.add(t.origin().getY());
            origin.add(t.origin().getZ());
            e.add("origin", origin);
            e.addProperty("rotation", t.rotation().name());
            e.addProperty("mirror", t.mirror().name());
            e.addProperty("visible", p.visible());
            e.addProperty("selected", p == selected);
            entries.add(e);
        }
        pending.forEach(entries::add);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("placements", entries);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Could not save placements to {}", file, e);
        }
    }

    /** Inside the schematics folder, stored relative to it so the save survives moving the instance. */
    private static String relativize(Path source) {
        Path root = SlopmaticaActions.schematicsDir().toAbsolutePath().normalize();
        Path abs = source.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    private static Path resolve(String stored) {
        Path p = Path.of(stored);
        return p.isAbsolute() ? p : SlopmaticaActions.schematicsDir().resolve(stored);
    }

    private static @Nullable Path storeFile(Minecraft mc) {
        String key;
        IntegratedServer server = mc.getSingleplayerServer();
        ServerData data = mc.getCurrentServer();
        if (server != null) {
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            key = "singleplayer-" + world.getFileName();
        } else if (data != null) {
            key = "server-" + data.ip;
        } else {
            return null;
        }
        String safe = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        return mc.gameDirectory.toPath().resolve("slopmatica").resolve("placements").resolve(safe + ".json");
    }
}
