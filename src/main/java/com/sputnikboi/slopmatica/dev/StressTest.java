package com.sputnikboi.slopmatica.dev;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.schematic.PackedIntArray;
import com.sputnikboi.slopmatica.schematic.RawSchematic;
import com.sputnikboi.slopmatica.schematic.Schematic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Developer stress testing: synthetic builds of any size, many copies of a build, forced rebuilds,
 * and a frame-time overlay. Test placements are never saved with the world and can be cleared in
 * one go. To simulate a slower computer, see the notes in the roadmap (fewer mesh threads with
 * {@code -Dslopmatica.workers=1}, less memory, a capped frame rate).
 */
public final class StressTest {
    public static final StressTest INSTANCE = new StressTest();

    /** A mix of shapes the mesher has to work for: full cubes, see-through blocks, stairs, fences, plants. */
    private static final String[] BLOCKS = {
        "minecraft:stone", "minecraft:oak_planks", "minecraft:glass", "minecraft:oak_stairs", "minecraft:oak_fence",
        "minecraft:cobblestone_wall", "minecraft:oak_leaves", "minecraft:white_wool", "minecraft:glowstone", "minecraft:stone_slab",
        "minecraft:iron_bars", "minecraft:torch", "minecraft:short_grass", "minecraft:chest", "minecraft:redstone_wire",
        "minecraft:water", "minecraft:copper_block", "minecraft:bookshelf", "minecraft:lantern", "minecraft:oak_door",
    };
    private static final int FRAMES = 240;

    private final Set<Placement> testPlacements = Collections.newSetFromMap(new WeakHashMap<>());
    private final long[] frameNanos = new long[FRAMES];
    private int frame;
    private long lastFrame;

    private StressTest() {
    }

    /** Test placements are left out of the per-world save. */
    public boolean isTest(Placement p) {
        return testPlacements.contains(p);
    }

