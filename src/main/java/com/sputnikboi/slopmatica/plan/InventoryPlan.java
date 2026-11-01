package com.sputnikboi.slopmatica.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Packs what a build still needs into what a player can carry: room left in partly filled stacks
 * already carried, free inventory slots, then shulker boxes (each one carried costs an inventory
 * slot and holds 27 stacks; more wait in the ender chest). Items are packed in the order given,
 * so with too little room the ones needed first make it and the rest is reported as not fitting.
 * Pure logic: items are any type, so this is tested without a game.
 */
public final class InventoryPlan {
    public static final int SHULKER_SLOTS = 27;

    public enum Where {
        /** On top of a stack you already carry. */
        TOP_UP,
        INVENTORY,
        /** In a shulker box you already carry that has room. */
        CARRIED_SHULKER,
        /** In a new shulker box in your inventory. */
        SHULKER,
        /** In a new shulker box in your ender chest. */
        ENDER_SHULKER
    }

    /** One stack to bring; {@code box} numbers shulker boxes from 0 within their kind. */
    public record Stack<T>(T item, int count, Where where, int box) {
    }

    /**
     * @param stacks          the stacks to bring, in packing order
     * @param overflow        what didn't fit, by item, in the order it was needed
     * @param shulkers        new shulker boxes carried in the inventory
     * @param enderShulkers   new shulker boxes in the ender chest
     * @param freeSlotsLeft   inventory slots still free afterwards
     */
    public record Result<T>(List<Stack<T>> stacks, Map<T, Long> overflow, int shulkers, int enderShulkers, int freeSlotsLeft) {
        public long total(T item) {
            return stacks.stream().filter(s -> s.item().equals(item)).mapToLong(Stack::count).sum();
        }
    }

    /**
     * @param need             items still needed and how many, in the order they are needed
     * @param maxStack         stack size of an item
     * @param topUpRoom        room left in stacks of that item already carried
     * @param freeSlots        empty inventory slots
     * @param carriedShulkerRoom empty slots in shulker boxes already carried (0 to not use them)
     * @param maxShulkers      new shulker boxes allowed in the inventory
     * @param maxEnderShulkers new shulker boxes allowed in the ender chest
     */
    public static <T> Result<T> plan(Map<T, Long> need, ToIntFunction<T> maxStack, Map<T, Integer> topUpRoom,
                                     int freeSlots, int carriedShulkerRoom, int maxShulkers, int maxEnderShulkers) {
        // First work out how many stacks are needed after topping up, to decide on shulker boxes.
        Map<T, Long> rest = new LinkedHashMap<>();
        List<Stack<T>> stacks = new ArrayList<>();
        for (Map.Entry<T, Long> e : need.entrySet()) {
            long left = e.getValue();
            int room = topUpRoom.getOrDefault(e.getKey(), 0);
            if (room > 0 && left > 0) {
                int put = (int) Math.min(room, left);
                stacks.add(new Stack<>(e.getKey(), put, Where.TOP_UP, 0));
                left -= put;
            }
            if (left > 0) {
                rest.put(e.getKey(), left);
            }
        }
        long stacksNeeded = 0;
        for (Map.Entry<T, Long> e : rest.entrySet()) {
            int size = Math.max(1, maxStack.applyAsInt(e.getKey()));
            stacksNeeded += (e.getValue() + size - 1) / size;
        }

        // Shulker boxes only once the free slots (and carried boxes) run out. Each new box in the
        // inventory turns one slot into 27, so it adds 26; ender chest boxes add a full 27.
        long shortBy = stacksNeeded - freeSlots - carriedShulkerRoom;
        int shulkers = 0, ender = 0;
        if (shortBy > 0) {
            shulkers = (int) Math.min(Math.min(maxShulkers, freeSlots), (shortBy + SHULKER_SLOTS - 2) / (SHULKER_SLOTS - 1));
            shortBy -= (long) shulkers * (SHULKER_SLOTS - 1);
            if (shortBy > 0) {
                ender = (int) Math.min(maxEnderShulkers, (shortBy + SHULKER_SLOTS - 1) / SHULKER_SLOTS);
            }
        }

        // Then pack, in order: free slots, carried boxes, new boxes, ender chest boxes.
        int slots = freeSlots - shulkers, carried = carriedShulkerRoom;
        int box = 0, boxUsed = 0, enderBox = 0, enderUsed = 0;
        Map<T, Long> overflow = new LinkedHashMap<>();
        for (Map.Entry<T, Long> e : rest.entrySet()) {
            T item = e.getKey();
            int size = Math.max(1, maxStack.applyAsInt(item));
            long left = e.getValue();
            while (left > 0) {
                int count = (int) Math.min(size, left);
                Stack<T> s;
                if (slots > 0) {
                    slots--;
                    s = new Stack<>(item, count, Where.INVENTORY, 0);
                } else if (carried > 0) {
                    carried--;
                    s = new Stack<>(item, count, Where.CARRIED_SHULKER, 0);
                } else if (box < shulkers) {
                    s = new Stack<>(item, count, Where.SHULKER, box);
                    if (++boxUsed == SHULKER_SLOTS) {
                        box++;
                        boxUsed = 0;
                    }
                } else if (enderBox < ender) {
                    s = new Stack<>(item, count, Where.ENDER_SHULKER, enderBox);
                    if (++enderUsed == SHULKER_SLOTS) {
                        enderBox++;
                        enderUsed = 0;
                    }
                } else {
                    overflow.merge(item, left, Long::sum);
                    break;
                }
                stacks.add(s);
                left -= count;
            }
        }
        return new Result<>(stacks, overflow, shulkers, ender, slots);
    }

    private InventoryPlan() {
    }
}
