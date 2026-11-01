package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.quick.QuickMenu;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the Quick Menu is open, clicks pick actions: no mining, attacking or placing (held ones included). */
@Mixin(Minecraft.class)
abstract class MinecraftQuickMenuMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noAttack(CallbackInfoReturnable<Boolean> cir) {
        if (QuickMenu.INSTANCE.isOpen()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noMining(boolean down, CallbackInfo ci) {
        if (QuickMenu.INSTANCE.isOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noUse(CallbackInfo ci) {
        if (QuickMenu.INSTANCE.isOpen()) {
            ci.cancel();
        }
    }
}
