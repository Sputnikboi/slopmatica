package com.sputnikboi.slopmatica.placement;

import com.sputnikboi.slopmatica.schematic.EntityData;
import com.sputnikboi.slopmatica.schematic.Schematic;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A schematic positioned in the world. Mutated only on the client thread; every change swaps in a
 * new immutable {@link Transform}, which is what mesh-building threads read.
 */
public final class Placement {
    private final Schematic schematic;
    private final String name;
    /** The dimension it was placed in, e.g. {@code minecraft:overworld}; shown only there. */
    private final String dimension;
    private volatile Transform transform;
    private boolean visible = true;
    /** Only this part of the schematic counts (schematic coordinates), or null for all of it. */
    private @Nullable BoundingBox crop;
    /** Materials to show; empty shows everything. Affects only what is drawn and aimed at. */
    private Set<Item> selectedMaterials = Set.of();
    /** Materials left out of the material panel's counts. */
    private Set<Item> ignoredMaterials = Set.of();
    /** The floating material panel: open in the placement view, pinned to stay on screen outside it, and where. */
    public boolean panelOpen, panelPinned;
    public int panelX = -1, panelY = -1;

    public Placement(Schematic schematic, String name, BlockPos origin) {
        this(schematic, name, origin, "minecraft:overworld");
    }

    public Placement(Schematic schematic, String name, BlockPos origin, String dimension) {
        this.schematic = schematic;
        this.name = name;
        this.dimension = dimension;
        this.transform = new Transform(schematic, origin, Rotation.NONE, Mirror.NONE, null, Layers.INSTANCE.minY(), Layers.INSTANCE.maxY(), Set.of());
    }

    public Schematic schematic() {
        return schematic;
    }

    public String name() {
        return name;
    }

    public String dimension() {
        return dimension;
    }

    public boolean inDimension(net.minecraft.world.level.Level level) {
        return dimension.equals(level.dimension().identifier().toString());
    }

