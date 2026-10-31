package com.sputnikboi.slopmatica.mixin.view;

import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand floating in front of the placement view's camera. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void slopmatica$hideHand(CallbackInfo ci) {
        if (PlacementView.INSTANCE.active()) {
            gameRenderState.optionsRenderState.cameraType = CameraType.THIRD_PERSON_BACK;
        }
    }
}
