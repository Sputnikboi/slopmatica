package com.sputnikboi.slopmatica.build;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.placement.PlacementStore;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the selection per world or server ({@code slopmatica/selection/}): saved a moment after it
 * changes and when you leave, put back when you join again. Leaving also clears it, so one world's
 * selection never shows up in another.
 */
public final class SelectionStore {
    public static final SelectionStore INSTANCE = new SelectionStore();

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Slopmatica");
    /** Ticks after the last change before saving (a drag changes it every frame). */
    private static final int DELAY = 40;

    private @Nullable Path file;
    private int saveIn = -1;

    private SelectionStore() {
    }

    /** The selection changed: save it soon. */
    public void changed() {
        if (file != null) {
            saveIn = DELAY;
        }
    }

    public void tick(Minecraft mc) {
        if (saveIn > 0 && --saveIn == 0) {
            save();
        }
    }

    public void onJoin(Minecraft mc) {
        file = PlacementStore.storeFile(mc, "selection");
        saveIn = -1;
        Selection sel = Selection.INSTANCE;
        sel.restore(new Selection.State(List.of(), null, null, null, List.of(), null, true));
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<SelectionShape.Part> parts = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("parts")) {
                JsonObject o = e.getAsJsonObject();
                SelectionShape shape = shape(o.getAsJsonObject("shape"));
                SelectionShape whole = o.has("whole") ? shape(o.getAsJsonObject("whole")) : null;
                parts.add(new SelectionShape.Part(shape, o.get("subtract").getAsBoolean(), whole));
            }
            sel.restore(new Selection.State(parts, pos(root, "first"), pos(root, "second"), null, List.of(),
                root.has("dimension") ? root.get("dimension").getAsString() : null, true));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the saved selection {}", file, e);
        }
        saveIn = -1; // Just loaded: nothing new to write.
    }

    public void onLeave() {
        if (saveIn > 0) {
            save();
        }
        saveIn = -1;
        file = null;
        Selection.INSTANCE.restore(new Selection.State(List.of(), null, null, null, List.of(), null, true));
    }

    private void save() {
        saveIn = -1;
        Path f = file;
        if (f == null) {
            return;
        }
        Selection.State state = Selection.INSTANCE.state();
        try {
            Files.createDirectories(f.getParent());
            if (state.parts().isEmpty() && state.first() == null) {
                Files.deleteIfExists(f);
                return;
            }
            JsonObject root = new JsonObject();
            if (state.dimension() != null) {
                root.addProperty("dimension", state.dimension());
            }
            putPos(root, "first", state.first());
            putPos(root, "second", state.second());
            JsonArray parts = new JsonArray();
            for (SelectionShape.Part part : state.parts()) {
                JsonObject o = new JsonObject();
                o.add("shape", json(part.shape()));
                o.addProperty("subtract", part.subtract());
                if (part.whole() != null && part.whole() != part.shape()) {
                    o.add("whole", json(part.whole()));
                }
                parts.add(o);
            }
            root.add("parts", parts);
            Files.writeString(f, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save the selection {}", f, e);
        }
    }

    // ---- Shapes as JSON ------------------------------------------------------------------------

    static JsonObject json(SelectionShape shape) {
        JsonObject o = new JsonObject();
        switch (shape) {
            case SelectionShape.Box b -> {
                o.addProperty("type", "box");
                o.add("bounds", box(b.box()));
            }
            case SelectionShape.Ball b -> {
                o.addProperty("type", "ball");
                o.add("center", pos(b.center()));
                o.addProperty("radius", b.radius());
            }
            case SelectionShape.Ellipsoid e -> {
                o.addProperty("type", "ellipsoid");
                o.add("bounds", box(e.bounds()));
            }
            case SelectionShape.Cylinder c -> {
                o.addProperty("type", "cylinder");
                o.add("center", pos(c.center()));
                o.addProperty("radius", c.radius());
                o.addProperty("minY", c.minY());
                o.addProperty("maxY", c.maxY());
            }
            case SelectionShape.Tube t -> {
                o.addProperty("type", "tube");
                o.add("bounds", box(t.bounds()));
            }
            case SelectionShape.Prism p -> {
                o.addProperty("type", "prism");
                JsonArray points = new JsonArray();
                p.points().forEach(pt -> points.add(pos(pt)));
                o.add("points", points);
                o.addProperty("minY", p.minY());
                o.addProperty("maxY", p.maxY());
                o.add("bounds", box(p.bounds()));
            }
            case SelectionShape.Cells c -> {
                o.addProperty("type", "cells");
                JsonArray cells = new JsonArray();
                for (LongIterator it = c.cells().iterator(); it.hasNext(); ) {
                    cells.add(it.nextLong());
                }
                o.add("cells", cells);
            }
            case SelectionShape.Stretched s -> {
                o.addProperty("type", "stretched");
                o.add("source", json(s.source()));
                o.add("bounds", box(s.bounds()));
            }
        }
        return o;
    }

    static SelectionShape shape(JsonObject o) {
        return switch (o.get("type").getAsString()) {
            case "box" -> new SelectionShape.Box(box(o.getAsJsonArray("bounds")));
            case "ball" -> new SelectionShape.Ball(pos(o.getAsJsonArray("center")), o.get("radius").getAsDouble());
            case "ellipsoid" -> new SelectionShape.Ellipsoid(box(o.getAsJsonArray("bounds")));
            case "cylinder" -> new SelectionShape.Cylinder(pos(o.getAsJsonArray("center")), o.get("radius").getAsDouble(),
                o.get("minY").getAsInt(), o.get("maxY").getAsInt());
            case "tube" -> new SelectionShape.Tube(box(o.getAsJsonArray("bounds")));
            case "prism" -> {
                List<BlockPos> points = new ArrayList<>();
                o.getAsJsonArray("points").forEach(e -> points.add(pos(e.getAsJsonArray())));
                yield new SelectionShape.Prism(List.copyOf(points), o.get("minY").getAsInt(), o.get("maxY").getAsInt(), box(o.getAsJsonArray("bounds")));
            }
            case "cells" -> {
                SelectionShape.Cells cells = new SelectionShape.Cells();
                for (JsonElement e : o.getAsJsonArray("cells")) {
                    long k = e.getAsLong();
                    cells.add(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
                }
                yield cells;
            }
            case "stretched" -> new SelectionShape.Stretched(shape(o.getAsJsonObject("source")), box(o.getAsJsonArray("bounds")));
            default -> throw new IllegalArgumentException("unknown selection shape " + o.get("type"));
        };
    }

    private static JsonArray box(BoundingBox b) {
        JsonArray a = new JsonArray();
        for (int v : new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) {
            a.add(v);
        }
        return a;
    }

    private static BoundingBox box(JsonArray a) {
        return new BoundingBox(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt());
    }

    private static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static void putPos(JsonObject o, String key, @Nullable BlockPos p) {
        if (p != null) {
            o.add(key, pos(p));
        }
    }

    private static @Nullable BlockPos pos(JsonObject o, String key) {
        return o.has(key) ? pos(o.getAsJsonArray(key)) : null;
    }
}
