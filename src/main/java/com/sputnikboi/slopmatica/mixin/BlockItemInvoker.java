package com.sputnikboi.slopmatica.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The state an item would place, with its own rules (wall torches, signs, heads), for Easy Place to try out. */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    @Nullable BlockState slopmatica$getPlacementState(BlockPlaceContext context);
}
