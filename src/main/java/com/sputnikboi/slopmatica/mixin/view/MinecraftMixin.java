package com.sputnikboi.slopmatica.mixin.view;

import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the placement view is open, clicks belong to it: the player neither attacks, mines, uses
 * nor picks blocks, and has no crosshair target (which would outline a block somewhere else).
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow
    public HitResult hitResult;

    @Shadow
    public Entity crosshairPickEntity;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noAttack(CallbackInfoReturnable<Boolean> cir) {
        if (PlacementView.INSTANCE.active()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = {"startUseItem", "pickBlockOrEntity"}, at = @At("HEAD"), cancellable = true)
    private void slopmatica$noUse(CallbackInfo ci) {
        if (PlacementView.INSTANCE.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noMining(boolean down, CallbackInfo ci) {
        if (PlacementView.INSTANCE.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void slopmatica$noTarget(float partialTicks, CallbackInfo ci) {
        if (PlacementView.INSTANCE.active()) {
            hitResult = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
            crosshairPickEntity = null;
            ci.cancel();
        }
    }
}
