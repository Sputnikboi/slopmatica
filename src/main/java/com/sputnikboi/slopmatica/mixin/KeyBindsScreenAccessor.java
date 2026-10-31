package com.sputnikboi.slopmatica.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBindsScreen.class)
public interface KeyBindsScreenAccessor {
    @Accessor("keyBindsList")
    KeyBindsList slopmatica$keyBindsList();

    /** The screen enables this button every frame, so a replacement footer must still provide it. */
    @Accessor("resetButton")
    void slopmatica$setResetButton(Button button);
}
