package com.sputnikboi.slopmatica.verify;

import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Top-right panel naming the schematic block under the crosshair and its status. For yellow
 * (right block, wrong state) it lists each property that differs, as "placed → wanted"; for red
 * (wrong block) it names both blocks.
 *
 * <p>The target is found by walking the crosshair ray cell by cell, so it works on ghost blocks
 * that aren't really there, and through the placement view's camera as well as the player's eyes.
 */
public final class BlockInfoHud {
    public static final BlockInfoHud INSTANCE = new BlockInfoHud();

    private static final double REACH = 64;
    private static final int TEXT = 0xFFE0E0E0, DIM = 0xFFA0A0A0;

    private record Target(BlockPos pos, BlockState want, BlockState actual, BlockStatus status) {
    }

    private @Nullable Target target;

    private BlockInfoHud() {
    }

    /** Client tick: what the crosshair is on. */
    public void tick(Minecraft mc) {
        target = null;
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || HologramRenderer.INSTANCE.hidden() || !SlopmaticaConfig.get().blockInfo) {
            return;
        }
        List<Placement.Transform> transforms = new ArrayList<>();
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (p.visible() && p.inDimension(level)) {
                transforms.add(p.transform());
            }
        }
        if (transforms.isEmpty()) {
            return;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        Vector3fc f = camera.forwardVector();
        target = trace(level, transforms, camera.position(), new Vec3(f.x(), f.y(), f.z()));
    }

    /**
     * Steps through the cells along the ray (Amanatides and Woo) until one holds a schematic block,
     * or a real block with no schematic block, which hides anything behind it.
     */
    private static @Nullable Target trace(ClientLevel level, List<Placement.Transform> transforms, Vec3 from, Vec3 dir) {
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
        double tDeltaX = Math.abs(1 / dir.x), tDeltaY = Math.abs(1 / dir.y), tDeltaZ = Math.abs(1 / dir.z);
        // An axis the ray runs parallel to is never crossed (and 0 * infinity would be NaN).
        double tMaxX = dir.x == 0 ? Double.MAX_VALUE : (dir.x > 0 ? x + 1 - from.x : from.x - x) * tDeltaX;
        double tMaxY = dir.y == 0 ? Double.MAX_VALUE : (dir.y > 0 ? y + 1 - from.y : from.y - y) * tDeltaY;
        double tMaxZ = dir.z == 0 ? Double.MAX_VALUE : (dir.z > 0 ? z + 1 - from.z : from.z - z) * tDeltaZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double t = 0;
        while (t <= REACH) {
            pos.set(x, y, z);
            BlockState want = null;
            for (Placement.Transform transform : transforms) {
                BlockState s = transform.stateAt(x, y, z);
                if (s != null && !s.isAir()) {
                    want = s;
                    break;
                }
            }
            BlockState actual = level.getBlockState(pos);
            if (want != null) {
                return new Target(pos.immutable(), want, actual, BlockMatcher.compare(want, actual));
            }
            if (!actual.isAir() && actual.getFluidState().isEmpty()) {
                return null;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    public void extract(GuiGraphicsExtractor graphics) {
        Target t = target;
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.gui.screen() != null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(t.want().getBlock().getName());
        lines.add(Component.translatable("slopmatica.info." + t.status().name().toLowerCase(java.util.Locale.ROOT)).withColor(t.status().color()));
        switch (t.status()) {
            case VARIANT -> {
                if (t.actual().getBlock() != t.want().getBlock()) {
                    lines.add(Component.translatable("slopmatica.info.placed", t.actual().getBlock().getName()).withColor(TEXT));
                }
                for (Property<?> property : t.want().getProperties()) {
                    if (t.actual().hasProperty(property) && !t.actual().getValue(property).equals(t.want().getValue(property))) {
                        lines.add(Component.literal(property.getName() + ": " + valueName(t.actual(), property) + " → " + valueName(t.want(), property)).withColor(TEXT));
                    }
                }
            }
            case WRONG -> lines.add(Component.translatable("slopmatica.info.placed", t.actual().getBlock().getName()).withColor(TEXT));
            default -> { }
        }
        lines.add(Component.literal(t.pos().toShortString()).withColor(DIM));

        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        int icon = 18;
        int panelW = width + icon + 12;
        int x = graphics.guiWidth() - panelW - 4;
        int y = 4;
        graphics.fill(x, y, x + panelW, y + 6 + lines.size() * 11, 0x90000000);
        ItemStack stack = new ItemStack(t.want().getBlock().asItem());
        if (!stack.is(Items.AIR)) {
            graphics.item(stack, x + 4, y + 4);
        }
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(mc.font, lines.get(i), x + icon + 6, y + 4 + i * 11, i == 0 ? 0xFFFFFFFF : TEXT);
        }
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
