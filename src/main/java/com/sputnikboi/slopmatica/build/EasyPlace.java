package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.mixin.BlockItemInvoker;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import com.sputnikboi.slopmatica.verify.SchematicRaycast;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Easy Place: right-clicking a missing ghost block places exactly that block there, in the
 * schematic's orientation, taking the item from the hotbar or inventory (or conjuring it in
 * creative). Holding right click keeps placing, like holding it normally does.
 *
 * <p>Orientation comes from simulating the placement client-side for every way it could be done
 * (each neighbouring block to click, upper or lower half of its face, each way to face) and using
 * the first that yields the wanted state. Facing is then sent to the server for that one click,
 * the way Litematica does it ("angle placement"), so no server-side mod is needed. With Turn to
 * Place off it never turns the player: only the current facing is tried, and when that can't give
 * the right state it says which way to look instead of placing the block wrong.
 *
 * <p>Angel placement (an option) also allows clicking the empty spot itself, so blocks can be placed
 * floating in mid-air; vanilla servers accept it, which is why servers that allow Litematica do.
 *
 * <p>With Easy Place on, normal placements that would put a block where the schematic wants a
 * different one (or air) are refused, unless that option is off.
 */
public final class EasyPlace {
    public static final EasyPlace INSTANCE = new EasyPlace();

    private static final float[] YAWS = {0, 90, 180, 270};
    private static final float[] PITCHES = {0, 75, -75};
    private static final double[] HALVES = {0.25, 0.75};

    /** One way to place the block: click this face at this point, facing this way. */
    private record Candidate(BlockHitResult hit, float yaw, float pitch, BlockState result, int score) {
    }

    private EasyPlace() {
    }

    public boolean enabled() {
        return SlopmaticaConfig.get().easyPlace;
    }

    public void toggle() {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        config.easyPlace = !config.easyPlace;
        config.save();
        SlopmaticaActions.actionBar(Component.literal(config.easyPlace ? "Easy Place on" : "Easy Place off").withColor(SlopmaticaActions.ACCENT));
    }

