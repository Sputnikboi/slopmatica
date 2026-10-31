package com.sputnikboi.slopmatica.placement;

import com.sputnikboi.slopmatica.schematic.Schematic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;

/**
 * The loaded placements and which one commands act on. Listeners hear about every change that
 * affects what the hologram shows, so the renderer can rebuild only when needed.
 */
public final class PlacementManager {
    public static final PlacementManager INSTANCE = new PlacementManager();

    private final List<Placement> placements = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private Placement selected;

    public List<Placement> placements() {
        return Collections.unmodifiableList(placements);
    }

    public Optional<Placement> selected() {
        return Optional.ofNullable(selected);
    }

    public Optional<Placement> byName(String name) {
        return placements.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    public Placement add(Schematic schematic, BlockPos origin) {
        Placement placement = new Placement(schematic, uniqueName(schematic.name()), origin);
        placements.add(placement);
        selected = placement;
        changed();
        return placement;
    }

    public void remove(Placement placement) {
        placements.remove(placement);
        if (selected == placement) {
            selected = placements.isEmpty() ? null : placements.getLast();
        }
        changed();
    }

    public void select(Placement placement) {
        selected = placement;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Call after mutating a placement (move, rotate, mirror, visibility). */
    public void changed() {
        listeners.forEach(Runnable::run);
    }

    private String uniqueName(String base) {
        String name = base;
        for (int i = 2; byName(name).isPresent(); i++) {
            name = base + " (" + i + ")";
        }
        return name;
    }
}
