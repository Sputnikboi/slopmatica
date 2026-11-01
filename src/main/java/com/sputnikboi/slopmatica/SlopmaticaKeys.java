package com.sputnikboi.slopmatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.EasyPlace;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.SchematicBrowserScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
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
    private static int order;

    private static final KeyMapping OPEN_MENU = register(MAIN, "open_menu", InputConstants.KEY_M);
    public static final KeyMapping PLACEMENT_VIEW = register(MAIN, "placement_view", InputConstants.KEY_K);
    private static final KeyMapping LOAD_SCHEMATIC = register(MAIN, "load_schematic");
    private static final KeyMapping TOGGLE_HOLOGRAM = register(MAIN, "toggle_hologram");
    private static final KeyMapping TOGGLE_OUTLINES = register(MAIN, "toggle_outlines");
    private static final KeyMapping TOGGLE_GHOSTS = register(MAIN, "toggle_ghosts");
    private static final KeyMapping TOGGLE_EASY_PLACE = register(MAIN, "toggle_easy_place");

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

    public static final KeyMapping SELECT_CORNER = register(COPY, "select_corner", InputConstants.KEY_B);
    public static final KeyMapping COPY_SELECTION = register(COPY, "copy_selection");
    public static final KeyMapping PASTE = register(COPY, "paste");
    public static final KeyMapping CLEAR_SELECTION = register(COPY, "clear_selection");

    private SlopmaticaKeys() {
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

    private static void tick(Minecraft mc) {
        Consumer<Component> feedback = SlopmaticaActions::actionBar;
        while (OPEN_MENU.consumeClick()) {
            mc.gui.setScreen(new SlopmaticaMenuScreen(null));
        }
        while (LOAD_SCHEMATIC.consumeClick()) {
            mc.gui.setScreen(new SchematicBrowserScreen(null));
        }
        while (TOGGLE_HOLOGRAM.consumeClick()) {
            HologramRenderer renderer = HologramRenderer.INSTANCE;
            renderer.setHidden(!renderer.hidden());
            feedback.accept(Component.literal(renderer.hidden() ? "Hologram hidden" : "Hologram shown").withColor(SlopmaticaActions.ACCENT));
        }
        while (TOGGLE_OUTLINES.consumeClick()) {
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.showOutlines = !config.showOutlines;
            config.save();
            feedback.accept(Component.literal(config.showOutlines ? "Outlines shown" : "Outlines hidden").withColor(SlopmaticaActions.ACCENT));
        }
        while (TOGGLE_GHOSTS.consumeClick()) {
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.showGhosts = !config.showGhosts;
            config.save();
            feedback.accept(Component.literal(config.showGhosts ? "Ghost blocks shown" : "Ghost blocks hidden").withColor(SlopmaticaActions.ACCENT));
        }
        while (TOGGLE_EASY_PLACE.consumeClick()) {
            EasyPlace.INSTANCE.toggle();
        }
        while (PLACEMENT_VIEW.consumeClick()) {
            PlacementView.INSTANCE.toggle(mc);
        }
        PlacementView.INSTANCE.handleKeys(mc);
        while (SELECT_CORNER.consumeClick()) {
            Selection.INSTANCE.setCorner(mc);
        }
        while (COPY_SELECTION.consumeClick()) {
            Clipboard.INSTANCE.copy(mc);
        }
        while (PASTE.consumeClick()) {
            Clipboard.INSTANCE.paste(mc);
        }
        while (CLEAR_SELECTION.consumeClick()) {
            Selection.INSTANCE.clear();
        }
        Placement p = PlacementManager.INSTANCE.selected().orElse(null);
        while (MOVE_HERE.consumeClick()) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.moveTo(p, mc.player.blockPosition(), feedback);
            }
        }
        while (NUDGE_FORWARD.consumeClick()) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.nudge(p, mc.player.getNearestViewDirection(), 1, feedback);
            }
        }
        while (NUDGE_BACK.consumeClick()) {
            if (p != null && mc.player != null) {
                SlopmaticaActions.nudge(p, mc.player.getNearestViewDirection().getOpposite(), 1, feedback);
            }
        }
        while (ROTATE.consumeClick()) {
            if (p != null) {
                SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, feedback);
            }
        }
        while (MIRROR.consumeClick()) {
            if (p != null) {
                SlopmaticaActions.setMirror(p, SlopmaticaActions.nextMirror(p.transform().mirror()), feedback);
            }
        }
    }
}