    /**
     * Called instead of vanilla's right click while Easy Place is on. True when it handled the
     * click (placed, or refused a wrong placement), so vanilla must not act on it too.
     */
    public boolean tryUse(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled() || player == null || level == null || mc.gameMode == null || player.isSpectator()) {
            return false;
        }
        List<Placement.Transform> transforms = SchematicRaycast.visibleTransforms(level);
        if (transforms.isEmpty()) {
            return false;
        }
        SchematicRaycast.Hit target = SchematicRaycast.trace(level, transforms, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange());
        if (target != null && target.status() == BlockStatus.MISSING && target.actual().canBeReplaced()) {
            return place(mc, player, level, target);
        }
        return SlopmaticaConfig.get().easyPlaceRestrict && refuseWrongPlacement(mc, player, level, transforms);
    }

    private boolean place(Minecraft mc, LocalPlayer player, ClientLevel level, SchematicRaycast.Hit target) {
        BlockState want = target.want();
        if (!(want.getBlock().asItem() instanceof BlockItem item)) {
            return false; // Fluids, fire and the like: nothing to hold.
        }
        ItemStack stack = new ItemStack(item);
        boolean mayTurn = SlopmaticaConfig.get().easyPlaceRotate;
        // Without turning, only the way the player already faces counts.
        float[] yaws = mayTurn ? YAWS : new float[] {player.getYRot()};
        float[] pitches = mayTurn ? PITCHES : new float[] {player.getXRot()};
        Candidate best = bestCandidate(player, level, target.pos(), want, stack, yaws, pitches);
        if (best == null) {
            fail(Component.translatable("slopmatica.easy_place.no_support", want.getBlock().getName()));
            return true;
        }
        if (!mayTurn && best.result() != want) {
            // Say which way would work rather than placing it wrong. "Better", not "exact": some
            // properties (a stair's corner shape) only come from neighbours and never match yet.
            Candidate needed = bestCandidate(player, level, target.pos(), want, stack, YAWS, PITCHES);
            if (needed != null && needed.score() > best.score()) {
                String look = Direction.fromYRot(needed.yaw()).getName() + (needed.pitch() > 45 ? ", looking down" : needed.pitch() < -45 ? ", looking up" : "");
                fail(Component.translatable("slopmatica.easy_place.face", look));
                return true;
            }
        }
        if (!holdItem(mc, player, item)) {
            fail(Component.translatable("slopmatica.easy_place.no_item", item.getName(new ItemStack(item))));
            return true;
        }

        float yaw = player.getYRot(), pitch = player.getXRot();
        boolean turn = best.yaw() != yaw || best.pitch() != pitch;
        if (turn) {
            look(mc, player, best.yaw(), best.pitch());
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, best.hit());
        if (turn) {
            look(mc, player, yaw, pitch);
        }
        // As vanilla's right click: swing the arm when the client predicted the placement.
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), false);
        }
        return true;
    }

    /** Turns the player for one placement, here and on the server. */
    private static void look(Minecraft mc, LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }

    /**
     * Tries every way to place {@code item} at {@code pos}: each solid neighbour to click, the
     * lower and upper part of its face (slabs, stairs, trapdoors), each way to face (stairs,
     * furnaces, pistons...). Exactly the wanted state wins; otherwise the closest match.
     */
    private static @Nullable Candidate bestCandidate(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want, ItemStack stack,
                                                     float[] yaws, float[] pitches) {
        float savedYaw = player.getYRot(), savedPitch = player.getXRot();
        Search search = new Search(player, pos, want, stack, yaws, pitches);
        try {
            // A normal click on a neighbouring block's face, as a player would.
            for (Direction toNeighbour : Direction.values()) {
                BlockPos neighbour = pos.relative(toNeighbour);
                BlockState support = level.getBlockState(neighbour);
                if (support.isAir() || support.canBeReplaced() || support.getCollisionShape(level, neighbour).isEmpty()) {
                    continue;
                }
                if (search.tryFace(neighbour, toNeighbour.getOpposite(), toNeighbour)) {
                    return search.best;
                }
            }
            // Angel placement: click the empty spot itself, on any of its faces. A replaceable
            // clicked block is placed into, so the block appears right there, floating if need be.
            if (SlopmaticaConfig.get().easyPlaceAngel) {
                for (Direction face : Direction.values()) {
                    if (search.tryFace(pos, face, face)) {
                        return search.best;
                    }
                }
            }
        } finally {
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
        }
        return search.best;
    }

    /** The best placement found so far while trying faces, points on them, and facings. */
    private static final class Search {
        final LocalPlayer player;
        final BlockPos pos;
        final BlockState want;
        final ItemStack stack;
        final float[] yaws, pitches;
        @Nullable Candidate best;
        int bestScore = -1;

        Search(LocalPlayer player, BlockPos pos, BlockState want, ItemStack stack, float[] yaws, float[] pitches) {
            this.player = player;
            this.pos = pos;
            this.want = want;
            this.stack = stack;
            this.yaws = yaws;
            this.pitches = pitches;
        }

        /**
         * Tries clicking {@code clicked} on {@code face}, at the side of {@code pos} toward
         * {@code side} (lower and upper half for sides, so slabs and stairs can go either way).
         * True once the exact wanted state is found.
         */
        boolean tryFace(BlockPos clicked, Direction face, Direction side) {
            for (double half : side.getAxis().isVertical() ? new double[] {0.5} : HALVES) {
                Vec3 hit = Vec3.atCenterOf(pos).relative(side, 0.5)
                    .with(Direction.Axis.Y, side.getAxis().isVertical() ? pos.getY() + (side == Direction.UP ? 1 : 0) : pos.getY() + half);
                BlockHitResult hitResult = new BlockHitResult(hit, face, clicked, false);
                for (float yaw : yaws) {
                    for (float pitch : pitches) {
                        player.setYRot(yaw);
                        player.setXRot(pitch);
                        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hitResult);
                        if (!context.getClickedPos().equals(pos) || !context.canPlace()) {
                            continue;
                        }
                        BlockState result = ((BlockItemInvoker) stack.getItem()).slopmatica$getPlacementState(context);
                        if (result == null) {
                            continue;
                        }
                        int score = score(result, want);
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Candidate(hitResult, yaw, pitch, result, score);
                            if (result == want) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }
    }

    /** How alike two states are: same block first, then the number of equal properties. */
    private static int score(BlockState result, BlockState want) {
        if (result.getBlock() != want.getBlock()) {
            return 0;
        }
        int score = 1;
        for (Property<?> property : want.getProperties()) {
            if (result.hasProperty(property) && result.getValue(property).equals(want.getValue(property))) {
                score++;
            }
        }
        return score;
    }

    /**
     * Puts {@code item} in the main hand: select it in the hotbar, swap it in from the inventory,
     * or in creative, take a fresh one. False if the player has none.
     */
    private static boolean holdItem(Minecraft mc, LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedItem().is(item)) {
            return true;
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                inventory.setSelectedSlot(slot);
                return true;
            }
        }
        for (int slot = Inventory.getSelectionSize(); slot < 36; slot++) {
            if (inventory.getItem(slot).is(item)) {
                // Main inventory slots keep their index in the player's inventory menu (9-35).
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, inventory.getSelectedSlot(), ContainerInput.SWAP, player);
                return true;
            }
        }
        if (player.hasInfiniteMaterials()) {
            ItemStack stack = new ItemStack(item);
            inventory.setItem(inventory.getSelectedSlot(), stack);
            // Hotbar slots are 36-44 in the inventory menu.
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + inventory.getSelectedSlot());
            return true;
        }
        return false;
    }

    /**
     * A normal placement that would put a block where the schematic wants another one, or air,
     * is refused. Clicks on blocks you can use (doors, levers, chests...) still go through.
     */
    private static boolean refuseWrongPlacement(Minecraft mc, LocalPlayer player, ClientLevel level, List<Placement.Transform> transforms) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem) || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockState clicked = level.getBlockState(hit.getBlockPos());
        if (!player.isSecondaryUseActive() && (clicked.hasBlockEntity() || clicked.getBlock() instanceof DoorBlock || clicked.getBlock() instanceof TrapDoorBlock
            || clicked.getBlock() instanceof FenceGateBlock || clicked.getBlock() instanceof ButtonBlock || clicked.getBlock() instanceof LeverBlock)) {
            return false;
        }
        BlockPos at = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, held, hit).getClickedPos();
        BlockState want = SchematicRaycast.wantedAt(transforms, at.getX(), at.getY(), at.getZ());
        if (want == null || want.getBlock().asItem() == held.getItem()) {
            return false;
        }
        fail(want.isAir()
            ? Component.translatable("slopmatica.easy_place.refused_air")
            : Component.translatable("slopmatica.easy_place.refused", want.getBlock().getName()));
        return true;
    }

    private static void fail(Component message) {
        SlopmaticaActions.actionBar(message.copy().withColor(SlopmaticaActions.ERROR));
    }
}
