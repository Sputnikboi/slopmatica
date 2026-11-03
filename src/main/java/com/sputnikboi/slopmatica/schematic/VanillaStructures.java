package com.sputnikboi.slopmatica.schematic;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Every structure piece the game ships (villages, bastions, end cities...), read from the player's
 * own copy of Minecraft: nothing is downloaded or shared. They are copied once per game version
 * into {@code schematics/.vanilla/<dimension>/...} (a dot folder, so the normal browser skips it)
 * and browsed from the browser's Vanilla tab.
 */
public final class VanillaStructures {
    private static final String SOURCE = "data/minecraft/structure";
    private static final Set<String> NETHER = Set.of("bastion", "nether_fossils");
    private static final Set<String> END = Set.of("end_city");

    private static @Nullable CompletableFuture<Path> extracting;

    private VanillaStructures() {
    }

    public static Path folder() {
        return SlopmaticaActions.schematicsDir().resolve(".vanilla");
    }

    /** True for files under the Vanilla folder (credited to Mojang in the browser). */
    public static boolean contains(Path file) {
        return file.toAbsolutePath().normalize().startsWith(folder().toAbsolutePath().normalize());
    }

    /** The folder, copied out of the game if this version hasn't been yet. Completes off-thread. */
    public static synchronized CompletableFuture<Path> ready() {
        if (extracting == null || extracting.isCompletedExceptionally()) {
            extracting = CompletableFuture.supplyAsync(VanillaStructures::extract, Util.ioPool());
        }
        return extracting;
    }

    private static Path extract() {
        Path out = folder();
        Path marker = out.resolve(".version");
        String version = SharedConstants.getCurrentVersion().name();
        try {
            if (Files.isRegularFile(marker) && Files.readString(marker).trim().equals(version)) {
                return out;
            }
            deleteTree(out);
            for (Path root : FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getRootPaths()) {
                Path source = root.resolve(SOURCE);
                if (!Files.isDirectory(source)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(source)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".nbt")).toList()) {
                        Path rel = source.relativize(file);
                        if (rel.getNameCount() < 2) {
                            continue; // empty.nbt and the like: not a structure.
                        }
                        String family = rel.getName(0).toString();
                        String dimension = NETHER.contains(family) ? "Nether" : END.contains(family) ? "The End" : "Overworld";
                        Path target = out.resolve(dimension).resolve(rel.toString().replace(file.getFileSystem().getSeparator(), "/"));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
            Files.writeString(marker, version);
            return out;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.delete(p);
            }
        }
    }
}
