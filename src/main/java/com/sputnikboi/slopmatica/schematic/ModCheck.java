package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Which mods a schematic file needs: the namespaces of its blocks and entities other than
 * {@code minecraft}. Modded schematics stay out of the cloud and the board. Read once per file
 * version (path, size, modification time) and cached.
 */
public final class ModCheck {
    private record Key(Path path, long size, long modified) {
    }

    private static final Map<Key, CompletableFuture<Set<String>>> CACHE = new ConcurrentHashMap<>();

    private ModCheck() {
    }

    /** Blocking: reads the file if it hasn't been checked in this version. Empty if unreadable. */
    public static Set<String> namespaces(Path file) {
        Key key = key(file);
        return key == null ? Set.of() : CACHE.computeIfAbsent(key, k -> CompletableFuture.completedFuture(read(file))).join();
    }

    /** Non-blocking, for screens: null until the file has been read in the background. */
    public static @Nullable Set<String> namespacesIfReady(Path file) {
        Key key = key(file);
        if (key == null) {
            return Set.of();
        }
        CompletableFuture<Set<String>> f = CACHE.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> read(file), Util.ioPool()));
        return f.getNow(null);
    }

    private static @Nullable Key key(Path file) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            return new Key(abs, Files.size(abs), Files.getLastModifiedTime(abs).toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    private static Set<String> read(Path file) {
        try {
            return namespaces(SchematicFormats.read(file));
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    public static Set<String> namespaces(RawSchematic raw) {
        Set<String> out = new TreeSet<>();
        for (RawSchematic.Region r : raw.regions()) {
            for (CompoundTag entry : r.palette()) {
                out.add(namespace(entry.getString("Name").or(() -> entry.getString("id")).orElse("")));
            }
            for (EntityData e : r.entities()) {
                out.add(namespace(e.nbt().getStringOr("id", "")));
            }
        }
        out.remove("minecraft");
        out.remove("");
        return out;
    }

    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
