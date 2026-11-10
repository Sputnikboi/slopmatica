package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.build.ReplaceBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace Block: the client breaking a block (survival or creative), with what was there before. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeReplaceMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void slopmatica$beforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            ReplaceBlock.INSTANCE.beforeBreak(mc.level.getBlockState(pos));
        }
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void slopmatica$broke(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            ReplaceBlock.INSTANCE.broke(pos);
        }
    }
}
