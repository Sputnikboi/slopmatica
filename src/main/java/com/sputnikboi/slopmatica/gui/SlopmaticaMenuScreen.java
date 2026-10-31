package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** The hub: load, manage placements, options. Opened with the menu key (M by default). */
public class SlopmaticaMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public SlopmaticaMenuScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout column = layout.addToContents(LinearLayout.vertical().spacing(8));
        column.defaultCellSetting().alignHorizontallyCenter();

        column.addChild(Button.builder(Component.translatable("slopmatica.menu.load"), b -> minecraft.gui.setScreen(new SchematicBrowserScreen(this)))
            .width(BUTTON_WIDTH).build());
        int loaded = PlacementManager.INSTANCE.placements().size();
        column.addChild(Button.builder(Component.translatable("slopmatica.menu.placements", loaded), b -> minecraft.gui.setScreen(new PlacementsScreen(this)))
            .width(BUTTON_WIDTH).build());
        Placement active = PlacementManager.INSTANCE.selected().orElse(null);
        Button materials = column.addChild(Button.builder(
            active == null ? Component.translatable("slopmatica.menu.materials.none") : Component.translatable("slopmatica.menu.materials"),
            b -> minecraft.gui.setScreen(new MaterialListScreen(this, active))
        ).width(BUTTON_WIDTH).build());
        materials.active = active != null;
        LinearLayout copyRow = column.addChild(LinearLayout.horizontal().spacing(4));
        copyRow.addChild(Button.builder(Component.translatable("slopmatica.menu.copy"), b -> {
            onClose();
            Clipboard.INSTANCE.copy(minecraft);
        }).width((BUTTON_WIDTH - 8) / 3).tooltip(Tooltip.create(Component.translatable("slopmatica.menu.copy.tooltip"))).build());
        copyRow.addChild(Button.builder(Component.translatable("slopmatica.menu.paste"), b -> {
            onClose();
            Clipboard.INSTANCE.paste(minecraft);
        }).width((BUTTON_WIDTH - 8) / 3).tooltip(Tooltip.create(Component.translatable("slopmatica.menu.paste.tooltip"))).build());
        copyRow.addChild(Button.builder(Component.translatable("slopmatica.menu.clear_selection"), b -> Selection.INSTANCE.clear())
            .width(BUTTON_WIDTH - 8 - 2 * ((BUTTON_WIDTH - 8) / 3)).build());
        column.addChild(Button.builder(Component.translatable("slopmatica.menu.cloud"), b -> minecraft.gui.setScreen(new CloudScreen(this)))
            .width(BUTTON_WIDTH).build());
        column.addChild(Button.builder(Component.translatable("slopmatica.menu.options"), b -> minecraft.gui.setScreen(new SlopmaticaOptionsScreen(this)))
            .width(BUTTON_WIDTH).build());
        column.addChild(Button.builder(hologramLabel(), b -> {
            HologramRenderer.INSTANCE.setHidden(!HologramRenderer.INSTANCE.hidden());
            b.setMessage(hologramLabel());
        }).width(BUTTON_WIDTH).build());
        column.addChild(new StringWidget(Component.translatable("slopmatica.menu.keybinds_hint").withColor(0xFFA0A0A0), font));

        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(BUTTON_WIDTH).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private static Component hologramLabel() {
        return Component.translatable(HologramRenderer.INSTANCE.hidden() ? "slopmatica.menu.hologram_hidden" : "slopmatica.menu.hologram_shown");
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
