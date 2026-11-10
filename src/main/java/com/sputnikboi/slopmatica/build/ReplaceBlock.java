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
 * left of the screen shows how many are left. Taking the block out of the offhand by hand forgets
 * it (no refill). One or the other with Easy Place.
 *
 * <p>Breaking a block of the offhand's own kind leaves it broken, so mistakes can be undone.
 */
public final class ReplaceBlock {
    public static final ReplaceBlock INSTANCE = new ReplaceBlock();

    /** Ticks a broken block waits to be air (the server may be slow to agree it broke) before it is given up on. */
    private static final int WAIT_TICKS = 10;
    /**
     * Ticks a just-placed block can't be broken, and for as long after as attack stays held: with
     * attack held, creative (and fast survival) breaking would otherwise break the new block a
     * moment later, and, being the offhand's kind, leave it broken.
     */
    private static final int PROTECT_TICKS = 10;
    /**
     * Ticks a placed block is watched: if the server takes it back (it hadn't finished breaking
     * the old block yet, which happens mining while falling, and breaks it a little later) it is
     * placed again, up to {@link #MAX_TRIES} times in all.
     */
    private static final int WATCH_TICKS = 40;
    private static final int MAX_TRIES = 3;
    /**
     * Ticks a block the player was mining counts as theirs if it disappears: the server can finish
     * breaking it before the client does (mining in the air, it disagrees about the speed), and
     * then the client never breaks it itself.
     */
    private static final int MINING_TICKS = 20;

    /** A block to replace: what was there, ticks waited for it to be gone, and placements tried before. */
    private record Broken(BlockPos pos, BlockState state, int age, int tries) {
    }

    /** A block it placed: what was broken there, and when. */
    private static final class Placed {
        final BlockState broken;
        final long at;
        final int tries;
        /** Can't be broken yet (see {@link #PROTECT_TICKS}). */
        boolean guarded = true;

        Placed(BlockState broken, long at, int tries) {
            this.broken = broken;
            this.at = at;
            this.tries = tries;
        }
    }

    /** A block being mined: what it is, and the last tick it was. */
    private record Mining(BlockState state, long at) {
    }

    private final Deque<Broken> pending = new ArrayDeque<>();
    private final java.util.Map<BlockPos, Placed> placed = new java.util.HashMap<>();
    private final java.util.Map<BlockPos, Mining> mining = new java.util.HashMap<>();
    private long ticks;
    /** The state at the position being broken, from just before the break (see the game mode mixin). */
    private @Nullable BlockState breaking;
    /** The block being placed: the offhand's, remembered so it can be refilled once it runs out. */
    private @Nullable Item item;
    /**
     * The offhand is empty because placing used up the last one: refilled (now, or once more of
     * the block turns up). Emptied any other way, the player took it out, and the block is forgotten.
     */
    private boolean ranOut;

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

    /** Whether breaking at pos is held off: a block just placed there (start/continueDestroyBlock do nothing). */
    public boolean protects(BlockPos pos) {
        Placed p = placed.isEmpty() || !enabled() ? null : placed.get(pos);
        return p != null && p.guarded;
    }

    /** start/continueDestroyBlock (when not protected): the player is mining the block at pos. */
    public void mining(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (enabled() && level != null && !level.getBlockState(pos).isAir()) {
            mining.put(pos.immutable(), new Mining(level.getBlockState(pos), ticks));
        }
    }

    /** MultiPlayerGameMode.destroyBlock, before: what is about to be broken. */
    public void beforeBreak(BlockState state) {
        breaking = state;
    }

    /**
     * MultiPlayerGameMode.destroyBlock, after it broke the block. Not placed here: the packet
     * saying it broke is only sent after this returns (see {@link #afterBreakSent}).
     */
    public void broke(BlockPos pos) {
        mining.remove(pos);
        placed.remove(pos); // broken on purpose (after its protection): not to be put back
        if (enabled() && breaking != null && !breaking.isAir()) {
            pending.add(new Broken(pos.immutable(), breaking, 0, 0));
        }
        breaking = null;
    }

    /** End of each client tick. */
    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled() || player == null || level == null || mc.gameMode == null || player.isSpectator()) {
            pending.clear();
            placed.clear();
            mining.clear();
            return;
        }
        ticks++;
        boolean attacking = mc.options.keyAttack.isDown();
        for (var it = placed.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Placed p = e.getValue();
            p.guarded &= attacking || ticks - p.at < PROTECT_TICKS;
            if (ticks - p.at >= WATCH_TICKS && !p.guarded) {
                it.remove();
            } else if (level.getBlockState(e.getKey()).canBeReplaced()) {
                // The server took it back: try again.
                it.remove();
                if (p.tries < MAX_TRIES) {
                    queue(e.getKey(), p.broken, p.tries);
                }
            }
        }
        for (var it = mining.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (ticks - e.getValue().at() > MINING_TICKS) {
                it.remove();
            } else if (level.getBlockState(e.getKey()).canBeReplaced()) {
                // Gone without the client breaking it: the server finished first.
                it.remove();
                queue(e.getKey(), e.getValue().state(), 0);
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof BlockItem) {
            item = offhand.getItem();
            ranOut = false;
        } else if (!offhand.isEmpty() || !ranOut) {
            // Something else there now, or the block taken out by hand: the player chose to stop.
            item = null;
            ranOut = false;
        }
        refill(mc, player);
        replacePending(mc, player, level, true);
    }

    /**
     * Right after start/continueDestroyBlock sent the break: replaced at once, before the player
     * moves this tick. A block broken under the player's feet is back before they start to fall
     * into its space (by the end of the tick they would be in the way of it, and it couldn't go).
     */
    public void afterBreakSent(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (enabled() && !pending.isEmpty() && player != null && mc.level != null && mc.gameMode != null && !player.isSpectator()) {
            replacePending(mc, player, mc.level, false);
        }
    }

    private void queue(BlockPos pos, BlockState state, int tries) {
        if (pending.stream().noneMatch(b -> b.pos().equals(pos))) {
            pending.add(new Broken(pos, state, 0, tries));
        }
    }

    /** Places what can be placed; the rest waits (and, once a tick, ages until given up on). */
    private void replacePending(Minecraft mc, LocalPlayer player, ClientLevel level, boolean age) {
        for (int n = pending.size(); n > 0; n--) {
            Broken b = pending.poll();
            if (!level.getBlockState(b.pos()).canBeReplaced()) {
                if (!age) {
                    pending.add(b);
                } else if (b.age() < WAIT_TICKS) {
                    pending.add(new Broken(b.pos(), b.state(), b.age() + 1, b.tries()));
                }
                continue;
            }
            if (!(player.getOffhandItem().getItem() instanceof BlockItem held) || b.state().is(held.getBlock())) {
                continue;
            }
            // The new block, turned the way the old one was where they share properties.
            BlockState want = held.getBlock().withPropertiesOf(b.state());
            if (EasyPlace.INSTANCE.placeFromOffhand(mc, player, level, b.pos(), want)) {
                placed.put(b.pos(), new Placed(b.state(), ticks, b.tries() + 1));
                ranOut |= player.getOffhandItem().isEmpty();
            }
            refill(mc, player);
        }
    }

    /** An offhand placing emptied gets the next stack of the remembered block from the inventory. */
    private void refill(Minecraft mc, LocalPlayer player) {
        if (item == null || !ranOut || !player.getOffhandItem().isEmpty()) {
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
