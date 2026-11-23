package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.build.SelectionLock;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lock to Selection: no breaking, placing or using blocks outside the selection. */
@Mixin(value = MultiPlayerGameMode.class, priority = 900)
abstract class MultiPlayerGameModeLockMixin {
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
    private void slopmatica$lockBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (SelectionLock.blocks(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void slopmatica$lockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (SelectionLock.blocksUse(hand, hit)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
