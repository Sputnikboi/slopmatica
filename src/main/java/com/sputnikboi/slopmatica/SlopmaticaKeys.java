package com.sputnikboi.slopmatica;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.gui.SchematicBrowserScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
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
 * Key Binds under "Slopmatica", and are saved in options.txt with every other key. Only the menu has
 * a default key; the rest start unbound so nothing collides with vanilla or other mods.
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

    private SlopmaticaKeys() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SlopmaticaKeys::tick);
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
