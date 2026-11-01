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
    /** Ghost blocks drawn fully solid instead of see-through. */
    public boolean solidGhosts = false;
    /** Show the technical options (thickness, fading, formats, cloud address...). */
    public boolean showAdvanced = false;
    /** The welcome screen has been seen (finished or skipped), so it no longer opens by itself. */
    public boolean welcomeShown = false;
    /** This instance uses the shared folder's schematics (a "Shared" tab in the browser). */
    public boolean shareSchematics = false;
    /** This instance's settings follow the shared folder's (and changes here go there too). */
    public boolean shareSettings = false;
    /** A custom shared folder, or null for the default one every install checks. */
    public @org.jspecify.annotations.Nullable String sharedFolder;
    /** The cloud settings profile this instance follows (its id), or null. */
    public @org.jspecify.annotations.Nullable String cloudProfile;
    /** Changes here go to the cloud profile, and its changes come here. */
    public boolean cloudProfileSync = false;
    /** The "pick a settings profile" question was asked in this instance. */
    public boolean profilePromptShown = false;
    /** When the profile was last taken from the cloud (server time, seconds). */
    public long cloudProfilePulled = 0;
    /** Developer frame-time overlay (advanced settings). */
    public boolean devOverlay = false;
    /** Opted in to sending anonymous usage data to the Slopmatica cloud server. Off unless chosen. */
    public boolean telemetry = false;
    /** Outline colours, ARGB; null means the default. */
    public Integer colorMissing, colorMatch, colorVariant, colorWrong;
    /** The placement view detaches the camera; off keeps you in your body (no freecam). */
    public boolean viewFreecam = true;
    /** Which missing block Easy Place places first on a right click. */
    public EasyPlaceOrder easyPlaceOrder = EasyPlaceOrder.FURTHEST;
    /** Keys listed in vanilla's Controls screen; null means the default set (the main ones). */
    public java.util.Set<String> controlsKeys;
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
    public CornerMode cornerMode = CornerMode.DYNAMIC;
    /** What pasting into the world may overwrite. */
    public PlacementBuilder.Replace pasteReplace = PlacementBuilder.Replace.NONE;
    /**
     * How far blue and green outlines reach before fading, as a percentage like Minecraft's
     * Entity Distance: 100% fades from 24 to 48 blocks, 200% from 48 to 96.
     */
    public int outlineDistance = 100;

    // Inventory plan (see InventoryPlanner).
    public com.sputnikboi.slopmatica.plan.InventoryPlanner.Scope planScope = com.sputnikboi.slopmatica.plan.InventoryPlanner.Scope.NEXT_BOTTOM_UP;
    /** Use every free inventory slot; off uses {@link #planSlots} instead. */
    public boolean planAutoSlots = true;
    public int planSlots = 27;
    /** New shulker boxes to fill: up to 27 carried plus 26 in the ender chest, unless unlimited. */
    public int planShulkers = 0;
    public boolean planUnlimitedShulkers = false;
    /** Also fill empty slots of shulker boxes already carried. */
    public boolean planUseCarriedShulkers = false;
    /** Count carried items whatever their data (names, enchantments...); off counts only plain ones. */
    public boolean planIgnoreNbt = true;
    /** Bring the plain block for stripped wood (plus an axe) and waxed copper (plus honeycomb). */
    public boolean planVariants = false;
    public com.sputnikboi.slopmatica.plan.InventoryPlanner.Sort planSort = com.sputnikboi.slopmatica.plan.InventoryPlanner.Sort.NEXT;
    /** Item ids left out of the plan. */
    public java.util.Set<String> planIgnored = new java.util.LinkedHashSet<>();
    /** Forget the plan when leaving a world (otherwise it lasts until the game closes). */
    public boolean planForgetOnLeave = false;
    /** The list beside the inventory: what doesn't fit, ender chest boxes, tools. */
    public boolean planSideList = true;
    /** Ghost items in empty inventory slots and container highlights. */
    public boolean planShowInInventory = true;

    /** Settings that belong to one instance: never copied by shared folders or cloud profiles. */
    public static final java.util.Set<String> INSTANCE_ONLY = java.util.Set.of("shareSchematics", "shareSettings", "sharedFolder", "welcomeShown",
        "cloudProfile", "cloudProfileSync", "profilePromptShown", "cloudProfilePulled");

    /** These settings as JSON, without the instance-only ones: what a shared copy or profile holds. */
    public com.google.gson.JsonObject shareableJson() {
        com.google.gson.JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
        INSTANCE_ONLY.forEach(json::remove);
        return json;
    }

    /** Takes shared settings (from a shared folder or a profile), keeping this instance's own choices. */
    public void applyShared(com.google.gson.JsonObject shared) {
        com.google.gson.JsonObject merged = GSON.toJsonTree(this).getAsJsonObject();
        for (var e : shared.entrySet()) {
            if (!INSTANCE_ONLY.contains(e.getKey())) {
                merged.add(e.getKey(), e.getValue());
            }
        }
        SlopmaticaConfig loaded = GSON.fromJson(merged, SlopmaticaConfig.class).sanitized();
        try {
            for (var field : SlopmaticaConfig.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.set(this, field.get(loaded));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SlopmaticaConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Where the corner key puts a selection corner. */
    public enum CornerMode {
        /** The block you look at, or your feet when you look at nothing. */
        DYNAMIC,
        /** Only the block you look at. */
        LOOK,
        /** Always where you stand. */
        FEET
    }

    /** Which missing ghost on the crosshair line Easy Place places (it never places off the line). */
    public enum EasyPlaceOrder {
        /** The furthest one first, so blocks placed nearby never get in the way of ones further off. */
        FURTHEST,
        NEAREST
    }

    /** Where outline fading starts, in blocks. */
    public float fadeStartBlocks() {
        return 24 * outlineDistance / 100.0F;
    }

    /** Where outlines are gone, in blocks. */
    public float fadeEndBlocks() {
        return 48 * outlineDistance / 100.0F;
    }

    /** Solid view draws ghosts at full opacity, whatever the opacity slider says. */
    public float effectiveGhostOpacity() {
        return solidGhosts ? 1.0F : ghostOpacity;
    }

    public int colorFor(BlockStatus status) {
        Integer custom = switch (status) {
            case MISSING -> colorMissing;
            case MATCH -> colorMatch;
            case VARIANT -> colorVariant;
            case WRONG -> colorWrong;
        };
        return custom != null ? custom | 0xFF000000 : status.defaultColor();
    }

    public void setColor(BlockStatus status, @org.jspecify.annotations.Nullable Integer argb) {
        switch (status) {
            case MISSING -> colorMissing = argb;
            case MATCH -> colorMatch = argb;
            case VARIANT -> colorVariant = argb;
            case WRONG -> colorWrong = argb;
        }
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
        if (shareSettings) {
            com.sputnikboi.slopmatica.share.SharedFolder.push(this);
        }
        if (cloudProfileSync && cloudProfile != null) {
            com.sputnikboi.slopmatica.cloud.SettingsProfiles.INSTANCE.onConfigSaved();
        }
    }

    private static SlopmaticaConfig load() {
        Path file = path();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                SlopmaticaConfig config = GSON.fromJson(reader, SlopmaticaConfig.class);
                if (config != null) {
                    if (config.shareSettings) {
                        // Another instance may have changed the shared settings since this one last ran.
                        com.sputnikboi.slopmatica.share.SharedFolder.pull(config);
                    }
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
        outlineDistance = Math.clamp(outlineDistance, 25, 500);
        if (easyPlaceOrder == null) {
            easyPlaceOrder = EasyPlaceOrder.FURTHEST;
        }
        if (copyFormat == null) {
            copyFormat = SchematicFormats.SLOPMATIC;
        }
        if (cornerMode == null) {
            cornerMode = CornerMode.DYNAMIC;
        }
        if (pasteReplace == null) {
            pasteReplace = PlacementBuilder.Replace.NONE;
        }
        if (planScope == null) {
            planScope = com.sputnikboi.slopmatica.plan.InventoryPlanner.Scope.NEXT_BOTTOM_UP;
        }
        if (planSort == null) {
            planSort = com.sputnikboi.slopmatica.plan.InventoryPlanner.Sort.NEXT;
        }
        if (planIgnored == null) {
            planIgnored = new java.util.LinkedHashSet<>();
        }
        planSlots = Math.clamp(planSlots, 1, 36);
        planShulkers = Math.clamp(planShulkers, 0, planUnlimitedShulkers ? 999 : 53);
        return this;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("slopmatica.json");
    }
}
