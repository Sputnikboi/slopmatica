package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.spawn.SpawnCheck;
import com.sputnikboi.slopmatica.update.Updates;
import java.net.URI;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
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

    private static final Identifier ICON = Identifier.fromNamespaceAndPath("slopmatica", "icon.png");
    private static final int CREDITS_WIDTH = 90;
    private LinearLayout column;
    private @Nullable Button updateButton;
    private Button creditsButton;

    @Override
    protected void init() {
        Updates.INSTANCE.check(false);
        layout.addTitleHeader(title, font);
        column = layout.addToContents(LinearLayout.vertical().spacing(8));
        column.defaultCellSetting().alignHorizontallyCenter();

        column.addChild(Button.builder(Component.translatable("slopmatica.menu.load"), b -> minecraft.gui.setScreen(new SchematicBrowserScreen(this)))
            .width(BUTTON_WIDTH).build());
        int loaded = PlacementManager.INSTANCE.placements().size();
        column.addChild(Button.builder(Component.translatable("slopmatica.menu.placements", loaded), b -> minecraft.gui.setScreen(new PlacementsScreen(this)))
            .width(BUTTON_WIDTH).build());
        Placement active = PlacementManager.INSTANCE.selected().orElse(null);
        LinearLayout materialRow = column.addChild(LinearLayout.horizontal().spacing(4));
        Button materials = materialRow.addChild(Button.builder(
            active == null ? Component.translatable("slopmatica.menu.materials.none") : Component.translatable("slopmatica.menu.materials"),
            b -> minecraft.gui.setScreen(new MaterialListScreen(this, active))
        ).width((BUTTON_WIDTH - 4) / 2).build());
        materials.active = active != null;
        Button plan = materialRow.addChild(Button.builder(Component.translatable("slopmatica.menu.plan"), b -> minecraft.gui.setScreen(new InventoryPlanScreen(this)))
            .width((BUTTON_WIDTH - 4) / 2).tooltip(Tooltip.create(Component.translatable("slopmatica.menu.plan.tooltip"))).build());
        plan.active = active != null;
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
        LinearLayout toggleRow = column.addChild(LinearLayout.horizontal().spacing(4));
        toggleRow.addChild(Button.builder(hologramLabel(), b -> {
            HologramRenderer.INSTANCE.setHidden(!HologramRenderer.INSTANCE.hidden());
            b.setMessage(hologramLabel());
        }).width((BUTTON_WIDTH - 4) / 2).build());
        toggleRow.addChild(Button.builder(spawnLabel(), b -> {
            SpawnCheck.INSTANCE.toggle(minecraft);
            b.setMessage(spawnLabel());
        }).width((BUTTON_WIDTH - 4) / 2).tooltip(Tooltip.create(Component.translatable("slopmatica.menu.spawn.tooltip"))).build());
        column.addChild(new StringWidget(Component.translatable("slopmatica.menu.keybinds_hint").withColor(0xFFA0A0A0), font));

        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(BUTTON_WIDTH).build());
        layout.visitWidgets(this::addRenderableWidget);

        // Bottom right: credits, linking to the website's feedback page.
        creditsButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.menu.credits"),
            b -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(CloudSync.INSTANCE.account().url + "/feedback?mod="
                + java.net.URLEncoder.encode(Updates.modVersion(), java.nio.charset.StandardCharsets.UTF_8)
                + "&mc=" + java.net.URLEncoder.encode(net.minecraft.SharedConstants.getCurrentVersion().name(), java.nio.charset.StandardCharsets.UTF_8))))
            .tooltip(Tooltip.create(Component.translatable("slopmatica.menu.credits.tooltip"))).width(CREDITS_WIDTH).build());
        // Top right, beside the version: a download icon when a newer release exists.
        updateButton = addRenderableWidget(Button.builder(Component.literal("⬇"), b -> {
            Updates.Release release = Updates.INSTANCE.update();
            if (release != null && !Updates.INSTANCE.pending()) {
                Updates.INSTANCE.install(release);
            }
        }).width(20).build());
        repositionElements();
    }

    /** A dark see-through panel behind the buttons, so they read well over any world. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        if (column != null) {
            int pad = 10;
            graphics.fill(column.getX() - pad, column.getY() - pad, column.getX() + column.getWidth() + pad, column.getY() + column.getHeight() + pad, 0x90000000);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, width / 2 - font.width(title) / 2 - 22, 6, 0, 0, 18, 18, 128, 128, 128, 128);

        // Status, top right: version (+ update), server reachable, cloud account.
        int right = width - 6;
        Updates.Release update = Updates.INSTANCE.update();
        updateButton.visible = update != null;
        updateButton.active = update != null && !Updates.INSTANCE.pending();
        updateButton.setTooltip(update == null ? null : Tooltip.create(Component.translatable(
            Updates.INSTANCE.pending() ? "slopmatica.menu.update_pending" : "slopmatica.menu.update", update.version())));
        String version = "Slopmatica " + Updates.modVersion();
        int versionRight = update != null ? right - 24 : right;
        graphics.text(font, version, versionRight - font.width(version), 8, 0xFFE0E0E0);

        Updates.Online online = Updates.INSTANCE.online();
        Component status = switch (online) {
            case ONLINE -> Component.translatable("slopmatica.menu.online").withColor(0xFF55DD55);
            case OFFLINE -> Component.translatable("slopmatica.menu.offline").withColor(0xFFFF6060);
            case UNKNOWN -> Component.translatable("slopmatica.menu.checking").withColor(0xFFA0A0A0);
        };
        graphics.text(font, status, right - font.width(status), 20, -1);
        Component cloud = CloudSync.INSTANCE.loggedIn()
            ? Component.translatable("slopmatica.menu.linked", CloudSync.INSTANCE.account().name).withColor(0xFF55CCFF)
            : Component.translatable("slopmatica.menu.not_linked").withColor(0xFFA0A0A0);
        graphics.text(font, cloud, right - font.width(cloud), 32, -1);
    }

    private static Component spawnLabel() {
        return Component.translatable(SpawnCheck.INSTANCE.active() ? "slopmatica.menu.spawn.on" : "slopmatica.menu.spawn.off");
    }

    private static Component hologramLabel() {
        return Component.translatable(HologramRenderer.INSTANCE.hidden() ? "slopmatica.menu.hologram_hidden" : "slopmatica.menu.hologram_shown");
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (creditsButton != null) {
            creditsButton.setPosition(width - CREDITS_WIDTH - 6, height - 26);
        }
        if (updateButton != null) {
            updateButton.setPosition(width - 26, 2);
            updateButton.setHeight(16);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
