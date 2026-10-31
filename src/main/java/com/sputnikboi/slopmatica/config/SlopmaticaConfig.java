package com.sputnikboi.slopmatica.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User options, stored as {@code config/slopmatica.json}. Keybinds are deliberately not here: they
 * live in vanilla's Controls screen and options.txt like every other key.
 */
public final class SlopmaticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SlopmaticaConfig instance;

    public float ghostOpacity = 0.65F;
    public boolean showGhosts = true;
    /** Master switch for every outline, flipped by its hotkey; the per-status switches below stay as set. */
    public boolean showOutlines = true;
    public boolean outlineMissing = true;
    public boolean outlineMatch = true;
    public boolean outlineVariant = true;
    public boolean outlineWrong = true;
    /** Multiplier on {@code HologramRenderer.BASE_LINE_WIDTH}, half of vanilla's block outline. */
    public float outlineWidth = 1.0F;
    public boolean fadeOutlines = true;
    /** Survival/adventure: getting hurt drops you out of the placement view, back to your body. */
    public boolean exitViewWhenHurt = true;
    /** What pasting into the world may overwrite. */
    public PlacementBuilder.Replace pasteReplace = PlacementBuilder.Replace.NONE;
    public int fadeStart = 24;
    public int fadeEnd = 48;

    public static SlopmaticaConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public boolean showsOutline(BlockStatus status) {
        return switch (status) {
            case MISSING -> outlineMissing;
            case MATCH -> outlineMatch;
            case VARIANT -> outlineVariant;
            case WRONG -> outlineWrong;
        };
    }

    public void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Could not save {}", file, e);
        }
    }

    private static SlopmaticaConfig load() {
        Path file = path();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                SlopmaticaConfig config = GSON.fromJson(reader, SlopmaticaConfig.class);
                if (config != null) {
                    return config.sanitized();
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Could not read {}; using defaults", file, e);
            }
        }
        return new SlopmaticaConfig();
    }

    private SlopmaticaConfig sanitized() {
        ghostOpacity = Math.clamp(ghostOpacity, 0.05F, 1.0F);
        outlineWidth = Math.clamp(outlineWidth, 0.01F, 4.0F);
        fadeStart = Math.clamp(fadeStart, 4, 256);
        fadeEnd = Math.clamp(fadeEnd, fadeStart + 4, 260);
        if (pasteReplace == null) {
            pasteReplace = PlacementBuilder.Replace.NONE;
        }
        return this;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("slopmatica.json");
    }
}
