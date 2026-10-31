package com.sputnikboi.slopmatica.verify;

import com.sputnikboi.slopmatica.build.EasyPlace;
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

    private SchematicRaycast.@Nullable Hit target;

    private BlockInfoHud() {
    }

    /** Client tick: what the crosshair is on. */
    public void tick(Minecraft mc) {
        target = null;
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || HologramRenderer.INSTANCE.hidden() || !SlopmaticaConfig.get().blockInfo) {
            return;
        }
        List<Placement.Transform> transforms = SchematicRaycast.visibleTransforms(level);
        if (transforms.isEmpty()) {
            return;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        Vector3fc f = camera.forwardVector();
        target = SchematicRaycast.trace(level, transforms, camera.position(), new Vec3(f.x(), f.y(), f.z()), REACH);
    }

    public void extract(GuiGraphicsExtractor graphics) {
        SchematicRaycast.Hit t = target;
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
        if (t.status() == BlockStatus.MISSING && EasyPlace.INSTANCE.enabled()) {
            lines.add(Component.translatable("slopmatica.info.easy_place").withColor(BlockStatus.MATCH.color()));
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
