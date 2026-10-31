package com.sputnikboi.slopmatica;

import com.mojang.blaze3d.platform.InputConstants;
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
 * Key Binds under "Slopmatica", and are saved in options.txt with every other key. The menu and the
 * placement view have default keys, as do the view's own controls (keys vanilla leaves free, and
 * only read while the view is open); the rest start unbound so nothing collides with other mods.
 */
public final class SlopmaticaKeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("slopmatica", "slopmatica"));

    private static final KeyMapping OPEN_MENU = register("open_menu", InputConstants.KEY_M);
    private static final KeyMapping LOAD_SCHEMATIC = register("load_schematic");
    private static final KeyMapping TOGGLE_HOLOGRAM = register("toggle_hologram");
    private static final KeyMapping MOVE_HERE = register("move_here");
    private static final KeyMapping NUDGE_FORWARD = register("nudge_forward");
    private static final KeyMapping NUDGE_BACK = register("nudge_back");
    private static final KeyMapping ROTATE = register("rotate");
    private static final KeyMapping MIRROR = register("mirror");
    private static final KeyMapping TOGGLE_OUTLINES = register("toggle_outlines");
    private static final KeyMapping TOGGLE_GHOSTS = register("toggle_ghosts");

    public static final KeyMapping PLACEMENT_VIEW = register("placement_view", InputConstants.KEY_N);
    public static final KeyMapping VIEW_ROTATE = register("view_rotate", InputConstants.KEY_R);
    public static final KeyMapping VIEW_MIRROR = register("view_mirror", InputConstants.KEY_X);
    /** Forward, back, left, right (relative to the camera), up, down. */
    public static final KeyMapping[] VIEW_NUDGES = {
        register("view_nudge_forward", InputConstants.KEY_UP),
        register("view_nudge_back", InputConstants.KEY_DOWN),
        register("view_nudge_left", InputConstants.KEY_LEFT),
        register("view_nudge_right", InputConstants.KEY_RIGHT),
        register("view_nudge_up", InputConstants.KEY_PAGEUP),
        register("view_nudge_down", InputConstants.KEY_PAGEDOWN),
    };

    private SlopmaticaKeys() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SlopmaticaKeys::tick);
        // Before vanilla handles clicks, which the view takes over.
        ClientTickEvents.START_CLIENT_TICK.register(PlacementView.INSTANCE::tick);
    }

    private static KeyMapping register(String name) {
        return register(name, InputConstants.UNKNOWN.getValue());
    }

    private static KeyMapping register(String name, int key) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.slopmatica." + name, key, CATEGORY));
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
        while (PLACEMENT_VIEW.consumeClick()) {
            PlacementView.INSTANCE.toggle(mc);
        }
        PlacementView.INSTANCE.handleKeys(mc, VIEW_ROTATE, VIEW_MIRROR, VIEW_NUDGES);
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
