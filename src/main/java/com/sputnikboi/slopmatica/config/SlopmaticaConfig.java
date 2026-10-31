package com.sputnikboi.slopmatica.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
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
    /** Top-right panel naming the schematic block under the crosshair and what is wrong with it. */
    public boolean blockInfo = true;
    /** Survival/adventure: getting hurt drops you out of the placement view, back to your body. */
    public boolean exitViewWhenHurt = true;
    /** The placement view's camera bumps into blocks instead of flying through them. */
    public boolean viewCollision = true;
    /** Right-clicking a missing ghost block places exactly that block (toggled with its key). */
    public boolean easyPlace = false;
    /** Easy Place may turn the player for a click to get a block's facing right (angle placement). */
    public boolean easyPlaceRotate = true;
    /** Easy Place may place blocks in mid-air, with nothing next to them (angel placement). */
    public boolean easyPlaceAngel = false;
    /** With Easy Place on, refuse normal placements that disagree with the schematic. */
    public boolean easyPlaceRestrict = true;
    /** Copy block entity data (chest contents, sign text) and entities (frames, armor stands), and paste them. */
    public boolean includeNbt = true;
    /** The file format copies are saved in. */
    public SchematicFormats copyFormat = SchematicFormats.SLOPMATIC;
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
        if (copyFormat == null) {
            copyFormat = SchematicFormats.SLOPMATIC;
        }
        if (pasteReplace == null) {
            pasteReplace = PlacementBuilder.Replace.NONE;
        }
        return this;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("slopmatica.json");
    }
}
