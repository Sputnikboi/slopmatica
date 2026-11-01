package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.verify.MaterialList;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a placement is made of: every item with how many are still missing, the total, and how
 * many you carry. Missing first, so the shopping list is at the top. Counts are taken when the
 * screen opens; Refresh recounts after building.
 */
public class MaterialListScreen extends Screen {
    private static final int DETAIL_COLOR = 0xFF909090;
    private static final int MISSING_COLOR = 0xFFFF7070;
    private static final int DONE_COLOR = 0xFF60D070;
    private static final int ENOUGH_COLOR = 0xFFE0E0E0;

    private final @Nullable Screen parent;
    private final Placement placement;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 48, 33);
    private MaterialList materials;
    private boolean missingOnly = true;
    private ItemList list;
    private StringWidget summary;
    private Button filterButton;

    public MaterialListScreen(@Nullable Screen parent, Placement placement) {
        super(Component.translatable("slopmatica.materials.title", placement.name()));
        this.parent = parent;
        this.placement = placement;
    }

    @Override
    protected void init() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        summary = header.addChild(new StringWidget(Math.min(width - 20, 500), 9, Component.empty(), font));
        list = layout.addToContents(new ItemList(minecraft));

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        filterButton = footer.addChild(Button.builder(Component.empty(), b -> {
            missingOnly = !missingOnly;
            refresh(false);
        }).width(120).build());
        footer.addChild(Button.builder(Component.translatable("slopmatica.materials.refresh"), b -> refresh(true)).width(100).build());
        footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(100).build());

        header.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(list);
        footer.visitWidgets(this::addRenderableWidget);
        refresh(materials == null);
        repositionElements();
    }

    private void refresh(boolean recount) {
        if (recount && minecraft.level != null && minecraft.player != null) {
            materials = MaterialList.count(placement, minecraft.level, minecraft.player.getInventory());
        }
        if (materials == null) {
            return;
        }
        list.replaceEntries(materials.entries().stream()
            .filter(e -> !missingOnly || e.missing() > 0)
            .map(ItemEntry::new)
            .toList());
        list.setScrollAmount(0);
        String text = String.format(Locale.ROOT, "%,d items missing of %,d", materials.missingItems(), materials.totalItems());
        if (materials.unloaded() > 0) {
            text += String.format(Locale.ROOT, "  ·  %,d blocks in unloaded chunks counted as missing", materials.unloaded());
        }
        summary.setMessage(Component.literal(text).withColor(materials.missingItems() == 0 ? DONE_COLOR : DETAIL_COLOR));
        filterButton.setMessage(Component.translatable(missingOnly ? "slopmatica.materials.show_missing" : "slopmatica.materials.show_all"));
        layout.arrangeElements();
    }

    /** 1234 -> "1,234 (19×64 + 18)", so you know how many stacks to grab. */
    private static String amount(int n) {
        if (n < 64) {
            return Integer.toString(n);
        }
        int stacks = n / 64, rest = n % 64;
        return String.format(Locale.ROOT, "%,d (%d×64%s)", n, stacks, rest == 0 ? "" : " + " + rest);
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

    private class ItemList extends ObjectSelectionList<ItemEntry> {
        ItemList(Minecraft minecraft) {
            super(minecraft, MaterialListScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), 20);
        }

        @Override
        public int getRowWidth() {
            return Math.min(460, MaterialListScreen.this.width - 40);
        }
    }

    private class ItemEntry extends ObjectSelectionList.Entry<ItemEntry> {
        private final MaterialList.Entry entry;
        private final ItemStack stack;

        ItemEntry(MaterialList.Entry entry) {
            this.entry = entry;
            this.stack = entry.icon();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int x = getContentX();
            int y = getContentYMiddle();
            graphics.item(stack, x + 2, y - 8);
            String missing = entry.missing() == 0 ? "done" : amount(entry.missing()) + " missing";
            String detail = "of " + amount(entry.total()) + "   have " + amount(entry.have());
            int detailWidth = font.width(detail);
            int missingWidth = font.width(missing);
            int right = getContentRight() - 4;
            graphics.text(font, detail, right - detailWidth, y - 4, DETAIL_COLOR);
            int color = entry.missing() == 0 ? DONE_COLOR : entry.have() >= entry.missing() ? ENOUGH_COLOR : MISSING_COLOR;
            graphics.text(font, missing, right - detailWidth - 12 - missingWidth, y - 4, color);
            int nameWidth = right - detailWidth - missingWidth - 24 - (x + 24);
            graphics.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), Math.max(nameWidth, 20)), x + 24, y - 4, -1);
        }

        @Override
        public Component getNarration() {
            return Component.literal(stack.getHoverName().getString() + ", " + entry.missing() + " missing of " + entry.total());
        }
    }
}
