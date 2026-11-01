package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.Nullable;

/** The schematic file formats, chosen by file extension. */
public enum SchematicFormats {
    /** Slopmatica's own, compact format; the default for files it writes. */
    SLOPMATIC(".slopmatic"),
    /** Vanilla structure files: structure blocks and {@code /place template}. */
    NBT(".nbt"),
    /** Litematica, for files made with or meant for it. */
    LITEMATIC(".litematic"),
    /** WorldEdit and FAWE ({@code //schem save}), the Sponge schematic format. */
    SCHEM(".schem");

    public static final List<String> EXTENSIONS = List.of(".slopmatic", ".nbt", ".litematic", ".schem");

    public final String extension;

    SchematicFormats(String extension) {
        this.extension = extension;
    }

    public static @Nullable SchematicFormats of(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (SchematicFormats format : values()) {
            if (name.endsWith(format.extension)) {
                return format;
            }
        }
        return null;
    }

    public static boolean isSchematic(Path file) {
        return of(file) != null && Files.isRegularFile(file);
    }

    public static RawSchematic read(Path file) throws IOException {
        SchematicFormats format = of(file);
        if (format == null) {
            throw new IOException("not a schematic file: " + file.getFileName());
        }
        return switch (format) {
            case SLOPMATIC -> SlopmaticFormat.read(file);
            case NBT -> StructureNbtFormat.read(file);
            case LITEMATIC -> LitematicFormat.read(file);
            case SCHEM -> SpongeFormat.read(file);
        };
    }

    public void write(Path file, BlockVolume volume) throws IOException {
        switch (this) {
            case SLOPMATIC -> SlopmaticFormat.write(file, volume);
            case NBT -> StructureNbtFormat.write(file, volume);
            case LITEMATIC -> LitematicFormat.write(file, volume);
            case SCHEM -> SpongeFormat.write(file, volume);
        }
    }

    /** Writes beside the target and moves into place, so a crash never leaves half a file. */
    static void writeAtomically(Path file, CompoundTag root) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The name without its schematic extension. */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        SchematicFormats format = of(file);
        return format == null ? name : name.substring(0, name.length() - format.extension.length());
    }
}
