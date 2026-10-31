package com.sputnikboi.slopmatica.mixin.view;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mouse look turns the placement view's camera instead of the player. A wrap rather than a
 * redirect, so it chains with other free-camera mods hooking the same call.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @WrapWithCondition(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean slopmatica$turnView(LocalPlayer player, double dx, double dy) {
        if (PlacementView.INSTANCE.active()) {
            PlacementView.INSTANCE.turn(dx, dy);
            return false;
        }
        return true;
    }
}
