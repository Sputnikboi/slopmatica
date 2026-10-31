package com.sputnikboi.slopmatica.mixin.view;

import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the placement view the camera sits at the view's position, detached so the player is drawn. */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void slopmatica$placementView(float partialTicks, CallbackInfo ci) {
        PlacementView view = PlacementView.INSTANCE;
        if (view.active()) {
            setRotation(view.yRot(), view.xRot());
            setPosition(view.position(partialTicks));
            detached = true;
        }
    }
}
