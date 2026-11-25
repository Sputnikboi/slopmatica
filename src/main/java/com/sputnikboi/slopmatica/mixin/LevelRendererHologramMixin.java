package com.sputnikboi.slopmatica.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the hologram into vanilla's main level pass between solid and translucent terrain, where
 * real blocks are, so water blends over ghosts under it instead of hiding them. Fabric's
 * translucent-terrain event can't be used: it fires inside the pass without handing it over.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererHologramMixin {
    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    /** Runs just before the main pass opens: the last moment buffers can be written. */
    @Inject(method = "prepareTranslucents", at = @At("HEAD"))
    private void slopmatica$prepareHologram(CallbackInfo ci) {
        HologramRenderer.INSTANCE.prepare(levelRenderState.cameraRenderState.pos);
    }

    /**
     * Solid terrain and features are drawn; translucent terrain comes next, in the same pass
     * (classic) or composited in later passes (improved transparency): either way, over this.
     */
    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void slopmatica$drawHologram(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, CallbackInfo ci) {
        HologramRenderer.INSTANCE.drawInMainPass(pass);
    }
}
