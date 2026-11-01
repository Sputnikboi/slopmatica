package com.sputnikboi.slopmatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.EasyPlace;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.SchematicBrowserScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.spawn.SpawnCheck;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.view.PlacementView;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;

/**
 * Keybinds. Registered with vanilla, so they show up (and are rebound) in Options > Controls >
 * Key Binds, and are saved in options.txt with every other key. They are split into categories,
 * the everyday keys first; the options screen has a button that shows only these.
 *
 * <p>Defaults avoid keys vanilla uses and Simple Voice Chat's N; M is Litematica's menu key.
 * Placement-view keys are only read while the view is open. The rest start unbound so nothing
 * collides with other mods.
 */
public final class SlopmaticaKeys {
    // Fabric lists mod categories alphabetically by id, so the ids carry the order.
    private static final KeyMapping.Category MAIN = category("1_main");
    private static final KeyMapping.Category PLACEMENT = category("2_placement");
    private static final KeyMapping.Category VIEW = category("3_view");
    private static final KeyMapping.Category COPY = category("4_copy");
    private static final KeyMapping.Category LAYERS = category("5_layers");
    private static int order;

    private static final KeyMapping OPEN_MENU = register(MAIN, "open_menu", InputConstants.KEY_M);
    public static final KeyMapping PLACEMENT_VIEW = register(MAIN, "placement_view", InputConstants.KEY_K);
    private static final KeyMapping LOAD_SCHEMATIC = register(MAIN, "load_schematic");
    private static final KeyMapping TOGGLE_HOLOGRAM = register(MAIN, "toggle_hologram");
    private static final KeyMapping TOGGLE_OUTLINES = register(MAIN, "toggle_outlines");
    private static final KeyMapping TOGGLE_GHOSTS = register(MAIN, "toggle_ghosts");
    private static final KeyMapping TOGGLE_EASY_PLACE = register(MAIN, "toggle_easy_place");
    private static final KeyMapping SPAWN_CHECK = register(MAIN, "spawn_check");
    private static final KeyMapping PLAN_INVENTORY = register(MAIN, "plan_inventory");

    private static final KeyMapping MOVE_HERE = register(PLACEMENT, "move_here");
    private static final KeyMapping NUDGE_FORWARD = register(PLACEMENT, "nudge_forward");
    private static final KeyMapping NUDGE_BACK = register(PLACEMENT, "nudge_back");
    private static final KeyMapping ROTATE = register(PLACEMENT, "rotate");
    private static final KeyMapping MIRROR = register(PLACEMENT, "mirror");

    public static final KeyMapping VIEW_ROTATE = register(VIEW, "view_rotate", InputConstants.KEY_R);
    public static final KeyMapping VIEW_MIRROR = register(VIEW, "view_mirror", InputConstants.KEY_Z);
    /** Forward, back, left, right (relative to the camera), up, down. Shift moves 5 blocks. */
    public static final KeyMapping[] VIEW_NUDGES = {
        register(VIEW, "view_nudge_forward", InputConstants.KEY_UP),
        register(VIEW, "view_nudge_back", InputConstants.KEY_DOWN),
        register(VIEW, "view_nudge_left", InputConstants.KEY_LEFT),
        register(VIEW, "view_nudge_right", InputConstants.KEY_RIGHT),
        // Not Page Up/Down: plenty of laptop keyboards have neither.
        register(VIEW, "view_nudge_up", InputConstants.KEY_PERIOD),
        register(VIEW, "view_nudge_down", InputConstants.KEY_COMMA),
    };
    public static final KeyMapping VIEW_PASTE = register(VIEW, "view_paste", InputConstants.KEY_RETURN);
    /** Next tool (move, select, modify selection, crop). Y is free in vanilla. */
    public static final KeyMapping VIEW_TOOL = register(VIEW, "view_tool", InputConstants.KEY_Y);

    public static final KeyMapping SELECT_CORNER = register(COPY, "select_corner", InputConstants.KEY_B);
    public static final KeyMapping COPY_SELECTION = register(COPY, "copy_selection");
    public static final KeyMapping PASTE = register(COPY, "paste");
    public static final KeyMapping CLEAR_SELECTION = register(COPY, "clear_selection");

    private static final KeyMapping LAYER_MODE = register(LAYERS, "layer_mode");
    private static final KeyMapping LAYER_UP = register(LAYERS, "layer_up");
    private static final KeyMapping LAYER_DOWN = register(LAYERS, "layer_down");
    private static final KeyMapping LAYER_HERE = register(LAYERS, "layer_here");

    private SlopmaticaKeys() {
    }

    /** One click of a key, counted for usage reports when those are switched on. */
    public static boolean pressed(KeyMapping key) {
        if (key.consumeClick()) {
            com.sputnikboi.slopmatica.telemetry.Telemetry.INSTANCE.key(key.getName());
            return true;
        }
        return false;
    }

    public static KeyMapping planKey() {
        return PLAN_INVENTORY;
    }

