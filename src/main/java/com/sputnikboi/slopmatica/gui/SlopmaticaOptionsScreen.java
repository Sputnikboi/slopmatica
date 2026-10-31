package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import com.mojang.serialization.Codec;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Slopmatica's settings, built from vanilla option widgets so they look and behave like the rest of
 * the options menu. Keybinds are not here on purpose: they are in Controls > Key Binds.
 */
public class SlopmaticaOptionsScreen extends OptionsSubScreen {
    private static final int KEYS_BUTTON_WIDTH = 90;
    private @Nullable Button keyBindsButton;

    public SlopmaticaOptionsScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("slopmatica.options.title"));
    }

    @Override
    protected void addOptions() {
        SlopmaticaConfig c = SlopmaticaConfig.get();

        list.addHeader(Component.translatable("slopmatica.options.ghosts"));
        list.addSmall(
            toggle("slopmatica.options.show_ghosts", c.showGhosts, v -> c.showGhosts = v, false),
            slider("slopmatica.options.ghost_opacity", 5, 100, Math.round(c.ghostOpacity * 100), "%", v -> c.ghostOpacity = v / 100.0F, false)
        );

        list.addHeader(Component.translatable("slopmatica.options.outlines"));
        list.addSmall(
            toggle("slopmatica.options.show_outlines", c.showOutlines, v -> c.showOutlines = v, false),
            slider("slopmatica.options.outline_width", 1, 400, Math.round(c.outlineWidth * 100), "%", v -> c.outlineWidth = v / 100.0F, true)
        );
        list.addSmall(
            toggle("slopmatica.options.outline_missing", c.outlineMissing, v -> c.outlineMissing = v, true),
            toggle("slopmatica.options.outline_match", c.outlineMatch, v -> c.outlineMatch = v, true),
            toggle("slopmatica.options.outline_variant", c.outlineVariant, v -> c.outlineVariant = v, true),
            toggle("slopmatica.options.outline_wrong", c.outlineWrong, v -> c.outlineWrong = v, true)
        );
        list.addSmall(
            toggle("slopmatica.options.fade_outlines", c.fadeOutlines, v -> c.fadeOutlines = v, false),
            slider("slopmatica.options.fade_start", 4, 128, c.fadeStart, " blocks", v -> {
                c.fadeStart = v;
                c.fadeEnd = Math.max(c.fadeEnd, v + 4);
            }, false),
            slider("slopmatica.options.fade_end", 8, 256, c.fadeEnd, " blocks", v -> {
                c.fadeEnd = v;
                c.fadeStart = Math.min(c.fadeStart, v - 4);
            }, false)
        );

        list.addHeader(Component.translatable("slopmatica.options.paste"));
        list.addSmall(new OptionInstance<>(
            "slopmatica.options.paste_replace",
            v -> Tooltip.create(Component.translatable("slopmatica.options.paste_replace." + v.name().toLowerCase(Locale.ROOT) + ".tooltip")),
            // Cycle buttons add the caption themselves.
            (caption, v) -> Component.translatable("slopmatica.options.paste_replace." + v.name().toLowerCase(Locale.ROOT)),
            new OptionInstance.Enum<>(List.of(PlacementBuilder.Replace.values()), Codec.STRING.xmap(PlacementBuilder.Replace::valueOf, Enum::name)),
            c.pasteReplace,
            v -> c.pasteReplace = v
        ));
    }

    @Override
    protected void init() {
        super.init();
        // Bottom right, out of the way of Done: straight to this mod's keys.
        keyBindsButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.options.keybinds"),
            b -> minecraft.gui.setScreen(new SlopmaticaKeyBindsScreen(this))).width(KEYS_BUTTON_WIDTH).build());
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (keyBindsButton != null) {
            keyBindsButton.setPosition(width - KEYS_BUTTON_WIDTH - 6, height - 26);
        }
    }

    /**
     * @param baked true when the option is baked into the section meshes, so changing it needs a
     *              rebuild; everything else is read every frame and applies instantly
     */
    private static OptionInstance<Boolean> toggle(String key, boolean value, Consumer<Boolean> setter, boolean baked) {
        return OptionInstance.createBoolean(key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")), value, v -> {
            setter.accept(v);
            changed(baked);
        });
    }

    private static OptionInstance<Integer> slider(String key, int min, int max, int value, String unit, Consumer<Integer> setter, boolean baked) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(v + unit)),
            new OptionInstance.IntRange(min, max),
            Math.clamp(value, min, max),
            v -> {
                setter.accept(v);
                changed(baked);
            }
        );
    }

    private static void changed(boolean baked) {
        if (baked) {
            HologramRenderer.INSTANCE.rebuildAll();
        }
    }

    @Override
    public void removed() {
        super.removed();
        SlopmaticaConfig.get().save();
    }
}
