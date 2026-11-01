package com.sputnikboi.slopmatica.plan;

import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.mixin.AbstractContainerScreenAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Shows the inventory plan where it's used. In your own inventory: ghost items in the empty slots
 * (hotbar first) for what to bring, ghost shulker boxes for the boxes to fill, "+N" on stacks to
 * top up, and what doesn't fit listed in red to the right; hold Shift over a ghost for details.
 * In chests and other containers: slots holding something the plan needs are outlined, blue for
 * the exact item and orange for a variant of it (the plain or stripped/waxed/aged form).
 */
public final class InventoryOverlay {
    private static final int EXACT = 0xFF3080FF;
    private static final int VARIANT = 0xFFFFA030;

    private InventoryOverlay() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> && !(screen instanceof CreativeModeInventoryScreen)) {
                ScreenEvents.afterExtract(screen).register(InventoryOverlay::draw);
                // The recalculate key works with an inventory open too (key mappings don't, normally).
                net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                    if (com.sputnikboi.slopmatica.SlopmaticaKeys.planKey().matches(event)) {
                        InventoryPlanner.INSTANCE.calculate(mc);
                    }
                });
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !clickSideList(mc, event.x(), event.y()));
            }
        });
    }

    private static void draw(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Minecraft mc = Minecraft.getInstance();
        InventoryPlanner.Plan plan = InventoryPlanner.INSTANCE.plan();
        if (plan == null || mc.player == null || !SlopmaticaConfig.get().planShowInInventory) {
            return;
        }
        AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
        AbstractContainerScreenAccessor pos = (AbstractContainerScreenAccessor) screen;
        int left = pos.slopmatica$leftPos(), top = pos.slopmatica$topPos();
        Inventory inventory = mc.player.getInventory();
        if (screen instanceof InventoryScreen) {
            drawOwnInventory(g, mc, container, inventory, plan, left, top, pos.slopmatica$imageWidth(), mouseX, mouseY);
        } else {
            highlightContainer(g, container, inventory, plan, left, top);
        }
    }

    private static void drawOwnInventory(GuiGraphicsExtractor g, Minecraft mc, AbstractContainerScreen<?> screen, Inventory inventory,
                                         InventoryPlanner.Plan plan, int left, int top, int imageWidth, int mouseX, int mouseY) {
        Font font = mc.font;
        List<Slot> empty = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE && slot.getItem().isEmpty()) {
                empty.add(slot);
            }
        }
        empty.sort(Comparator.comparingInt(Slot::getContainerSlot)); // Hotbar (0-8) first.

        InventoryPlan.Result<Item> r = plan.result();
        int next = 0;
        List<String> tooltip = null;
        for (InventoryPlan.Stack<Item> s : r.stacks()) {
            if (s.where() == InventoryPlan.Where.TOP_UP) {
                // "+N" on a stack of that item you already carry.
                for (Slot slot : screen.getMenu().slots) {
                    if (slot.container == inventory && slot.getItem().is(s.item()) && slot.getItem().getCount() < slot.getItem().getMaxStackSize()) {
                        String text = "+" + s.count();
                        g.text(font, text, left + slot.x + 17 - font.width(text), top + slot.y, 0xFF60E060);
                        break;
                    }
                }
            } else if (s.where() == InventoryPlan.Where.INVENTORY && next < empty.size()) {
                Slot slot = empty.get(next++);
                ghost(g, font, new ItemStack(s.item()), String.valueOf(s.count()), left + slot.x, top + slot.y);
                if (hovered(slot, left, top, mouseX, mouseY)) {
                    tooltip = List.of(Component.translatable("slopmatica.plan.ghost", s.count(), new ItemStack(s.item()).getHoverName()).getString());
                }
            }
        }
        for (int box = 0; box < r.shulkers() && next < empty.size(); box++) {
            Slot slot = empty.get(next++);
            ghost(g, font, new ItemStack(Items.SHULKER_BOX), "#" + (box + 1), left + slot.x, top + slot.y);
            if (hovered(slot, left, top, mouseX, mouseY)) {
                tooltip = boxContents(r, InventoryPlan.Where.SHULKER, box);
            }
        }
        if (tooltip != null && mc.hasShiftDown()) {
            g.setTooltipForNextFrame(font, tooltip.stream().map(t -> (Component) Component.literal(t)).toList(), Optional.empty(), mouseX, mouseY);
        }

        if (!SlopmaticaConfig.get().planSideList) {
            sideClose = sideRecalc = null;
            return;
        }
        // To the right: a header with recalculate and close, ender chest boxes, what doesn't fit
        // (in red), and tools worth bringing.
        int x = left + imageWidth + 6, y = top + 2;
        g.text(font, Component.translatable("slopmatica.plan.title").withStyle(ChatFormatting.BOLD), x, y, 0xFFE0E0E0);
        int hx = x + font.width(Component.translatable("slopmatica.plan.title").withStyle(ChatFormatting.BOLD)) + 6;
        sideRecalc = new int[] {hx, y - 1, hx + 10, y + 9};
        g.text(font, "↻", hx + 1, y, hovering(sideRecalc, mouseX, mouseY) ? 0xFFFFFFFF : 0xFF60C0FF);
        sideClose = new int[] {hx + 14, y - 1, hx + 24, y + 9};
        g.text(font, "×", hx + 15, y, hovering(sideClose, mouseX, mouseY) ? 0xFFFFFFFF : 0xFFE07070);
        if (hovering(sideRecalc, mouseX, mouseY)) {
            g.setTooltipForNextFrame(font, Component.translatable("slopmatica.plan.recalculate"), mouseX, mouseY);
        } else if (hovering(sideClose, mouseX, mouseY)) {
            g.setTooltipForNextFrame(font, Component.translatable("slopmatica.plan.side.tooltip"), mouseX, mouseY);
        }
        y += 14;
        if (r.enderShulkers() > 0) {
            g.text(font, Component.translatable("slopmatica.plan.ender", r.enderShulkers()), x, y, 0xFFE0E0E0);
            y += 12;
        }
        if (!r.overflow().isEmpty()) {
            g.text(font, Component.translatable("slopmatica.plan.overflow").withStyle(ChatFormatting.BOLD), x, y, 0xFFFF6060);
            y += 12;
            for (Map.Entry<Item, Long> e : r.overflow().entrySet()) {
                if (y > screenHeight(mc) - 18) {
                    g.text(font, "…", x, y, 0xFFFF6060);
                    break;
                }
                ItemStack stack = new ItemStack(e.getKey());
                g.item(stack, x, y);
                g.text(font, e.getValue() + " " + stack.getHoverName().getString(), x + 18, y + 4, 0xFFFF6060);
                y += 18;
            }
        }
        if (!plan.tools().isEmpty()) {
            y += 4;
            g.text(font, Component.translatable("slopmatica.plan.tools").withStyle(ChatFormatting.BOLD), x, y, 0xFFE0E0E0);
            y += 12;
            for (InventoryPlanner.Tool tool : plan.tools()) {
                g.item(new ItemStack(tool.icon()), x, y);
                Component label = tool.uses() > 0
                    ? Component.translatable("slopmatica.plan.tool.uses", tool.kind(), tool.uses())
                    : Component.translatable("slopmatica.plan.tool.variants", tool.kind());
                g.text(font, label, x + 18, y + 4, tool.carried() ? 0xFF60E060 : 0xFFE0E0E0);
                y += 18;
            }
        }
    }

    /** Hit areas of the side list's recalculate and close buttons, from the last frame. */
    private static int @org.jspecify.annotations.Nullable [] sideRecalc, sideClose;

    private static boolean hovering(int @org.jspecify.annotations.Nullable [] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
    }

    /** A click on the side list's buttons; true if it was one (so the screen doesn't also get it). */
    private static boolean clickSideList(Minecraft mc, double mx, double my) {
        if (InventoryPlanner.INSTANCE.plan() == null || !SlopmaticaConfig.get().planSideList) {
            return false;
        }
        if (hovering(sideRecalc, mx, my)) {
            InventoryPlanner.INSTANCE.calculate(mc);
            return true;
        }
        if (hovering(sideClose, mx, my)) {
            SlopmaticaConfig.get().planSideList = false;
            SlopmaticaConfig.get().save();
            sideClose = sideRecalc = null;
            return true;
        }
        return false;
    }

    private static int screenHeight(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight();
    }

    private static List<String> boxContents(InventoryPlan.Result<Item> r, InventoryPlan.Where where, int box) {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("slopmatica.plan.box", box + 1).getString());
        Map<Item, Long> inBox = new java.util.LinkedHashMap<>();
        for (InventoryPlan.Stack<Item> s : r.stacks()) {
            if (s.where() == where && s.box() == box) {
                inBox.merge(s.item(), (long) s.count(), Long::sum);
            }
        }
        inBox.forEach((item, n) -> lines.add("  " + n + " " + new ItemStack(item).getHoverName().getString()));
        return lines;
    }

    private static boolean hovered(Slot slot, int left, int top, int mouseX, int mouseY) {
        int x = left + slot.x, y = top + slot.y;
        return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
    }

    /** An item drawn washed out, so it reads as "goes here" rather than "is here". */
    private static void ghost(GuiGraphicsExtractor g, Font font, ItemStack stack, String label, int x, int y) {
        g.item(stack, x, y);
        g.fill(x, y, x + 16, y + 16, 0x908B8B8B);
        g.text(font, label, x + 17 - font.width(label), y + 9, 0xFFFFFFFF);
    }

    /** Still to bring of an item: the plan's count, minus what's on the cursor (already picked up). */
    private static long remaining(InventoryPlanner.Plan plan, Item item, ItemStack cursor) {
        long left = plan.stillToBring(item);
        return cursor.is(item) ? left - cursor.getCount() : left;
    }

    private static void highlightContainer(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, Inventory inventory,
                                           InventoryPlanner.Plan plan, int left, int top) {
        if (screen.getMenu() instanceof net.minecraft.world.inventory.CraftingMenu) {
            return; // A crafting table's grid isn't storage.
        }
        ItemStack cursor = screen.getMenu().getCarried();
        Set<String> families = new HashSet<>();
        plan.toBring().forEach((item, n) -> {
            if (remaining(plan, item, cursor) > 0) {
                families.add(InventoryPlanner.family(item));
            }
        });
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (slot.container == inventory || stack.isEmpty()) {
                continue;
            }
            int color = remaining(plan, stack.getItem(), cursor) > 0 ? EXACT : families.contains(InventoryPlanner.family(stack.getItem())) ? VARIANT : 0;
            if (color != 0) {
                int x = left + slot.x, y = top + slot.y;
                g.fill(x, y, x + 16, y + 16, (color & 0x00FFFFFF) | 0x40000000);
                g.fill(x - 1, y - 1, x + 17, y, color);
                g.fill(x - 1, y + 16, x + 17, y + 17, color);
                g.fill(x - 1, y, x, y + 16, color);
                g.fill(x + 16, y, x + 17, y + 16, color);
            }
        }
    }
}
