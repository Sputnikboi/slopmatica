package com.sputnikboi.slopmatica.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sputnikboi.slopmatica.quick.ActionWheel;
import com.sputnikboi.slopmatica.quick.QuickMenu;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the Quick Menu is open, scrolling moves its selection and the item in your hand stays put.
 * While the action wheel is open, scrolling turns its page and the mouse moves its pointer instead
 * of the camera.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerQuickMenuMixin {
    @WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
    private int slopmatica$scrollQuickMenu(double wheel, int current, int limit, Operation<Integer> original) {
        if (ActionWheel.INSTANCE.isOpen()) {
            ActionWheel.INSTANCE.turnPage(wheel > 0 ? -1 : 1);
            return current;
        }
        QuickMenu menu = QuickMenu.INSTANCE;
        if (!menu.isOpen()) {
            return original.call(wheel, current, limit);
        }
        menu.select(original.call(wheel, menu.selected(), limit));
        return current;
    }

    @WrapWithCondition(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean slopmatica$pointWheel(LocalPlayer player, double dx, double dy) {
        if (ActionWheel.INSTANCE.isOpen()) {
            ActionWheel.INSTANCE.move(dx, dy);
            return false;
        }
        return true;
    }
}
