package com.sputnikboi.slopmatica.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ModdedSchematicTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CompoundTag name(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", id);
        return tag;
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static CompoundTag entity(String id) {
        CompoundTag e = new CompoundTag();
        e.putString("id", id);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(0.5));
        pos.add(DoubleTag.valueOf(0));
        pos.add(DoubleTag.valueOf(0.5));
        e.put("Pos", pos);
        return e;
    }

    /** 4 x 1 x 1: stone, two shafts and a cogwheel from a mod that isn't installed; plus one unknown entity. */
    private static RawSchematic raw() {
        ListTag palette = new ListTag();
        palette.add(name("minecraft:air"));
        palette.add(name("minecraft:stone"));
        palette.add(name("create:shaft"));
        palette.add(name("create:cogwheel"));
        int[] ids = {1, 2, 2, 3};
        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(4, 1, 1));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", PackedIntArrayAccess.pack(ids, 2));
        ListTag entities = new ListTag();
        entities.add(entity("create:contraption"));
        entities.add(entity("minecraft:armor_stand"));
        region.put("Entities", entities);
        CompoundTag regions = new CompoundTag();
        regions.put("main", region);
        CompoundTag root = new CompoundTag();
        root.put("Regions", regions);
        try {
            return LitematicFormat.parse(root, "modded");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void missingBlocksAreLeftOutAndCounted() {
        Schematic s = Schematic.fromRaw(raw(), Path.of("modded.litematic"));
        assertEquals(Map.of("create:shaft", 2L, "create:cogwheel", 1L), s.missingBlocks());
        assertEquals(List.of("create:shaft", "create:cogwheel"), List.copyOf(s.missingBlocks().keySet()), "most common first");
        assertEquals(Map.of("create:contraption", 1), s.missingEntities());
        assertEquals(1, s.regions().get(0).entities().size(), "the armor stand stays");
        assertEquals(Set.of("create"), s.modNamespaces());
        assertTrue(s.modded());
        assertTrue(s.incomplete());
        assertEquals(1, s.blockCount(), "only the stone counts");
        // "No opinion", not air: the spot isn't demanded to be empty.
        int shaft = s.regions().get(0).paletteIndexAt(1, 0, 0);
        assertTrue(s.regions().get(0).palette()[shaft].is(Blocks.STRUCTURE_VOID));
    }

    @Test
    void modCheckReadsNamespacesWithoutResolvingBlocks() {
        assertEquals(Set.of("create"), ModCheck.namespaces(raw()));
    }

    @Test
    void vanillaOnlyIsNotModded() {
        Schematic s = Schematic.fromRaw(raw(), Path.of("x.litematic"));
        assertFalse(s.modNamespaces().contains("minecraft"));
    }
}
