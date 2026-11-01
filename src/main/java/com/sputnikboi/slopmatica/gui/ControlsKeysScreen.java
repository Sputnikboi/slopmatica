package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.quick.QuickAction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Which Slopmatica keys are listed in vanilla's Controls screen. Hidden keys keep working and stay
 * in Slopmatica's own Key Binds screen; this only keeps Controls from being flooded.
 */
public class ControlsKeysScreen extends OptionsSubScreen {
    public ControlsKeysScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("slopmatica.controls_keys.title"));
    }

    @Override
    protected void addOptions() {
        KeyMapping.Category current = null;
        List<OptionInstance<?>> row = new ArrayList<>();
        for (KeyMapping key : SlopmaticaKeys.all()) {
            if (key.getCategory() != current) {
                flush(row);
                current = key.getCategory();
                list.addHeader(current.label());
            }
            // Key names are long, so one per row; the cycle button adds the name itself.
            row.add(OptionInstance.createBoolean(key.getName(), OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.controls_keys.tooltip")),
                (caption, value) -> Component.translatable(value ? "slopmatica.controls_keys.shown" : "slopmatica.controls_keys.hidden"),
                SlopmaticaKeys.shownInControls(key), v -> SlopmaticaKeys.setShownInControls(key, v)));
        }
        flush(row);

        // Every Quick Menu action, setting toggles included, can have a key of its own.
        list.addHeader(Component.translatable("slopmatica.controls_keys.quick"));
        for (QuickAction action : QuickAction.all()) {
            Button name = Button.builder(action.label().get(), b -> { }).width(150).build();
            name.active = false;
            Button key = Button.builder(keyLabel(action.id()), b -> {
                listening = action.id();
                b.setMessage(Component.translatable("slopmatica.welcome.key_listening", action.label().get()).withStyle(net.minecraft.ChatFormatting.YELLOW));
            }).width(150).tooltip(Tooltip.create(Component.translatable("slopmatica.controls_keys.quick.tooltip"))).build();
            list.addSmall(name, key);
        }
    }

    private @Nullable String listening;

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
            SlopmaticaConfig.get().save();
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    private void flush(List<OptionInstance<?>> row) {
        if (!row.isEmpty()) {
            row.forEach(list::addBig);
            row.clear();
        }
    }

    @Override
    public void removed() {
        super.removed();
        SlopmaticaConfig.get().save();
    }
}
