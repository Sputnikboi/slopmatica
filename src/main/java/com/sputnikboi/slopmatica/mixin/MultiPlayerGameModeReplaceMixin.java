package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.build.ReplaceBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace Block: the client breaking a block (survival or creative), with what was there before, and the break sent. */
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

    /** A block Replace Block just placed isn't broken right away by attack still being held. */
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
    private void slopmatica$protect(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (ReplaceBlock.INSTANCE.protects(pos)) {
            cir.setReturnValue(false);
        }
    }

    /** Both send the break packet before returning: now the replacement may follow it. */
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("RETURN"))
    private void slopmatica$breakSent(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        ReplaceBlock.INSTANCE.afterBreakSent(Minecraft.getInstance());
    }
}
