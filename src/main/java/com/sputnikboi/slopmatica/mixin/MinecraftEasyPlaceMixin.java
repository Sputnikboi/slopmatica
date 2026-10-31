package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.build.EasyPlace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right click (and holding it) goes to Easy Place first when it is on. */
@Mixin(Minecraft.class)
abstract class MinecraftEasyPlaceMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void slopmatica$easyPlace(CallbackInfo ci) {
        if (EasyPlace.INSTANCE.tryUse((Minecraft) (Object) this)) {
            ci.cancel();
        }
    }
}
