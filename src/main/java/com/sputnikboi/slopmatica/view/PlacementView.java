package com.sputnikboi.slopmatica.view;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.verify.SchematicRaycast;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Placement view: a free camera for positioning one placement. The player stays put while the
 * camera flies with the movement keys and looks with the mouse. Left click picks the placement up
 * so it follows the crosshair and puts it down again. Right click frees the cursor
 * ({@link PlacementViewScreen}): drag the build around and use the on-screen buttons. The view's own
 * keys rotate, mirror and nudge it (Shift: 5 blocks); the mouse wheel nudges it up and down.
 *
 * <p>Client thread only. The mixins in {@code mixin/view} ask {@link #active()} to hand the
 * camera, mouse and clicks over to this class and to freeze the player's own input.
 */
public final class PlacementView {
    public static final PlacementView INSTANCE = new PlacementView();

    private static final double SPEED = 0.6;
    private static final double SPRINT_SPEED = 2.0;
    private static final double REACH = 256;
    /** Shift is also "fly down"; don't sink while it is held to nudge 5 blocks at a time. */
    private static final long SHIFT_NUDGE_GRACE_MS = 600;
    /** Half the size of the camera's collision box: enough that the near plane never shows a wall's inside. */
    private static final double CAMERA_RADIUS = 0.25;

    /** The view is open; it may be on a build ({@link #placement}) or, without one, just for selecting. */
    private boolean undoWasDown;
    private boolean open;
    private @Nullable Placement placement;
    private double x, y, z, xo, yo, zo;
    private float yRot, xRot;
    /** Where the placement was when the view opened, for Reset. */
    private @Nullable BlockPos startOrigin;
    private @Nullable Rotation startRotation;
    private net.minecraft.world.level.block.@Nullable Mirror startMirror;
    private long lastShiftNudge;
    /** Health plus absorption last tick, to notice damage. */
    private float lastHealth;

    private Tool tool = Tool.MOVE;

    /** Where a carried build was picked up, for cancelling the move. */
    private @Nullable BlockPos carryStart;

    /** Origin minus the targeted block when picked up, so it doesn't jump to the crosshair. */
    private @Nullable BlockPos carryOffset;
    private double carryDistance;
    private @Nullable BlockPos lastTarget;

    private PlacementView() {
    }

    public boolean active() {
        return open;
    }

    /**
     * Whether the camera is detached right now. With freecam turned off in Options the view
     * keeps you in your body: you walk and look as usual, and only clicks and keys go to the view.
     */
    public boolean freecam() {
        return open && SlopmaticaConfig.get().viewFreecam;
    }

    public @Nullable Placement placement() {
        return placement;
    }

    public Tool tool() {
        return tool;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        if (tool != Tool.MOVE && carryOffset != null && placement != null) {
            putDown(placement);
        }
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.tool.now", tool.label()).withColor(SlopmaticaActions.ACCENT));
    }

    /** Switches the view to another placement, keeping the camera where it is. */
    public void switchTo(Placement target) {
        if (placement == null || target == placement) {
            return;
        }
        carryOffset = null;
        placement = target;
        PlacementManager.INSTANCE.select(target);
        startOrigin = target.transform().origin();
        startRotation = target.transform().rotation();
        startMirror = target.transform().mirror();
        SlopmaticaActions.actionBar(Component.literal("Placement view: " + target.name()).withColor(SlopmaticaActions.ACCENT));
    }

    /** Enters the view for the placement under the crosshair, or the active one; leaves it if already in. */
    public void toggle(Minecraft mc) {
        if (active()) {
            exit(mc);
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Placement target = placementAlong(mc, eye, mc.player.getViewVector(1.0F));
        if (target == null) {
            target = PlacementManager.INSTANCE.selected().filter(p -> p.visible() && p.inDimension(mc.level)).orElse(null);
        }
        enter(mc, target, false); // Without a build: a view for making a selection.
    }

    /** Opens the view on a placement (or none, to make a selection), optionally already carrying it (used by paste). */
    public void enter(Minecraft mc, @Nullable Placement target, boolean carrying) {
        if (mc.player == null) {
            return;
        }
        open = true;
        placement = target;
        Vec3 eye = mc.player.getEyePosition();
        x = xo = eye.x;
        y = yo = eye.y;
        z = zo = eye.z;
        yRot = mc.player.getYRot();
        xRot = mc.player.getXRot();
        lastHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        carryOffset = null;
        if (target == null) {
            tool = Tool.SELECT;
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.no_build").withColor(SlopmaticaActions.ACCENT));
            return;
        }
        PlacementManager.INSTANCE.select(target);
        startOrigin = target.transform().origin();
        startRotation = target.transform().rotation();
        startMirror = target.transform().mirror();
        carryOffset = null;
        if (carrying) {
            pickUp(mc, target);
        } else {
            SlopmaticaActions.actionBar(Component.literal("Placement view: " + target.name()).withColor(SlopmaticaActions.ACCENT));
        }
    }

    /** Leaving while carrying puts the placement down where it is. */
    public void exit(Minecraft mc) {
        if (!open) {
            return;
        }
        open = false;
        placement = null;
        carryOffset = null;
        if (!mc.isSameThread()) {
            // Disconnects arrive on the network thread; screens may only change on the client thread.
            mc.execute(() -> {
                if (mc.gui.screen() instanceof PlacementViewScreen) {
                    mc.gui.setScreen(null);
                }
            });
            return;
        }
        if (mc.gui.screen() instanceof PlacementViewScreen) {
            mc.gui.setScreen(null);
        }
        if (mc.player != null) {
            SlopmaticaActions.actionBar(Component.literal("Placement view closed").withColor(SlopmaticaActions.ACCENT));
        }
    }

    /** The nearest visible placement whose box the ray passes through. */
    private static @Nullable Placement placementAlong(Minecraft mc, Vec3 from, Vec3 direction) {
        Vec3 to = from.add(direction.scale(REACH));
        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (!p.visible() || mc.level == null || !p.inDimension(mc.level)) {
                continue;
            }
            AABB box = AABB.of(p.transform().worldBounds());
            double dist = box.contains(from) ? 0 : box.clip(from, to).map(from::distanceToSqr).orElse(Double.MAX_VALUE);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    /** Mouse look, called instead of turning the player. Same feel as {@code Entity.turn}. */
    public void turn(double dx, double dy) {
        yRot += (float) dx * 0.15F;
        xRot = Mth.clamp(xRot + (float) dy * 0.15F, -90.0F, 90.0F);
    }

    public Vec3 position(float partialTicks) {
        return new Vec3(Mth.lerp(partialTicks, xo, x), Mth.lerp(partialTicks, yo, y), Mth.lerp(partialTicks, zo, z));
    }

    /** Facing of the view: the free camera's, or with freecam off, the player's own. */
    public float yRot() {
        LocalPlayer player = Minecraft.getInstance().player;
        return !freecam() && player != null ? player.getYRot() : yRot;
    }

    public float xRot() {
        LocalPlayer player = Minecraft.getInstance().player;
        return !freecam() && player != null ? player.getXRot() : xRot;
    }

    public boolean carrying() {
        return carryOffset != null;
    }

    /** Runs at the start of the client tick, before vanilla handles the keys this view takes over. */
    public void tick(Minecraft mc) {
        if (!open) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            open = false;
            placement = null;
            return;
        }
        Placement p = placement;
        if (p != null && !PlacementManager.INSTANCE.placements().contains(p)) {
            placement = p = null; // Unloaded meanwhile: carry on without a build.
            carryOffset = null;
        }
        // Your body is standing defenceless somewhere: getting hurt brings you back to it.
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean hurt = health < lastHealth;
        lastHealth = health;
        if (hurt && SlopmaticaConfig.get().exitViewWhenHurt && mc.gameMode != null && mc.gameMode.getPlayerMode().isSurvival()) {
            exit(mc);
            SlopmaticaActions.actionBar(Component.literal("You took damage: placement view closed").withColor(SlopmaticaActions.ERROR));
            return;
        }
        if (mc.gui.screen() instanceof PauseScreen) {
            // Escape leaves the view rather than pausing.
            mc.gui.setScreen(null);
            exit(mc);
            return;
        }
        xo = x;
        yo = y;
        zo = z;
        if (mc.gui.screen() != null) {
            return;
        }
        if (freecam()) {
            fly(mc);
        } else {
            // No freecam: the view looks out of the player's eyes.
            Vec3 eye = mc.player.getEyePosition();
            x = xo = eye.x;
            y = yo = eye.y;
            z = zo = eye.z;
        }

        while (mc.options.keyAttack.consumeClick()) {
            switch (tool) {
                case MOVE -> {
                    if (p == null) {
                        SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.no_build").withColor(SlopmaticaActions.ERROR));
                    } else if (carryOffset == null) {
                        pickUp(mc, p);
                    } else {
                        putDown(p);
                    }
                }
                case SELECT -> {
                    BlockPos block = pointedBlock(mc, eye(), look());
                    if (block != null) {
                        UndoStack.INSTANCE.saveSelection();
                        Selection.INSTANCE.setCorner(block, mc.level.dimension().identifier().toString());
                    }
                }
                default -> SlopmaticaActions.actionBar(Component.translatable("slopmatica.tool.needs_cursor", tool.label()).withColor(SlopmaticaActions.ERROR));
            }
        }
        while (mc.options.keyUse.consumeClick()) {
            if (carryOffset != null && p != null) {
                cancelCarry(p); // Right click while moving a build: put it back where it was.
            } else {
                mc.gui.setScreen(new PlacementViewScreen());
            }
        }
        while (mc.options.keyPickItem.consumeClick()) {
            // Swallowed so the player doesn't pick blocks from where they stand.
        }
        if (carryOffset != null && p != null) {
            BlockPos target = target(mc);
            if (!target.equals(lastTarget)) {
                lastTarget = target;
                p.setOrigin(target.offset(carryOffset));
                PlacementManager.INSTANCE.changed();
            }
        }
    }

    /** Handles the view's own keys. Runs every tick so presses outside the view are drained, not queued. */
    public void handleKeys(Minecraft mc) {
        while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_TOOL)) {
            if (active()) {
                setTool(tool.next());
            }
        }
        while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_SAVE)) {
            if (active() && mc.gui.screen() == null) {
                mc.gui.setScreen(new com.sputnikboi.slopmatica.gui.SaveSelectionScreen(null));
            }
        }
        while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_ROTATE)) {
            rotate(mc, Rotation.CLOCKWISE_90);
        }
        // Ctrl+Z undoes whatever the mirror key is bound to (Ctrl and the letters also fly, so the raw keys are watched).
        boolean undoDown = mc.hasControlDown() && InputConstants.isKeyDown(InputConstants.KEY_Z);
        if (undoDown && !undoWasDown && active() && mc.gui.screen() == null) {
            undo(mc);
        }
        undoWasDown = undoDown;
        while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_MIRROR)) {
            if (!mc.hasControlDown()) {
                mirror(mc);
            }
        }
        for (int i = 0; i < SlopmaticaKeys.VIEW_NUDGES.length; i++) {
            while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_NUDGES[i])) {
                nudge(mc, i);
            }
        }
        while (SlopmaticaKeys.pressed(SlopmaticaKeys.VIEW_PASTE)) {
            build(mc);
        }
    }

    public void rotate(Minecraft mc, Rotation by) {
        Placement p = placement;
        if (p != null) {
            UndoStack.INSTANCE.saveBuild(p);
            SlopmaticaActions.rotate(p, by, SlopmaticaActions::actionBar);
            rebaseCarry(p);
        }
    }

    public void mirror(Minecraft mc) {
        Placement p = placement;
        if (p != null) {
            UndoStack.INSTANCE.saveBuild(p);
            SlopmaticaActions.setMirror(p, SlopmaticaActions.nextMirror(p.transform().mirror()), SlopmaticaActions::actionBar);
            rebaseCarry(p);
        }
    }

    /**
     * Nudges along {@code direction}: 0 forward, 1 back, 2 left, 3 right (relative to where the
     * camera looks), 4 up, 5 down. Five blocks with Shift held.
     */
    public void nudge(Minecraft mc, int direction) {
        Placement p = placement;
        if (p == null) {
            return;
        }
        int amount = 1;
        if (mc.hasShiftDown()) {
            amount = 5;
            lastShiftNudge = Util.getMillis();
        }
        Direction facing = Direction.fromYRot(yRot());
        Direction[] directions = {facing, facing.getOpposite(), facing.getCounterClockWise(), facing.getClockWise(), Direction.UP, Direction.DOWN};
        UndoStack.INSTANCE.saveBuild(p);
        SlopmaticaActions.nudge(p, directions[direction], amount, SlopmaticaActions::actionBar);
        rebaseCarry(p);
    }

    /** Mouse wheel: up or down. */
    public void scroll(Minecraft mc, double amount) {
        if (amount > 0) {
            nudge(mc, 4);
        } else if (amount < 0) {
            nudge(mc, 5);
        }
    }

    /** Back to where the placement was when the view opened. */
    public void reset(Minecraft mc) {
        Placement p = placement;
        if (p != null && startOrigin != null) {
            carryOffset = null;
            UndoStack.INSTANCE.saveBuild(p);
            p.setRotation(startRotation);
            p.setMirror(startMirror);
            SlopmaticaActions.moveTo(p, startOrigin, SlopmaticaActions::actionBar);
        }
    }

    /** Pastes the placement's blocks into the world (creative), then leaves the view. */
    public void build(Minecraft mc) {
        Placement p = placement;
        if (p != null && PlacementBuilder.INSTANCE.start(mc, p, SlopmaticaActions::actionBar)) {
            exit(mc);
        }
    }

    private void fly(Minecraft mc) {
        var o = mc.options;
        double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double strafe = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        boolean sinking = o.keyShift.isDown() && Util.getMillis() - lastShiftNudge > SHIFT_NUDGE_GRACE_MS;
        double up = (o.keyJump.isDown() ? 1 : 0) - (sinking ? 1 : 0);
        double len = Math.sqrt(forward * forward + strafe * strafe + up * up);
        if (len < 1.0E-4) {
            return;
        }
        double speed = (o.keySprint.isDown() ? SPRINT_SPEED : SPEED) / len;
        double sin = Mth.sin(yRot * Mth.DEG_TO_RAD), cos = Mth.cos(yRot * Mth.DEG_TO_RAD);
        Vec3 move = new Vec3((-sin * forward + cos * strafe) * speed, up * speed, (cos * forward + sin * strafe) * speed);
        if (SlopmaticaConfig.get().viewCollision && mc.level != null) {
            // A small box around the camera slides along blocks like the player does, so the view
            // can't end up inside walls. Ghost blocks aren't solid, so the build itself is no obstacle.
            AABB box = new AABB(x - CAMERA_RADIUS, y - CAMERA_RADIUS, z - CAMERA_RADIUS, x + CAMERA_RADIUS, y + CAMERA_RADIUS, z + CAMERA_RADIUS);
            move = Entity.collideBoundingBox(CollisionContext.empty(), move, box, mc.level, List.of());
        }
        x += move.x;
        y += move.y;
        z += move.z;
    }

    private Vec3 look() {
        float pitch = xRot() * Mth.DEG_TO_RAD, yaw = -yRot() * Mth.DEG_TO_RAD;
        float cp = Mth.cos(pitch);
        return new Vec3(Mth.sin(yaw) * cp, -Mth.sin(pitch), Mth.cos(yaw) * cp);
    }

    /**
     * The ray through a point on screen ({@code mx, my} in GUI pixels of a {@code w x h} screen),
     * for clicking on the build with a free cursor.
     */
    public Vec3 rayThrough(Minecraft mc, double mx, double my, int w, int h) {
        Camera camera = mc.gameRenderer.mainCamera();
        double tan = Math.tan(Math.toRadians(camera.getFov()) / 2);
        double nx = (2 * mx / w - 1) * tan * w / h;
        double ny = (1 - 2 * my / h) * tan;
        Vector3fc f = camera.forwardVector(), u = camera.upVector(), l = camera.leftVector();
        return new Vec3(f.x() - l.x() * nx + u.x() * ny, f.y() - l.y() * nx + u.y() * ny, f.z() - l.z() * nx + u.z() * ny).normalize();
    }

    public Vec3 eye() {
        return new Vec3(x, y, z);
    }

    /**
     * The block a ray points at: the nearer of a real block and a schematic block (ghosts count,
     * so you can select parts of the build that aren't built yet). Null if neither is in reach.
     */
    public @Nullable BlockPos pointedBlock(Minecraft mc, Vec3 from, Vec3 dir) {
        if (mc.level == null) {
            return null;
        }
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        double worldDist = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().distanceTo(from) : Double.MAX_VALUE;
        SchematicRaycast.Hit ghost = SchematicRaycast.trace(mc.level, SchematicRaycast.visibleTransforms(mc.level), from, dir, REACH);
        if (ghost != null && ghost.distance() <= worldDist) {
            return ghost.pos();
        }
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** The crop tool's boxes: the part that shows (orange) inside the whole build (grey). */
    public void submitToolBoxes(LevelRenderContext context) {
        Placement p = placement;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || tool != Tool.CROP) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth() * 2;
        Placement.Transform t = p.transform();
        BoundingBox shown = t.worldBounds();
        BoundingBox all = fullWorldBounds(p);
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lines(), (pose, buffer) -> {
            if (!all.equals(shown)) {
                Selection.box(buffer, pose, cam, all.minX(), all.minY(), all.minZ(), all.maxX() + 1, all.maxY() + 1, all.maxZ() + 1, 0.004, 0xFF808080, width / 2);
            }
            Selection.box(buffer, pose, cam, shown.minX(), shown.minY(), shown.minZ(), shown.maxX() + 1, shown.maxY() + 1, shown.maxZ() + 1, 0.008, 0xFFFFA020, width);
        });
    }

    /** The whole build's box in the world, ignoring any crop. */
    public static BoundingBox fullWorldBounds(Placement p) {
        BoundingBox b = p.schematic().bounds();
        Placement.Transform t = p.transform();
        return BoundingBox.fromCorners(t.toWorld(b.minX(), b.minY(), b.minZ()), t.toWorld(b.maxX(), b.maxY(), b.maxZ()));
    }

    /** The block (real or ghost) the free camera looks at, for keys that act on what you look at. */
    public @Nullable BlockPos cameraPointed(Minecraft mc) {
        return pointedBlock(mc, new Vec3(x, y, z), look());
    }

    /** Where something placed from the free camera goes: in front of the face it looks at. */
    public BlockPos cameraTarget(Minecraft mc) {
        return target(mc);
    }

    /** The block in front of the face under the crosshair, or a point at carrying distance in the air. */
    private BlockPos target(Minecraft mc) {
        Vec3 from = new Vec3(x, y, z);
        Vec3 dir = look();
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        return BlockPos.containing(from.add(dir.scale(carryDistance)));
    }

    private void pickUp(Minecraft mc, Placement p) {
        Vec3 from = new Vec3(x, y, z);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(look().scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        carryDistance = hit.getType() == HitResult.Type.BLOCK
            ? Math.max(4, hit.getLocation().distanceTo(from))
            : Math.max(8, AABB.of(p.transform().worldBounds()).getCenter().distanceTo(from));
        BlockPos target = target(mc);
        UndoStack.INSTANCE.saveBuild(p);
        carryStart = p.transform().origin();
        carryOffset = p.transform().origin().subtract(target);
        lastTarget = target;
        SlopmaticaActions.actionBar(Component.literal("Carrying " + p.name() + " (left click to put down)").withColor(SlopmaticaActions.ACCENT));
    }

    /** Stops carrying and puts the build back where it was picked up. */
    private void cancelCarry(Placement p) {
        carryOffset = null;
        if (carryStart != null) {
            p.setOrigin(carryStart);
            PlacementManager.INSTANCE.changed();
        }
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.move_cancelled", p.name()).withColor(SlopmaticaActions.ACCENT));
    }

    /** Undoes the last move, turn, crop or selection change. */
    public void undo(Minecraft mc) {
        carryOffset = null;
        String what = UndoStack.INSTANCE.undo();
        SlopmaticaActions.actionBar((what == null ? Component.translatable("slopmatica.view.undo_nothing")
            : Component.translatable("slopmatica.view.undone", what)).withColor(SlopmaticaActions.ACCENT));
    }

    /** Shrinks the selection to the blocks in it. */
    public void fitSelection(Minecraft mc) {
        if (mc.level == null) {
            return;
        }
        Selection.State before = Selection.INSTANCE.state();
        if (Selection.INSTANCE.fitToBlocks(mc.level)) {
            UndoStack.INSTANCE.saveSelectionState(before);
            var box = Selection.INSTANCE.box();
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.fitted", box.getXSpan(), box.getYSpan(), box.getZSpan()).withColor(SlopmaticaActions.ACCENT));
        } else {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.view.fit_nothing").withColor(SlopmaticaActions.ERROR));
        }
    }

    private void putDown(Placement p) {
        carryOffset = null;
        SlopmaticaActions.actionBar(Component.literal("Placed " + p.name() + " at " + p.transform().origin().toShortString()).withColor(SlopmaticaActions.ACCENT));
    }

    /** After a rotate or nudge while carrying, keep carrying from the new position. */
    private void rebaseCarry(Placement p) {
        if (carryOffset != null && lastTarget != null) {
            carryOffset = p.transform().origin().subtract(lastTarget);
        }
    }

    /** Controls reminder, top left, while flying (the cursor screen draws its own). */
    public void extractHud(GuiGraphicsExtractor graphics) {
        Placement p = placement;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) {
            return;
        }
        if (!open) {
            // Outside the view, a reminder that layer view is hiding part of every build.
            if (Layers.INSTANCE.mode() != Layers.Mode.ALL && !PlacementManager.INSTANCE.placements().isEmpty()) {
                drawPanel(graphics, mc, List.of(SlopmaticaKeys.layersMessage().getString()), 4, 4);
            }
            return;
        }
        if (p == null) {
            drawPanel(graphics, mc, List.of(
                "Build view: no build selected",
                "Tool: " + tool.label().getString() + " (" + key(SlopmaticaKeys.VIEW_TOOL) + " to change)",
                "Left click: set a selection corner   Right click: free cursor",
                key(SlopmaticaKeys.VIEW_SAVE) + ": save the selection   Ctrl+Z: undo",
                "Done: " + key(SlopmaticaKeys.PLACEMENT_VIEW) + " or Esc"
            ), 4, 4);
            return;
        }
        KeyMapping[] n = SlopmaticaKeys.VIEW_NUDGES;
        List<String> lines = new ArrayList<>();
        lines.add("Placement view: " + p.name() + (carryOffset != null ? "  (carrying)" : ""));
        int warnings = 0;
        for (Recommendations.Warning w : Recommendations.of(mc, p)) {
            lines.add("⚠ " + w.text().getString());
            warnings++;
        }
        lines.add("Fly: " + key(mc.options.keyUp) + key(mc.options.keyLeft) + key(mc.options.keyDown) + key(mc.options.keyRight)
            + ", " + key(mc.options.keyJump) + " / " + key(mc.options.keyShift) + ", " + key(mc.options.keySprint) + " faster");
        lines.add("Tool: " + tool.label().getString() + " (" + key(SlopmaticaKeys.VIEW_TOOL) + " to change)");
        lines.add(switch (tool) {
            case MOVE -> carryOffset == null ? "Left click: pick up   Right click: free cursor" : "Left click: put down   Right click: free cursor";
            case SELECT -> "Left click: set a corner   Right click: free cursor";
            default -> "Right click: free cursor, then drag a face of the box";
        });
        lines.add("Rotate: " + key(SlopmaticaKeys.VIEW_ROTATE) + "   Mirror: " + key(SlopmaticaKeys.VIEW_MIRROR) + " (" + SlopmaticaActions.mirrorLabel(p.transform().mirror()) + ")");
        lines.add("Nudge: " + key(n[0]) + " " + key(n[1]) + " " + key(n[2]) + " " + key(n[3]) + "   up/down: " + key(n[4]) + " " + key(n[5]) + " or scroll");
        lines.add("Hold Shift to nudge 5 blocks");
        lines.add("Done: " + key(SlopmaticaKeys.PLACEMENT_VIEW) + " or Esc");
        drawPanel(graphics, mc, lines, 4, 4, warnings);
    }

    static void drawPanel(GuiGraphicsExtractor graphics, Minecraft mc, List<String> lines, int x, int y) {
        drawPanel(graphics, mc, lines, x, y, 0);
    }

    /** {@code warnings}: how many lines after the first are warnings (drawn orange). */
    static void drawPanel(GuiGraphicsExtractor graphics, Minecraft mc, List<String> lines, int x, int y, int warnings) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        graphics.fill(x, y, x + 8 + width, y + 4 + lines.size() * 11, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? SlopmaticaActions.ACCENT : i <= warnings ? SlopmaticaActions.WARNING : 0xFFE0E0E0;
            graphics.text(mc.font, lines.get(i), x + 4, y + 3 + i * 11, color);
        }
    }

    static String key(KeyMapping mapping) {
        return mapping.isUnbound() ? "(unbound)" : mapping.getTranslatedKeyMessage().getString();
    }
}
