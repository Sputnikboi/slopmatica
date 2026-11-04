package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.mixin.BlockItemInvoker;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import net.minecraft.world.phys.shapes.CollisionContext;
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
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
 * <p>Angel placement (an option, always on in creative) also allows clicking the empty spot
 * itself, so blocks can be placed floating in mid-air; vanilla servers accept it, which is why
 * servers that allow Litematica do.
 *
 * <p>Blocks that are built up in steps (double slabs, more candles, sea pickles or snow layers)
 * are added to: a single slab where the schematic wants a double one gets its second half.
 *
 * <p>With Easy Place on, normal placements that would put a block where the schematic wants a
 * different one (or air), or outside every schematic, are refused, unless those options are off.
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
     *
     * <p>Only blocks on the crosshair line are placed: the line goes through ghost blocks (they
     * aren't really there) up to the first real block, and of the missing ones on it, the
     * furthest or nearest that can be placed right now is. Nothing off the line, nothing through
     * walls. A missing item doesn't stop it: the next block on the line is tried, and if none can
     * be placed the click goes on to vanilla (to open a chest, say), after showing why.
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
        List<SchematicRaycast.Hit> line = new java.util.ArrayList<>();
        for (SchematicRaycast.Hit hit : SchematicRaycast.traceAll(level, transforms, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange())) {
            if (hit.status() == BlockStatus.MISSING && hit.actual().canBeReplaced() || buildsUpTo(hit.actual(), hit.want())) {
                line.add(hit);
            }
        }
        if (SlopmaticaConfig.get().easyPlaceOrder == SlopmaticaConfig.EasyPlaceOrder.FURTHEST) {
            java.util.Collections.reverse(line);
        }
        Component firstProblem = null;
        if (DEBUG) {
            org.slf4j.LoggerFactory.getLogger("Slopmatica/easyplace").info("ray {} line {}", SchematicRaycast.traceAll(level, transforms, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange()), line);
        }
        for (SchematicRaycast.Hit hit : line) {
            Component problem = attempt(mc, player, level, hit.pos(), hit.want());
            if (DEBUG) {
                org.slf4j.LoggerFactory.getLogger("Slopmatica/easyplace").info("attempt {} -> {}", hit.pos(), problem == null ? "placed" : problem.getString());
            }
            if (problem == null) {
                holding = true;
                return true;
            }
            if (firstProblem == null && problem != NOT_AN_ITEM) {
                firstProblem = problem;
            }
        }
        if (firstProblem != null && !holding) {
            // Only for a fresh click: while right click is held, sweeping over ghosts that can't be
            // placed yet (nothing under them so far) is normal, not worth a warning each time.
            fail(firstProblem);
        }
        if (holding) {
            // Still holding right click after placing ghosts: the line is done, but a held click
            // must not fall through to vanilla and drop the held block wherever the crosshair is.
            return true;
        }
        return refuseWrongPlacement(mc, player, level, transforms);
    }

    private static final boolean DEBUG = Boolean.getBoolean("slopmatica.debug.easyplace");

    /** Right click has been held since Easy Place last placed something. */
    private boolean holding;

    /** Client tick: letting go of right click ends a held Easy Place run. */
    public void tick(Minecraft mc) {
        if (holding && !mc.options.keyUse.isDown()) {
            holding = false;
        }
    }

    private static final Component NOT_AN_ITEM = Component.empty();

    /** Places {@code want} at {@code pos}; null on success, otherwise why not. */
    private @Nullable Component attempt(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want) {
        // Two-block things are placed from their base: a bed from its foot, a door or tall plant
        // from its lower half; the item puts the other half in itself.
        if (want.hasProperty(BlockStateProperties.BED_PART) && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
            && want.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            pos = pos.relative(want.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
            want = want.setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
        } else if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
            want = want.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        }
        BlockState here = level.getBlockState(pos);
        boolean addTo = buildsUpTo(here, want);
        if (!addTo && !here.canBeReplaced()) {
            return Component.translatable("slopmatica.easy_place.occupied", want.getBlock().getName(), here.getBlock().getName());
        }
        if (!(want.getBlock().asItem() instanceof BlockItem item)) {
            return NOT_AN_ITEM; // Fluids, fire and the like: nothing to hold.
        }
        if (!player.hasInfiniteMaterials() && !has(player, item)) {
            return Component.translatable("slopmatica.easy_place.no_item", item.getName(new ItemStack(item)));
        }
        ItemStack stack = new ItemStack(item);
        boolean mayTurn = SlopmaticaConfig.get().easyPlaceRotate;
        // Without turning, only the way the player already faces counts.
        float[] yaws = mayTurn ? YAWS : new float[] {player.getYRot()};
        float[] pitches = mayTurn ? PITCHES : new float[] {player.getXRot()};
        Search search = bestCandidate(player, level, pos, want, addTo ? here : null, stack, yaws, pitches);
        Candidate best = search.best;
        if (best == null) {
            // Say what actually stopped it: you standing there, reach, or nothing to click.
            String why = search.obstructed ? "slopmatica.easy_place.in_the_way" : search.tooFar ? "slopmatica.easy_place.too_far" : "slopmatica.easy_place.no_support";
            return Component.translatable(why, want.getBlock().getName());
        }
        if (!mayTurn && best.result() != want) {
            // Say which way would work rather than placing it wrong. "Better", not "exact": some
            // properties (a stair's corner shape) only come from neighbours and never match yet.
            Candidate needed = bestCandidate(player, level, pos, want, addTo ? here : null, stack, YAWS, PITCHES).best;
            if (needed != null && needed.score() > best.score()) {
                String look = Direction.fromYRot(needed.yaw()).getName() + (needed.pitch() > 45 ? ", looking down" : needed.pitch() < -45 ? ", looking up" : "");
                return Component.translatable("slopmatica.easy_place.face", look);
            }
        }
        if (!holdItem(mc, player, item)) {
            return Component.translatable("slopmatica.easy_place.no_item", item.getName(new ItemStack(item)));
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
        return null;
    }

    private static boolean has(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /** Turns the player for one placement, here and on the server. */
    private static void look(Minecraft mc, LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }

    /** Properties that count up as more is placed into the same block (a slab's second half is its own case). */
    private static final List<net.minecraft.world.level.block.state.properties.IntegerProperty> COUNTS = List.of(
        BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.LAYERS, BlockStateProperties.EGGS,
        BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT);

    /**
     * Whether {@code have} becomes {@code want} by placing more of the same into it: a single slab
     * where a double one goes, fewer candles, pickles, layers, eggs or petals than wanted.
     */
    static boolean buildsUpTo(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock() || have == want) {
            return false;
        }
        boolean more = false;
        for (Property<?> property : want.getProperties()) {
            Comparable<?> a = have.getValue(property), b = want.getValue(property);
            if (a.equals(b)) {
                continue;
            }
            if (property == BlockStateProperties.SLAB_TYPE && b == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
                more = true;
            } else if (COUNTS.contains(property) && (Integer) a < (Integer) b) {
                more = true;
            } else if (property != BlockStateProperties.WATERLOGGED) {
                return false; // Something else differs too (facing, half...): adding won't fix it.
            }
        }
        return more;
    }

    /**
     * Tries every way to place {@code item} at {@code pos}: each solid neighbour to click, the
     * lower and upper part of its face (slabs, stairs, trapdoors), each way to face (stairs,
     * furnaces, pistons...). Exactly the wanted state wins; otherwise the closest match.
     * {@code addTo}, when set, is the block already there to place more into (see {@link #buildsUpTo}).
     */
    private static Search bestCandidate(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want, @Nullable BlockState addTo,
                                        ItemStack stack, float[] yaws, float[] pitches) {
        float savedYaw = player.getYRot(), savedPitch = player.getXRot();
        Search search = new Search(player, level, pos, want, addTo, stack, yaws, pitches);
        try {
            // A normal click on a neighbouring block's face, as a player would.
            for (Direction toNeighbour : Direction.values()) {
                BlockPos neighbour = pos.relative(toNeighbour);
                BlockState support = level.getBlockState(neighbour);
                if (support.isAir() || support.canBeReplaced() || support.getCollisionShape(level, neighbour).isEmpty() || interactable(support)) {
                    continue; // Clicking a door, lever or chest would use it instead of placing.
                }
                if (search.tryFace(neighbour, toNeighbour.getOpposite(), toNeighbour)) {
                    return search;
                }
            }
            // Click the spot itself, on any of its faces: a block being added to (the slab's own
            // top) or, with angel placement, the empty spot, which is placed into, so the block
            // appears right there, floating if need be. Creative always allows it.
            if (addTo != null || SlopmaticaConfig.get().easyPlaceAngel || player.getAbilities().instabuild) {
                for (Direction face : Direction.values()) {
                    if (search.tryFace(pos, face, face)) {
                        return search;
                    }
                }
            }
        } finally {
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
        }
        return search;
    }

    /** The best placement found so far while trying faces, points on them, and facings. */
    private static final class Search {
        final LocalPlayer player;
        final ClientLevel level;
        final BlockPos pos;
        final BlockState want;
        final @Nullable BlockState addTo;
        final ItemStack stack;
        final float[] yaws, pitches;
        @Nullable Candidate best;
        int bestScore = -1;
        /** Why nothing was found: the block would go into you (or a mob), or the face to click is out of reach. */
        boolean obstructed, tooFar;

        Search(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState want, @Nullable BlockState addTo, ItemStack stack, float[] yaws, float[] pitches) {
            this.player = player;
            this.level = level;
            this.pos = pos;
            this.want = want;
            this.addTo = addTo;
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
            // The server only accepts clicks on blocks within reach (creative reaches further).
            if (!player.isWithinBlockInteractionRange(clicked, 1.0)) {
                tooFar = true;
                return false;
            }
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
                        // As BlockItem does: no placing into the player or another entity.
                        if (!level.isUnobstructed(result, pos, CollisionContext.placementContext(player))) {
                            obstructed = true;
                            continue;
                        }
                        // Adding to a block must get closer to the wanted one, never undo it.
                        if (addTo != null && result != want && !buildsUpTo(result, want)) {
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

    /** Blocks a right click uses rather than places against: doors, levers, chests, anvils... */
    static boolean interactable(BlockState state) {
        var block = state.getBlock();
        return state.hasBlockEntity() || block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
            || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof net.minecraft.world.level.block.DiodeBlock
            || block instanceof net.minecraft.world.level.block.NoteBlock || block instanceof net.minecraft.world.level.block.BedBlock
            || block instanceof net.minecraft.world.level.block.CakeBlock || block instanceof net.minecraft.world.level.block.CandleCakeBlock
            || block instanceof net.minecraft.world.level.block.CraftingTableBlock || block instanceof net.minecraft.world.level.block.AnvilBlock
            || block instanceof net.minecraft.world.level.block.StonecutterBlock || block instanceof net.minecraft.world.level.block.GrindstoneBlock
            || block instanceof net.minecraft.world.level.block.LoomBlock || block instanceof net.minecraft.world.level.block.CartographyTableBlock
            || block instanceof net.minecraft.world.level.block.SmithingTableBlock || block instanceof net.minecraft.world.level.block.ComposterBlock
            || block instanceof net.minecraft.world.level.block.AbstractCauldronBlock || block instanceof net.minecraft.world.level.block.RespawnAnchorBlock
            || block instanceof net.minecraft.world.level.block.DragonEggBlock || block instanceof net.minecraft.world.level.block.FlowerPotBlock
            || block instanceof net.minecraft.world.level.block.SweetBerryBushBlock || block instanceof net.minecraft.world.level.block.DaylightDetectorBlock
            || block instanceof net.minecraft.world.level.block.BellBlock;
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
            // Creative: a fresh one, into an empty hotbar slot if there is one, so nothing in hand is lost.
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.setSelectedSlot(slot);
                    break;
                }
            }
            ItemStack stack = new ItemStack(item);
            inventory.setItem(inventory.getSelectedSlot(), stack);
            // Hotbar slots are 36-44 in the inventory menu.
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + inventory.getSelectedSlot());
            return true;
        }
        return false;
    }

    /**
     * A normal placement that would put a block where the schematic wants another one, or air
     * (Block Wrong Placements), or outside every schematic (Only Inside Schematics), is refused.
     * Clicks on blocks you can use (doors, levers, chests...) still go through.
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
        SlopmaticaConfig config = SlopmaticaConfig.get();
        BlockState want = SchematicRaycast.wantedAt(transforms, at.getX(), at.getY(), at.getZ());
        if (want == null) {
            // Outside what is shown. Inside a schematic's box but hidden by the layer view still
            // counts as inside: that is scaffolding for another layer, not a stray block.
            if (!config.easyPlaceInsideOnly || transforms.stream().anyMatch(t -> t.worldBounds().isInside(at))) {
                return false;
            }
            fail(Component.translatable("slopmatica.easy_place.refused_outside"));
            return true;
        }
        if (!config.easyPlaceRestrict || want.getBlock().asItem() == held.getItem()) {
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
