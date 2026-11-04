package com.sputnikboi.slopmatica.schematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class StructureMarkersTest {

    private static CompoundTag state(String name, String... properties) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        if (properties.length > 0) {
            CompoundTag props = new CompoundTag();
            for (int i = 0; i < properties.length; i += 2) {
                props.putString(properties[i], properties[i + 1]);
            }
            tag.put("Properties", props);
        }
        return tag;
    }

    private static CompoundTag block(int x, int y, int z, int state, CompoundTag nbt) {
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        block.put("pos", pos);
        block.putInt("state", state);
        if (nbt != null) {
            block.put("nbt", nbt);
        }
        return block;
    }

    private static CompoundTag nbt(String... pairs) {
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < pairs.length; i += 2) {
            tag.putString(pairs[i], pairs[i + 1]);
        }
        return tag;
    }

    private static String nameAt(CompoundTag root, int index) {
        ListTag palette = root.getListOrEmpty("palette");
        int state = root.getListOrEmpty("blocks").getCompoundOrEmpty(index).getIntOr("state", -1);
        return palette.getCompoundOrEmpty(state).getStringOr("Name", "");
    }

    @Test
    void jigsawsBecomeTheirFinalStateAndMarkersBecomeChests() {
        ListTag palette = new ListTag();
        palette.add(state("minecraft:jigsaw", "orientation", "north_up"));
        palette.add(state("minecraft:structure_block", "mode", "data"));
        palette.add(state("minecraft:chest", "facing", "east"));
        palette.add(state("minecraft:stone"));
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0, nbt("final_state", "minecraft:oak_stairs[facing=east,half=top]")));
        blocks.add(block(1, 0, 0, 0, nbt("final_state", "minecraft:air")));
        blocks.add(block(2, 0, 0, 2, null));
        blocks.add(block(2, 1, 0, 1, nbt("mode", "DATA", "metadata", "chest"))); // igloo: loot for the chest below
        blocks.add(block(3, 0, 0, 1, nbt("mode", "DATA", "metadata", "ChestSouth"))); // mansion: a chest here
        blocks.add(block(4, 0, 0, 1, nbt("mode", "DATA", "metadata", "Mage")));
        blocks.add(block(5, 0, 0, 3, null));
        CompoundTag root = new CompoundTag();
        root.put("palette", palette);
        root.put("blocks", blocks);

        StructureNbtFormat.resolveMarkers(root, "schematics/.vanilla/Overworld/igloo/top.nbt");
        assertEquals("minecraft:oak_stairs", nameAt(root, 0));
        CompoundTag stairs = palette.getCompoundOrEmpty(root.getListOrEmpty("blocks").getCompoundOrEmpty(0).getIntOr("state", -1));
        assertEquals("top", stairs.getCompoundOrEmpty("Properties").getStringOr("half", ""));
        assertFalse(root.getListOrEmpty("blocks").getCompoundOrEmpty(0).contains("nbt"));
        assertEquals("minecraft:air", nameAt(root, 1));
        assertEquals("minecraft:air", nameAt(root, 3));
        assertEquals("minecraft:chests/igloo_chest", root.getListOrEmpty("blocks").getCompoundOrEmpty(2).getCompoundOrEmpty("nbt").getStringOr("LootTable", ""));
        assertEquals("minecraft:air", nameAt(root, 5));
        assertEquals("minecraft:stone", nameAt(root, 6));

        // The same marker in a mansion places a chest facing the way it says.
        CompoundTag mansion = new CompoundTag();
        ListTag p2 = new ListTag();
        p2.add(state("minecraft:structure_block", "mode", "data"));
        ListTag b2 = new ListTag();
        b2.add(block(0, 0, 0, 0, nbt("mode", "DATA", "metadata", "ChestSouth")));
        mansion.put("palette", p2);
        mansion.put("blocks", b2);
        StructureNbtFormat.resolveMarkers(mansion, "x/woodland_mansion/1x1_a1.nbt");
        assertEquals("minecraft:chest", nameAt(mansion, 0));
        assertEquals("south", p2.getCompoundOrEmpty(b2.getCompoundOrEmpty(0).getIntOr("state", -1)).getCompoundOrEmpty("Properties").getStringOr("facing", ""));
        assertEquals("minecraft:chests/woodland_mansion", b2.getCompoundOrEmpty(0).getCompoundOrEmpty("nbt").getStringOr("LootTable", ""));
    }
}
