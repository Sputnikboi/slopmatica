package com.sputnikboi.slopmatica.quick;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.QuickMenuEditScreen;
import com.sputnikboi.slopmatica.render.GuiShapes;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The action wheel, the Quick Menu's other style: the same actions in a ring around the
 * crosshair, on as many pages as you like. The actions on a page share the ring evenly, however
 * many there are. While it is open the mouse moves a pointer instead of the camera; left click
 * runs the action it points at (in the empty middle: back a page), right click turns the page.
 * It closes on the Quick Menu key (or letting go of it, when held to use), after use (by option),
 * and whenever a screen opens, so an action that opens a menu always closes it.
 */
public final class ActionWheel {
    public static final ActionWheel INSTANCE = new ActionWheel();

    /** How far the pointer goes from the centre (mouse units); it reaches the middle of the ring. */
    private static final double REACH = 40;
    /** The wheel in GUI pixels at 100% size: outer edge, the empty middle (no action), and where icons sit. */
    private static final int OUTER = 80, HOLE = 17, ICONS = (OUTER + HOLE) / 2 + 2;
    /** Mouse units inside which the pointer is in the empty middle. */
    private static final double DEADZONE = REACH * HOLE / ICONS;
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
            // Held to use, letting go closes it; key repeat while held must not.
            if (SlopmaticaConfig.get().quickMode != QuickMenu.Mode.RELEASE) {
                close();
            }
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

    /** The page's slots that hold an action, in order: these share the ring, empty slots take no room. */
    private List<Integer> active() {
        List<String> slots = pages().get(page());
        List<Integer> active = new java.util.ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (QuickAction.get(slots.get(i)) != null) {
                active.add(i);
            }
        }
        return active;
    }

    /** The wedge the pointer is on (an index into {@link #active}), clockwise from the top; -1 in the middle. */
    private int hoveredWedge(int wedges) {
        if (wedges == 0 || px * px + py * py < DEADZONE * DEADZONE) {
            return -1;
        }
        double angle = Math.atan2(px, -py); // 0 at the top, clockwise
        return Math.floorMod((int) Math.round(angle / (2 * Math.PI / wedges)), wedges);
    }

    /** Puts the pointer somewhere (the dev harness, for screenshots). */
    public void point(double x, double y) {
        px = x;
        py = y;
    }

    /** The page slot the pointer is on; -1 in the middle. */
    public int hovered() {
        List<Integer> active = active();
        int wedge = hoveredWedge(active.size());
        return wedge < 0 ? -1 : active.get(wedge);
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
        if (SlopmaticaConfig.get().quickMode == QuickMenu.Mode.RELEASE && !com.sputnikboi.slopmatica.SlopmaticaKeys.QUICK_MENU.isDown()) {
            // Hold to use: letting go runs the action pointed at; from the empty middle, nothing.
            int slot = hovered();
            String id = slot < 0 ? null : pages().get(page()).get(slot);
            close();
            QuickMenu.run(mc, QuickAction.get(id));
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
            } else {
                turnPage(-1); // The empty middle: back a page.
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
        double scale = scale();
        List<String> slots = pages().get(page());
        List<Integer> active = active();
        int wedge = hoveredWedge(active.size());
        int hovered = wedge < 0 ? -1 : active.get(wedge);
        // Drawn as shapes, not a texture: sharp at any size and GUI scale.
        float outer = (float) (OUTER * scale), hole = (float) (HOLE * scale);
        GuiShapes shapes = new GuiShapes();
        float t = (float) Math.max(1.2 * scale, 2 * shapes.pixel());
        int n = active.size();
        double sector = 2 * Math.PI / Math.max(1, n);
        shapes.ring(cx, cy, hole + t, outer - t, 0xB0141414);
        if (wedge < 0) {
            shapes.ring(cx, cy, 0, hole - t, 0x50FFFFFF); // The middle lit: no action (a click goes back a page).
        } else {
            // The hovered wedge, exactly between its dividers (they're drawn over its edges).
            double mid = sector * wedge;
            shapes.sector(cx, cy, hole + t, outer - t, n > 1 ? mid - sector / 2 : 0, n > 1 ? mid + sector / 2 : 2 * Math.PI, 0xA03A6FD0);
        }
        shapes.ring(cx, cy, outer - t, outer, 0xB0FFFFFF);
        shapes.ring(cx, cy, hole - t, hole + t, 0xB8FFFFFF);
        if (n > 1) {
            for (int i = 0; i < n; i++) {
                double a = sector * i - sector / 2;
                float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
                shapes.line(cx + sx * (hole + t), cy + sy * (hole + t), cx + sx * (outer - t), cy + sy * (outer - t), t * 0.8F, 0x90FFFFFF);
            }
        }
        shapes.submit(g);
        // The icons, as big as the wheel is.
        float iconScale = (float) scale;
        for (int i = 0; i < n; i++) {
            double angle = sector * i;
            float x = cx + (float) (Math.sin(angle) * ICONS * scale) - 8 * iconScale;
            float y = cy - (float) (Math.cos(angle) * ICONS * scale) - 8 * iconScale;
            QuickAction action = QuickAction.get(slots.get(active.get(i)));
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(iconScale, iconScale);
            action.drawIcon(g, 0, 0);
            if (!action.availableNow(mc)) {
                g.fill(0, 0, 16, 16, 0xB0303030);
            }
            if (action.state() != null) {
                g.fill(13, -1, 17, 3, action.state().get() ? 0xFF50E050 : 0xFFE05050);
            }
            g.pose().popMatrix();
        }
        // The pointer, then the hovered action's name and the page under the wheel.
        int dx = cx + (int) Math.round(px / REACH * ICONS * scale), dy = cy + (int) Math.round(py / REACH * ICONS * scale);
        g.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFFFFFFFF);
        QuickAction current = hovered < 0 ? null : QuickAction.get(slots.get(hovered));
        Component name = current == null ? Component.translatable(active.isEmpty() ? "slopmatica.wheel.empty_page" : "slopmatica.wheel.point")
            : current.label().get().copy().withColor(current.availableNow(mc) ? 0xFFFFFFFF : 0xFFA0A0A0);
        Component hint = SlopmaticaConfig.get().quickMode == QuickMenu.Mode.RELEASE
            ? Component.translatable("slopmatica.wheel.hint.release", com.sputnikboi.slopmatica.SlopmaticaKeys.QUICK_MENU.getTranslatedKeyMessage(), page() + 1, pages().size(),
                mc.options.keyInventory.getTranslatedKeyMessage())
            : Component.translatable("slopmatica.wheel.hint", page() + 1, pages().size(), mc.options.keyInventory.getTranslatedKeyMessage());
        int ly = cy + (int) Math.ceil(outer) + 6;
        for (Component line : List.of(name, hint.copy().withColor(0xFFA0A0A0))) {
            int w = mc.font.width(line);
            g.fill(cx - w / 2 - 3, ly - 2, cx + w / 2 + 3, ly + 10, 0x90000000);
            g.centeredText(mc.font, line, cx, ly, 0xFFFFFFFF);
            ly += 13;
        }
    }

    /** The wheel size option as a factor. */
    private static double scale() {
        return Math.clamp(SlopmaticaConfig.get().wheelSize, 50, 200) / 100.0;
    }


}
