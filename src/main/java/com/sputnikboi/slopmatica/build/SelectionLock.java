package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Lock to Selection: while it's on and there is a selection here, you can only break, place and
 * use blocks inside it, so digging out an area marked for removal never takes a block too many.
 * Containers and tool stations, doors, trapdoors and fence gates still work anywhere, and redstone
 * controls (levers, buttons...) too unless {@code lockRedstone} is on.
 * Checked on the client before anything is sent to the server.
 */
public final class SelectionLock {
    private static long lastWarning;

    private SelectionLock() {
    }

    public static boolean enabled() {
        return SlopmaticaConfig.get().lockToSelection;
    }

    public static void toggle() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        c.lockToSelection = !c.lockToSelection;
        c.save();
        SlopmaticaActions.actionBar(Component.translatable(c.lockToSelection
            ? (active() ? "slopmatica.lock.on" : "slopmatica.lock.on_no_selection") : "slopmatica.lock.off").withColor(SlopmaticaActions.ACCENT));
    }

    /** On, with a selection in this dimension to keep to. */
    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return enabled() && mc.level != null && Selection.INSTANCE.box() != null
            && mc.level.dimension().identifier().toString().equals(Selection.INSTANCE.dimension());
    }

    /** Whether the lock stops this block from being touched (and says so, now and then). */
    public static boolean blocks(BlockPos pos) {
        if (!active() || Selection.INSTANCE.contains(pos.getX(), pos.getY(), pos.getZ())) {
            return false;
        }
        warn();
        return true;
    }

    /** Using an item on a block: a block being placed must land inside; anything else, the block clicked must be inside. */
    public static boolean blocksUse(InteractionHand hand, BlockHitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.player == null || mc.level == null) {
            return false;
        }
        BlockPos target = hit.getBlockPos();
        if (!mc.player.isSecondaryUseActive()) {
            net.minecraft.world.level.block.Block block = mc.level.getBlockState(target).getBlock();
            // Chests, furnaces, crafting tables, doors and gates: fine anywhere. Levers, buttons
            // and the like too, unless the redstone lock is on.
            if (opensMenu(mc, target) || passage(block) || redstone(block) && !SlopmaticaConfig.get().lockRedstone) {
                return false;
            }
        }
        if (mc.player.getItemInHand(hand).getItem() instanceof BlockItem && !mc.level.getBlockState(target).canBeReplaced()) {
            target = target.relative(hit.getDirection()); // Where the new block goes.
        }
        return blocks(target);
    }

    /** Doors, trapdoors and fence gates: opened and closed by hand. */
    private static boolean passage(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
            || b instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    /** Redstone controls a click changes: levers, buttons, repeaters, comparators, daylight detectors, note blocks. */
    private static boolean redstone(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.ButtonBlock
            || b instanceof net.minecraft.world.level.block.DiodeBlock || b instanceof net.minecraft.world.level.block.DaylightDetectorBlock
            || b instanceof net.minecraft.world.level.block.NoteBlock;
    }

    /** A container or tool station: a block that opens a menu when used. */
    private static boolean opensMenu(Minecraft mc, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        return state.getMenuProvider(mc.level, pos) != null
            || mc.level.getBlockEntity(pos) instanceof net.minecraft.world.Container
            || state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock;
    }

    private static void warn() {
        long now = Util.getMillis();
        if (now - lastWarning > 1500) {
            lastWarning = now;
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.lock.blocked").withColor(SlopmaticaActions.ERROR));
        }
    }
}