    /** A synthetic build at your feet: about 60% filled with a seeded mix of blocks. */
    public void spawn(Minecraft mc, int sx, int sy, int sz) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        long start = Util.getNanos();
        Schematic schematic = Schematic.fromRaw(synthetic(sx, sy, sz, 42), Path.of("slopmatica-test", sx + "x" + sy + "x" + sz + ".test"));
        BlockPos origin = mc.player.blockPosition().offset(2, 0, 2);
        Placement p = PlacementManager.INSTANCE.add(schematic, origin, mc.level.dimension().identifier().toString());
        testPlacements.add(p);
        SlopmaticaActions.actionBar(Component.literal(String.format(Locale.ROOT, "Test build %dx%dx%d (%,d blocks) made in %d ms",
            sx, sy, sz, schematic.blockCount(), (Util.getNanos() - start) / 1_000_000)).withColor(SlopmaticaActions.ACCENT));
    }

    public static RawSchematic synthetic(int sx, int sy, int sz, long seed) {
        List<CompoundTag> palette = new ArrayList<>();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        for (String id : BLOCKS) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", id);
            palette.add(tag);
        }
        long volume = (long) sx * sy * sz;
        PackedIntArray blocks = new PackedIntArray(PackedIntArray.bitsFor(palette.size()), volume);
        long state = seed;
        for (long i = 0; i < volume; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L; // LCG: fast and repeatable.
            int r = (int) (state >>> 33);
            int pick = Math.floorMod(r, 100);
            blocks.set(i, pick < 40 ? 0 : 1 + Math.floorMod(r >>> 8, BLOCKS.length));
        }
        RawSchematic.Region region = new RawSchematic.Region("test", 0, 0, 0, sx, sy, sz, palette, blocks, Map.of(), List.of());
        return new RawSchematic("Test " + sx + "x" + sy + "x" + sz, "Slopmatica", SharedConstants.getCurrentVersion().dataVersion().version(), List.of(region));
    }

    /** Copies of the selected build in a square grid beside it. */
    public void copies(Minecraft mc, int count) {
        Placement source = PlacementManager.INSTANCE.selected().orElse(null);
        if (source == null || mc.level == null) {
            SlopmaticaActions.actionBar(Component.literal("Select a build to copy first").withColor(SlopmaticaActions.ERROR));
            return;
        }
        BoundingBox b = source.transform().worldBounds();
        int side = (int) Math.ceil(Math.sqrt(count + 1));
        int stepX = b.getXSpan() + 2, stepZ = b.getZSpan() + 2;
        for (int i = 1; i <= count; i++) {
            BlockPos origin = source.transform().origin().offset((i % side) * stepX, 0, (i / side) * stepZ);
            Placement p = PlacementManager.INSTANCE.add(source.schematic(), origin, mc.level.dimension().identifier().toString());
            p.setRotation(source.transform().rotation());
            p.setMirror(source.transform().mirror());
            testPlacements.add(p);
        }
        PlacementManager.INSTANCE.select(source);
        PlacementManager.INSTANCE.changed();
        SlopmaticaActions.actionBar(Component.literal(count + " test copies of " + source.name()).withColor(SlopmaticaActions.ACCENT));
    }

    public void clear() {
        int n = 0;
        for (Placement p : List.copyOf(PlacementManager.INSTANCE.placements())) {
            if (testPlacements.remove(p)) {
                PlacementManager.INSTANCE.remove(p);
                n++;
            }
        }
        SlopmaticaActions.actionBar(Component.literal("Removed " + n + " test builds").withColor(SlopmaticaActions.ACCENT));
    }

    public void rebuild() {
        HologramRenderer.INSTANCE.rebuildAll();
        SlopmaticaActions.actionBar(Component.literal("Rebuilding every hologram section").withColor(SlopmaticaActions.ACCENT));
    }

    /** The slowest of the last {@code frames} HUD frames, in ms (dev harness). */
    public double slowestRecentMillis(int frames) {
        long max = 0;
        for (int i = 1; i <= Math.min(frames, Math.min(frame, FRAMES)); i++) {
            max = Math.max(max, frameNanos[(frame - i) % FRAMES]);
        }
        return max / 1.0E6;
    }

    /** Frame-time graph and hologram stats, bottom left. Times each HUD frame, so it costs almost nothing. */
    public void extractHud(GuiGraphicsExtractor g) {
        long now = Util.getNanos();
        if (lastFrame != 0) {
            frameNanos[frame++ % FRAMES] = now - lastFrame;
            com.sputnikboi.slopmatica.online.Online.frame(now - lastFrame);
        }
        lastFrame = now;
        if (!com.sputnikboi.slopmatica.config.SlopmaticaConfig.get().devOverlay) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int n = Math.min(frame, FRAMES);
        if (n == 0) {
            return;
        }
        long sum = 0, worst = 0;
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) {
            long t = frameNanos[i];
            sum += t;
            worst = Math.max(worst, t);
            sorted[i] = t;
        }
        java.util.Arrays.sort(sorted);
        double avgMs = sum / (double) n / 1e6, worstMs = worst / 1e6, p99Ms = sorted[Math.min(n - 1, (int) (n * 0.99))] / 1e6;

        int x = 4, h = 50, y = g.guiHeight() - h - 70;
        g.fill(x, y - 24, x + FRAMES + 4, y + h + 2, 0x90000000);
        g.text(mc.font, String.format(Locale.ROOT, "%.0f fps  avg %.1f ms  99%% %.1f ms  worst %.1f ms", 1000 / avgMs, avgMs, p99Ms, worstMs), x + 2, y - 22, 0xFFFFFFFF);
        g.text(mc.font, HologramRenderer.INSTANCE.stats(), x + 2, y - 12, 0xFFB0B0B0);
        // One bar per frame; the line marks 16.7 ms (60 fps), red bars are over 33 ms.
        for (int i = 0; i < n; i++) {
            long t = frameNanos[(frame - n + i) % FRAMES];
            int bar = (int) Math.min(h, t / 1e6 * h / 50.0);
            int color = t > 33_000_000 ? 0xFFFF5050 : t > 16_700_000 ? 0xFFFFD050 : 0xFF50E050;
            g.fill(x + 2 + i, y + h - bar, x + 3 + i, y + h, color);
        }
        int line = y + h - (int) (16.7 * h / 50.0);
        g.fill(x + 2, line, x + 2 + FRAMES, line + 1, 0x80FFFFFF);
    }
}
