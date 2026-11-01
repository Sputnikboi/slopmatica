package com.sputnikboi.slopmatica.placement;

/**
 * Layer view, for every placement at once: show everything, a single layer, or everything at or
 * below / at or above a layer (world Y). Applied through {@link Placement.Transform#shownStateAt},
 * so the hologram, outlines, block info and Easy Place all follow it.
 */
public final class Layers {
    public static final Layers INSTANCE = new Layers();

    public enum Mode {
        ALL, SINGLE, BELOW, ABOVE;

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private Mode mode = Mode.ALL;
    private int layer;

    private Layers() {
    }

    public Mode mode() {
        return mode;
    }

    public int layer() {
        return layer;
    }

    public int minY() {
        return switch (mode) {
            case ALL, BELOW -> Integer.MIN_VALUE;
            case SINGLE, ABOVE -> layer;
        };
    }

    public int maxY() {
        return switch (mode) {
            case ALL, ABOVE -> Integer.MAX_VALUE;
            case SINGLE, BELOW -> layer;
        };
    }

    /**
     * Where a layer view should start for someone standing at {@code playerY}: that height, pulled
     * into the selected build's height range so the first thing shown isn't an empty layer.
     */
    public static int startHeight(int playerY) {
        return PlacementManager.INSTANCE.selected()
            .map(p -> Math.clamp(playerY, p.transform().worldBounds().minY(), p.transform().worldBounds().maxY()))
            .orElse(playerY);
    }

    /** Next mode; {@code here} is the layer to start from when leaving "all". */
    public void cycleMode(int here) {
        if (mode == Mode.ALL) {
            layer = here;
        }
        set(mode.next(), layer);
    }

    public void setMode(Mode mode, int here) {
        if (this.mode == Mode.ALL && mode != Mode.ALL) {
            layer = here;
        }
        set(mode, layer);
    }

    public void move(int by) {
        if (mode != Mode.ALL) {
            set(mode, layer + by);
        }
    }

    public void setLayer(int y) {
        set(mode, y);
    }

    private void set(Mode mode, int layer) {
        this.mode = mode;
        this.layer = layer;
        PlacementManager.INSTANCE.placements().forEach(Placement::refresh);
        PlacementManager.INSTANCE.changed();
    }
}
