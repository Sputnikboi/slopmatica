package com.sputnikboi.slopmatica.mixin.view;

import com.sputnikboi.slopmatica.view.PlacementView;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The movement keys fly the placement view's camera, so the player stands still meanwhile. */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void slopmatica$freezePlayer(CallbackInfo ci) {
        if (PlacementView.INSTANCE.active()) {
            keyPresses = Input.EMPTY;
            moveVector = Vec2.ZERO;
        }
    }
}