    public static KeyMapping openMenu() {
        return OPEN_MENU;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SlopmaticaKeys::tick);
        // Before vanilla handles clicks, which the view takes over.
        ClientTickEvents.START_CLIENT_TICK.register(PlacementView.INSTANCE::tick);
    }

    /** Whether a mapping is one of ours, for the options screen's Slopmatica-only key list. */
    public static boolean isOurs(KeyMapping mapping) {
        return mapping.getCategory().id().getNamespace().equals("slopmatica");
    }

    /** All of our mappings, in category and declaration order. */
    public static java.util.List<KeyMapping> all() {
        return java.util.Arrays.stream(Minecraft.getInstance().options.keyMappings).filter(SlopmaticaKeys::isOurs).sorted().toList();
    }

    /**
     * Whether one of our keys is listed in vanilla's Controls screen. By default only the main
     * ones are, so Controls isn't flooded; Options > Keys in Controls changes that.
     */
    public static boolean shownInControls(KeyMapping mapping) {
        java.util.Set<String> chosen = SlopmaticaConfig.get().controlsKeys;
        return chosen != null ? chosen.contains(mapping.getName()) : mapping.getCategory() == MAIN;
    }

    public static void setShownInControls(KeyMapping mapping, boolean shown) {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        if (config.controlsKeys == null) {
            config.controlsKeys = new java.util.TreeSet<>();
            all().stream().filter(k -> k.getCategory() == MAIN).forEach(k -> config.controlsKeys.add(k.getName()));
        }
        if (shown) {
            config.controlsKeys.add(mapping.getName());
        } else {
            config.controlsKeys.remove(mapping.getName());
        }
    }

    private static KeyMapping.Category category(String name) {
        return KeyMapping.Category.register(Identifier.fromNamespaceAndPath("slopmatica", name));
    }

    private static KeyMapping register(KeyMapping.Category category, String name) {
        return register(category, name, InputConstants.UNKNOWN.getValue());
    }

    /** Listed in declaration order within each category, not alphabetically. */
    private static KeyMapping register(KeyMapping.Category category, String name, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slopmatica." + name, InputConstants.Type.KEYBOARD, key, category, order++));
    }

    /** "Layers: single, Y 64" and the like. */
    public static Component layersMessage() {
        Layers layers = Layers.INSTANCE;
        String mode = layers.mode().name().toLowerCase(java.util.Locale.ROOT);
        return layers.mode() == Layers.Mode.ALL
            ? Component.translatable("slopmatica.layers.all").withColor(SlopmaticaActions.ACCENT)
            : Component.translatable("slopmatica.layers.status", Component.translatable("slopmatica.layers." + mode), layers.layer()).withColor(SlopmaticaActions.ACCENT);
    }

    private static void tick(Minecraft mc) {
        Consumer<Component> feedback = SlopmaticaActions::actionBar;
        while (pressed(OPEN_MENU)) {
            mc.gui.setScreen(new SlopmaticaMenuScreen(null));
        }
        while (pressed(LOAD_SCHEMATIC)) {
            mc.gui.setScreen(new SchematicBrowserScreen(null));
        }
        while (pressed(TOGGLE_HOLOGRAM)) {
            HologramRenderer renderer = HologramRenderer.INSTANCE;
            renderer.setHidden(!renderer.hidden());
            feedback.accept(Component.literal(renderer.hidden() ? "Hologram hidden" : "Hologram shown").withColor(SlopmaticaActions.ACCENT));
        }
        while (pressed(TOGGLE_OUTLINES)) {
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.showOutlines = !config.showOutlines;
            config.save();
            feedback.accept(Component.literal(config.showOutlines ? "Outlines shown" : "Outlines hidden").withColor(SlopmaticaActions.ACCENT));
        }
        while (pressed(TOGGLE_GHOSTS)) {
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.showGhosts = !config.showGhosts;
            config.save();
            feedback.accept(Component.literal(config.showGhosts ? "Ghost blocks shown" : "Ghost blocks hidden").withColor(SlopmaticaActions.ACCENT));
        }
        while (pressed(TOGGLE_EASY_PLACE)) {
            EasyPlace.INSTANCE.toggle();
        }
        while (pressed(SPAWN_CHECK)) {
            SpawnCheck.INSTANCE.toggle(mc);
        }
        while (pressed(PLAN_INVENTORY)) {
            com.sputnikboi.slopmatica.plan.InventoryPlanner.INSTANCE.calculate(mc);
        }
        while (pressed(PLACEMENT_VIEW)) {
            PlacementView.INSTANCE.toggle(mc);
        }
        PlacementView.INSTANCE.handleKeys(mc);
        int here = Layers.startHeight(mc.player != null ? mc.player.getBlockY() : 0);
        Layers layers = Layers.INSTANCE;
        while (pressed(LAYER_MODE)) {
            layers.cycleMode(here);
            feedback.accept(layersMessage());
        }
        while (pressed(LAYER_UP)) {
            layers.move(1);
            feedback.accept(layersMessage());
        }
        while (pressed(LAYER_DOWN)) {
            layers.move(-1);
            feedback.accept(layersMessage());
        }
        while (pressed(LAYER_HERE)) {
            layers.setLayer(here);
            feedback.accept(layersMessage());
        }
        while (pressed(SELECT_CORNER)) {
            Selection.INSTANCE.setCorner(mc);
        }
        while (pressed(COPY_SELECTION)) {
            Clipboard.INSTANCE.copy(mc);
        }
        while (pressed(PASTE)) {
            Clipboard.INSTANCE.paste(mc);
        }
        while (pressed(CLEAR_SELECTION)) {
            Selection.INSTANCE.clear();
        }
        Placement p = PlacementManager.INSTANCE.selected().orElse(null);
        while (pressed(MOVE_HERE)) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.moveTo(p, mc.player.blockPosition(), feedback);
            }
        }
        while (pressed(NUDGE_FORWARD)) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.nudge(p, mc.player.getNearestViewDirection(), 1, feedback);
            }
        }
        while (pressed(NUDGE_BACK)) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.nudge(p, mc.player.getNearestViewDirection().getOpposite(), 1, feedback);
            }
        }
        while (pressed(ROTATE)) {
            if (p != null) {
                SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, feedback);
            }
        }
        while (pressed(MIRROR)) {
            if (p != null) {
                SlopmaticaActions.setMirror(p, SlopmaticaActions.nextMirror(p.transform().mirror()), feedback);
            }
        }
    }
}
