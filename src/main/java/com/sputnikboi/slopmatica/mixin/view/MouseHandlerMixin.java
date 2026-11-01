package com.sputnikboi.slopmatica.mixin.view;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mouse look turns the placement view's camera instead of the player. A wrap rather than a
 * redirect, so it chains with other free-camera mods hooking the same call.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    /** The mouse wheel nudges the placement up and down instead of changing hotbar slot. */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void slopmatica$scrollView(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (PlacementView.INSTANCE.active() && mc.gui.screen() == null && handle == mc.getWindow().handle() && yoffset != 0) {
            PlacementView.INSTANCE.scroll(mc, yoffset);
            ci.cancel();
        }
    }

    @WrapWithCondition(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean slopmatica$turnView(LocalPlayer player, double dx, double dy) {
        if (PlacementView.INSTANCE.freecam()) {
            PlacementView.INSTANCE.turn(dx, dy);
            return false;
        }
        return true;
    }
}
