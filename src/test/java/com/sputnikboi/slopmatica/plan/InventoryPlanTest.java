package com.sputnikboi.slopmatica.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryPlanTest {
    private static Map<String, Long> need(Object... pairs) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return m;
    }

    private static int stack(String item) {
        return item.equals("sign") ? 16 : 64;
    }

    @Test
    void fitsInFreeSlotsWithoutShulkers() {
        var r = InventoryPlan.plan(need("stone", 130, "sign", 20), InventoryPlanTest::stack, Map.of(), 10, 0, 27, 26);
        assertEquals(0, r.shulkers());
        assertEquals(5, r.stacks().size()); // 64+64+2 stone, 16+4 signs
        assertEquals(5, 10 - r.freeSlotsLeft());
        assertTrue(r.overflow().isEmpty());
        assertEquals(130, r.total("stone"));
    }

    @Test
    void topsUpCarriedStacksFirst() {
        var r = InventoryPlan.plan(need("stone", 70), InventoryPlanTest::stack, Map.of("stone", 54), 1, 0, 0, 0);
        assertEquals(InventoryPlan.Where.TOP_UP, r.stacks().get(0).where());
        assertEquals(54, r.stacks().get(0).count());
        assertEquals(16, r.stacks().get(1).count());
        assertTrue(r.overflow().isEmpty());
    }

    /** 40 stacks with 10 free slots: one box turns a slot into 27, so 9 + 27 = 36, a second makes 62. */
    @Test
    void usesTheFewestShulkerBoxes() {
        var r = InventoryPlan.plan(need("stone", 40L * 64), InventoryPlanTest::stack, Map.of(), 10, 0, 27, 26);
        assertEquals(2, r.shulkers());
        assertEquals(0, r.enderShulkers());
        assertTrue(r.overflow().isEmpty());
        assertEquals(8, r.stacks().stream().filter(s -> s.where() == InventoryPlan.Where.INVENTORY).count());
    }

    @Test
    void enderChestAfterTheInventoryIsFull() {
        // 3 free slots: at most 3 boxes in the inventory (81 stacks), then the ender chest.
        var r = InventoryPlan.plan(need("stone", 100L * 64), InventoryPlanTest::stack, Map.of(), 3, 0, 27, 26);
        assertEquals(3, r.shulkers());
        assertEquals(1, r.enderShulkers());
        assertTrue(r.overflow().isEmpty());
    }

    @Test
    void whatComesFirstFitsAndTheRestOverflows() {
        var r = InventoryPlan.plan(need("stone", 64 * 3, "dirt", 64), InventoryPlanTest::stack, Map.of(), 2, 0, 0, 0);
        assertEquals(128, r.total("stone"));
        assertEquals(Map.of("stone", 64L, "dirt", 64L), r.overflow());
    }

    @Test
    void carriedShulkerRoomIsUsedBeforeNewBoxes() {
        var r = InventoryPlan.plan(need("stone", 64 * 5), InventoryPlanTest::stack, Map.of(), 2, 3, 27, 26);
        assertEquals(0, r.shulkers());
        assertEquals(3, r.stacks().stream().filter(s -> s.where() == InventoryPlan.Where.CARRIED_SHULKER).count());
    }
}
