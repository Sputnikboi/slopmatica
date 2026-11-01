package com.sputnikboi.slopmatica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sputnikboi.slopmatica.quick.QuickMenu;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While the Quick Menu is open, scrolling moves its selection and the item in your hand stays put. */
@Mixin(MouseHandler.class)
abstract class MouseHandlerQuickMenuMixin {
    @WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
    private int slopmatica$scrollQuickMenu(double wheel, int current, int limit, Operation<Integer> original) {
        QuickMenu menu = QuickMenu.INSTANCE;
        if (!menu.isOpen()) {
            return original.call(wheel, current, limit);
        }
        menu.select(original.call(wheel, menu.selected(), limit));
        return current;
    }
}
