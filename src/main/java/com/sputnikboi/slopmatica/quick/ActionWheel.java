package com.sputnikboi.slopmatica.quick;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.QuickMenuEditScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The action wheel, the Quick Menu's other style: the same actions in a ring around the
 * crosshair, on as many pages as you like. While it is open the mouse moves a pointer instead of
 * the camera; left click runs the action it points at, right click (or scrolling) turns the page.
 * It closes on the Quick Menu key, after use (by option), and whenever a screen opens, so an
 * action that opens a menu always closes it.
 */
public final class ActionWheel {
    public static final ActionWheel INSTANCE = new ActionWheel();

    /** How far the pointer goes from the centre (mouse units); it reaches the middle of the ring. */
    private static final double REACH = 40;
    /** The wheel in GUI pixels: outer edge, the empty middle (no action), and where icons sit. */
    private static final int OUTER = 80, HOLE = 17, ICONS = (OUTER + HOLE) / 2 + 2;
    /** Mouse units inside which the pointer is in the empty middle. */
    private static final double DEADZONE = REACH * HOLE / ICONS;
    /** The wheel's textures are drawn this big and scaled down, so edges stay smooth at any GUI scale. */
    private static final int TEXTURE = 512;

    /** Ring, middle and highlighted wedges, made for one slot count. */
    private int texturesFor = -1;

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
        makeTextures(mc, slots.size());
        // The ring (one wedge per action), then the hovered wedge, or the middle lit when no action is picked.
        blitWheel(g, cx, cy, "ring");
        blitWheel(g, cx, cy, hovered < 0 ? "middle" : "wedge_" + hovered);
        for (int i = 0; i < slots.size(); i++) {
            double angle = 2 * Math.PI * i / slots.size();
            int x = cx + (int) Math.round(Math.sin(angle) * ICONS) - 8;
            int y = cy - (int) Math.round(Math.cos(angle) * ICONS) - 8;
            QuickAction action = QuickAction.get(slots.get(i));
            if (action != null) {
                g.item(action.icon().get(), x, y);
                if (!action.availableNow(mc)) {
                    g.fill(x, y, x + 16, y + 16, 0xB0303030);
                }
                if (action.state() != null) {
                    g.fill(x + 13, y - 1, x + 17, y + 3, action.state().get() ? 0xFF50E050 : 0xFFE05050);
                }
            }
        }
        // The pointer, then the hovered action's name and the page under the wheel.
        int dx = cx + (int) Math.round(px / REACH * ICONS), dy = cy + (int) Math.round(py / REACH * ICONS);
        g.fill(dx - 1, dy - 1, dx + 2, dy + 2, 0xFFFFFFFF);
        QuickAction current = hovered < 0 ? null : QuickAction.get(slots.get(hovered));
        Component name = current == null ? Component.translatable(hovered < 0 ? "slopmatica.wheel.point" : "slopmatica.quick.empty")
            : current.label().get().copy().withColor(current.availableNow(mc) ? 0xFFFFFFFF : 0xFFA0A0A0);
        Component hint = SlopmaticaConfig.get().quickMode == QuickMenu.Mode.RELEASE
            ? Component.translatable("slopmatica.wheel.hint.release", com.sputnikboi.slopmatica.SlopmaticaKeys.QUICK_MENU.getTranslatedKeyMessage(), page() + 1, pages().size(),
                mc.options.keyInventory.getTranslatedKeyMessage())
            : Component.translatable("slopmatica.wheel.hint", page() + 1, pages().size(), mc.options.keyInventory.getTranslatedKeyMessage());
        int ly = cy + OUTER + 6;
        for (Component line : List.of(name, hint.copy().withColor(0xFFA0A0A0))) {
            int w = mc.font.width(line);
            g.fill(cx - w / 2 - 3, ly - 2, cx + w / 2 + 3, ly + 10, 0x90000000);
            g.centeredText(mc.font, line, cx, ly, 0xFFFFFFFF);
            ly += 13;
        }
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("slopmatica", "wheel/" + name);
    }

    private static void blitWheel(GuiGraphicsExtractor g, int cx, int cy, String name) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture(name), cx - OUTER, cy - OUTER, 0, 0, OUTER * 2, OUTER * 2, TEXTURE, TEXTURE, TEXTURE, TEXTURE);
    }

    /** Paints the ring, the middle and one lit wedge per slot (once per slot count). */
    private void makeTextures(Minecraft mc, int slots) {
        if (texturesFor == slots) {
            return;
        }
        texturesFor = slots;
        register(mc, "ring", paint(slots, -2));
        register(mc, "middle", paint(slots, -1));
        for (int i = 0; i < slots; i++) {
            register(mc, "wedge_" + i, paint(slots, i));
        }
    }

    private static void register(Minecraft mc, String name, NativeImage image) {
        mc.getTextureManager().register(texture(name), new DynamicTexture(() -> "Slopmatica wheel " + name, image));
    }

    /**
     * One layer of the wheel, 3x3 supersampled for smooth edges. {@code which}: -2 the ring with its
     * dividers and the outline of the empty middle; -1 the middle lit; otherwise that slot's wedge lit.
     */
    private static NativeImage paint(int slots, int which) {
        NativeImage image = new NativeImage(TEXTURE, TEXTURE, true);
        double scale = (double) TEXTURE / (OUTER * 2); // texture pixels per GUI pixel
        double sector = 2 * Math.PI / slots;
        for (int y = 0; y < TEXTURE; y++) {
            for (int x = 0; x < TEXTURE; x++) {
                float a = 0, r = 0, gr = 0, b = 0;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        // In GUI pixels from the centre.
                        double gx = (x + (sx + 0.5) / 3) / scale - OUTER, gy = (y + (sy + 0.5) / 3) / scale - OUTER;
                        int c = sample(gx, gy, sector, which);
                        float ca = (c >>> 24) / 255F;
                        a += ca;
                        r += ca * ((c >> 16) & 0xFF);
                        gr += ca * ((c >> 8) & 0xFF);
                        b += ca * (c & 0xFF);
                    }
                }
                if (a > 0) {
                    image.setPixel(x, y, (Math.round(a / 9 * 255) << 24) | (Math.round(r / a) << 16) | (Math.round(gr / a) << 8) | Math.round(b / a));
                }
            }
        }
        return image;
    }

    /** One layer's colour at a point (GUI pixels from the centre); 0 for nothing. */
    private static int sample(double gx, double gy, double sector, int which) {
        double d = Math.sqrt(gx * gx + gy * gy);
        if (d > OUTER) {
            return 0;
        }
        if (which == -1) {
            return d < HOLE - 1.5 ? 0x50FFFFFF : 0; // the middle lit: a click does nothing
        }
        if (d < HOLE) {
            return which == -2 && d > HOLE - 1.2 ? 0xC0FFFFFF : 0; // the empty middle's outline
        }
        // Angle clockwise from the top, shifted so each slot's wedge is centred on its icon.
        double angle = Math.atan2(gx, -gy) + sector / 2;
        angle = (angle % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
        int slot = (int) (angle / sector);
        // Distance to the nearest divider, in GUI pixels along the arc.
        double edge = Math.min(angle - slot * sector, (slot + 1) * sector - angle) * d;
        if (which == -2) {
            if (d > OUTER - 1.2 || d < HOLE + 1.2) {
                return 0xB0FFFFFF; // outer rim and inner edge
            }
            return edge < 0.6 ? 0x90FFFFFF : 0xB0141414; // dividers, then the wedge
        }
        return slot == which && edge > 1 && d > HOLE + 1.5 && d < OUTER - 1.5 ? 0xA03A6FD0 : 0;
    }
}
