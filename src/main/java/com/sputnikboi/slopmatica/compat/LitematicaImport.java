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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Reads Litematica's {@code config/litematica.json} and turns every setting that has an equivalent
 * here into an {@link Item}: where it came from, the value it gives, and whether that is just
 * Litematica's default. The import screen shows them all; the player picks and adjusts, and only
 * settings changed in Litematica are ticked unless defaults are asked for too (a value still at
 * Litematica's default says nothing about what they want).
 * Names and defaults are from Litematica for 26.3 (sakura-ryoko's fork, which writes malilib JSON:
 * {@code {"Generic": {...}, "Visuals": {...}, "Colors": {...}, "Hotkeys": {...}}}).
 */
public final class LitematicaImport {
    public static final String FILE = "litematica.json";

    /** What kind of value an item holds, which decides how it is shown and edited. */
    public enum Kind {
        /** Boolean. */
        ON_OFF,
        /** Integer percent. */
        PERCENT,
        /** One of {@link Item#choices}. */
        CHOICE,
        /** Integer 0xRRGGBB. */
        COLOR,
        /** A vanilla key name (key.keyboard.x). */
        KEY
    }

    /**
     * One Slopmatica setting (or key) a Litematica setting maps to.
     *
     * @param option    what to call it: our option's translation key, or the key mapping's name
     * @param target    our config field name, or the key mapping name for keys
     * @param source    the Litematica setting, "Section › name"
     * @param raw       its value as written in the file
     * @param atDefault the file still has Litematica's default there
     */
    public static final class Item {
        public final String option, target, source, raw;
        public final boolean atDefault;
        public final Kind kind;
        public final List<Object> choices;
        private final java.util.function.BiConsumer<SlopmaticaConfig, Object> setter;
        /** Editable before applying. */
        public Object value;

        Item(String option, String target, String source, String raw, boolean atDefault, Kind kind, Object value, List<Object> choices,
             java.util.function.BiConsumer<SlopmaticaConfig, Object> setter) {
            this.option = option;
            this.target = target;
            this.source = source;
            this.raw = raw;
            this.atDefault = atDefault;
            this.kind = kind;
            this.value = value;
            this.choices = choices;
            this.setter = setter;
        }

        /** Sets this on a config (keys are set on their key mappings by {@link #apply}). */
        public void applyTo(SlopmaticaConfig config) {
            setter.accept(config, value);
        }

        public String display() {
            return switch (kind) {
                case ON_OFF -> (Boolean) value ? "ON" : "OFF";
                case PERCENT -> value + "%";
                case CHOICE -> String.valueOf(value);
                case COLOR -> String.format("#%06X", (Integer) value & 0xFFFFFF);
                case KEY -> {
                    try {
                        yield InputConstants.getKey((String) value).getDisplayName().getString();
                    } catch (IllegalArgumentException e) {
                        yield String.valueOf(value);
                    }
                }
            };
        }
    }

    /** @param skipped readable notes on what could not be carried over */
    public record Plan(List<Item> items, List<String> skipped) {
        /** Items changed in Litematica (or all of them, with defaults). */
        public List<Item> shown(boolean defaults) {
            return items.stream().filter(i -> defaults || !i.atDefault).toList();
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
        List<Item> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        JsonObject generic = section(root, "Generic"), visuals = section(root, "Visuals"), colors = section(root, "Colors"), hotkeys = section(root, "Hotkeys");

        onOff(items, generic, "Generic", "easyPlaceMode", false, "slopmatica.options.easy_place_on", "easyPlace", v -> v, (c, v) -> c.easyPlace = v);
        onOff(items, generic, "Generic", "placementRestriction", false, "slopmatica.options.easy_place_restrict", "easyPlaceRestrict", v -> v, (c, v) -> c.easyPlaceRestrict = v);
        JsonElement replace = generic.get("pasteReplaceBehavior");
        if (replace != null && replace.isJsonPrimitive()) {
            String text = replace.getAsString();
            try {
                PlacementBuilder.Replace value = PlacementBuilder.Replace.valueOf(text.toUpperCase(Locale.ROOT));
                items.add(new Item("slopmatica.options.paste_replace", "pasteReplace", "Generic › pasteReplaceBehavior", text,
                    text.equalsIgnoreCase("NONE"), Kind.CHOICE, value, List.of((Object[]) PlacementBuilder.Replace.values()),
                    (c, v) -> c.pasteReplace = (PlacementBuilder.Replace) v));
            } catch (IllegalArgumentException e) {
                skipped.add("pasteReplaceBehavior " + text);
            }
        }

        onOff(items, visuals, "Visuals", "enableSchematicBlocksRendering", true, "slopmatica.options.show_ghosts", "showGhosts", v -> v, (c, v) -> c.showGhosts = v);
        // Litematica draws ghosts solid unless this is on; that is our Solid View.
        onOff(items, visuals, "Visuals", "renderBlocksAsTranslucent", false, "slopmatica.options.solid_ghosts", "solidGhosts", v -> !v, (c, v) -> c.solidGhosts = v);
        percent(items, visuals, "Visuals", "ghostBlockAlpha", 0.5, 5, 100, "slopmatica.options.ghost_opacity", "ghostOpacity", (c, v) -> c.ghostOpacity = v / 100F);
        // Either switch off in Litematica turns outlines off.
        Boolean overlay = flag(visuals, "enableSchematicOverlay"), outlines = flag(visuals, "schematicOverlayEnableOutlines");
        if (overlay != null || outlines != null) {
            boolean on = !Boolean.FALSE.equals(overlay) && !Boolean.FALSE.equals(outlines);
            String which = Boolean.FALSE.equals(overlay) || outlines == null ? "enableSchematicOverlay" : "schematicOverlayEnableOutlines";
            items.add(new Item("slopmatica.options.show_outlines", "showOutlines", "Visuals › " + which, String.valueOf(on), on, Kind.ON_OFF, on, List.of(),
                (c, v) -> c.showOutlines = (Boolean) v));
        }
        // Litematica's line width 1.0 is a normal line, like our 100%.
        percent(items, visuals, "Visuals", "schematicOverlayOutlineWidth", 1.0, 1, 400, "slopmatica.options.outline_width", "outlineWidth", (c, v) -> c.outlineWidth = v / 100F);
        onOff(items, visuals, "Visuals", "schematicOverlayTypeMissing", true, "slopmatica.import.outline.missing", "outlineMissing", v -> v, (c, v) -> c.outlineMissing = v);
        onOff(items, visuals, "Visuals", "schematicOverlayTypeWrongBlock", true, "slopmatica.import.outline.wrong", "outlineWrong", v -> v, (c, v) -> c.outlineWrong = v);
        onOff(items, visuals, "Visuals", "schematicOverlayTypeWrongState", true, "slopmatica.import.outline.variant", "outlineVariant", v -> v, (c, v) -> c.outlineVariant = v);

        color(items, skipped, colors, "schematicOverlayColorMissing", "#2C33B3E6", BlockStatus.MISSING);
        color(items, skipped, colors, "schematicOverlayColorWrongBlock", "#4CFF3333", BlockStatus.WRONG);
        color(items, skipped, colors, "schematicOverlayColorWrongState", "#4CFF9010", BlockStatus.VARIANT);

        hotkey(items, skipped, hotkeys, "openGuiMainMenu", "M", "open_menu");
        hotkey(items, skipped, hotkeys, "openGuiLoadSchematics", "", "load_schematic");
        hotkey(items, skipped, hotkeys, "toggleAllRendering", "M,R", "toggle_hologram");
        hotkey(items, skipped, hotkeys, "toggleSchematicBlockRendering", "", "toggle_ghosts");
        hotkey(items, skipped, hotkeys, "toggleOverlayRendering", "", "toggle_outlines");
        hotkey(items, skipped, hotkeys, "easyPlaceToggle", "", "toggle_easy_place");
        return new Plan(items, skipped);
    }

    // ---- applying ------------------------------------------------------------------------

    /** Applies the given items and saves; returns the notes for keys that turned out not to exist here. */
    public static List<String> apply(List<Item> items) {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        List<String> problems = new ArrayList<>();
        boolean keys = false;
        for (Item item : items) {
            if (item.kind != Kind.KEY) {
                item.applyTo(config);
                continue;
            }
            KeyMapping target = KeyMapping.get(item.target);
            try {
                if (target != null) {
                    target.setKey(InputConstants.getKey((String) item.value));
                    keys = true;
                }
            } catch (IllegalArgumentException e) {
                problems.add(item.target + ": " + item.value);
            }
        }
        config.save();
        if (keys) {
            KeyMapping.resetMapping();
            Minecraft.getInstance().options.save();
        }
        return problems;
    }

    // ---- malilib values ------------------------------------------------------------------

    private static JsonObject section(JsonObject root, String name) {
        return root.has(name) && root.get(name).isJsonObject() ? root.getAsJsonObject(name) : new JsonObject();
    }

    /** A boolean option, or a hotkeyed one ({"enabled": .., "hotkey": {..}}); null if missing. */
    private static @Nullable Boolean flag(JsonObject section, String name) {
        JsonElement e = section.get(name);
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("enabled");
        }
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : null;
    }

    /** @param map from Litematica's value to ours (some mean the opposite) */
    private static void onOff(List<Item> items, JsonObject section, String sectionName, String name, boolean litematicaDefault, String option, String target,
                              java.util.function.UnaryOperator<Boolean> map, java.util.function.BiConsumer<SlopmaticaConfig, Boolean> setter) {
        Boolean value = flag(section, name);
        if (value != null) {
            items.add(new Item(option, target, sectionName + " › " + name, String.valueOf(value), value == litematicaDefault, Kind.ON_OFF,
                map.apply(value), List.of(), (c, v) -> setter.accept(c, (Boolean) v)));
        }
    }

    private static void percent(List<Item> items, JsonObject section, String sectionName, String name, double litematicaDefault, int min, int max,
                                String option, String target, java.util.function.BiConsumer<SlopmaticaConfig, Integer> setter) {
        JsonElement e = section.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return;
        }
        double value = e.getAsDouble();
        int pct = Math.clamp(Math.round(value * 100), min, max);
        items.add(new Item(option, target, sectionName + " › " + name, e.getAsString(), Math.abs(value - litematicaDefault) < 1e-6, Kind.PERCENT,
            pct, List.of(min, max), (c, v) -> setter.accept(c, Math.clamp((Integer) v, min, max))));
    }

    /** malilib writes colours as #AARRGGBB; alpha is ours to decide, so only RGB carries over. */
    private static void color(List<Item> items, List<String> skipped, JsonObject colors, String name, String litematicaDefault, BlockStatus status) {
        JsonElement e = colors.get(name);
        if (e == null || !e.isJsonPrimitive()) {
            return;
        }
        String value = e.getAsString();
        String hex = value.startsWith("#") ? value.substring(1) : value.startsWith("0x") ? value.substring(2) : value;
        try {
            int rgb = (int) (Long.parseLong(hex, 16) & 0xFFFFFF);
            items.add(new Item("slopmatica.import.color." + status.name().toLowerCase(Locale.ROOT), "color" + status.name(), "Colors › " + name, value,
                value.equalsIgnoreCase(litematicaDefault), Kind.COLOR, rgb, List.of(), (c, v) -> c.setColor(status, 0xFF000000 | (Integer) v)));
        } catch (NumberFormatException ex) {
            skipped.add(name + " " + value);
        }
    }

    /** Hotkeys are {"keys": "M", ...} (or a bare string in old files); combinations like "M,R" have no vanilla equivalent. */
    private static void hotkey(List<Item> items, List<String> skipped, JsonObject hotkeys, String name, String litematicaDefault, String ours) {
        JsonElement e = hotkeys.get(name);
        if (e != null && e.isJsonObject()) {
            e = e.getAsJsonObject().get("keys");
        }
        if (e == null || !e.isJsonPrimitive()) {
            return;
        }
        String value = e.getAsString().trim();
        if (value.isEmpty()) {
            return; // Unbound: keep ours.
        }
        if (value.contains(",")) {
            skipped.add(name + " = " + value.replace(",", " + ") + " (key combinations can't be imported)");
            return;
        }
        String mapping = "key.slopmatica." + ours;
        items.add(new Item(mapping, mapping, "Hotkeys › " + name, value, value.equalsIgnoreCase(litematicaDefault), Kind.KEY,
            vanillaKeyName(value), List.of(), (c, v) -> { }));
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
}
