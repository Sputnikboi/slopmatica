package com.sputnikboi.slopmatica.spawn;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Spawn-proofing check: finds where hostile mobs could spawn in the selection (or, without one,
 * the selected build's box) and marks each spot with an X on the floor. Uses vanilla's own rules
 * for ground spawns (a valid floor, two free blocks above it, so one-block gaps don't count) with
 * a zombie as the mob, and the dimension's block-light limit. Spots that are dark even in daylight
 * (no sky light) are purple; spots only dark enough at night are pink.
 *
 * <p>Runs on the client thread in slices of {@link #PER_TICK} positions, rescanning continuously
 * while on, so marks follow torches and building as you go.
 */
public final class SpawnCheck {
    public static final SpawnCheck INSTANCE = new SpawnCheck();

    private static final int PER_TICK = 40_000;
    private static final long MAX_VOLUME = 8_000_000;
    private static final int PAUSE_TICKS = 20;
    private static final int DRAW_DISTANCE = 96;
    private static final int MAX_MARKS = 40_000;
    private static final int ALWAYS_COLOR = 0xFFB040FF;
    private static final int NIGHT_COLOR = 0xFFFF80C0;

    private boolean active;
    private @Nullable BoundingBox area;
    private @Nullable String dimension;
    /** Finished results, shown; and the pass in progress. */
    private LongArrayList always = new LongArrayList(), night = new LongArrayList();
    private LongArrayList nextAlways = new LongArrayList(), nextNight = new LongArrayList();
    private long cursor;
    private int pause;
    private boolean scanned;

    private SpawnCheck() {
    }

    public boolean active() {
        return active;
    }

    public void toggle(Minecraft mc) {
        if (active) {
            stop();
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.spawn.off").withColor(SlopmaticaActions.ACCENT));
        } else {
            start(mc);
        }
    }

    public void start(Minecraft mc) {
        if (mc.level == null) {
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        BoundingBox box = dim.equals(Selection.INSTANCE.dimension()) ? Selection.INSTANCE.box() : null;
        if (box == null) {
            box = PlacementManager.INSTANCE.selected().filter(p -> p.inDimension(mc.level)).map(p -> p.transform().worldBounds()).orElse(null);
        }
        if (box == null) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.spawn.no_area").withColor(SlopmaticaActions.ERROR));
            return;
        }
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > MAX_VOLUME) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.spawn.too_big", volume, MAX_VOLUME).withColor(SlopmaticaActions.ERROR));
            return;
        }
        active = true;
        area = box;
        dimension = dim;
        always = new LongArrayList();
        night = new LongArrayList();
        restartPass();
        scanned = false;
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.spawn.on", box.getXSpan(), box.getYSpan(), box.getZSpan()).withColor(SlopmaticaActions.ACCENT));
    }

    public void stop() {
        active = false;
        area = null;
        always = new LongArrayList();
        night = new LongArrayList();
    }

    private void restartPass() {
        nextAlways = new LongArrayList();
        nextNight = new LongArrayList();
        cursor = 0;
    }

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        BoundingBox box = area;
        if (!active || box == null) {
            return;
        }
        if (level == null || !level.dimension().identifier().toString().equals(dimension)) {
            stop(); // Left the world or dimension: the marks would be meaningless.
            return;
        }
        if (pause > 0) {
            pause--;
            return;
        }
        int sx = box.getXSpan(), sz = box.getZSpan();
        long volume = (long) sx * box.getYSpan() * sz;
        int blockLimit = level.dimensionType().monsterSpawnBlockLightLimit();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long end = Math.min(volume, cursor + PER_TICK);
        for (; cursor < end; cursor++) {
            int x = (int) (cursor % sx), z = (int) (cursor / sx % sz), y = (int) (cursor / ((long) sx * sz));
            pos.set(box.minX() + x, box.minY() + y, box.minZ() + z);
            if (!level.isLoaded(pos) || !SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, EntityTypes.ZOMBIE)) {
                continue;
            }
            if (level.getBrightness(LightLayer.BLOCK, pos) > blockLimit) {
                continue;
            }
            (level.getBrightness(LightLayer.SKY, pos) == 0 ? nextAlways : nextNight).add(pos.asLong());
        }
        if (cursor >= volume) {
            always = nextAlways;
            night = nextNight;
            scanned = true;
            restartPass();
            pause = PAUSE_TICKS;
        }
    }

    /** Spots dark enough even in daylight (no sky light). */
    public int alwaysCount() {
        return always.size();
    }

    /** Spots dark enough only at night. */
    public int nightCount() {
        return night.size();
    }

    public void submit(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || (always.isEmpty() && night.isEmpty())) {
            return;
        }
        Vec3 cam = context.levelState().cameraRenderState.pos;
        float width = mc.getWindow().getAppropriateLineWidth() * 2;
        LongArrayList a = always, n = night;
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lines(), (pose, buffer) -> {
            int[] drawn = {0};
            for (LongArrayList list : List.of(a, n)) {
                int color = list == a ? ALWAYS_COLOR : NIGHT_COLOR;
                for (int i = 0; i < list.size() && drawn[0] < MAX_MARKS; i++) {
                    long p = list.getLong(i);
                    int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                    if (Math.abs(x - cam.x) > DRAW_DISTANCE || Math.abs(y - cam.y) > DRAW_DISTANCE || Math.abs(z - cam.z) > DRAW_DISTANCE) {
                        continue;
                    }
                    // An X lying on the floor of the spawn spot, slightly raised against z-fighting.
                    float fy = (float) (y + 0.02 - cam.y);
                    float x0 = (float) (x + 0.15 - cam.x), x1 = (float) (x + 0.85 - cam.x);
                    float z0 = (float) (z + 0.15 - cam.z), z1 = (float) (z + 0.85 - cam.z);
                    line(buffer, pose, x0, fy, z0, x1, fy, z1, color, width);
                    line(buffer, pose, x0, fy, z1, x1, fy, z0, color, width);
                    drawn[0]++;
                }
            }
        });
    }

    private static void line(com.mojang.blaze3d.vertex.VertexConsumer b, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
        float nx = bx - ax, ny = by - ay, nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        b.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        b.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    /** Summary, top left, while on (the placement view's own panel takes that corner while it is open). */
    public void extractHud(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        BoundingBox box = area;
        if (!active || box == null || mc.gui.screen() != null || com.sputnikboi.slopmatica.view.PlacementView.INSTANCE.active()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("slopmatica.spawn.title").getString());
        if (!scanned) {
            lines.add(Component.translatable("slopmatica.spawn.scanning").getString());
        } else if (always.isEmpty() && night.isEmpty()) {
            lines.add(Component.translatable("slopmatica.spawn.none").getString());
        } else {
            lines.add(Component.translatable("slopmatica.spawn.always", always.size()).getString());
            lines.add(Component.translatable("slopmatica.spawn.night", night.size()).getString());
        }
        lines.add(Component.translatable("slopmatica.spawn.area", box.getXSpan(), box.getYSpan(), box.getZSpan()).getString());
        int y = 4 + (com.sputnikboi.slopmatica.placement.Layers.INSTANCE.mode() != com.sputnikboi.slopmatica.placement.Layers.Mode.ALL ? 18 : 0);
        int w = 0;
        for (String line : lines) {
            w = Math.max(w, mc.font.width(line));
        }
        graphics.fill(4, y, 12 + w, y + 4 + lines.size() * 11, 0x90000000);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(mc.font, lines.get(i), 8, y + 3 + i * 11, i == 0 ? SlopmaticaActions.ACCENT : 0xFFE0E0E0);
        }
        if (scanned && (!always.isEmpty() || !night.isEmpty())) {
            // Colour keys in front of the two count lines.
            graphics.fill(8, y + 3 + 11 + 2, 13, y + 3 + 11 + 7, ALWAYS_COLOR);
            graphics.fill(8, y + 3 + 22 + 2, 13, y + 3 + 22 + 7, NIGHT_COLOR);
        }
    }
}
