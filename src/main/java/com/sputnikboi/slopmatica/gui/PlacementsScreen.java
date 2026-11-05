package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * Loaded placements. Clicking one makes it the active placement, which the buttons here, the
 * keybinds and the commands all act on.
 */
public class PlacementsScreen extends Screen {
    private static final int DETAIL_COLOR = 0xFF909090;
    private static final int ACTIVE_COLOR = 0xFF55CCFF;
    private static final int BUTTON_WIDTH = 96;
    /** Each build's icon, the same 3D render the schematic browser shows. */
    private static final int ICON = 32;

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
    private PlacementList list;
    private Button visibilityButton;
    private Button mirrorButton;
    private Button[] placementButtons;

    public PlacementsScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.placements.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        list = layout.addToContents(new PlacementList(minecraft));

        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout row1 = footer.addChild(LinearLayout.horizontal().spacing(6));
        LinearLayout row2 = footer.addChild(LinearLayout.horizontal().spacing(6));
        visibilityButton = row1.addChild(button("", p -> SlopmaticaActions.setVisible(p, !p.visible(), this::status)));
        Button moveHere = row1.addChild(button("slopmatica.placements.move_here", p -> {
            if (minecraft.player != null) {
                SlopmaticaActions.moveOriginPointTo(p, minecraft.player.blockPosition(), this::status);
            }
        }));
        Button rotate = row1.addChild(button("slopmatica.placements.rotate", p -> SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, this::status)));
        mirrorButton = row2.addChild(button("", p -> SlopmaticaActions.setMirror(p, SlopmaticaActions.nextMirror(p.transform().mirror()), this::status)));
        Button unload = row2.addChild(button("slopmatica.placements.unload", p -> SlopmaticaActions.unload(p, this::status)));
        Button materials = row1.addChild(button("slopmatica.placements.materials", p -> minecraft.gui.setScreen(new MaterialListScreen(this, p))));
        Button build = row2.addChild(button("slopmatica.placements.build", p -> {
            if (PlacementBuilder.INSTANCE.start(minecraft, p, this::status)) {
                minecraft.gui.setScreen(null);
            }
        }));
        row2.addChild(button("slopmatica.info.button", p -> minecraft.gui.setScreen(new SchematicInfoScreen(this, p.schematic().source(), p))));
        row2.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(BUTTON_WIDTH).build());
        placementButtons = new Button[] {visibilityButton, moveHere, rotate, materials, mirrorButton, unload, build};

        addRenderableWidget(list);
        footer.visitWidgets(this::addRenderableWidget);
        layout.visitWidgets(w -> {
            if (!children().contains(w)) {
                addRenderableWidget(w);
            }
        });
        refresh();
        repositionElements();
    }

    private Button button(String key, Consumer<Placement> action) {
        return Button.builder(key.isEmpty() ? Component.empty() : Component.translatable(key), b -> PlacementManager.INSTANCE.selected().ifPresent(p -> {
            action.accept(p);
            refresh();
        })).width(BUTTON_WIDTH).build();
    }

    /** Button feedback is already visible in the list, so it only goes to the action bar. */
    private void status(Component message) {
        SlopmaticaActions.actionBar(message);
    }

    private void refresh() {
        Placement active = PlacementManager.INSTANCE.selected().orElse(null);
        list.replaceEntries(PlacementManager.INSTANCE.placements().stream().map(PlacementEntry::new).toList());
        for (PlacementEntry entry : list.children()) {
            if (entry.placement == active) {
                list.setSelected(entry);
            }
        }
        for (Button b : placementButtons) {
            b.active = active != null;
        }
        visibilityButton.setMessage(Component.translatable(active != null && !active.visible() ? "slopmatica.placements.show" : "slopmatica.placements.hide"));
        mirrorButton.setMessage(Component.translatable("slopmatica.placements.mirror", active == null ? "-" : SlopmaticaActions.mirrorLabel(active.transform().mirror())));
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private class PlacementList extends ObjectSelectionList<PlacementEntry> {
        PlacementList(Minecraft minecraft) {
            super(minecraft, PlacementsScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), ICON + 4);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, PlacementsScreen.this.width - 40);
        }
    }

    private class PlacementEntry extends ObjectSelectionList.Entry<PlacementEntry> {
        final Placement placement;

        PlacementEntry(Placement placement) {
            this.placement = placement;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            boolean active = PlacementManager.INSTANCE.selected().orElse(null) == placement;
            BlockPos o = placement.transform().origin();
            var size = placement.schematic().bounds();
            String detail = String.format(Locale.ROOT, "at %d %d %d  ·  %d x %d x %d  ·  rotated %s  ·  mirror %s%s",
                o.getX(), o.getY(), o.getZ(), size.getXSpan(), size.getYSpan(), size.getZSpan(),
                SlopmaticaActions.rotationLabel(placement.transform().rotation()), SlopmaticaActions.mirrorLabel(placement.transform().mirror()),
                placement.visible() ? "" : "  ·  hidden");
            // Builds without a file (pasted, test builds) have no icon.
            java.nio.file.Path source = placement.schematic().source();
            net.minecraft.resources.Identifier icon = source != null && java.nio.file.Files.isRegularFile(source)
                ? com.sputnikboi.slopmatica.render.SchematicThumbnails.INSTANCE.get(source) : null;
            int iconY = getContentYMiddle() - ICON / 2;
            if (icon != null) {
                int tex = com.sputnikboi.slopmatica.render.SchematicThumbnails.SIZE;
                graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon, getContentX() + 1, iconY, 0, 0, ICON, ICON, tex, tex, tex, tex);
            } else {
                graphics.fill(getContentX() + 1, iconY, getContentX() + 1 + ICON, iconY + ICON, 0x40000000);
            }
            int x = getContentX() + ICON + 6, w = getContentWidth() - ICON - 8, y = getContentYMiddle() - 10;
            graphics.text(font, font.plainSubstrByWidth(placement.name(), w), x, y, active ? ACTIVE_COLOR : -1);
            graphics.text(font, font.plainSubstrByWidth(detail, w), x, y + 11, DETAIL_COLOR);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            PlacementManager.INSTANCE.select(placement);
            refresh();
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(placement.name());
        }
    }
}
