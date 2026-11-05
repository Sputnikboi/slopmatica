package com.sputnikboi.slopmatica.build;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Easy Place's tool uses: a block in the world that a tool turns into (or towards) the schematic's
 * block. Grass or dirt to a path (shovel), to farmland (hoe), a log to a stripped log, copper scraped
 * back a stage or its wax taken off (axe), copper waxed (honeycomb). What a tool does is asked of
 * the tool itself (26.3 items carry their block transformations), so modded tools work too. Copper
 * may take several clicks: one step each, wax off, scrape, wax on.
 */
final class ToolUses {
    /**
     * One click with {@code item} on the block; {@code carried} false when the player doesn't have
     * one (survival: Easy Place says which tool is missing).
     */
    record Use(Item item, BlockState result, boolean carried) {
    }

    /** Tried when nothing carried does it: given in creative, named as missing in survival. */
    private static final List<Item> DEFAULTS = List.of(Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, Items.HONEYCOMB);

    private ToolUses() {
    }

    /** The next tool use that brings {@code have} (at pos) closer to {@code want}, or null if none does. */
    static @Nullable Use find(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState have, BlockState want) {
        if (have == want || have.isAir()) {
            return null;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            BlockState result = stack.isEmpty() ? null : resultOf(stack, level, pos, have);
            if (result != null && closer(have, result, want)) {
                return new Use(stack.getItem(), result, true);
            }
        }
        for (Item item : DEFAULTS) {
            BlockState result = resultOf(new ItemStack(item), level, pos, have);
            if (result != null && closer(have, result, want)) {
                return new Use(item, result, player.hasInfiniteMaterials());
            }
        }
        return null;
    }

    /** What using the item on the top face of the block at pos makes of it, or null if nothing. */
    private static @Nullable BlockState resultOf(ItemStack stack, ClientLevel level, BlockPos pos, BlockState have) {
        if (stack.is(Items.HONEYCOMB)) {
            return HoneycombItem.getWaxed(have).orElse(null);
        }
        var transformer = stack.get(DataComponents.BLOCK_TRANSFORMER);
        if (transformer == null) {
            return null;
        }
        // As the tool does it: the first transformation that applies wins.
        for (var data : transformer.value().transforms()) {
            if (data.disallowedFaces().contains(Direction.UP)) {
                continue;
            }
            BlockState state = data.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
            if (state != null) {
                return state;
            }
        }
        return null;
    }

    /**
     * Whether the use is a step towards the wanted block: it makes that block (properties that only
     * come from neighbours or time, like farmland's moisture, may still differ), or it is copper
     * getting closer to the wanted stage and wax.
     */
    static boolean closer(BlockState have, BlockState result, BlockState want) {
        if (result == have) {
            return false;
        }
        if (result.getBlock() == want.getBlock()) {
            return true;
        }
        int before = copperSteps(have, want), after = copperSteps(result, want);
        return after < before;
    }

    /** Axe and honeycomb uses from one copper block to another, or MAX_VALUE if none get there (copper never ages back up). */
    static int copperSteps(BlockState from, BlockState to) {
        Block a = unwaxed(from.getBlock()), b = unwaxed(to.getBlock());
        if (WeatheringCopper.getFirst(a) != WeatheringCopper.getFirst(b) || !(a instanceof WeatheringCopper)) {
            return Integer.MAX_VALUE;
        }
        int scrape = stage(a) - stage(b);
        if (scrape < 0) {
            return Integer.MAX_VALUE;
        }
        boolean waxedNow = from.getBlock() != a, waxedThen = to.getBlock() != b;
        // Wax has to come off before scraping, and goes back on after.
        int waxOff = waxedNow && (scrape > 0 || !waxedThen) ? 1 : 0;
        int waxOn = waxedThen && (!waxedNow || scrape > 0) ? 1 : 0;
        return scrape + waxOff + waxOn;
    }

    private static Block unwaxed(Block block) {
        Block plain = HoneycombItem.WAX_OFF_BY_BLOCK.get().get(block);
        return plain != null ? plain : block;
    }

    /** How oxidized: 0 for fresh copper, one more per stage. */
    private static int stage(Block block) {
        int n = 0;
        for (var previous = WeatheringCopper.getPrevious(block); previous.isPresent(); previous = WeatheringCopper.getPrevious(previous.get())) {
            n++;
        }
        return n;
    }
}
