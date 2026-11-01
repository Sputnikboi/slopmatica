package com.sputnikboi.slopmatica.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel sits, for drawing the inventory plan over its slots. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int slopmatica$leftPos();

    @Accessor("topPos")
    int slopmatica$topPos();

    @Accessor("imageWidth")
    int slopmatica$imageWidth();
}
