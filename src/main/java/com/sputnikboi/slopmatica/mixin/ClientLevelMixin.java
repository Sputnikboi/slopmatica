package com.sputnikboi.slopmatica.mixin;

import com.sputnikboi.slopmatica.render.HologramRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client-side block change (server packets and local placement prediction) passes here. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void slopmatica$onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
        if (old != current) {
            HologramRenderer.INSTANCE.onBlockChanged(pos);
        }
    }
}
