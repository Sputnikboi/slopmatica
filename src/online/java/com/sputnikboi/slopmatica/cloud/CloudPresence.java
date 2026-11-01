package com.sputnikboi.slopmatica.cloud;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Which schematics exist both on disk and in the cloud, by content: a file outside
 * {@code schematics/cloud/} with the same bytes as one inside it (renamed or not) is in both
 * places. Hashes are computed off-thread and remembered by path, size and modification time, so a
 * folder of hundreds of schematics is only read once per change.
 */
public final class CloudPresence {
    public static final CloudPresence INSTANCE = new CloudPresence();

    private record Stamp(long size, long mtime, String sha) {
    }

    private final Map<Path, Stamp> hashes = new ConcurrentHashMap<>();
    private volatile Set<String> cloud = Set.of();
    private volatile Set<String> local = Set.of();
    private final AtomicBoolean scanning = new AtomicBoolean();

    private CloudPresence() {
    }

    /** Rescans in the background; the browser asks again every frame, so badges appear when done. */
    public void refresh() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        Util.ioPool().execute(() -> {
            try {
                Path root = SlopmaticaActions.schematicsDir().toAbsolutePath().normalize();
                Path cloudDir = CloudSync.cloudDir().toAbsolutePath().normalize();
                Set<String> inCloud = ConcurrentHashMap.newKeySet(), onDisk = ConcurrentHashMap.newKeySet();
                try (Stream<Path> files = Files.walk(root, 9)) {
                    files.filter(SchematicFormats::isSchematic).forEach(p -> {
                        Path abs = p.toAbsolutePath().normalize();
                        String rel = root.relativize(abs).toString();
                        if (rel.startsWith(".") || rel.contains("/.") || rel.contains("\\.")) {
                            return; // the cloud trash and other housekeeping
                        }
                        String sha = hash(abs);
                        if (sha != null) {
                            (abs.startsWith(cloudDir) ? inCloud : onDisk).add(sha);
                        }
                    });
                } catch (IOException ignored) {
                    // Partial knowledge just means fewer badges.
                }
                cloud = inCloud;
                local = onDisk;
            } finally {
                scanning.set(false);
            }
        });
    }

    /** A file outside the cloud folder whose content is also in the cloud. */
    public boolean inCloud(Path file) {
        String sha = known(file);
        return sha != null && cloud.contains(sha);
    }

    /** A cloud file whose content is also somewhere outside the cloud folder. */
    public boolean onDisk(Path file) {
        String sha = known(file);
        return sha != null && local.contains(sha);
    }

    private @Nullable String known(Path file) {
        Stamp stamp = hashes.get(file.toAbsolutePath().normalize());
        return stamp == null ? null : stamp.sha();
    }

    private @Nullable String hash(Path file) {
        try {
            long size = Files.size(file), mtime = Files.getLastModifiedTime(file).toMillis();
            Stamp stamp = hashes.get(file);
            if (stamp != null && stamp.size() == size && stamp.mtime() == mtime) {
                return stamp.sha();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            hashes.put(file, new Stamp(size, mtime, sha));
            return sha;
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }
}
