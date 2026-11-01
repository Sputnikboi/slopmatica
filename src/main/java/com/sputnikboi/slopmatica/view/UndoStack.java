package com.sputnikboi.slopmatica.view;

import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Undo for the build view: each move, turn, mirror or crop of a build, and each change to the
 * selection, saves how things were just before it. Undo puts the last one back.
 */
public final class UndoStack {
    public static final UndoStack INSTANCE = new UndoStack();

    private static final int MAX = 64;

    private sealed interface Step permits BuildStep, SelectionStep {
        String describe();

        /** Puts it back; false if what it refers to is gone. */
        boolean restore();
    }

    private record BuildStep(Placement placement, BlockPos origin, Rotation rotation, Mirror mirror, @Nullable BoundingBox crop) implements Step {
        @Override
        public String describe() {
            return placement.name();
        }

        @Override
        public boolean restore() {
            if (!PlacementManager.INSTANCE.placements().contains(placement)) {
                return false;
            }
            placement.setRotation(rotation);
            placement.setMirror(mirror);
            placement.setCrop(crop);
            placement.setOrigin(origin);
            PlacementManager.INSTANCE.changed();
            return true;
        }
    }

    private record SelectionStep(Selection.State state) implements Step {
        @Override
        public String describe() {
            return "selection";
        }

        @Override
        public boolean restore() {
            Selection.INSTANCE.restore(state);
            return true;
        }
    }

    private final Deque<Step> steps = new ArrayDeque<>();

    private UndoStack() {
    }

    /** Call just before changing a build. */
    public void saveBuild(@Nullable Placement p) {
        if (p != null) {
            Placement.Transform t = p.transform();
            push(new BuildStep(p, t.origin(), t.rotation(), t.mirror(), p.crop()));
        }
    }

    /** Call just before changing the selection. */
    public void saveSelection() {
        saveSelectionState(Selection.INSTANCE.state());
    }

    /** A selection as it was before a change already made. */
    public void saveSelectionState(Selection.State state) {
        push(new SelectionStep(state));
    }

    private void push(Step step) {
        steps.push(step);
        while (steps.size() > MAX) {
            steps.removeLast();
        }
    }

    /** Undoes the last step; returns what was undone, or null if there was nothing. */
    public @Nullable String undo() {
        while (!steps.isEmpty()) {
            Step step = steps.pop();
            if (step.restore()) {
                return step.describe();
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
