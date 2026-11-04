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
 * slots or the wheel's pages on top, every action below.
 * Click an action and then a slot to put it there (or a slot first, then the action); click two
 * slots to swap them; right click a slot to empty it. New setting toggles are made at the bottom.
 */
public class QuickMenuEditScreen extends Screen {
    private static final int SLOT = 22;
    private static final int ENTRY_W = 150, ENTRY_H = 20;

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
        gridY = barY + SLOT + 22;
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
            addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.action_keys"), b -> minecraft.gui.setScreen(new ControlsKeysScreen(this)))
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
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.new_toggle"), b -> {
            addingToggle = true;
            rebuildWidgets();
        }).bounds(bx + 114, by, 90, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.new_toggle.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.reset"), b -> {
            if (wheel) {
                List<String> defaults = page < QuickLayout.WHEEL_DEFAULT.size() ? QuickLayout.WHEEL_DEFAULT.get(page) : java.util.Collections.nCopies(QuickLayout.WHEEL_SLOTS, "");
                saveSlots(new ArrayList<>(defaults));
            } else {
                saveSlots(new ArrayList<>(QuickAction.DEFAULT_LAYOUT));
            }
        }).bounds(bx + 208, by, 90, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(bx + 302, by, 90, 20).build());
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
        pending = toggle.id();
        addingToggle = false;
        toggleValues = "";
        rebuildWidgets();
    }

    private List<QuickAction> actions() {
        return QuickAction.all();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 10, -1);
        g.centeredText(font, Component.translatable("slopmatica.quick.edit.help"), width / 2, 21, 0xFFA0A0A0);
        List<String> layout = slots();
        Component hovered = null;
        if (wheel) {
            g.centeredText(font, Component.translatable("slopmatica.quick.edit.page", page + 1, ActionWheel.pages().size()), width / 2, barY + SLOT + 4, 0xFFA0A0A0);
        }
        for (int i = 0; i < layout.size(); i++) {
            int x = barX + i * SLOT, y = barY;
            boolean over = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            g.fill(x, y, x + SLOT - 2, y + SLOT - 2, i == selectedSlot ? 0xFF3A6FD0 : over ? 0xFF505050 : 0xFF303030);
            g.text(font, String.valueOf(i + 1), x + 1, y + 1, 0xFF808080);
            QuickAction action = QuickAction.get(layout.get(i));
            if (action != null) {
                action.drawIcon(g, x + 2, y + 2);
                if (over) {
                    hovered = action.label().get();
                }
            }
        }
        List<QuickAction> all = actions();
        for (int i = 0; i < all.size(); i++) {
            QuickAction action = all.get(i);
            int x = gridX + (i % columns) * (ENTRY_W + 4), y = gridY + (i / columns) * (ENTRY_H + 2);
            if (y + ENTRY_H > height - 56) {
                break;
            }
            boolean over = mouseX >= x && mouseX < x + ENTRY_W && mouseY >= y && mouseY < y + ENTRY_H;
            boolean inUse = layout.contains(action.id());
            g.fill(x, y, x + ENTRY_W, y + ENTRY_H, action.id().equals(pending) ? 0xFF3A6FD0 : over ? 0x60FFFFFF : 0x40000000);
            action.drawIcon(g, x + 2, y + 2);
            Component label = action.label().get();
            g.text(font, font.plainSubstrByWidth(label.getString(), ENTRY_W - 24), x + 21, y + 6, inUse ? 0xFF80C080 : 0xFFFFFFFF);
            if (over) {
                hovered = Component.empty().append(label).append(Component.literal(action.standard() ? "" : "  (optional)").withStyle(ChatFormatting.GRAY));
            }
        }
        if (hovered != null) {
            g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
        }
        if (addingToggle) {
            g.text(font, Component.translatable("slopmatica.quick.edit.toggle_help"), width / 2 - 230, height - 64, 0xFFA0A0A0);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mx = event.x(), my = event.y();
        SlopmaticaConfig c = SlopmaticaConfig.get();
        List<String> layout = slots();
        for (int i = 0; i < layout.size(); i++) {
            int x = barX + i * SLOT;
            if (mx >= x && mx < x + SLOT && my >= barY && my < barY + SLOT) {
                if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                    layout.set(i, "");
                } else if (pending != null) {
                    layout.set(i, pending);
                    pending = null;
                    selectedSlot = i;
                } else if (selectedSlot >= 0 && selectedSlot != i) {
                    String moved = layout.get(selectedSlot);
                    layout.set(selectedSlot, layout.get(i));
                    layout.set(i, moved);
                    selectedSlot = -1;
                } else {
                    selectedSlot = selectedSlot == i ? -1 : i;
                }
                saveSlots(layout);
                return true;
            }
        }
        List<QuickAction> all = actions();
        for (int i = 0; i < all.size(); i++) {
            int x = gridX + (i % columns) * (ENTRY_W + 4), y = gridY + (i / columns) * (ENTRY_H + 2);
            if (mx >= x && mx < x + ENTRY_W && my >= y && my < y + ENTRY_H) {
                String id = all.get(i).id();
                if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && id.startsWith(SettingToggle.PREFIX)) {
                    // Right click a made toggle: forget it (and take it off the bar).
                    c.quickToggles.remove(id);
                    c.quickMenu.replaceAll(s -> s.equals(id) ? "" : s);
                    c.wheelPages.forEach(p -> p.replaceAll(s -> s.equals(id) ? "" : s));
                } else if (selectedSlot >= 0) {
                    layout.set(selectedSlot, id);
                    saveSlots(layout);
                } else {
                    pending = id.equals(pending) ? null : id;
                }
                c.save();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
