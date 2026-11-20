package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.quick.QuickAction;
import com.sputnikboi.slopmatica.quick.SettingToggle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Action keys in one place: make a key that switches a setting (on/off, or through a list of
 * values) and press the key for it right away, and give any Quick Menu action, made here or
 * built in, a key of its own. Keys run outside menus; Escape while choosing removes one.
 */
public class ActionKeysScreen extends OptionsSubScreen {
    private static final int WIDE = 150;

    private String setting = SettingToggle.SETTINGS.keySet().iterator().next();
    private String values = "";
    private Component status = Component.empty();
    /** The action whose key is being chosen: the next key pressed goes to it. */
    private @Nullable String listening;

    public ActionKeysScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("slopmatica.action_keys.title"));
    }

    @Override
    protected void addOptions() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        list.addHeader(Component.translatable("slopmatica.action_keys.new"));
        list.addSmall(
            CycleButton.<String>builder(s -> Component.translatable(SettingToggle.SETTINGS.get(s)), setting)
                .withValues(List.copyOf(SettingToggle.SETTINGS.keySet()))
                .create(0, 0, WIDE, 20, Component.translatable("slopmatica.quick.edit.setting"), (b, v) -> setting = v),
            valuesBox());
        Button add = Button.builder(Component.translatable("slopmatica.action_keys.add"), b -> addToggle()).width(WIDE)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.action_keys.add.tooltip"))).build();
        list.addSmall(add, new StringWidget(WIDE, 20, status, font));

        list.addHeader(Component.translatable("slopmatica.action_keys.list"));
        // The player's own setting switches first, so a new one shows up right here.
        List<QuickAction> actions = new ArrayList<>(QuickAction.all());
        actions.sort(java.util.Comparator.comparing((QuickAction a) -> !a.id().startsWith(SettingToggle.PREFIX)));
        for (QuickAction action : actions) {
            boolean custom = action.id().startsWith(SettingToggle.PREFIX);
            Button name = Button.builder(custom ? Component.literal("✕ ").append(action.label().get()) : action.label().get(), b -> remove(action))
                .width(WIDE).build();
            name.active = custom;
            if (custom) {
                name.setTooltip(Tooltip.create(Component.translatable("slopmatica.action_keys.remove")));
            }
            Button key = Button.builder(listening != null && listening.equals(action.id())
                    ? Component.translatable("slopmatica.action_keys.press").withStyle(ChatFormatting.YELLOW) : keyLabel(action.id()),
                b -> listen(action.id())).width(WIDE).tooltip(Tooltip.create(Component.translatable("slopmatica.controls_keys.quick.tooltip"))).build();
            list.addSmall(name, key);
        }
        if (c.quickToggles.isEmpty()) {
            list.addSmall(new StringWidget(WIDE * 2, 20, Component.translatable("slopmatica.action_keys.none").withColor(0xFFA0A0A0), font), null);
        }
    }

    private EditBox valuesBox() {
        EditBox box = new EditBox(font, 0, 0, WIDE, 20, Component.translatable("slopmatica.quick.edit.values"));
        box.setValue(values);
        box.setHint(Component.translatable("slopmatica.quick.edit.values.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
        box.setResponder(v -> values = v);
        box.setTooltip(Tooltip.create(Component.translatable("slopmatica.quick.edit.values.tooltip")));
        return box;
    }

    /** Makes the toggle (or finds it, if it exists) and waits for its key. */
    private void addToggle() {
        List<String> parsed = new ArrayList<>();
        for (String v : values.split("[,;\\s]+")) {
            if (!v.isBlank()) {
                parsed.add(v.trim().replace("%", ""));
            }
        }
        SettingToggle toggle = new SettingToggle(setting, parsed);
        if (toggle.steps().size() < 2) {
            status = Component.translatable("slopmatica.action_keys.need_values").withColor(0xFFFF8080);
            rebuildWidgets();
            return;
        }
        SlopmaticaConfig c = SlopmaticaConfig.get();
        if (!c.quickToggles.contains(toggle.id())) {
            c.quickToggles.add(toggle.id());
        }
        c.save();
        values = "";
        status = Component.empty();
        listen(toggle.id());
    }

    private void remove(QuickAction action) {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        c.quickToggles.remove(action.id());
        c.quickKeys.remove(action.id());
        c.save();
        rebuildWidgets();
    }

    private void listen(String actionId) {
        listening = actionId;
        status = Component.translatable("slopmatica.action_keys.press").withStyle(ChatFormatting.YELLOW);
        rebuildWidgets();
    }

    private static Component keyLabel(String actionId) {
        String key = SlopmaticaConfig.get().quickKeys.get(actionId);
        if (key == null) {
            return Component.translatable("key.keyboard.unknown");
        }
        try {
            return InputConstants.getKey(key).getDisplayName();
        } catch (IllegalArgumentException e) {
            return Component.literal(key);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            var keys = SlopmaticaConfig.get().quickKeys;
            if (event.isEscape()) {
                keys.remove(listening);
            } else {
                keys.put(listening, InputConstants.getKey(event).getName());
            }
            listening = null;
            status = Component.empty();
            SlopmaticaConfig.get().save();
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        super.removed();
        SlopmaticaConfig.get().save();
    }

    /** init() adds everything to the layout again, so empty it first (or every rebuild stacks another copy). */
    @Override
    protected void rebuildWidgets() {
        double scroll = list != null ? list.scrollAmount() : 0;
        layout.removeChildren();
        super.rebuildWidgets();
        if (list != null) {
            list.setScrollAmount(scroll);
        }
    }
}
