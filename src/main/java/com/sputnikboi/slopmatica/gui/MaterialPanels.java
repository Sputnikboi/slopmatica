package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.placement.PlacementStore;
import com.sputnikboi.slopmatica.verify.MaterialList;
import com.sputnikboi.slopmatica.view.PlacementView;
import com.sputnikboi.slopmatica.view.PlacementViewScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Small floating material lists, one per build. Opened from the build list in the placement view
 * and shown while in it; pinned ones stay on screen outside it too. With the free cursor they can
 * be dragged by the title bar and scrolled; left click on a material shows only the selected
 * materials in the hologram, right click leaves it out of the counts. Recounted every two seconds.
 */
public final class MaterialPanels {
    public static final MaterialPanels INSTANCE = new MaterialPanels();

    private static final int W = 176;
    private static final int TITLE = 14;
    private static final int ROW = 18;
    private static final int MAX_ROWS = 8;
    private static final int FOOTER = 11;
    private static final int DEFAULT_TOP = 70;
    private static final long REFRESH_MS = 2000;
    private static final int BUTTON = 10;

    private record Cached(MaterialList list, long time) {
    }

    private final Map<Placement, Cached> cache = new WeakHashMap<>();
    private final Map<Placement, Integer> scroll = new WeakHashMap<>();
    private @Nullable Placement dragging;
    private int dragDX, dragDY;

    private MaterialPanels() {
    }

