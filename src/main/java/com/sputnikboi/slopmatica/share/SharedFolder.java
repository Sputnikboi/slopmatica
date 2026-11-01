package com.sputnikboi.slopmatica.share;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One folder shared by every Minecraft instance on this computer, for schematics and settings.
 * By default it is a fixed place per user ({@code %APPDATA%\Slopmatica} on Windows,
 * {@code ~/Library/Application Support/Slopmatica} on macOS, {@code ~/.local/share/slopmatica}
 * elsewhere), which every install checks on its own, so a new instance finds it without setup.
 * A custom folder works too, but other instances only find it if they are pointed at it.
 *
 * <p>Each instance chooses separately whether it uses the shared schematics and the shared
 * settings; those choices (and the folder) are never themselves shared.
 */
public final class SharedFolder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SETTINGS = "settings.json";
    /** Settings that belong to this instance alone. */
    private static final Set<String> INSTANCE_ONLY = Set.of("shareSchematics", "shareSettings", "sharedFolder", "welcomeShown");

    private SharedFolder() {
    }

    public static Path defaultFolder() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, "Slopmatica");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Slopmatica");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "slopmatica") : Path.of(home, ".local", "share", "slopmatica");
    }

    public static Path folder() {
        return folder(SlopmaticaConfig.get());
    }

    /** The folder a config points at; used while the config itself is still loading. */
    public static Path folder(SlopmaticaConfig config) {
        String custom = config.sharedFolder;
        if (custom != null && !custom.isBlank()) {
            try {
                return Path.of(custom);
            } catch (RuntimeException e) {
                LOGGER.warn("Bad shared folder {}, using the default", custom);
            }
        }
        return defaultFolder();
    }

    /** A folder of your own choosing: other instances won't find it unless told. */
    public static boolean isCustom() {
        return !folder().toAbsolutePath().normalize().equals(defaultFolder().toAbsolutePath().normalize());
    }

    public static Path schematicsDir() {
        Path dir = folder().resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Could not create {}", dir, e);
        }
        return dir;
    }

    /** Whether another instance has already shared its settings in the default folder. */
    public static boolean defaultHasSettings() {
        return Files.isRegularFile(defaultFolder().resolve(SETTINGS));
    }

    /**
     * Startup, and when sharing is switched on: take the shared settings, keeping this instance's
     * own choices. Returns false if there were none (then this instance's settings become the shared ones).
     */
    public static boolean pull(SlopmaticaConfig config) {
        Path file = folder(config).resolve(SETTINGS);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonElement shared = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!shared.isJsonObject()) {
                return false;
            }
            JsonObject merged = GSON.toJsonTree(config).getAsJsonObject();
            for (var e : shared.getAsJsonObject().entrySet()) {
                if (!INSTANCE_ONLY.contains(e.getKey())) {
                    merged.add(e.getKey(), e.getValue());
                }
            }
            SlopmaticaConfig loaded = GSON.fromJson(merged, SlopmaticaConfig.class);
            for (var field : SlopmaticaConfig.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.set(config, field.get(loaded));
                }
            }
            return true;
        } catch (IOException | RuntimeException | IllegalAccessException e) {
            LOGGER.warn("Could not read shared settings {}", file, e);
            return false;
        }
    }

    /** After a save: write the settings to the shared folder (without this instance's own choices). */
    public static void push(SlopmaticaConfig config) {
        Path file = folder(config).resolve(SETTINGS);
        try {
            JsonObject json = GSON.toJsonTree(config).getAsJsonObject();
            INSTANCE_ONLY.forEach(json::remove);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(SETTINGS + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not write shared settings {}", file, e);
        }
    }

    /** For messages: the folder as the player would type it. */
    public static String display(@Nullable Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString();
    }
}
