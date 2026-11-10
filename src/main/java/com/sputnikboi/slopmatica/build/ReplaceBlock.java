package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Replace Block: every block the player breaks is replaced with the block in their offhand,
 * keeping what it can of the broken one's facing (stairs stay stairs the same way round, logs
 * keep their axis). When the offhand runs out it is refilled from the inventory, and the bottom
 * left of the screen shows how many are left. One or the other with Easy Place.
 *
 * <p>Breaking a block of the offhand's own kind leaves it broken, so mistakes can be undone.
 */
public final class ReplaceBlock {
    public static final ReplaceBlock INSTANCE = new ReplaceBlock();

    /** Ticks a broken block waits to be air (the server may be slow to agree it broke) before it is given up on. */
    private static final int WAIT_TICKS = 10;

    private record Broken(BlockPos pos, BlockState state, int age) {
    }

    private final Deque<Broken> pending = new ArrayDeque<>();
    /** The state at the position being broken, from just before the break (see the game mode mixin). */
    private @Nullable BlockState breaking;
    /** The block being placed: the offhand's, remembered so it can be refilled once it runs out. */
    private @Nullable Item item;

    private ReplaceBlock() {
    }

    public boolean enabled() {
        return SlopmaticaConfig.get().replaceBlock;
    }

    public void toggle() {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        config.replaceBlock = !config.replaceBlock;
        boolean wasEasyPlace = config.replaceBlock && config.easyPlace;
        config.easyPlace &= !config.replaceBlock;
        config.save();
        pending.clear();
        SlopmaticaActions.actionBar(Component.translatable(config.replaceBlock ? (wasEasyPlace ? "slopmatica.replace.on_easy_place_off" : "slopmatica.replace.on")
            : "slopmatica.replace.off").withColor(SlopmaticaActions.ACCENT));
    }

    /** MultiPlayerGameMode.destroyBlock, before: what is about to be broken. */
    public void beforeBreak(BlockState state) {
        breaking = state;
    }

    /**
     * MultiPlayerGameMode.destroyBlock, after it broke the block. Placed at the end of the tick,
     * not here: the packet saying it broke is only sent after this returns.
     */
    public void broke(BlockPos pos) {
        if (enabled() && breaking != null && !breaking.isAir()) {
            pending.add(new Broken(pos.immutable(), breaking, 0));
        }
        breaking = null;
    }

    /** End of each client tick. */
    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled() || player == null || level == null || mc.gameMode == null || player.isSpectator()) {
            pending.clear();
            return;
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof BlockItem) {
            item = offhand.getItem();
        } else if (!offhand.isEmpty()) {
            item = null; // Something else there now: the player chose to stop.
        }
        refill(mc, player);
        for (int n = pending.size(); n > 0; n--) {
            Broken b = pending.poll();
            if (!level.getBlockState(b.pos()).canBeReplaced()) {
                if (b.age() < WAIT_TICKS) {
                    pending.add(new Broken(b.pos(), b.state(), b.age() + 1));
                }
                continue;
            }
            if (!(player.getOffhandItem().getItem() instanceof BlockItem held) || b.state().is(held.getBlock())) {
                continue;
            }
            // The new block, turned the way the old one was where they share properties.
            BlockState want = held.getBlock().withPropertiesOf(b.state());
            EasyPlace.INSTANCE.placeFromOffhand(mc, player, level, b.pos(), want);
            refill(mc, player);
        }
    }

    /** An empty offhand gets the next stack of the remembered block from the inventory. */
    private void refill(Minecraft mc, LocalPlayer player) {
        if (item == null || !player.getOffhandItem().isEmpty()) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(item)) {
                // Hotbar slots are 36-44 in the inventory menu, the rest keep their index; button 40 is the offhand.
                int menuSlot = slot < Inventory.getSelectionSize() ? 36 + slot : slot;
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, Inventory.SLOT_OFFHAND, ContainerInput.SWAP, player);
                return;
            }
        }
    }

    /** How many of the block there are left, offhand and inventory together. */
    private static int left(LocalPlayer player, Item item) {
        int n = player.getOffhandItem().is(item) ? player.getOffhandItem().getCount() : 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(item)) {
                n += inventory.getItem(slot).getCount();
            }
        }
        return n;
    }

    /** HUD, bottom left while on: the block and how many are left. */
    public void extractHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!enabled() || player == null || mc.gui.screen() != null) {
            return;
        }
        Item shown = player.getOffhandItem().getItem() instanceof BlockItem held ? held : item;
        String text = shown == null ? Component.translatable("slopmatica.replace.hud_empty").getString()
            : player.hasInfiniteMaterials() ? Component.translatable("slopmatica.replace.hud_creative").getString()
            : Component.translatable("slopmatica.replace.hud", left(player, shown)).getString();
        int x = 4, y = g.guiHeight() - 24, w = 20 + mc.font.width(text) + 6;
        g.fill(x, y, x + w, y + 20, 0x90000000);
        if (shown != null) {
            g.item(new ItemStack(shown), x + 2, y + 2);
        }
        int color = shown != null && !player.hasInfiniteMaterials() && left(player, shown) == 0 ? 0xFFFF8080 : 0xFFE0E0E0;
        g.text(mc.font, text, x + 21, y + 6, color);
    }
}
