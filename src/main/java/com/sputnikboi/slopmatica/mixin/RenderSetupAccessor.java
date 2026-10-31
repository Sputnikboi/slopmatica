package com.sputnikboi.slopmatica.mixin;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    /** Values are package-private {@code RenderSetup.TextureBinding}s; read them via {@link TextureBindingAccessor}. */
    @Accessor("textures")
    Map<String, Object> slopmatica$textures();
}
