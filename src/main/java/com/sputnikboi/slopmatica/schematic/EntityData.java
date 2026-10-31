package com.sputnikboi.slopmatica.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;

/**
 * An entity stored in a schematic (item frame, armor stand, painting, mob...), as vanilla structure
 * templates keep them: its position relative to the region, the block it belongs to (for hanging
 * entities, the block they hang on), and its full NBT including {@code id}.
 */
public record EntityData(Vec3 pos, BlockPos blockPos, CompoundTag nbt) {
    /** Hanging entities record the block they hang on; vanilla calls it {@code block_pos}. */
    public static final String BLOCK_POS = "block_pos";

    /** The NBT with {@code Pos} (and {@code block_pos}, if it has one) set to these relative positions. */
    public CompoundTag relativeNbt() {
        CompoundTag tag = nbt.copy();
        tag.put("Pos", doubles(pos.x, pos.y, pos.z));
        if (tag.contains(BLOCK_POS)) {
            tag.putIntArray(BLOCK_POS, new int[] {blockPos.getX(), blockPos.getY(), blockPos.getZ()});
        }
        tag.remove("UUID");
        return tag;
    }

    /** From NBT whose {@code Pos} (and {@code block_pos}) are relative already, as Litematica-style formats store it. */
    public static EntityData fromRelativeNbt(CompoundTag tag) {
        ListTag pos = tag.getListOrEmpty("Pos");
        Vec3 p = new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0));
        int[] bp = tag.getIntArray(BLOCK_POS).filter(a -> a.length == 3).orElse(null);
        return new EntityData(p, bp != null ? new BlockPos(bp[0], bp[1], bp[2]) : BlockPos.containing(p), tag);
    }

    public EntityData offset(int dx, int dy, int dz) {
        return new EntityData(pos.add(dx, dy, dz), blockPos.offset(dx, dy, dz), nbt);
    }

    public static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double v : values) {
            list.add(DoubleTag.valueOf(v));
        }
        return list;
    }
}
