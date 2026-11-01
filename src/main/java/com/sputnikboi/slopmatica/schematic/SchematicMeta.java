package com.sputnikboi.slopmatica.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * What a schematic recommends about where it goes: a biome, a position, a rotation and a range
 * of game versions; and its origin point, the block that lands where you place it (by default the
 * middle of its bottom layer). All optional. Kept inside the schematic file, in a compound other
 * tools ignore ({@code SlopmaticaMeta}: at the root, or in a .schem's Metadata), so it travels
 * with the file through the cloud and to other computers.
 */
public final class SchematicMeta {
    static final String KEY = "SlopmaticaMeta";

    public @Nullable String biome;
    /** Where the origin point should be, in the world. */
    public @Nullable BlockPos position;
    public @Nullable Rotation rotation;
    /** Game versions it was made for, like "1.21" and "26.3"; either may be missing. */
    public @Nullable String minVersion, maxVersion;
    /** The origin point in schematic coordinates; null for the default. */
    public @Nullable BlockPos origin;

    public boolean isEmpty() {
        return biome == null && position == null && rotation == null && minVersion == null && maxVersion == null && origin == null;
    }

    public SchematicMeta copy() {
        SchematicMeta m = new SchematicMeta();
        m.biome = biome;
        m.position = position;
        m.rotation = rotation;
        m.minVersion = minVersion;
        m.maxVersion = maxVersion;
        m.origin = origin;
        return m;
    }

    CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        if (biome != null) {
            tag.putString("Biome", biome);
        }
        if (position != null) {
            tag.putIntArray("Position", new int[] {position.getX(), position.getY(), position.getZ()});
        }
        if (rotation != null) {
            tag.putString("Rotation", rotation.name());
        }
        if (minVersion != null) {
            tag.putString("MinVersion", minVersion);
        }
        if (maxVersion != null) {
            tag.putString("MaxVersion", maxVersion);
        }
        if (origin != null) {
            tag.putIntArray("Origin", new int[] {origin.getX(), origin.getY(), origin.getZ()});
        }
        return tag;
    }

    static SchematicMeta fromTag(@Nullable CompoundTag tag) {
        SchematicMeta m = new SchematicMeta();
        if (tag == null) {
            return m;
        }
        m.biome = tag.getString("Biome").filter(s -> !s.isBlank()).orElse(null);
        m.position = tag.getIntArray("Position").filter(a -> a.length == 3).map(a -> new BlockPos(a[0], a[1], a[2])).orElse(null);
        m.rotation = tag.getString("Rotation").map(s -> {
            try {
                return Rotation.valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }).orElse(null);
        m.minVersion = tag.getString("MinVersion").filter(s -> !s.isBlank()).orElse(null);
        m.maxVersion = tag.getString("MaxVersion").filter(s -> !s.isBlank()).orElse(null);
        m.origin = tag.getIntArray("Origin").filter(a -> a.length == 3).map(a -> new BlockPos(a[0], a[1], a[2])).orElse(null);
        return m;
    }

    /** Where the compound lives in a file's NBT: a .schem's Metadata (inside Schematic for v3), elsewhere the root. */
    private static CompoundTag holder(CompoundTag root, SchematicFormats format, boolean create) {
        if (format != SchematicFormats.SCHEM) {
            return root;
        }
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        CompoundTag meta = schematic.getCompound("Metadata").orElse(null);
        if (meta == null) {
            meta = new CompoundTag();
            if (create) {
                schematic.put("Metadata", meta);
            }
        }
        return meta;
    }

    /** The file's recommendations; empty if it has none or can't be read. */
    public static SchematicMeta read(Path file) {
        SchematicFormats format = SchematicFormats.of(file);
        if (format == null) {
            return new SchematicMeta();
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return fromTag(holder(root, format, false).getCompound(KEY).orElse(null));
        } catch (IOException | RuntimeException e) {
            return new SchematicMeta();
        }
    }

    /** Writes these into the file (rewriting it beside itself and swapping, so a crash can't break it). */
    public void write(Path file) throws IOException {
        SchematicFormats format = SchematicFormats.of(file);
        if (format == null) {
            throw new IOException("not a schematic: " + file.getFileName());
        }
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag holder = holder(root, format, true);
        if (isEmpty()) {
            holder.remove(KEY);
        } else {
            holder.put(KEY, toTag());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Whether {@code version} is inside the recommended range (dotted numbers compared part by
     * part, so "1.21.4" is above "1.21" and "26.3" above both).
     */
    public boolean versionOk(String version) {
        // A maximum like "1.21" covers 1.21.x: only as many parts as it has are compared.
        return (minVersion == null || compareVersions(version, minVersion) >= 0)
            && (maxVersion == null || compareVersions(truncate(version, maxVersion.split("[^0-9]+").length), maxVersion) <= 0);
    }

    private static String truncate(String version, int parts) {
        String[] p = version.split("[^0-9]+");
        return String.join(".", java.util.Arrays.copyOf(p, Math.min(parts, p.length)));
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, yi = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }
}
