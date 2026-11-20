package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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

        // Keys for Quick Menu actions and setting toggles have their own screen.
        list.addHeader(Component.translatable("slopmatica.controls_keys.quick"));
        list.addSmall(Button.builder(Component.translatable("slopmatica.quick.action_keys"), b -> minecraft.gui.setScreen(new ActionKeysScreen(this)))
            .width(150).tooltip(Tooltip.create(Component.translatable("slopmatica.quick.action_keys.tooltip"))).build(), null);
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
