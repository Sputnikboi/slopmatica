package com.sputnikboi.slopmatica.view;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Placement view: a free camera for positioning one placement. The player stays put while the
 * camera flies with the movement keys and looks with the mouse. Left click picks the placement up
 * so it follows the crosshair and puts it down again; right click while carrying puts it back
 * where it was. The view's own keys rotate, mirror and nudge it one block at a time.
 *
 * <p>Client thread only. The mixins in {@code mixin/view} ask {@link #active()} to hand the
 * camera, mouse and clicks over to this class and to freeze the player's own input.
 */
public final class PlacementView {
    public static final PlacementView INSTANCE = new PlacementView();

    private static final double SPEED = 0.6;
    private static final double SPRINT_SPEED = 2.0;
    private static final double REACH = 256;

    private @Nullable Placement placement;
    private double x, y, z, xo, yo, zo;
    private float yRot, xRot;

    /** Origin minus the targeted block when picked up, so it doesn't jump to the crosshair. */
    private @Nullable BlockPos carryOffset;
    private @Nullable BlockPos carryStart;
    private double carryDistance;
    private @Nullable BlockPos lastTarget;

    private PlacementView() {
    }

    public boolean active() {
        return placement != null;
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
        Placement target = placementAlong(eye, mc.player.getViewVector(1.0F));
        if (target == null) {
            target = PlacementManager.INSTANCE.selected().filter(Placement::visible).orElse(null);
        }
        if (target == null) {
            SlopmaticaActions.actionBar(Component.literal("Look at a placement first (or load one)").withColor(SlopmaticaActions.ERROR));
            return;
        }
        PlacementManager.INSTANCE.select(target);
        placement = target;
        x = xo = eye.x;
        y = yo = eye.y;
        z = zo = eye.z;
        yRot = mc.player.getYRot();
        xRot = mc.player.getXRot();
        carryOffset = null;
        carryStart = null;
        SlopmaticaActions.actionBar(Component.literal("Placement view: " + target.name()).withColor(SlopmaticaActions.ACCENT));
    }

    /** Leaving while carrying puts the placement down where it is. */
    public void exit(Minecraft mc) {
        if (placement == null) {
            return;
        }
        placement = null;
        carryOffset = null;
        carryStart = null;
        if (mc.player != null) {
            SlopmaticaActions.actionBar(Component.literal("Placement view closed").withColor(SlopmaticaActions.ACCENT));
        }
    }

    /** The nearest visible placement whose box the ray passes through. */
    private static @Nullable Placement placementAlong(Vec3 from, Vec3 direction) {
        Vec3 to = from.add(direction.scale(REACH));
        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (!p.visible()) {
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

    public float yRot() {
        return yRot;
    }

    public float xRot() {
        return xRot;
    }

    /** Runs at the start of the client tick, before vanilla handles the keys this view takes over. */
    public void tick(Minecraft mc) {
        Placement p = placement;
        if (p == null) {
            return;
        }
        if (mc.player == null || mc.level == null || !PlacementManager.INSTANCE.placements().contains(p)) {
            placement = null;
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
        fly(mc);

        Consumer<Component> feedback = SlopmaticaActions::actionBar;
        while (mc.options.keyAttack.consumeClick()) {
            if (carryOffset == null) {
                pickUp(mc, p);
            } else {
                carryOffset = null;
                carryStart = null;
                feedback.accept(Component.literal("Placed " + p.name() + " at " + p.transform().origin().toShortString()).withColor(SlopmaticaActions.ACCENT));
            }
        }
        while (mc.options.keyUse.consumeClick()) {
            if (carryOffset != null && carryStart != null) {
                SlopmaticaActions.moveTo(p, carryStart, msg -> { });
                carryOffset = null;
                carryStart = null;
                feedback.accept(Component.literal("Move cancelled").withColor(SlopmaticaActions.ACCENT));
            }
        }
        while (mc.options.keyPickItem.consumeClick()) {
            // Swallowed so the player doesn't pick blocks from where they stand.
        }
        if (carryOffset != null) {
            BlockPos target = target(mc);
            if (!target.equals(lastTarget)) {
                lastTarget = target;
                p.setOrigin(target.offset(carryOffset));
                PlacementManager.INSTANCE.changed();
            }
        }
    }

    /** Handles the view's own keys. Runs every tick so presses outside the view are drained, not queued. */
    public void handleKeys(Minecraft mc, KeyMapping rotate, KeyMapping mirror, KeyMapping[] nudges) {
        Placement p = placement;
        Consumer<Component> feedback = SlopmaticaActions::actionBar;
        while (rotate.consumeClick()) {
            if (p != null) {
                SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, feedback);
                rebaseCarry(mc, p);
            }
        }
        while (mirror.consumeClick()) {
            if (p != null) {
                SlopmaticaActions.setMirror(p, SlopmaticaActions.nextMirror(p.transform().mirror()), feedback);
                rebaseCarry(mc, p);
            }
        }
        Direction facing = Direction.fromYRot(yRot);
        Direction[] directions = {facing, facing.getOpposite(), facing.getCounterClockWise(), facing.getClockWise(), Direction.UP, Direction.DOWN};
        for (int i = 0; i < nudges.length; i++) {
            while (nudges[i].consumeClick()) {
                if (p != null) {
                    SlopmaticaActions.nudge(p, directions[i], 1, feedback);
                    rebaseCarry(mc, p);
                }
            }
        }
    }

    private void fly(Minecraft mc) {
        var o = mc.options;
        double forward = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double strafe = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double up = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
        double len = Math.sqrt(forward * forward + strafe * strafe + up * up);
        if (len < 1.0E-4) {
            return;
        }
        double speed = (o.keySprint.isDown() ? SPRINT_SPEED : SPEED) / len;
        double sin = Mth.sin(yRot * Mth.DEG_TO_RAD), cos = Mth.cos(yRot * Mth.DEG_TO_RAD);
        x += (-sin * forward + cos * strafe) * speed;
        z += (cos * forward + sin * strafe) * speed;
        y += up * speed;
    }

    private Vec3 look() {
        float pitch = xRot * Mth.DEG_TO_RAD, yaw = -yRot * Mth.DEG_TO_RAD;
        float cp = Mth.cos(pitch);
        return new Vec3(Mth.sin(yaw) * cp, -Mth.sin(pitch), Mth.cos(yaw) * cp);
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
        carryStart = p.transform().origin();
        carryOffset = carryStart.subtract(target);
        lastTarget = target;
        SlopmaticaActions.actionBar(Component.literal("Carrying " + p.name() + " (left click to place, right click to cancel)").withColor(SlopmaticaActions.ACCENT));
    }

    /** After a rotate or nudge while carrying, keep carrying from the new position. */
    private void rebaseCarry(Minecraft mc, Placement p) {
        if (carryOffset != null && lastTarget != null) {
            carryOffset = p.transform().origin().subtract(lastTarget);
        }
    }

    /** Controls reminder, top left, while the view is open. */
    public void extractHud(GuiGraphicsExtractor graphics, KeyMapping toggle, KeyMapping rotate, KeyMapping mirror, KeyMapping[] nudges) {
        Placement p = placement;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.gui.screen() != null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Placement view: " + p.name() + (carryOffset != null ? "  (carrying)" : ""));
        lines.add("Fly: " + key(mc.options.keyUp) + key(mc.options.keyLeft) + key(mc.options.keyDown) + key(mc.options.keyRight)
            + ", " + key(mc.options.keyJump) + " / " + key(mc.options.keyShift) + ", " + key(mc.options.keySprint) + " faster");
        lines.add(carryOffset == null ? "Left click: pick up" : "Left click: put down   Right click: cancel move");
        lines.add("Rotate: " + key(rotate) + "   Mirror: " + key(mirror) + " (" + SlopmaticaActions.mirrorLabel(p.transform().mirror()) + ")");
        lines.add("Nudge: " + key(nudges[0]) + " " + key(nudges[1]) + " " + key(nudges[2]) + " " + key(nudges[3]) + "   up/down: " + key(nudges[4]) + " " + key(nudges[5]));
        lines.add("Done: " + key(toggle) + " or Esc");
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        graphics.fill(4, 4, 12 + width, 8 + lines.size() * 11, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(mc.font, lines.get(i), 8, 7 + i * 11, i == 0 ? SlopmaticaActions.ACCENT : 0xFFE0E0E0);
        }
    }

    private static String key(KeyMapping mapping) {
        return mapping.isUnbound() ? "(unbound)" : mapping.getTranslatedKeyMessage().getString();
    }
}
