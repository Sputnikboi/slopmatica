package com.sputnikboi.slopmatica.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/** The title screen gets a button with the mod's icon, left of Singleplayer, that opens the Slopmatica menu. */
public final class TitleScreenButton {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("slopmatica", "icon.png");

    private TitleScreenButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) {
                return;
            }
            AbstractWidget singleplayer = Screens.getWidgets(screen).stream()
                .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.singleplayer"))
                .findFirst().orElse(null);
            // Next to Singleplayer, as the language and accessibility buttons sit next to Options and Quit;
            // in the top left corner if some other mod moved it.
            int x = singleplayer != null ? singleplayer.getX() - 24 : 4, y = singleplayer != null ? singleplayer.getY() : 4;
            Button button = new IconButton(x, y, b -> mc.gui.setScreen(new SlopmaticaMenuScreen(screen)));
            button.setTooltip(Tooltip.create(Component.translatable("slopmatica.menu.title")));
            Screens.getWidgets(screen).add(button);
        });
    }

    /** A 20x20 button showing the mod's icon, fading in with the title screen's other buttons. */
    private static final class IconButton extends Button.Plain {
        IconButton(int x, int y, OnPress onPress) {
            super(x, y, 20, 20, Component.translatable("slopmatica.menu.title"), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            extractDefaultSprite(g);
            g.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 2, getY() + 2, 0, 0, 16, 16, 128, 128, 128, 128, ARGB.white(alpha));
        }
    }
}
