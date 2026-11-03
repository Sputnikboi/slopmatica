package com.sputnikboi.slopmatica.quick;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.QuickMenuEditScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The action wheel, the Quick Menu's other style: the same actions in a ring around the
 * crosshair, on as many pages as you like. While it is open the mouse moves a pointer instead of
 * the camera; left click runs the action it points at, right click (or scrolling) turns the page.
 * It closes on the Quick Menu key, after use (by option), and whenever a screen opens, so an
 * action that opens a menu always closes it.
 */
public final class ActionWheel {
    public static final ActionWheel INSTANCE = new ActionWheel();

    /** How far the pointer goes from the centre, and how far it must go to pick a slot (mouse units). */
    private static final double REACH = 40, DEADZONE = 10;
    /** Where the slots sit around the crosshair (GUI pixels). */
    private static final int RADIUS = 58, BOX = 22;

    private boolean open;
    private int page;
    private double px, py;

    private ActionWheel() {
    }

    public boolean isOpen() {
        return open;
    }

    public static List<List<String>> pages() {
        return SlopmaticaConfig.get().wheelPages;
    }

    public int page() {
        return Math.min(page, pages().size() - 1);
    }

    public void toggle(Minecraft mc) {
        if (open) {
            close();
        } else if (mc.player != null && mc.gui.screen() == null) {
            open = true;
            px = py = 0;
        }
    }

    public void close() {
        if (!open) {
            return;
        }
        open = false;
        if (SlopmaticaConfig.get().wheelResetPage) {
            page = 0;
        }
    }

    /** Mouse movement while open (instead of turning). */
    public void move(double dx, double dy) {
        px += dx;
        py += dy;
        double length = Math.sqrt(px * px + py * py);
        if (length > REACH) {
            px *= REACH / length;
            py *= REACH / length;
        }
    }

    public void turnPage(int by) {
        page = Math.floorMod(page() + by, pages().size());
    }

    /** The slot the pointer is on, clockwise from the top; -1 in the middle. */
    public int hovered() {
        if (px * px + py * py < DEADZONE * DEADZONE) {
            return -1;
        }
        double angle = Math.atan2(px, -py); // 0 at the top, clockwise
        double sector = 2 * Math.PI / QuickLayout.WHEEL_SLOTS;
        return Math.floorMod((int) Math.round(angle / sector), QuickLayout.WHEEL_SLOTS);
    }

    /** Start of the client tick, before vanilla handles clicks and the inventory key. */
    public void tick(Minecraft mc) {
        if (!open) {
            return;
        }
        if (mc.player == null || mc.gui.screen() != null) {
            close();
            return;
        }
        while (mc.options.keyInventory.consumeClick()) {
            close();
            mc.gui.setScreen(new QuickMenuEditScreen(null, true));
            return;
        }
        boolean next = false, run = false;
        while (mc.options.keyUse.consumeClick()) {
            next = true;
        }
        while (mc.options.keyAttack.consumeClick()) {
            run = true;
        }
        if (next) {
            turnPage(1);
        }
        if (run) {
            int slot = hovered();
            if (slot >= 0) {
                trigger(mc, pages().get(page()).get(slot));
            }
        }
    }

    private void trigger(Minecraft mc, String id) {
        QuickAction action = QuickAction.get(id);
        if (action == null) {
            return;
        }
        if (!action.availableNow(mc)) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.quick.unavailable", action.label().get()).withColor(SlopmaticaActions.ERROR));
            return;
        }
        action.run().accept(mc);
        if (SlopmaticaConfig.get().wheelCloseAfterUse || mc.gui.screen() != null) {
            close();
        }
    }

    /** Drawn over the HUD while open. */
    public void extract(GuiGraphicsExtractor g, Minecraft mc) {
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        List<String> slots = pages().get(page());
        int hovered = hovered();
        for (int i = 0; i < slots.size(); i++) {
            double angle = 2 * Math.PI * i / slots.size();
            int x = cx + (int) Math.round(Math.sin(angle) * RADIUS) - BOX / 2;
            int y = cy - (int) Math.round(Math.cos(angle) * RADIUS) - BOX / 2;
            QuickAction action = QuickAction.get(slots.get(i));
            boolean over = i == hovered;
            if (over) {
                g.fill(x - 2, y - 2, x + BOX + 2, y + BOX + 2, 0xFFFFFFFF);
            }
            g.fill(x, y, x + BOX, y + BOX, over ? 0xE03A6FD0 : 0xB0000000);
            if (action != null) {
                g.item(action.icon().get(), x + 3, y + 3);
                if (!action.availableNow(mc)) {
                    g.fill(x + 3, y + 3, x + 19, y + 19, 0xB0303030);
                }
                if (action.state() != null) {
                    g.fill(x + 15, y + 3, x + 19, y + 7, action.state().get() ? 0xFF50E050 : 0xFFE05050);
                }
            }
        }
        // The pointer, then the hovered action's name and the page under the wheel.
        int dx = cx + (int) Math.round(px / REACH * (RADIUS - BOX / 2)), dy = cy + (int) Math.round(py / REACH * (RADIUS - BOX / 2));
        g.fill(dx - 2, dy - 2, dx + 2, dy + 2, 0xFFFFFFFF);
        QuickAction current = hovered < 0 ? null : QuickAction.get(slots.get(hovered));
        Component name = current == null ? Component.translatable(hovered < 0 ? "slopmatica.wheel.point" : "slopmatica.quick.empty")
            : current.label().get().copy().withColor(current.availableNow(mc) ? 0xFFFFFFFF : 0xFFA0A0A0);
        Component hint = Component.translatable("slopmatica.wheel.hint", page() + 1, pages().size(), mc.options.keyInventory.getTranslatedKeyMessage());
        int ly = cy + RADIUS + BOX / 2 + 6;
        for (Component line : List.of(name, hint.copy().withColor(0xFFA0A0A0))) {
            int w = mc.font.width(line);
            g.fill(cx - w / 2 - 3, ly - 2, cx + w / 2 + 3, ly + 10, 0x90000000);
            g.centeredText(mc.font, line, cx, ly, 0xFFFFFFFF);
            ly += 13;
        }
    }
}
