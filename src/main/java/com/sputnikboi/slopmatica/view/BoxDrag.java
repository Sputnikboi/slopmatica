package com.sputnikboi.slopmatica.view;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Dragging one face of a block box along its normal with the mouse: grab the face under the
 * cursor, then follow the point on the face's axis closest to the cursor's ray. Used to resize
 * the selection and to crop a build.
 */
public final class BoxDrag {
    private final BoundingBox start;
    private final Direction face;
    private final Vec3 anchor;

    private BoxDrag(BoundingBox start, Direction face, Vec3 anchor) {
        this.start = start;
        this.face = face;
        this.anchor = anchor;
    }

    /** Starts a drag if the ray hits the box, on the face it enters through (or leaves through, from inside). */
    public static @Nullable BoxDrag grab(BoundingBox box, Vec3 from, Vec3 dir) {
        AABB aabb = AABB.of(box);
        Vec3 far = from.add(dir.scale(1024));
        Vec3 hit;
        if (aabb.contains(from)) {
            // From inside: the face in front of you, found by tracing back from beyond it.
            hit = aabb.clip(far, from).orElse(null);
        } else {
            hit = aabb.clip(from, far).orElse(null);
        }
        if (hit == null) {
            return null;
        }
        return new BoxDrag(box, faceAt(aabb, hit), hit);
    }

    private static Direction faceAt(AABB box, Vec3 p) {
        double[] dist = {
            Math.abs(p.y - box.minY), Math.abs(p.y - box.maxY), Math.abs(p.z - box.minZ),
            Math.abs(p.z - box.maxZ), Math.abs(p.x - box.minX), Math.abs(p.x - box.maxX),
        };
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int best = 0;
        for (int i = 1; i < dist.length; i++) {
            if (dist[i] < dist[best]) {
                best = i;
            }
        }
        return faces[best];
    }

    public Direction face() {
        return face;
    }

    /** The box with the grabbed face moved to follow the ray; never thinner than one block. */
    public BoundingBox follow(Vec3 from, Vec3 dir) {
        Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
        // Closest point on the line anchor + s*n to the ray from + t*dir.
        Vec3 w = anchor.subtract(from);
        double b = n.dot(dir), d = n.dot(w), e = dir.dot(w);
        double denom = 1 - b * b;
        if (denom < 1.0E-4) {
            return start; // Looking straight along the axis: no way to tell how far.
        }
        double s = (b * e - d) / denom;
        int delta = (int) Math.round(s);
        int minX = start.minX(), minY = start.minY(), minZ = start.minZ(), maxX = start.maxX(), maxY = start.maxY(), maxZ = start.maxZ();
        switch (face) {
            case WEST -> minX = Math.min(minX - delta, maxX);
            case EAST -> maxX = Math.max(maxX + delta, minX);
            case DOWN -> minY = Math.min(minY - delta, maxY);
            case UP -> maxY = Math.max(maxY + delta, minY);
            case NORTH -> minZ = Math.min(minZ - delta, maxZ);
            case SOUTH -> maxZ = Math.max(maxZ + delta, minZ);
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
