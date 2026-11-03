package com.sputnikboi.slopmatica.gui;

import com.mojang.serialization.Codec;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.online.Online;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import com.sputnikboi.slopmatica.share.SharedFolder;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Slopmatica's settings, built from vanilla option widgets so they look and behave like the rest of
 * the options menu. The page shows what most players want; technical settings (thickness, fading,
 * file formats, the cloud address...) only appear with "Advanced settings" on, so the mod stays
 * approachable. Keybinds are not here on purpose: they are behind the Key Binds button.
 */
public class SlopmaticaOptionsScreen extends OptionsSubScreen {
    private static final int KEYS_BUTTON_WIDTH = 90;
    private static final int WIDE = 150;
    private @Nullable Button keyBindsButton;

    public SlopmaticaOptionsScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("slopmatica.options.title"));
    }

    @Override
    protected void addOptions() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        boolean advanced = c.showAdvanced;

        list.addSmall(OptionInstance.createBoolean("slopmatica.options.advanced",
            OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.options.advanced.tooltip")), c.showAdvanced, v -> {
                c.showAdvanced = v;
                // Rebuild so the advanced rows appear or go.
                minecraft.gui.setScreen(new SlopmaticaOptionsScreen(lastScreen));
            }));

        list.addHeader(Component.translatable("slopmatica.options.ghosts"));
        list.addSmall(
            toggle("slopmatica.options.show_ghosts", c.showGhosts, v -> c.showGhosts = v, false),
            slider("slopmatica.options.ghost_opacity", 5, 100, Math.round(c.ghostOpacity * 100), "%", v -> c.ghostOpacity = v / 100.0F, false),
            toggle("slopmatica.options.solid_ghosts", c.solidGhosts, v -> c.solidGhosts = v, false),
            toggle("slopmatica.options.block_info", c.blockInfo, v -> c.blockInfo = v, false)
        );

        list.addHeader(Component.translatable("slopmatica.options.outlines"));
        list.addSmall(toggle("slopmatica.options.show_outlines", c.showOutlines, v -> c.showOutlines = v, false),
            slider("slopmatica.options.outline_width", 1, 400, Math.round(c.outlineWidth * 100), "%", v -> c.outlineWidth = v / 100.0F, true));
        list.addSmall(colorButton(BlockStatus.MISSING), colorButton(BlockStatus.MATCH));
        list.addSmall(colorButton(BlockStatus.VARIANT), colorButton(BlockStatus.WRONG));
        if (advanced) {
            list.addSmall(
                toggle("slopmatica.options.outline_missing", c.outlineMissing, v -> c.outlineMissing = v, true),
                toggle("slopmatica.options.outline_match", c.outlineMatch, v -> c.outlineMatch = v, true),
                toggle("slopmatica.options.outline_variant", c.outlineVariant, v -> c.outlineVariant = v, true),
                toggle("slopmatica.options.outline_wrong", c.outlineWrong, v -> c.outlineWrong = v, true),
                toggle("slopmatica.options.fade_outlines", c.fadeOutlines, v -> c.fadeOutlines = v, false),
                slider("slopmatica.options.outline_distance", 25, 500, c.outlineDistance, "%", v -> c.outlineDistance = v, false)
            );
        }

        list.addHeader(Component.translatable("slopmatica.options.view"));
        list.addSmall(
            toggle("slopmatica.options.view_freecam", c.viewFreecam, v -> c.viewFreecam = v, false),
            toggle("slopmatica.options.view_collision", c.viewCollision, v -> c.viewCollision = v, false),
            toggle("slopmatica.options.exit_view_when_hurt", c.exitViewWhenHurt, v -> c.exitViewWhenHurt = v, false),
            toggle("slopmatica.options.ignore_recommendations", c.ignoreRecommendations, v -> c.ignoreRecommendations = v, false)
        );

        list.addHeader(Component.translatable("slopmatica.options.easy_place"));
        list.addSmall(
            toggle("slopmatica.options.easy_place_on", c.easyPlace, v -> c.easyPlace = v, false),
            cycle("slopmatica.options.easy_place_order", SlopmaticaConfig.EasyPlaceOrder.values(), c.easyPlaceOrder, v -> c.easyPlaceOrder = v),
            toggle("slopmatica.options.easy_place_restrict", c.easyPlaceRestrict, v -> c.easyPlaceRestrict = v, false),
            toggle("slopmatica.options.easy_place_angel", c.easyPlaceAngel, v -> c.easyPlaceAngel = v, false)
        );
        if (advanced) {
            list.addSmall(toggle("slopmatica.options.easy_place_rotate", c.easyPlaceRotate, v -> c.easyPlaceRotate = v, false));
        }

        list.addHeader(Component.translatable("slopmatica.options.paste"));
        list.addSmall(
            toggle("slopmatica.options.include_nbt", c.includeNbt, v -> c.includeNbt = v, false),
            cycle("slopmatica.options.paste_replace", PlacementBuilder.Replace.values(), c.pasteReplace, v -> c.pasteReplace = v)
        );
        list.addSmall(cycle("slopmatica.options.corner_mode", SlopmaticaConfig.CornerMode.values(), c.cornerMode, v -> c.cornerMode = v), null);
        if (advanced) {
            list.addSmall(new OptionInstance<>(
                "slopmatica.options.copy_format",
                v -> Tooltip.create(Component.translatable("slopmatica.options.copy_format." + v.name().toLowerCase(Locale.ROOT) + ".tooltip")),
                (caption, v) -> Component.literal(v.extension),
                new OptionInstance.Enum<>(List.of(SchematicFormats.values()), Codec.STRING.xmap(SchematicFormats::valueOf, Enum::name)),
                c.copyFormat,
                v -> c.copyFormat = v
            ));
        }

        list.addHeader(Component.translatable("slopmatica.options.quick"));
        list.addSmall(
            cycle("slopmatica.quick.style", com.sputnikboi.slopmatica.quick.QuickMenu.Style.values(), c.quickStyle, v -> c.quickStyle = v).createButton(minecraft.options),
            Button.builder(Component.translatable("slopmatica.options.quick_actions"), b -> minecraft.gui.setScreen(new QuickMenuEditScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("slopmatica.options.quick_actions.tooltip"))).width(WIDE).build());
        list.addSmall(
            cycle("slopmatica.quick.mode", com.sputnikboi.slopmatica.quick.QuickMenu.Mode.values(), c.quickMode, v -> c.quickMode = v).createButton(minecraft.options),
            toggle("slopmatica.options.quick_close", c.quickCloseAfterUse, v -> c.quickCloseAfterUse = v, false).createButton(minecraft.options));
        list.addSmall(
            toggle("slopmatica.options.wheel_close", c.wheelCloseAfterUse, v -> c.wheelCloseAfterUse = v, false).createButton(minecraft.options),
            toggle("slopmatica.wheel.reset_page", c.wheelResetPage, v -> c.wheelResetPage = v, false).createButton(minecraft.options));

        list.addHeader(Component.translatable("slopmatica.options.controls"));
        list.addSmall(
            Button.builder(Component.translatable("slopmatica.options.keybinds_all"), b -> minecraft.gui.setScreen(new SlopmaticaKeyBindsScreen(this))).width(WIDE).build(),
            Button.builder(Component.translatable("slopmatica.options.controls_keys"), b -> minecraft.gui.setScreen(new ControlsKeysScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("slopmatica.options.controls_keys.tooltip"))).width(WIDE).build()
        );
        Button tour = Button.builder(Component.translatable("slopmatica.options.tour"), b -> {
            minecraft.gui.setScreen(null);
            com.sputnikboi.slopmatica.tour.FeatureTour.INSTANCE.start(minecraft);
        }).tooltip(Tooltip.create(Component.translatable("slopmatica.options.tour.tooltip"))).width(WIDE).build();
        tour.active = minecraft.level != null;
        list.addSmall(tour, null);

        list.addHeader(Component.translatable("slopmatica.options.other"));
        Button importButton = Button.builder(Component.translatable("slopmatica.options.import_litematica"), b -> minecraft.gui.setScreen(new LitematicaImportScreen(this)))
            .tooltip(Tooltip.create(Component.translatable("slopmatica.options.import_litematica.tooltip"))).width(WIDE).build();
        if (Online.available()) {
            list.addSmall(toggle("slopmatica.options.telemetry", c.telemetry, v -> c.telemetry = v, false).createButton(minecraft.options), importButton);
        } else {
            list.addSmall(importButton, null);
        }
        list.addSmall(Button.builder(Component.translatable("slopmatica.options.welcome"), b -> minecraft.gui.setScreen(new WelcomeScreen(this))).width(WIDE).build(),
            Button.builder(Component.translatable("slopmatica.options.plan"), b -> minecraft.gui.setScreen(new InventoryPlanScreen(this))).width(WIDE)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.menu.plan.tooltip"))).build());

        // Sharing between instances on this computer.
        list.addHeader(Component.translatable("slopmatica.options.sharing"));
        list.addSmall(
            toggle("slopmatica.options.share_schematics", c.shareSchematics, v -> c.shareSchematics = v, false),
            OptionInstance.createBoolean("slopmatica.options.share_settings",
                OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.options.share_settings.tooltip")), c.shareSettings, v -> {
                    c.shareSettings = v;
                    if (v && !SharedFolder.pull(c)) {
                        SharedFolder.push(c); // Nothing shared yet: this instance's settings start it off.
                    }
                    c.save();
                    HologramRenderer.INSTANCE.rebuildAll();
                    minecraft.gui.setScreen(new SlopmaticaOptionsScreen(lastScreen)); // Settings may have changed.
                })
        );
        if (advanced) {
            EditBox folder = new EditBox(font, 0, 0, WIDE, 20, Component.translatable("slopmatica.options.shared_folder"));
            folder.setMaxLength(1024);
            folder.setValue(c.sharedFolder == null ? "" : c.sharedFolder);
            folder.setHint(Component.literal(SharedFolder.display(SharedFolder.defaultFolder())).withStyle(EditBox.SEARCH_HINT_STYLE));
            folder.setResponder(text -> pendingFolder = text);
            folder.setTooltip(Tooltip.create(Component.translatable("slopmatica.options.shared_folder.tooltip", SharedFolder.display(SharedFolder.defaultFolder()))));
            if (SharedFolder.isCustom()) {
                folder.setTextColor(0xFFFFA030);
            }
            list.addSmall(folder, Button.builder(Component.translatable("slopmatica.options.shared_folder.default"), b -> folder.setValue("")).width(WIDE).build());
        }

        if (advanced) {
            var dev = com.sputnikboi.slopmatica.dev.StressTest.INSTANCE;
            list.addHeader(Component.translatable("slopmatica.options.developer"));
            list.addSmall(
                toggle("slopmatica.options.dev_overlay", c.devOverlay, v -> c.devOverlay = v, false).createButton(minecraft.options),
                Button.builder(Component.translatable("slopmatica.options.dev_rebuild"), b -> dev.rebuild()).width(WIDE).build());
            list.addSmall(
                Button.builder(Component.literal("Test Build 64³"), b -> dev.spawn(minecraft, 64, 64, 64)).width(WIDE)
                    .tooltip(Tooltip.create(Component.translatable("slopmatica.options.dev_build.tooltip"))).build(),
                Button.builder(Component.literal("Test Build 128³"), b -> dev.spawn(minecraft, 128, 128, 128)).width(WIDE)
                    .tooltip(Tooltip.create(Component.translatable("slopmatica.options.dev_build.tooltip"))).build());
            list.addSmall(
                Button.builder(Component.literal("Test Build 256×128×256"), b -> dev.spawn(minecraft, 256, 128, 256)).width(WIDE)
                    .tooltip(Tooltip.create(Component.translatable("slopmatica.options.dev_build.tooltip"))).build(),
                Button.builder(Component.translatable("slopmatica.options.dev_copies"), b -> dev.copies(minecraft, 24)).width(WIDE)
                    .tooltip(Tooltip.create(Component.translatable("slopmatica.options.dev_copies.tooltip"))).build());
            list.addSmall(Button.builder(Component.translatable("slopmatica.options.dev_clear"), b -> dev.clear()).width(WIDE).build(), null);

        }
        if (Online.available() && advanced) {
            list.addHeader(Component.translatable("slopmatica.options.cloud"));
            EditBox url = new EditBox(font, 0, 0, WIDE, 20, Component.translatable("slopmatica.cloud.server"));
            url.setMaxLength(256);
            url.setValue(Online.cloudUrl());
            url.setHint(Component.literal(Online.defaultCloudUrl()).withStyle(EditBox.SEARCH_HINT_STYLE));
            url.setResponder(text -> pendingUrl = text);
            url.setTooltip(Tooltip.create(Component.translatable("slopmatica.options.cloud_url.tooltip")));
            list.addSmall(url, Button.builder(Component.translatable("slopmatica.options.cloud_url.reset"), b -> url.setValue(Online.defaultCloudUrl())).width(WIDE).build());
        }
    }

    private @Nullable String pendingUrl;
    private @Nullable String pendingFolder;

    private Button colorButton(BlockStatus status) {
        String key = "slopmatica.options.outline_" + status.name().toLowerCase(Locale.ROOT);
        return Button.builder(Component.translatable("slopmatica.options.color_of", Component.translatable(key)).withColor(status.color()),
            b -> minecraft.gui.setScreen(new ColorPickerScreen(this, status))).width(WIDE)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.options.color.tooltip"))).build();
    }

    private boolean shownBefore;

    /** 26.3 keeps a screen's widgets when you come back to it: rebuild, so changes show at once. */
    @Override
    public void added() {
        super.added();
        if (shownBefore) {
            rebuildWidgets();
        }
        shownBefore = true;
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

    /** A cycle button over an enum; labels and tooltips come from {@code key.<value>} and {@code key.<value>.tooltip}. */
    private static <E extends Enum<E>> OptionInstance<E> cycle(String key, E[] values, E value, Consumer<E> setter) {
        Class<E> type = values[0].getDeclaringClass();
        return new OptionInstance<E>(
            key,
            v -> Tooltip.create(Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT) + ".tooltip")),
            // Cycle buttons add the caption themselves.
            (caption, v) -> Component.translatable(key + "." + v.name().toLowerCase(Locale.ROOT)),
            new OptionInstance.Enum<E>(List.of(values), Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name)),
            value,
            setter::accept
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
        if (pendingUrl != null && !pendingUrl.isBlank()) {
            Online.setCloudUrl(pendingUrl);
        }
        if (pendingFolder != null) {
            SlopmaticaConfig c = SlopmaticaConfig.get();
            c.sharedFolder = pendingFolder.isBlank() ? null : pendingFolder.trim();
            if (SharedFolder.isCustom()) {
                // New installs only ever look in the default folder.
                com.sputnikboi.slopmatica.SlopmaticaActions.chat(Component.translatable("slopmatica.options.shared_folder.warning",
                    SharedFolder.display(SharedFolder.defaultFolder())).withColor(com.sputnikboi.slopmatica.SlopmaticaActions.WARNING));
            }
        }
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