    /** In the placement view: the open panels. Elsewhere: the pinned ones. */
    private List<Placement> shown(Minecraft mc) {
        List<Placement> out = new ArrayList<>();
        if (mc.level == null) {
            return out;
        }
        boolean inView = PlacementView.INSTANCE.active();
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (p.inDimension(mc.level) && (p.panelPinned || (inView && p.panelOpen))) {
                out.add(p);
            }
        }
        return out;
    }

    private @Nullable MaterialList list(Minecraft mc, Placement p) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        Cached c = cache.get(p);
        long now = Util.getMillis();
        if (c == null || now - c.time() > REFRESH_MS) {
            c = new Cached(MaterialList.count(p, mc.level, mc.player.getInventory()), now);
            cache.put(p, c);
        }
        return c.list();
    }

    /** Rows in display order: counted materials by what's missing, then the ignored ones. */
    private static List<MaterialList.Entry> rows(Placement p, MaterialList list) {
        List<MaterialList.Entry> counted = new ArrayList<>(), ignored = new ArrayList<>();
        for (MaterialList.Entry e : list.entries()) {
            (p.ignoredMaterials().contains(e.item()) ? ignored : counted).add(e);
        }
        counted.addAll(ignored);
        return counted;
    }

    private record Rect(int x, int y, int h, List<MaterialList.Entry> rows, int first) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + W && my >= y && my < y + h;
        }
    }

    private Rect rect(Minecraft mc, Placement p, int index, int screenW, int screenH, boolean interactive) {
        MaterialList list = list(mc, p);
        List<MaterialList.Entry> rows = list == null ? List.of() : rows(p, list);
        int visible = Math.min(MAX_ROWS, rows.size());
        int first = Math.clamp(scroll.getOrDefault(p, 0), 0, Math.max(0, rows.size() - visible));
        int h = TITLE + Math.max(1, visible) * ROW + (rows.size() > visible ? 10 : 0) + (interactive ? FOOTER : 0) + 3;
        int x = p.panelX >= 0 ? p.panelX : screenW - (index + 1) * (W + 6);
        int y = p.panelY >= 0 ? p.panelY : DEFAULT_TOP;
        x = Math.clamp(x, 0, Math.max(0, screenW - W));
        y = Math.clamp(y, 0, Math.max(0, screenH - h));
        return new Rect(x, y, h, rows, first);
    }

    /** HUD: open/pinned panels, not interactive. The cursor screen draws its own, interactive ones. */
    public void extractHud(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PlacementViewScreen) {
            return;
        }
        draw(graphics, mc, -1, -1, false);
    }

    public void draw(GuiGraphicsExtractor graphics, Minecraft mc, int mouseX, int mouseY, boolean interactive) {
        List<Placement> panels = shown(mc);
        int sw = graphics.guiWidth(), sh = graphics.guiHeight();
        for (int i = 0; i < panels.size(); i++) {
            Placement p = panels.get(i);
            drawPanel(graphics, mc, p, rect(mc, p, i, sw, sh, interactive), mouseX, mouseY, interactive);
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, Minecraft mc, Placement p, Rect r, int mouseX, int mouseY, boolean interactive) {
        Font font = mc.font;
        int x = r.x(), y = r.y();
        g.fill(x - 1, y - 1, x + W + 1, y + r.h() + 1, 0xFF3A3A44);
        g.fill(x, y, x + W, y + r.h(), 0xD0101014);
        g.fill(x, y, x + W, y + TITLE, 0xFF22283A);

        int left = 0;
        for (MaterialList.Entry e : r.rows()) {
            if (!p.ignoredMaterials().contains(e.item())) {
                left += e.missing();
            }
        }
        String status = left == 0 ? "done" : left + " left";
        int buttonsW = interactive ? 2 * (BUTTON + 2) : (p.panelPinned ? BUTTON + 2 : 0);
        int nameW = W - 8 - font.width(status) - 6 - buttonsW;
        g.text(font, font.plainSubstrByWidth(p.name(), nameW), x + 4, y + 3, 0xFFFFFFFF);
        g.text(font, status, x + W - 4 - buttonsW - font.width(status), y + 3, left == 0 ? 0xFF60E060 : 0xFFFFD060);
        if (interactive || p.panelPinned) {
            int px = x + W - 2 - (interactive ? 2 * (BUTTON + 2) : BUTTON + 2);
            g.text(font, "P", px + 2, y + 3, p.panelPinned ? 0xFFFFD060 : 0xFF808080);
            if (interactive) {
                g.text(font, "×", x + W - BUTTON - 1, y + 3, 0xFFE07070);
            }
        }

        if (r.rows().isEmpty()) {
            g.text(font, Component.translatable("slopmatica.panel.empty"), x + 4, y + TITLE + 5, 0xFFA0A0A0);
        }
        int visible = Math.min(MAX_ROWS, r.rows().size());
        for (int i = 0; i < visible; i++) {
            MaterialList.Entry e = r.rows().get(r.first() + i);
            int ry = y + TITLE + 1 + i * ROW;
            boolean ignored = p.ignoredMaterials().contains(e.item());
            boolean selected = p.selectedMaterials().contains(e.item());
            boolean hovered = interactive && mouseX >= x && mouseX < x + W && mouseY >= ry && mouseY < ry + ROW;
            if (selected) {
                g.fill(x + 1, ry, x + W - 1, ry + ROW, 0x803060C0);
            } else if (hovered) {
                g.fill(x + 1, ry, x + W - 1, ry + ROW, 0x30FFFFFF);
            }
            ItemStack stack = new ItemStack(e.item());
            g.item(stack, x + 2, ry + 1);
            String count = ignored ? "-" : e.missing() == 0 ? "✔" : String.valueOf(e.missing());
            int countColor = ignored ? 0xFF606060 : e.missing() == 0 ? 0xFF60E060 : e.have() >= e.missing() ? 0xFFFFFFFF : 0xFFFF8080;
            int countW = font.width(count);
            Component name = stack.getHoverName();
            g.text(font, font.plainSubstrByWidth(name.getString(), W - 26 - countW - 6), x + 21, ry + 5, ignored ? 0xFF606060 : 0xFFE0E0E0);
            g.text(font, count, x + W - 4 - countW, ry + 5, countColor);
            if (hovered) {
                g.setTooltipForNextFrame(font, List.of(
                    name,
                    Component.translatable("slopmatica.panel.counts", e.missing(), e.total(), e.have()).withStyle(ChatFormatting.GRAY),
                    Component.translatable(selected ? "slopmatica.panel.selected" : "slopmatica.panel.select_hint").withStyle(ChatFormatting.DARK_GRAY),
                    Component.translatable(ignored ? "slopmatica.panel.ignored" : "slopmatica.panel.ignore_hint").withStyle(ChatFormatting.DARK_GRAY)
                ), java.util.Optional.empty(), mouseX, mouseY);
            }
        }
        int fy = y + TITLE + 1 + Math.max(1, visible) * ROW;
        if (r.rows().size() > visible) {
            g.text(font, Component.translatable("slopmatica.panel.more", r.first() + 1, r.first() + visible, r.rows().size()), x + 4, fy, 0xFF909090);
            fy += 10;
        }
        if (interactive) {
            g.text(font, Component.translatable("slopmatica.panel.hint"), x + 4, fy, 0xFF707070);
        }
    }

    // ---- mouse (cursor screen only) ----------------------------------------------------------

    private @Nullable Placement panelAt(Minecraft mc, double mx, double my, int sw, int sh) {
        List<Placement> panels = shown(mc);
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (rect(mc, panels.get(i), i, sw, sh, true).contains(mx, my)) {
                return panels.get(i);
            }
        }
        return null;
    }

    public boolean mouseClicked(Minecraft mc, double mx, double my, int button, int sw, int sh) {
        List<Placement> panels = shown(mc);
        for (int i = panels.size() - 1; i >= 0; i--) {
            Placement p = panels.get(i);
            Rect r = rect(mc, p, i, sw, sh, true);
            if (!r.contains(mx, my)) {
                continue;
            }
            if (my < r.y() + TITLE) {
                int closeX = r.x() + W - BUTTON - 2, pinX = closeX - BUTTON - 2;
                if (mx >= closeX) {
                    p.panelOpen = false;
                    p.panelPinned = false;
                } else if (mx >= pinX) {
                    p.panelPinned = !p.panelPinned;
                } else {
                    dragging = p;
                    dragDX = (int) mx - r.x();
                    dragDY = (int) my - r.y();
                    // Remember where it is, so dragging starts from the panel's actual place.
                    p.panelX = r.x();
                    p.panelY = r.y();
                }
                PlacementStore.INSTANCE.markDirty();
                return true;
            }
            int row = (int) (my - r.y() - TITLE - 1) / ROW;
            int visible = Math.min(MAX_ROWS, r.rows().size());
            if (row >= 0 && row < visible) {
                Item item = r.rows().get(r.first() + row).item();
                if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    p.setIgnoredMaterials(Placement.toggled(p.ignoredMaterials(), item));
                    PlacementStore.INSTANCE.markDirty();
                } else {
                    p.setSelectedMaterials(Placement.toggled(p.selectedMaterials(), item));
                    PlacementManager.INSTANCE.changed();
                }
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my) {
        if (dragging == null) {
            return false;
        }
        dragging.panelX = Math.max(0, (int) mx - dragDX);
        dragging.panelY = Math.max(0, (int) my - dragDY);
        return true;
    }

    public boolean mouseReleased() {
        if (dragging == null) {
            return false;
        }
        dragging = null;
        PlacementStore.INSTANCE.markDirty();
        return true;
    }

    public boolean mouseScrolled(Minecraft mc, double mx, double my, double amount, int sw, int sh) {
        Placement p = panelAt(mc, mx, my, sw, sh);
        if (p == null) {
            return false;
        }
        MaterialList list = list(mc, p);
        int max = list == null ? 0 : Math.max(0, list.entries().size() - MAX_ROWS);
        scroll.put(p, Math.clamp(scroll.getOrDefault(p, 0) + (amount > 0 ? -1 : 1), 0, max));
        return true;
    }

    /** Recount soon, e.g. after the build moved. */
    public void invalidate() {
        cache.clear();
    }
}
