package com.sputnikboi.slopmatica.dev;

import com.sputnikboi.slopmatica.gui.MaterialListScreen;
import com.sputnikboi.slopmatica.gui.PlacementsScreen;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.view.PlacementView;
import com.sputnikboi.slopmatica.gui.SchematicBrowserScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaOptionsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scripted client for headless testing. Inert unless {@code -Dslopmatica.dev.script=<file>} is set.
 * One step per line, run once the player is in a world:
 * {@code wait <ticks>}, {@code /<command>} (client or server), {@code hud}, {@code shot <name>},
 * {@code screen <menu|browser|placements|options|keybinds|none>}, {@code type <text>},
 * {@code key <tab|down|enter>} (both go to the open screen), {@code press <mapping>} (one click of a
 * key mapping, e.g. {@code key.slopmatica.placement_view} or {@code key.attack}), {@code down <mapping>}
 * / {@code up <mapping>} (hold and release), {@code bind <mapping> <key.keyboard.o>}, {@code look <dx> <dy>} (mouse movement), {@code quit}.
 */
public final class DevHarness {
    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica/dev");

    private final List<String> steps;
    private int next;
    private int waitTicks;

    private DevHarness(List<String> steps) {
        this.steps = steps;
    }

    public static void install() {
        String script = System.getProperty("slopmatica.dev.script");
        if (script == null) {
            return;
        }
        try {
            DevHarness harness = new DevHarness(Files.readAllLines(Path.of(script)));
            ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
            LOGGER.info("Dev harness running {}", script);
        } catch (IOException e) {
            LOGGER.error("Could not read dev script {}", script, e);
        }
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (next < steps.size()) {
            String step = steps.get(next++).trim();
            if (step.isEmpty() || step.startsWith("#")) {
                continue;
            }
            LOGGER.info("dev> {}", step);
            if (step.startsWith("wait ")) {
                waitTicks = Integer.parseInt(step.substring(5).trim());
            } else if (step.startsWith("/")) {
                mc.player.connection.sendCommand(step.substring(1));
            } else if (step.equals("hud")) {
                mc.gui.hud.toggle();
            } else if (step.startsWith("shot ")) {
                String name = step.substring(5).trim() + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> LOGGER.info("dev shot {}: {}", name, msg.getString()));
            } else if (step.startsWith("screen ")) {
                mc.gui.setScreen(switch (step.substring(7).trim()) {
                    case "menu" -> new SlopmaticaMenuScreen(null);
                    case "browser" -> new SchematicBrowserScreen(null);
                    case "placements" -> new PlacementsScreen(null);
                    case "options" -> new SlopmaticaOptionsScreen(null);
                    case "materials" -> new MaterialListScreen(null, PlacementManager.INSTANCE.selected().orElseThrow());
                    case "keybinds" -> new KeyBindsScreen(null, mc.options);
                    default -> null;
                });
            } else if (step.startsWith("type ") && mc.gui.screen() != null) {
                step.substring(5).codePoints().forEach(cp -> mc.gui.screen().charTyped(new CharacterEvent(cp)));
            } else if (step.startsWith("key ") && mc.gui.screen() != null) {
                // 26.3 key events carry the SDL scancode and the SDL keycode; focus navigation reads the keycode.
                KeyEvent event = switch (step.substring(4).trim()) {
                    case "tab" -> new KeyEvent(InputConstants.KEY_TAB, 9, 0);
                    case "down" -> new KeyEvent(InputConstants.KEY_DOWN, 0x40000051, 0);
                    default -> new KeyEvent(InputConstants.KEY_RETURN, 13, 0);
                };
                boolean handled = mc.gui.screen().keyPressed(event);
                LOGGER.info("dev key {} handled={} focus={}", step, handled, mc.gui.screen() == null ? null : mc.gui.screen().getFocused());
            } else if (step.startsWith("press ")) {
                KeyMapping mapping = KeyMapping.get(step.substring(6).trim());
                KeyMapping.click(InputConstants.getKey(mapping.saveString()));
            } else if (step.startsWith("bind ")) {
                String[] parts = step.split(" ");
                KeyMapping.get(parts[1]).setKey(InputConstants.getKey(parts[2]));
                KeyMapping.resetMapping();
            } else if (step.startsWith("down ") || step.startsWith("up ")) {
                String[] parts = step.split(" ");
                KeyMapping.get(parts[1]).setDown(parts[0].equals("down"));
            } else if (step.startsWith("look ")) {
                String[] parts = step.split(" ");
                double dx = Double.parseDouble(parts[1]), dy = Double.parseDouble(parts[2]);
                // What the mouse mixin does with real mouse movement.
                if (PlacementView.INSTANCE.active()) {
                    PlacementView.INSTANCE.turn(dx, dy);
                } else {
                    mc.player.turn(dx, dy);
                }
            } else if (step.equals("quit")) {
                mc.stop();
            } else {
                LOGGER.warn("Unknown dev step: {}", step);
            }
            return;
        }
    }
}