    public Transform transform() {
        return transform;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setOrigin(BlockPos origin) {
        Transform t = transform;
        rebuild(origin.immutable(), t.rotation, t.mirror);
    }

    public void setRotation(Rotation rotation) {
        Transform t = transform;
        rebuild(t.origin, rotation, t.mirror);
    }

    public void setMirror(Mirror mirror) {
        Transform t = transform;
        rebuild(t.origin, t.rotation, mirror);
    }

    public @Nullable BoundingBox crop() {
        return crop;
    }

    /** Crops to a box in schematic coordinates (clamped to the schematic), or null to show all of it. */
    public void setCrop(@Nullable BoundingBox box) {
        BoundingBox b = schematic.bounds();
        if (box != null) {
            box = new BoundingBox(Math.max(box.minX(), b.minX()), Math.max(box.minY(), b.minY()), Math.max(box.minZ(), b.minZ()),
                Math.min(box.maxX(), b.maxX()), Math.min(box.maxY(), b.maxY()), Math.min(box.maxZ(), b.maxZ()));
            if (box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ() || box.equals(b)) {
                box = null; // Nothing left, or nothing cut: no crop.
            }
        }
        crop = box;
        refresh();
    }

    public Set<Item> selectedMaterials() {
        return selectedMaterials;
    }

    public void setSelectedMaterials(Set<Item> items) {
        selectedMaterials = Set.copyOf(items);
        refresh();
    }

    public Set<Item> ignoredMaterials() {
        return ignoredMaterials;
    }

    public void setIgnoredMaterials(Set<Item> items) {
        ignoredMaterials = Set.copyOf(items);
    }

    /** Rebuilds the transform after a crop, material or {@link Layers} change. */
    public void refresh() {
        Transform t = transform;
        rebuild(t.origin, t.rotation, t.mirror);
    }

    private void rebuild(BlockPos origin, Rotation rotation, Mirror mirror) {
        transform = new Transform(schematic, origin, rotation, mirror, crop, Layers.INSTANCE.minY(), Layers.INSTANCE.maxY(), selectedMaterials);
    }

    /** Toggles one material in a set, keeping insertion order. */
    public static Set<Item> toggled(Set<Item> set, Item item) {
        Set<Item> out = new LinkedHashSet<>(set);
        if (!out.remove(item)) {
            out.add(item);
        }
        return out;
    }

    public static final class Transform {
        private final BlockPos origin;
        private final Rotation rotation;
        private final Mirror mirror;
        private final List<Schematic.Region> regions;
        /** Each region's palette with this transform's mirror and rotation already applied. */
        private final BlockState[][] palettes;
        private final BoundingBox worldBounds;
        private final @Nullable BoundingBox crop;
        /** What is drawn: the layer range (world Y) and the selected materials (empty: all). */
        private final int shownMinY, shownMaxY;
        private final Set<Item> shownItems;

        Transform(Schematic schematic, BlockPos origin, Rotation rotation, Mirror mirror, @Nullable BoundingBox crop,
                  int shownMinY, int shownMaxY, Set<Item> shownItems) {
            this.origin = origin;
            this.rotation = rotation;
            this.mirror = mirror;
            this.crop = crop;
            this.shownMinY = shownMinY;
            this.shownMaxY = shownMaxY;
            this.shownItems = shownItems;
            this.regions = schematic.regions();
            this.palettes = new BlockState[regions.size()][];
            for (int i = 0; i < regions.size(); i++) {
                BlockState[] src = regions.get(i).palette();
                BlockState[] dst = new BlockState[src.length];
                for (int j = 0; j < src.length; j++) {
                    dst[j] = src[j].mirror(mirror).rotate(rotation);
                }
                palettes[i] = dst;
            }
            BoundingBox b = crop != null ? crop : schematic.bounds();
            BlockPos a = toWorld(b.minX(), b.minY(), b.minZ());
            BlockPos c = toWorld(b.maxX(), b.maxY(), b.maxZ());
            this.worldBounds = BoundingBox.fromCorners(a, c);
        }

        public BlockPos origin() {
            return origin;
        }

        public Rotation rotation() {
            return rotation;
        }

        public Mirror mirror() {
            return mirror;
        }

        public BoundingBox worldBounds() {
            return worldBounds;
        }

        /**
         * The block the schematic wants at a world position, or null when no region covers it.
         * Air inside a region is returned as air (an intentional gap, not "no opinion").
         */
        public @Nullable BlockState stateAt(int wx, int wy, int wz) {
            if (!worldBounds.isInside(wx, wy, wz)) {
                return null;
            }
            int dx = wx - origin.getX();
            int dz = wz - origin.getZ();
            int y = wy - origin.getY();
            // Undo the rotation, then the mirror (a mirror is its own inverse).
            int x, z;
            switch (rotation) {
                case CLOCKWISE_90 -> { x = dz; z = -dx; }
                case COUNTERCLOCKWISE_90 -> { x = -dz; z = dx; }
                case CLOCKWISE_180 -> { x = -dx; z = -dz; }
                default -> { x = dx; z = dz; }
            }
            switch (mirror) {
                case LEFT_RIGHT -> z = -z;
                case FRONT_BACK -> x = -x;
                default -> { }
            }
            if (crop != null && !crop.isInside(x, y, z)) {
                return null;
            }

            BlockState found = null;
            for (int i = 0; i < regions.size(); i++) {
                int id = regions.get(i).paletteIndexAt(x, y, z);
                if (id >= 0) {
                    BlockState state = palettes[i][id];
                    if (state.is(Blocks.STRUCTURE_VOID)) {
                        // Vanilla's "leave whatever is there" (unlisted .nbt positions).
                        continue;
                    }
                    if (!state.isAir()) {
                        return state;
                    }
                    found = state;
                }
            }
            return found;
        }

        /**
         * What is drawn and can be aimed at: {@link #stateAt}, minus blocks outside the layer range
         * and materials not selected (those have no opinion, so rays pass through them). Material
         * lists, pasting and Easy Place's inventory checks use the whole schematic instead.
         */
        public @Nullable BlockState shownStateAt(int wx, int wy, int wz) {
            if (wy < shownMinY || wy > shownMaxY) {
                return null;
            }
            BlockState state = stateAt(wx, wy, wz);
            if (state != null && !shownItems.isEmpty() && !state.isAir() && !shownItems.contains(state.getBlock().asItem())) {
                return null;
            }
            return state;
        }

        /** Block entity data the schematic has for a world position, or null. */
        public net.minecraft.nbt.@Nullable CompoundTag tileEntityAt(int wx, int wy, int wz) {
            if (!worldBounds.isInside(wx, wy, wz)) {
                return null;
            }
            BlockPos local = toLocal(wx, wy, wz);
            for (Schematic.Region region : regions) {
                var tag = region.tileEntityAt(local.getX(), local.getY(), local.getZ());
                if (tag != null) {
                    return tag;
                }
            }
            return null;
        }

        /**
         * The schematic's entities moved into the world: positions mirrored, rotated and offset
         * like the blocks (a point at the near edge of a block lands at the near edge of that
         * block's new place). Facing is not changed here; the entity does that itself.
         */
        public List<EntityData> worldEntities() {
            List<EntityData> out = new java.util.ArrayList<>();
            for (Schematic.Region region : regions) {
                for (EntityData e : region.entities()) {
                    double x = e.pos().x, z = e.pos().z;
                    switch (mirror) {
                        case LEFT_RIGHT -> z = 1 - z;
                        case FRONT_BACK -> x = 1 - x;
                        default -> { }
                    }
                    double rx, rz;
                    switch (rotation) {
                        case CLOCKWISE_90 -> { rx = 1 - z; rz = x; }
                        case COUNTERCLOCKWISE_90 -> { rx = z; rz = 1 - x; }
                        case CLOCKWISE_180 -> { rx = 1 - x; rz = 1 - z; }
                        default -> { rx = x; rz = z; }
                    }
                    Vec3 pos = new Vec3(origin.getX() + rx, origin.getY() + e.pos().y, origin.getZ() + rz);
                    BlockPos blockPos = toWorld(e.blockPos().getX(), e.blockPos().getY(), e.blockPos().getZ());
                    out.add(new EntityData(pos, blockPos, e.nbt()));
                }
            }
            return out;
        }

        /** World-space to schematic-space: the inverse of {@link #toWorld}. */
        public BlockPos toLocal(int wx, int wy, int wz) {
            int dx = wx - origin.getX();
            int dz = wz - origin.getZ();
            int x, z;
            switch (rotation) {
                case CLOCKWISE_90 -> { x = dz; z = -dx; }
                case COUNTERCLOCKWISE_90 -> { x = -dz; z = dx; }
                case CLOCKWISE_180 -> { x = -dx; z = -dz; }
                default -> { x = dx; z = dz; }
            }
            switch (mirror) {
                case LEFT_RIGHT -> z = -z;
                case FRONT_BACK -> x = -x;
                default -> { }
            }
            return new BlockPos(x, wy - origin.getY(), z);
        }

        /** Schematic-space to world-space; the same convention as vanilla structure templates. */
        public BlockPos toWorld(int x, int y, int z) {
            switch (mirror) {
                case LEFT_RIGHT -> z = -z;
                case FRONT_BACK -> x = -x;
                default -> { }
            }
            int rx, rz;
            switch (rotation) {
                case CLOCKWISE_90 -> { rx = -z; rz = x; }
                case COUNTERCLOCKWISE_90 -> { rx = z; rz = -x; }
                case CLOCKWISE_180 -> { rx = -x; rz = -z; }
                default -> { rx = x; rz = z; }
            }
            return new BlockPos(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
        }
    }
}
