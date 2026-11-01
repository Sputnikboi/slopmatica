package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.quick.QuickAction;
import com.sputnikboi.slopmatica.quick.QuickMenu;
import com.sputnikboi.slopmatica.quick.SettingToggle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * Customising the Quick Menu (E while it is open): the nine slots on top, every action below.
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

    public QuickMenuEditScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.quick.edit.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        barX = width / 2 - 9 * SLOT / 2;
        barY = 34;
        columns = Math.max(1, Math.min(4, (width - 20) / (ENTRY_W + 4)));
        gridX = width / 2 - columns * (ENTRY_W + 4) / 2;
        gridY = barY + SLOT + 22;
        SlopmaticaConfig c = SlopmaticaConfig.get();
        int by = height - 28;
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
        if (!addingToggle) {
            // How the menu works, above the buttons (the new-toggle form takes this row while open).
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
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.new_toggle"), b -> {
            addingToggle = true;
            rebuildWidgets();
        }).bounds(width / 2 - 152, by, 100, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.new_toggle.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.quick.edit.reset"), b -> {
            c.quickMenu = new ArrayList<>(QuickAction.DEFAULT_LAYOUT);
            c.save();
        }).bounds(width / 2 - 48, by, 96, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 52, by, 100, 20).build());
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
        List<String> layout = new ArrayList<>(QuickMenu.layout());
        Component hovered = null;
        for (int i = 0; i < 9; i++) {
            int x = barX + i * SLOT, y = barY;
            boolean over = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
            g.fill(x, y, x + SLOT - 2, y + SLOT - 2, i == selectedSlot ? 0xFF3A6FD0 : over ? 0xFF505050 : 0xFF303030);
            g.text(font, String.valueOf(i + 1), x + 1, y + 1, 0xFF808080);
            QuickAction action = QuickAction.get(layout.get(i));
            if (action != null) {
                g.item(action.icon().get(), x + 2, y + 2);
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
            g.item(action.icon().get(), x + 2, y + 2);
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
        List<String> layout = new ArrayList<>(QuickMenu.layout());
        for (int i = 0; i < 9; i++) {
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
                c.quickMenu = layout;
                c.save();
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
                    layout.replaceAll(s -> s.equals(id) ? "" : s);
                    c.quickMenu = layout;
                } else if (selectedSlot >= 0) {
                    layout.set(selectedSlot, id);
                    c.quickMenu = layout;
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
