package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.mixin.KeyBindsScreenAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Vanilla's Key Binds screen showing only Slopmatica's keys (the list is filtered by
 * {@code KeyBindsListMixin}). Rebinding works exactly as in Controls, because it is that screen;
 * "Reset Keys" here resets only these.
 */
public class SlopmaticaKeyBindsScreen extends KeyBindsScreen {
    public SlopmaticaKeyBindsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options);
    }

    @Override
    protected void addTitle() {
        layout.addTitleHeader(Component.translatable("slopmatica.keys.title"), font);
    }

    @Override
    protected void addFooter() {
        LinearLayout buttons = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        buttons.addChild(Button.builder(Component.translatable("slopmatica.keys.reset"), b -> {
            for (KeyMapping key : options.keyMappings) {
                if (SlopmaticaKeys.isOurs(key)) {
                    key.setKey(key.getDefaultKey());
                }
            }
            ((KeyBindsScreenAccessor) this).slopmatica$keyBindsList().resetMappingAndUpdateButtons();
        }).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
    }
}
