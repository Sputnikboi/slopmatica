package com.sputnikboi.slopmatica.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Reads Litematica's {@code config/litematica.json} and carries over what has an equivalent here.
 * Only settings the player actually changed are imported: a value still at Litematica's default
 * says nothing about what they want, and copying it would only override this mod's own defaults.
 * Names and defaults are from Litematica for 26.3 (sakura-ryoko's fork, which writes malilib JSON:
 * {@code {"Generic": {...}, "Visuals": {...}, "Colors": {...}, "Hotkeys": {...}}}).
 */
public final class LitematicaImport {
    public static final String FILE = "litematica.json";

    /** One setting to change: the option's translation key, the new value as shown, and how to apply it. */
    public record Change(String option, String value, Consumer<SlopmaticaConfig> apply) {
    }

    /**
     * @param keys     our key mapping name (key.slopmatica.x) to a vanilla key name (key.keyboard.y)
     * @param skipped  readable notes on what could not be carried over
     */
    public record Plan(List<Change> changes, Map<String, String> keys, List<String> skipped) {
        public boolean isEmpty() {
            return changes.isEmpty() && keys.isEmpty();
        }
    }

    private LitematicaImport() {
    }

    // ---- finding config files -------------------------------------------------------------

    /** This instance's Litematica config, if Litematica is or was installed here. */
    public static @Nullable Path local() {
        Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE);
        return Files.isRegularFile(file) ? file : null;
    }

    /**
     * Litematica configs in other instances: siblings of this instance (launchers such as Modrinth,
     * Prism and CurseForge keep every instance in one folder) and the default .minecraft folder.
     */
    public static List<Path> otherInstances() {
        Path here = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
        List<Path> found = new ArrayList<>();
        Path parent = here.getParent();
        if (parent != null) {
            try (Stream<Path> siblings = Files.list(parent)) {
                siblings.filter(p -> !p.equals(here)).sorted().limit(64).forEach(dir -> {
                    // Prism keeps the game in <instance>/.minecraft or <instance>/minecraft.
                    for (Path game : List.of(dir, dir.resolve(".minecraft"), dir.resolve("minecraft"))) {
                        Path file = game.resolve("config").resolve(FILE);
                        if (Files.isRegularFile(file)) {
                            found.add(file);
                            break;
                        }
                    }
                });
            } catch (IOException ignored) {
                // Not a folder we can read: nothing to offer.
            }
        }
        String appData = System.getenv("APPDATA");
        Path vanilla = appData != null ? Path.of(appData, ".minecraft") : Path.of(System.getProperty("user.home"), ".minecraft");
        Path file = vanilla.resolve("config").resolve(FILE);
        if (!vanilla.toAbsolutePath().normalize().equals(here) && Files.isRegularFile(file) && !found.contains(file)) {
            found.add(file);
        }
        return found;
    }

    /** Accepts the config file itself, an instance folder, or its config folder. */
    public static @Nullable Path resolve(Path input) {
        if (Files.isRegularFile(input)) {
            return input;
        }
        for (Path candidate : List.of(input.resolve("config").resolve(FILE), input.resolve(FILE),
            input.resolve(".minecraft").resolve("config").resolve(FILE), input.resolve("minecraft").resolve("config").resolve(FILE))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A short name for a found config: its instance folder. */
    public static String instanceName(Path configFile) {
        Path game = configFile.toAbsolutePath().getParent().getParent();
        if (game == null || game.getFileName() == null) {
            return configFile.toString();
        }
        // Prism: <instance>/.minecraft with instance.cfg next to it; name the instance, not the folder.
        Path instance = game.getParent();
        if (instance != null && instance.getFileName() != null && Files.isRegularFile(instance.resolve("instance.cfg"))) {
            return instance.getFileName().toString();
        }
        return game.getFileName().toString();
    }

    // ---- reading -------------------------------------------------------------------------

    public static Plan read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IOException("Not a Litematica config");
            }
            return read(root.getAsJsonObject());
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("Not a Litematica config: " + e.getMessage(), e);
        }
    }

    public static Plan read(JsonObject root) {
        List<Change> changes = new ArrayList<>();
        Map<String, String> keys = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        JsonObject generic = section(root, "Generic"), visuals = section(root, "Visuals"), colors = section(root, "Colors"), hotkeys = section(root, "Hotkeys");

        Boolean easyPlace = bool(generic, "easyPlaceMode", false);
        if (easyPlace != null) {
            changes.add(new Change("slopmatica.options.easy_place_on", onOff(easyPlace), c -> c.easyPlace = easyPlace));
        }
        Boolean restrict = bool(generic, "placementRestriction", false);
        if (restrict != null) {
            changes.add(new Change("slopmatica.options.easy_place_restrict", onOff(restrict), c -> c.easyPlaceRestrict = restrict));
        }
        String replace = string(generic, "pasteReplaceBehavior", "NONE");
        if (replace != null) {
            try {
                PlacementBuilder.Replace value = PlacementBuilder.Replace.valueOf(replace.toUpperCase(Locale.ROOT));
                changes.add(new Change("slopmatica.options.paste_replace", value.name(), c -> c.pasteReplace = value));
            } catch (IllegalArgumentException e) {
                skipped.add("pasteReplaceBehavior " + replace);
            }
        }

        Boolean blocks = bool(visuals, "enableSchematicBlocksRendering", true);
        if (blocks != null) {
            changes.add(new Change("slopmatica.options.show_ghosts", onOff(blocks), c -> c.showGhosts = blocks));
        }
        Boolean translucent = bool(visuals, "renderBlocksAsTranslucent", false);
        if (translucent != null) {
            // Litematica draws ghosts solid unless this is on; that is our Solid View.
            changes.add(new Change("slopmatica.options.solid_ghosts", onOff(!translucent), c -> c.solidGhosts = !translucent));
        }
        Double alpha = number(visuals, "ghostBlockAlpha", 0.5);
        if (alpha != null) {
            float opacity = (float) Math.clamp(alpha, 0.05, 1.0);
            changes.add(new Change("slopmatica.options.ghost_opacity", Math.round(opacity * 100) + "%", c -> c.ghostOpacity = opacity));
        }
        Boolean overlay = bool(visuals, "enableSchematicOverlay", true), outlines = bool(visuals, "schematicOverlayEnableOutlines", true);
        if (Boolean.FALSE.equals(overlay) || Boolean.FALSE.equals(outlines)) {
            changes.add(new Change("slopmatica.options.show_outlines", onOff(false), c -> c.showOutlines = false));
        }
        Double width = number(visuals, "schematicOverlayOutlineWidth", 1.0);
        if (width != null) {
            // Litematica's 1.0 is a normal line, like our 100%.
            float scale = (float) Math.clamp(width, 0.01, 4.0);
            changes.add(new Change("slopmatica.options.outline_width", Math.round(scale * 100) + "%", c -> c.outlineWidth = scale));
        }
        outlineType(changes, visuals, "schematicOverlayTypeMissing", "slopmatica.options.outline_missing", (c, v) -> c.outlineMissing = v);
        outlineType(changes, visuals, "schematicOverlayTypeWrongBlock", "slopmatica.options.outline_wrong", (c, v) -> c.outlineWrong = v);
        outlineType(changes, visuals, "schematicOverlayTypeWrongState", "slopmatica.options.outline_variant", (c, v) -> c.outlineVariant = v);

        color(changes, skipped, colors, "schematicOverlayColorMissing", "#2C33B3E6", BlockStatus.MISSING);
        color(changes, skipped, colors, "schematicOverlayColorWrongBlock", "#4CFF3333", BlockStatus.WRONG);
        color(changes, skipped, colors, "schematicOverlayColorWrongState", "#4CFF9010", BlockStatus.VARIANT);

        hotkey(keys, skipped, hotkeys, "openGuiMainMenu", "M", "open_menu");
        hotkey(keys, skipped, hotkeys, "openGuiLoadSchematics", "", "load_schematic");
        hotkey(keys, skipped, hotkeys, "toggleAllRendering", "M,R", "toggle_hologram");
        hotkey(keys, skipped, hotkeys, "toggleSchematicBlockRendering", "", "toggle_ghosts");
        hotkey(keys, skipped, hotkeys, "toggleOverlayRendering", "", "toggle_outlines");
        hotkey(keys, skipped, hotkeys, "easyPlaceToggle", "", "toggle_easy_place");
        return new Plan(changes, keys, skipped);
    }

    // ---- applying ------------------------------------------------------------------------

    /** Applies the plan and saves; returns the notes for keys that turned out not to exist here. */
    public static List<String> apply(Plan plan) {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        plan.changes().forEach(change -> change.apply().accept(config));
        config.save();
        List<String> problems = new ArrayList<>();
        plan.keys().forEach((mapping, key) -> {
            KeyMapping target = KeyMapping.get(mapping);
            try {
                if (target != null) {
                    target.setKey(InputConstants.getKey(key));
                }
            } catch (IllegalArgumentException e) {
                problems.add(mapping + ": " + key);
            }
        });
        if (!plan.keys().isEmpty()) {
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        }
        return problems;
    }

    // ---- malilib values ------------------------------------------------------------------

    private static JsonObject section(JsonObject root, String name) {
        return root.has(name) && root.get(name).isJsonObject() ? root.getAsJsonObject(name) : new JsonObject();
    }

    /** A boolean option, or a hotkeyed one ({"enabled": .., "hotkey": {..}}); null if missing or at the default. */
    private static @Nullable Boolean bool(JsonObject section, String name, boolean litematicaDefault) {
        JsonElement e = section.get(name);
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("enabled");
        }
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        boolean value = e.getAsBoolean();
        return value == litematicaDefault ? null : value;
    }

    private static @Nullable Double number(JsonObject section, String name, double litematicaDefault) {
        JsonElement e = section.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double value = e.getAsDouble();
        return Math.abs(value - litematicaDefault) < 1e-6 ? null : value;
    }

    private static @Nullable String string(JsonObject section, String name, String litematicaDefault) {
        JsonElement e = section.get(name);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String value = e.getAsString();
        return value.equalsIgnoreCase(litematicaDefault) ? null : value;
    }

    private static void outlineType(List<Change> changes, JsonObject visuals, String name, String option, java.util.function.BiConsumer<SlopmaticaConfig, Boolean> setter) {
        Boolean value = bool(visuals, name, true);
        if (value != null) {
            changes.add(new Change(option, onOff(value), c -> setter.accept(c, value)));
        }
    }

    /** malilib writes colours as #AARRGGBB; alpha is ours to decide, so only RGB carries over. */
    private static void color(List<Change> changes, List<String> skipped, JsonObject colors, String name, String litematicaDefault, BlockStatus status) {
        String value = string(colors, name, litematicaDefault);
        if (value == null) {
            return;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value.startsWith("0x") ? value.substring(2) : value;
        try {
            int rgb = (int) (Long.parseLong(hex, 16) & 0xFFFFFF);
            changes.add(new Change("slopmatica.options.outline_" + status.name().toLowerCase(Locale.ROOT), String.format("#%06X", rgb),
                c -> c.setColor(status, 0xFF000000 | rgb)));
        } catch (NumberFormatException e) {
            skipped.add(name + " " + value);
        }
    }

    /** Hotkeys are {"keys": "M", ...} (or a bare string in old files); combinations like "M,R" have no vanilla equivalent. */
    private static void hotkey(Map<String, String> keys, List<String> skipped, JsonObject hotkeys, String name, String litematicaDefault, String ours) {
        JsonElement e = hotkeys.get(name);
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("keys");
        }
        if (e == null || !e.isJsonPrimitive()) {
            return;
        }
        String value = e.getAsString().trim();
        if (value.equalsIgnoreCase(litematicaDefault) || value.isEmpty()) {
            return; // Unchanged, or unbound: keep ours.
        }
        if (value.contains(",")) {
            skipped.add(name + " = " + value.replace(",", " + ") + " (key combinations can't be imported)");
            return;
        }
        keys.put("key.slopmatica." + ours, vanillaKeyName(value));
    }

    /** malilib key names (SDL scancode names: M, LEFT_ALT, PAGE_UP, KP_1, MOUSE_RIGHT) to vanilla's. */
    public static String vanillaKeyName(String malilib) {
        String n = malilib.trim().toUpperCase(Locale.ROOT);
        return switch (n) {
            case "MOUSE_LEFT", "BUTTON_1" -> "key.mouse.left";
            case "MOUSE_RIGHT", "BUTTON_2" -> "key.mouse.right";
            case "MOUSE_MIDDLE", "BUTTON_3" -> "key.mouse.middle";
            case "MOUSE_BACK", "BUTTON_4" -> "key.mouse.4";
            case "MOUSE_FORWARD", "BUTTON_5" -> "key.mouse.5";
            case "RETURN", "ENTER" -> "key.keyboard.enter";
            case "GRAVE", "GRAVE_ACCENT" -> "key.keyboard.grave.accent";
            case "KP_PLUS", "KP_ADD" -> "key.keyboard.keypad.add";
            case "KP_MINUS", "KP_SUBTRACT" -> "key.keyboard.keypad.subtract";
            case "KP_EQUALS", "KP_EQUAL" -> "key.keyboard.keypad.equal";
            case "CAPSLOCK" -> "key.keyboard.caps.lock";
            case "PRINTSCREEN" -> "key.keyboard.print.screen";
            case "SCROLLLOCK" -> "key.keyboard.scroll.lock";
            case "NUMLOCKCLEAR" -> "key.keyboard.num.lock";
            case "PAGEUP" -> "key.keyboard.page.up";
            case "PAGEDOWN" -> "key.keyboard.page.down";
            default -> n.startsWith("KP_")
                ? "key.keyboard.keypad." + n.substring(3).toLowerCase(Locale.ROOT).replace('_', '.')
                : "key.keyboard." + n.toLowerCase(Locale.ROOT).replace('_', '.');
        };
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
