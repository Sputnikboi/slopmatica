package com.sputnikboi.slopmatica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.gui.SlopmaticaKeyBindsScreen;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Inside {@link SlopmaticaKeyBindsScreen} the list holds only Slopmatica's keys. */
@Mixin(KeyBindsList.class)
abstract class KeyBindsListMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/ArrayUtils;clone([Ljava/lang/Object;)[Ljava/lang/Object;"))
    private Object[] slopmatica$onlyOurs(Object[] mappings, Operation<Object[]> original, @Local(argsOnly = true) KeyBindsScreen screen) {
        Object[] all = original.call((Object) mappings);
        if (!(screen instanceof SlopmaticaKeyBindsScreen)) {
            return all;
        }
        return Arrays.stream(all).map(KeyMapping.class::cast).filter(SlopmaticaKeys::isOurs).toArray(KeyMapping[]::new);
    }
}
