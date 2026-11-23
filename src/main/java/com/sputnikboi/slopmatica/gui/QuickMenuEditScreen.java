package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.quick.ActionWheel;
import com.sputnikboi.slopmatica.quick.QuickAction;
import com.sputnikboi.slopmatica.quick.QuickLayout;
import com.sputnikboi.slopmatica.quick.QuickMenu;
import com.sputnikboi.slopmatica.quick.SettingToggle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Customising the Quick Menu (E while it is open, or Options → Quick Actions): the hotbar's nine
 * slots or the wheel's pages on top; below, everything that can go in them, searchable and grouped
 * (actions by what they're for, then every setting as a ready-made toggle). Click one to put it in
 * the chosen slot (or the first empty one), or drag it onto a slot. Click two slots to swap them;
 * right click a slot to empty it; right click a setting to choose its own steps.
 */
public class QuickMenuEditScreen extends Screen {
    private static final int SLOT = 22;
    private static final int ENTRY_W = 150, ENTRY_H = 20;
    private static final int HEADER_H = 14;
    /** The editor's groups, in order. */
    private static final List<String> CATEGORIES = List.of("build", "selection", "view", "menus", "online", "other",
        "set_display", "set_building", "set_selection", "set_plan", "custom");

    /** One thing in the list: an action, or a setting's ready-made toggle. */
    private record Entry(String id, QuickAction action, String category, @Nullable String setting, Component tooltip) {
    }

    /** One drawn row piece: a group heading (entry null) or an entry, at list coordinates. */
    private record Placed(int x, int y, @Nullable Entry entry, @Nullable String heading) {
    }

    private @Nullable EditBox search;
    private String query = "";
    private double scroll;
    private int listTop, listBottom;
    /** Pressed on an entry: it, where, and whether it has been dragged off. */
    private @Nullable Entry pressed;
    private double pressX, pressY;
    private boolean dragging;
    private @Nullable Component notice;
    private long noticeUntil;

    private final @Nullable Screen parent;
    private int selectedSlot = -1;
    private @Nullable String pending;
    private int barX, barY, gridX, gridY, columns;
    private boolean addingToggle;
    private String toggleSetting = SettingToggle.SETTINGS.keySet().iterator().next();
    private String toggleValues = "";

    /** Editing the wheel's pages rather than the hotbar. */
    private boolean wheel;
    private int page;

    /** Opens on the style the Quick Menu key uses. */
    public QuickMenuEditScreen(@Nullable Screen parent) {
        this(parent, SlopmaticaConfig.get().quickStyle == QuickMenu.Style.WHEEL);
    }

    public QuickMenuEditScreen(@Nullable Screen parent, boolean wheel) {
        super(Component.translatable("slopmatica.quick.edit.title"));
        this.parent = parent;
        this.wheel = wheel;
        this.page = wheel ? ActionWheel.INSTANCE.page() : 0;
    }

    /** The slots being edited: the hotbar, or the current wheel page. */
    private List<String> slots() {
        List<List<String>> pages = ActionWheel.pages();
        page = Math.clamp(page, 0, pages.size() - 1);
        return new ArrayList<>(wheel ? pages.get(page) : QuickMenu.layout());
    }

    private void saveSlots(List<String> slots) {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        if (wheel) {
            c.wheelPages.set(page, slots);
        } else {
            c.quickMenu = slots;
        }
        c.save();
    }

    private void showTab(boolean wheel, int page) {
        this.wheel = wheel;
        this.page = page;
        selectedSlot = -1;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        int slotCount = slots().size();
        barX = width / 2 - slotCount * SLOT / 2;
        barY = 34;
        columns = Math.max(1, Math.min(4, (width - 20) / (ENTRY_W + 4)));
        gridX = width / 2 - columns * (ENTRY_W + 4) / 2;
        gridY = barY + SLOT + 16;
        search = addRenderableWidget(new EditBox(font, gridX, gridY, Math.min(200, columns * (ENTRY_W + 4) - 4), 18, Component.translatable("slopmatica.quick.edit.search")));
        search.setValue(query);
        search.setHint(Component.translatable("slopmatica.quick.edit.search.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
        search.setResponder(v -> {
            query = v;
            scroll = 0;
        });
        listTop = gridY + 22;
        listBottom = height - (addingToggle ? 64 : 56);
        SlopmaticaConfig c = SlopmaticaConfig.get();
        int by = height - 28;
        // Which layout: tabs left of the slots; the wheel's page controls on the right.
        int left = width / 2 - 9 * SLOT / 2 - 112, right = width / 2 + 9 * SLOT / 2 + 6;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.tab.hotbar"), b -> showTab(false, 0)).bounds(left, barY, 52, 20).build()).active = wheel;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.tab.wheel"), b -> showTab(true, 0)).bounds(left + 56, barY, 52, 20).build()).active = !wheel;
        if (wheel) {
            int pages = c.wheelPages.size();
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> showTab(true, Math.floorMod(page - 1, pages))).bounds(right, barY, 18, 20).build()).active = pages > 1;
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> showTab(true, Math.floorMod(page + 1, pages))).bounds(right + 20, barY, 18, 20).build()).active = pages > 1;
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.page_add"), b -> {
                List<String> empty = new ArrayList<>();
                for (int i = 0; i < QuickLayout.WHEEL_SLOTS; i++) {
                    empty.add("");
                }
                c.wheelPages.add(page + 1, empty);
                c.save();
                showTab(true, page + 1);
            }).bounds(right + 42, barY, 20, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.page_add.tooltip"))).build()).active = pages < 9;
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.page_remove"), b -> {
                c.wheelPages.remove(page);
                c.save();
                showTab(true, page - 1);
            }).bounds(right + 64, barY, 20, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.page_remove.tooltip"))).build()).active = pages > 1;
        }
        if (addingToggle) {
            // The new-toggle form takes the bottom rows.
            int fx = width / 2 - 230;
            addRenderableWidget(CycleButton.<String>builder(s -> Component.translatable(SettingToggle.SETTINGS.get(s)), toggleSetting)
                .withValues(List.copyOf(SettingToggle.SETTINGS.keySet()))
                .create(fx, by - 24, 180, 20, Component.translatable("slopmatica.quick.edit.setting"), (b, v) -> toggleSetting = v));
            EditBox values = addRenderableWidget(new EditBox(font, fx + 184, by - 24, 160, 20, Component.translatable("slopmatica.quick.edit.values")));
            values.setValue(toggleValues);
            values.setHint(Component.translatable("slopmatica.quick.edit.values.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
            values.setResponder(v -> toggleValues = v);
            values.setTooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.values.tooltip")));
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.add"), b -> addToggle()).bounds(fx + 348, by - 24, 54, 20).build());
            addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
                addingToggle = false;
                rebuildWidgets();
            }).bounds(fx + 406, by - 24, 54, 20).build());
        }
        if (!addingToggle && wheel) {
            // How the wheel works, above the buttons (the new-toggle form takes this row while open).
            int wx = width / 2 - 205;
            addRenderableWidget(CycleButton.onOffBuilder(c.wheelCloseAfterUse)
                .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.wheel.close_after_use.tooltip")))
                .create(wx, by - 24, 114, 20, Component.translatable("slopmatica.quick.close_after_use"), (b, v) -> {
                    c.wheelCloseAfterUse = v;
                    c.save();
                }));
            addRenderableWidget(CycleButton.onOffBuilder(c.wheelResetPage)
                .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.wheel.reset_page.tooltip")))
                .create(wx + 118, by - 24, 106, 20, Component.translatable("slopmatica.wheel.reset_page"), (b, v) -> {
                    c.wheelResetPage = v;
                    c.save();
                }));
            AbstractSliderButton size = new AbstractSliderButton(wx + 228, by - 24, 86, 20, Component.empty(), (Math.clamp(c.wheelSize, 50, 200) - 50) / 150.0) {
                {
                    updateMessage();
                }

                @Override
                protected void updateMessage() {
                    setMessage(Component.translatable("slopmatica.wheel.size.short", 50 + (int) Math.round(value * 150)));
                }

                @Override
                protected void applyValue() {
                    c.wheelSize = 50 + (int) Math.round(value * 150);
                    c.save();
                }
            };
            size.setTooltip(Tooltip.create(Component.translatable("slopmatica.wheel.size.tooltip")));
            addRenderableWidget(size);
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.action_keys"), b -> minecraft.gui.setScreen(new ActionKeysScreen(this)))
                .bounds(wx + 318, by - 24, 92, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.action_keys.tooltip"))).build());
        } else if (!addingToggle) {
            // How the hotbar works.
            addRenderableWidget(CycleButton.<QuickMenu.Mode>builder(m -> Component.translatable("slopmatica.quick.mode." + m.name().toLowerCase(Locale.ROOT)), c.quickMode)
                .withValues(QuickMenu.Mode.values())
                .withTooltip(m -> Tooltip.create(Component.translatable("slopmatica.quick.mode." + m.name().toLowerCase(Locale.ROOT) + ".tooltip")))
                .create(width / 2 - 152, by - 24, 150, 20, Component.translatable("slopmatica.quick.mode"), (b, v) -> {
                    c.quickMode = v;
                    c.save();
                }));
            addRenderableWidget(CycleButton.onOffBuilder(c.quickCloseAfterUse)
                .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.quick.close_after_use.tooltip")))
                .create(width / 2 + 2, by - 24, 150, 20, Component.translatable("slopmatica.quick.close_after_use"), (b, v) -> {
                    c.quickCloseAfterUse = v;
                    c.save();
                }));
        }
        int bx = width / 2 - 196;
        addRenderableWidget(CycleButton.<QuickMenu.Style>builder(st -> Component.translatable("slopmatica.quick.style." + st.name().toLowerCase(Locale.ROOT)), c.quickStyle)
            .withValues(QuickMenu.Style.values())
            .withTooltip(st -> Tooltip.create(Component.translatable("slopmatica.quick.style.tooltip")))
            .create(bx, by, 110, 20, Component.translatable("slopmatica.quick.style"), (b, v) -> {
                c.quickStyle = v;
                c.save();
            }));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.reset"), b -> {
            if (wheel) {
                List<String> defaults = page < QuickLayout.WHEEL_DEFAULT.size() ? QuickLayout.WHEEL_DEFAULT.get(page) : java.util.Collections.nCopies(QuickLayout.WHEEL_SLOTS, "");
                saveSlots(new ArrayList<>(defaults));
            } else {
                saveSlots(new ArrayList<>(QuickAction.DEFAULT_LAYOUT));
            }
        }).bounds(bx + 114, by, 90, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(bx + 208, by, 90, 20).build());
    }

    private void addToggle() {
        List<String> values = new ArrayList<>();
        for (String v : toggleValues.split("[,;\\s]+")) {
            if (!v.isBlank()) {
                values.add(v.trim().replace("%", ""));
            }
        }
        SettingToggle toggle = new SettingToggle(toggleSetting, values);
        if (toggle.steps().size() < 2) {
            toggleValues = "";
            minecraft.gui.setScreen(this); // Keeps the form; the hint says what's needed.
            return;
        }
        SlopmaticaConfig c = SlopmaticaConfig.get();
        if (!c.quickToggles.contains(toggle.id())) {
            c.quickToggles.add(toggle.id());
        }
        c.save();
        addingToggle = false;
        toggleValues = "";
        rebuildWidgets();
        QuickAction action = toggle.action();
        place(new Entry(toggle.id(), action, "custom", null, action.label().get()));
    }

    /** Everything that can go in a slot, in group order, filtered by the search. */
    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (QuickAction a : QuickAction.all()) {
            Component tip = a.label().get();
            if (a.id().startsWith(SettingToggle.PREFIX)) {
                SettingToggle t = SettingToggle.parse(a.id());
                tip = Component.translatable("slopmatica.quick.edit.custom.tooltip", t == null ? "" : t.stepsText());
            } else if (!a.standard()) {
                tip = tip.copy().append(Component.literal("  (optional)").withStyle(ChatFormatting.GRAY));
            }
            out.add(new Entry(a.id(), a, a.category(), null, tip));
        }
        for (String setting : SettingToggle.SETTINGS.keySet()) {
            SettingToggle t = SettingToggle.standard(setting);
            if (SettingToggle.parse(t.id()) == null) {
                continue;
            }
            out.add(new Entry(t.id(), t.action(), SettingToggle.CATEGORY.getOrDefault(setting, "set_display"), setting,
                Component.translatable("slopmatica.quick.edit.setting.tooltip", Component.translatable(SettingToggle.SETTINGS.get(setting)), t.stepsText())));
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            out.removeIf(e -> !(label(e).getString() + " " + Component.translatable("slopmatica.quick.cat." + e.category()).getString())
                .toLowerCase(Locale.ROOT).contains(q));
        }
        out.sort(java.util.Comparator.comparingInt(e -> CATEGORIES.indexOf(e.category())));
        return out;
    }

    /** A setting entry shows the setting's name; anything else its own label. */
    private static Component label(Entry e) {
        return e.setting() != null ? Component.translatable(SettingToggle.SETTINGS.get(e.setting())) : e.action().label().get();
    }

    /** Where every heading and entry goes, before scrolling (y from the list's top). */
    private List<Placed> layoutList() {
        List<Placed> out = new ArrayList<>();
        String category = null;
        int y = 0, col = 0;
        for (Entry e : entries()) {
            if (!e.category().equals(category)) {
                if (col > 0) {
                    y += ENTRY_H + 2;
                }
                col = 0;
                category = e.category();
                out.add(new Placed(gridX, y + (out.isEmpty() ? 0 : 4), null, category));
                y += HEADER_H + (out.size() == 1 ? 0 : 4);
            }
            out.add(new Placed(gridX + col * (ENTRY_W + 4), y, e, null));
            if (++col == columns) {
                col = 0;
                y += ENTRY_H + 2;
            }
        }
        return out;
    }

    private int contentHeight(List<Placed> placed) {
        int h = 0;
        for (Placed p : placed) {
            h = Math.max(h, p.y() + (p.entry() == null ? HEADER_H : ENTRY_H));
        }
        return h;
    }

    private @Nullable Entry entryAt(double mx, double my) {
        if (my < listTop || my >= listBottom) {
            return null;
        }
        for (Placed p : layoutList()) {
            int y = listTop + p.y() - (int) scroll;
            if (p.entry() != null && mx >= p.x() && mx < p.x() + ENTRY_W && my >= y && my < y + ENTRY_H) {
                return p.entry();
            }
        }
        return null;
    }

    private int slotAt(double mx, double my) {
        int count = slots().size();
        for (int i = 0; i < count; i++) {
            int x = barX + i * SLOT;
            if (mx >= x && mx < x + SLOT && my >= barY && my < barY + SLOT) {
                return i;
            }
        }
        return -1;
    }

    private void notice(Component text) {
        notice = text;
        noticeUntil = net.minecraft.util.Util.getMillis() + 3000;
    }

    /** Clicked in the list: into the chosen slot (then the next one is chosen), else the first empty one. */
    private void place(Entry e) {
        List<String> layout = slots();
        int slot = selectedSlot;
        if (slot < 0) {
            slot = layout.indexOf("");
        }
        if (slot < 0) {
            pending = e.id();
            notice(Component.translatable("slopmatica.quick.edit.full"));
            return;
        }
        put(slot, e.id());
    }

    private void put(int slot, String id) {
        List<String> layout = slots();
        layout.set(slot, id);
        saveSlots(layout);
        pending = null;
        selectedSlot = selectedSlot >= 0 && slot + 1 < layout.size() ? slot + 1 : -1;
        QuickAction a = QuickAction.get(id);
        notice(Component.translatable("slopmatica.quick.edit.placed", a == null ? id : a.label().get(), slot + 1));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 10, -1);
        g.centeredText(font, Component.translatable("slopmatica.quick.edit.help"), width / 2, 21, 0xFFA0A0A0);
        List<String> layout = slots();
        Component hovered = null;
        int dropSlot = dragging ? slotAt(mouseX, mouseY) : -1;
        for (int i = 0; i < layout.size(); i++) {
            int x = barX + i * SLOT, y = barY;
            boolean over = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            g.fill(x, y, x + SLOT - 2, y + SLOT - 2, i == selectedSlot || i == dropSlot ? 0xFF3A6FD0 : over ? 0xFF505050 : 0xFF303030);
            g.text(font, String.valueOf(i + 1), x + 1, y + 1, 0xFF808080);
            QuickAction action = QuickAction.get(layout.get(i));
            if (action != null) {
                action.drawIcon(g, x + 2, y + 2);
                if (over && !dragging) {
                    hovered = action.label().get();
                }
            }
        }
        if (wheel) {
            g.centeredText(font, Component.translatable("slopmatica.quick.edit.page", page + 1, ActionWheel.pages().size()), width / 2, barY + SLOT + 2, 0xFFA0A0A0);
        }
        if (notice != null && net.minecraft.util.Util.getMillis() < noticeUntil && search != null) {
            g.text(font, notice, search.getX() + search.getWidth() + 8, gridY + 5, 0xFF80C0FF);
        } else if (search != null) {
            g.text(font, Component.translatable(selectedSlot >= 0 ? "slopmatica.quick.edit.hint_slot" : "slopmatica.quick.edit.hint", selectedSlot + 1),
                search.getX() + search.getWidth() + 8, gridY + 5, 0xFF808080);
        }

        List<Placed> placed = layoutList();
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight(placed) - (listBottom - listTop)));
        g.enableScissor(0, listTop, width, listBottom);
        for (Placed p : placed) {
            int y = listTop + p.y() - (int) scroll;
            if (y + ENTRY_H < listTop || y > listBottom) {
                continue;
            }
            if (p.entry() == null) {
                g.text(font, Component.translatable("slopmatica.quick.cat." + p.heading()).withStyle(ChatFormatting.BOLD), p.x(), y + 3, 0xFFFFD040);
                g.fill(p.x(), y + HEADER_H - 2, gridX + columns * (ENTRY_W + 4) - 4, y + HEADER_H - 1, 0x40FFFFFF);
                continue;
            }
            Entry e = p.entry();
            boolean over = !dragging && mouseX >= p.x() && mouseX < p.x() + ENTRY_W && mouseY >= y && mouseY < y + ENTRY_H && mouseY >= listTop && mouseY < listBottom;
            boolean inUse = layout.contains(e.id());
            g.fill(p.x(), y, p.x() + ENTRY_W, y + ENTRY_H, e.id().equals(pending) ? 0xFF3A6FD0 : over ? 0x60FFFFFF : 0x40000000);
            e.action().drawIcon(g, p.x() + 2, y + 2);
            String text = label(e).getString();
            g.text(font, font.plainSubstrByWidth(text, ENTRY_W - 24 - (inUse ? 10 : 0)), p.x() + 21, y + 6, inUse ? 0xFF80C080 : 0xFFFFFFFF);
            if (inUse) {
                g.text(font, "✔", p.x() + ENTRY_W - 10, y + 6, 0xFF80C080);
            }
            if (over) {
                hovered = e.tooltip();
            }
        }
        g.disableScissor();
        int content = contentHeight(placed), shown = listBottom - listTop;
        if (content > shown) {
            // A scrollbar: there's more below (or above).
            int bx = gridX + columns * (ENTRY_W + 4), thumb = Math.max(12, shown * shown / content);
            int ty = listTop + (int) ((shown - thumb) * scroll / (content - shown));
            g.fill(bx, listTop, bx + 3, listBottom, 0x40FFFFFF);
            g.fill(bx, ty, bx + 3, ty + thumb, 0xC0FFFFFF);
        }
        if (placed.isEmpty()) {
            g.centeredText(font, Component.translatable("slopmatica.quick.edit.nothing"), width / 2, listTop + 10, 0xFF808080);
        }
        if (dragging && pressed != null) {
            pressed.action().drawIcon(g, mouseX - 8, mouseY - 8);
        }
        if (hovered != null) {
            g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
        if (addingToggle) {
            g.text(font, Component.translatable("slopmatica.quick.edit.toggle_help"), width / 2 - 230, height - 64, 0xFFA0A0A0);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (y >= listTop && y < listBottom) {
            scroll -= scrollY * (ENTRY_H + 2) * 2;
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mx = event.x(), my = event.y();
        SlopmaticaConfig c = SlopmaticaConfig.get();
        List<String> layout = slots();
        int i = slotAt(mx, my);
        if (i >= 0) {
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                layout.set(i, "");
                saveSlots(layout);
            } else if (pending != null) {
                put(i, pending);
            } else if (selectedSlot >= 0 && selectedSlot != i) {
                String moved = layout.get(selectedSlot);
                layout.set(selectedSlot, layout.get(i));
                layout.set(i, moved);
                selectedSlot = -1;
                saveSlots(layout);
            } else {
                selectedSlot = selectedSlot == i ? -1 : i;
            }
            return true;
        }
        Entry e = entryAt(mx, my);
        if (e == null) {
            return false;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (e.id().startsWith(SettingToggle.PREFIX) && e.setting() == null) {
                // Right click your own toggle: forget it (and take it off the bar).
                String id = e.id();
                c.quickToggles.remove(id);
                c.quickMenu.replaceAll(s -> s.equals(id) ? "" : s);
                c.wheelPages.forEach(p -> p.replaceAll(s -> s.equals(id) ? "" : s));
                c.save();
            } else if (e.setting() != null) {
                // Right click a setting: choose its own steps.
                toggleSetting = e.setting();
                toggleValues = "";
                addingToggle = true;
                rebuildWidgets();
            }
            return true;
        }
        pressed = e;
        pressX = mx;
        pressY = my;
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pressed != null) {
            if (!dragging && Math.hypot(event.x() - pressX, event.y() - pressY) > 4) {
                dragging = true;
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Entry e = pressed;
        if (e == null) {
            return super.mouseReleased(event);
        }
        pressed = null;
        if (dragging) {
            dragging = false;
            int slot = slotAt(event.x(), event.y());
            if (slot >= 0) {
                selectedSlot = -1;
                put(slot, e.id());
            }
        } else if (e.equals(entryAt(event.x(), event.y()))) {
            place(e);
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
