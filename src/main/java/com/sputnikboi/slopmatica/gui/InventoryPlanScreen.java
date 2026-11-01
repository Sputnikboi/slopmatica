package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.plan.InventoryPlan;
import com.sputnikboi.slopmatica.plan.InventoryPlanner;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The inventory plan: what to bring for the next part of the build and where it goes. Settings on
 * the left (each change recalculates); the plan on the right, one row per item, with what doesn't
 * fit in red. Right click a row to leave that item out.
 */
public class InventoryPlanScreen extends Screen {
    private static final int LEFT_W = 170;
    private static final int ROW = 18;

    private final @Nullable Screen parent;
    private int scroll;

    private record Row(Item item, long count, int stacks, Set<InventoryPlan.Where> where, boolean overflow) {
    }

    private List<Row> rows = List.of();

    public InventoryPlanScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.plan.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        int x = 10, y = 28, step = 22;
        addRenderableWidget(CycleButton.<InventoryPlanner.Scope>builder(v -> Component.translatable("slopmatica.plan.scope." + v.name().toLowerCase(Locale.ROOT)), c.planScope)
            .withValues(InventoryPlanner.Scope.values())
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.scope." + v.name().toLowerCase(Locale.ROOT) + ".tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.scope"), (b, v) -> changed(() -> c.planScope = v)));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.planAutoSlots)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.auto_slots.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.auto_slots"), (b, v) -> changed(() -> c.planAutoSlots = v, true)));
        y += step;
        IntSlider slots = addRenderableWidget(new IntSlider(x, y, LEFT_W, "slopmatica.plan.slots", 1, 36, c.planSlots, v -> c.planSlots = v));
        slots.active = !c.planAutoSlots; // Greyed out while every free slot is used.
        y += step;
        int maxShulkers = c.planUnlimitedShulkers ? 200 : 53;
        addRenderableWidget(new IntSlider(x, y, LEFT_W, "slopmatica.plan.shulkers", 0, maxShulkers, Math.min(c.planShulkers, maxShulkers), v -> c.planShulkers = v))
            .setTooltip(Tooltip.create(Component.translatable("slopmatica.plan.shulkers.tooltip")));
        y += step;
        if (c.showAdvanced) {
            addRenderableWidget(CycleButton.onOffBuilder(c.planUnlimitedShulkers)
                .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.unlimited.tooltip")))
                .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.unlimited"), (b, v) -> changed(() -> c.planUnlimitedShulkers = v, true)));
            y += step;
        }
        addRenderableWidget(CycleButton.onOffBuilder(c.planUseCarriedShulkers)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.carried.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.carried"), (b, v) -> changed(() -> c.planUseCarriedShulkers = v)));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.planIgnoreNbt)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.ignore_nbt.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.ignore_nbt"), (b, v) -> changed(() -> c.planIgnoreNbt = v)));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.planVariants)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.variants.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.variants"), (b, v) -> changed(() -> c.planVariants = v)));
        y += step;
        addRenderableWidget(CycleButton.<InventoryPlanner.Sort>builder(v -> Component.translatable("slopmatica.plan.sort." + v.name().toLowerCase(Locale.ROOT)), c.planSort)
            .withValues(InventoryPlanner.Sort.values())
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.sort"), (b, v) -> changed(() -> c.planSort = v)));
        y += step;
        if (c.showAdvanced) {
            addRenderableWidget(CycleButton.onOffBuilder(c.planForgetOnLeave)
                .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.forget.tooltip")))
                .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.forget"), (b, v) -> changed(() -> c.planForgetOnLeave = v)));
            y += step;
        }
        addRenderableWidget(CycleButton.onOffBuilder(c.planSideList)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.side.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.side"), (b, v) -> changed(() -> c.planSideList = v)));
        y += step;
        addRenderableWidget(CycleButton.onOffBuilder(c.planShowInInventory)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.plan.show.tooltip")))
            .create(x, y, LEFT_W, 20, Component.translatable("slopmatica.plan.show"), (b, v) -> changed(() -> c.planShowInInventory = v)));
        y += step;
        Button reset = addRenderableWidget(Button.builder(Component.translatable("slopmatica.plan.ignored", c.planIgnored.size()), b -> changed(() -> c.planIgnored.clear(), true))
            .bounds(x, y, LEFT_W, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.plan.ignored.tooltip"))).build());
        reset.active = !c.planIgnored.isEmpty();
        y += step + 4;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.plan.calculate").withStyle(ChatFormatting.BOLD), b -> recalculate())
            .bounds(x, y, LEFT_W, 20).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 75, height - 26, 150, 20).build());
        if (InventoryPlanner.INSTANCE.plan() == null) {
            InventoryPlanner.INSTANCE.calculate(minecraft);
        }
        buildRows();
    }

    private void changed(Runnable apply) {
        changed(apply, false);
    }

    /** Applies a setting, saves, recalculates; {@code relayout} rebuilds the widgets (greying out, labels). */
    private void changed(Runnable apply, boolean relayout) {
        apply.run();
        SlopmaticaConfig.get().save();
        recalculate();
        if (relayout) {
            rebuildWidgets();
        }
    }

    private void recalculate() {
        InventoryPlanner.INSTANCE.calculate(minecraft);
        buildRows();
    }

    private void buildRows() {
        InventoryPlanner.Plan plan = InventoryPlanner.INSTANCE.plan();
        if (plan == null) {
            rows = List.of();
            return;
        }
        Map<Item, long[]> counts = new LinkedHashMap<>();
        Map<Item, Set<InventoryPlan.Where>> where = new LinkedHashMap<>();
        for (InventoryPlan.Stack<Item> s : plan.result().stacks()) {
            long[] c = counts.computeIfAbsent(s.item(), k -> new long[2]);
            c[0] += s.count();
            if (s.where() != InventoryPlan.Where.TOP_UP) {
                c[1]++;
            }
            where.computeIfAbsent(s.item(), k -> EnumSet.noneOf(InventoryPlan.Where.class)).add(s.where());
        }
        List<Row> out = new ArrayList<>();
        counts.forEach((item, c) -> out.add(new Row(item, c[0], (int) c[1], where.get(item), false)));
        plan.result().overflow().forEach((item, n) -> out.add(new Row(item, n, 0, Set.of(), true)));
        rows = out;
        scroll = Math.min(scroll, Math.max(0, rows.size() - 1));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 10, -1);
        InventoryPlanner.Plan plan = InventoryPlanner.INSTANCE.plan();
        int x = LEFT_W + 24, y = 28, w = width - x - 10;
        if (plan == null) {
            g.text(font, Component.translatable("slopmatica.plan.none"), x, y, 0xFFA0A0A0);
            return;
        }
        InventoryPlan.Result<Item> r = plan.result();
        String header = Component.translatable("slopmatica.plan.header", r.stacks().size(), r.shulkers(), r.enderShulkers(), r.freeSlotsLeft()).getString();
        g.text(font, header, x, y, 0xFFFFFFFF);
        int extraLines = 0;
        if (plan.truncated()) {
            g.text(font, Component.translatable("slopmatica.plan.truncated"), x, y + 10, 0xFFFFA030);
            extraLines++;
        }
        if (!plan.tools().isEmpty()) {
            List<String> tools = new ArrayList<>();
            for (InventoryPlanner.Tool t : plan.tools()) {
                tools.add((t.uses() > 0 ? Component.translatable("slopmatica.plan.tool.uses", t.kind(), t.uses())
                    : Component.translatable("slopmatica.plan.tool.variants", t.kind())).getString() + (t.carried() ? " ✔" : ""));
            }
            g.text(font, font.plainSubstrByWidth(Component.translatable("slopmatica.plan.tools").getString() + " " + String.join(", ", tools), w), x, y + 10 + extraLines * 10, 0xFFB0D0FF);
            extraLines++;
        }
        int top = y + 14 + extraLines * 10, bottom = height - 32;
        int visible = Math.max(1, (bottom - top) / ROW);
        if (rows.isEmpty()) {
            g.text(font, Component.translatable("slopmatica.plan.nothing"), x, top + 4, 0xFF60E060);
        }
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int ry = top + i * ROW;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW;
            if (hovered) {
                g.fill(x, ry, x + w, ry + ROW, 0x30FFFFFF);
            }
            ItemStack stack = new ItemStack(row.item());
            g.item(stack, x + 1, ry + 1);
            int max = Math.max(1, stack.getMaxStackSize());
            String amount = row.count() >= max && max > 1
                ? (row.count() / max) + " × " + max + (row.count() % max > 0 ? " + " + row.count() % max : "")
                : String.valueOf(row.count());
            int color = row.overflow() ? 0xFFFF6060 : 0xFFE0E0E0;
            g.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w / 2 - 24), x + 20, ry + 5, color);
            g.text(font, amount, x + w / 2, ry + 5, color);
            String place = row.overflow() ? Component.translatable("slopmatica.plan.where.overflow").getString() : whereLabel(row.where());
            g.text(font, place, x + w - font.width(place) - 2, ry + 5, row.overflow() ? 0xFFFF6060 : 0xFF909090);
            if (hovered) {
                g.setTooltipForNextFrame(font, List.of(stack.getHoverName(), Component.translatable("slopmatica.plan.row.ignore").withStyle(ChatFormatting.DARK_GRAY)),
                    Optional.empty(), mouseX, mouseY);
            }
        }
        if (rows.size() > visible) {
            g.text(font, Component.translatable("slopmatica.panel.more", scroll + 1, Math.min(rows.size(), scroll + visible), rows.size()), x, bottom - 2, 0xFF909090);
        }
    }

    private static String whereLabel(Set<InventoryPlan.Where> where) {
        List<String> parts = new ArrayList<>();
        for (InventoryPlan.Where w : where) {
            parts.add(Component.translatable("slopmatica.plan.where." + w.name().toLowerCase(Locale.ROOT)).getString());
        }
        return String.join(", ", parts);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        InventoryPlanner.Plan plan = InventoryPlanner.INSTANCE.plan();
        int x = LEFT_W + 24, top = 28 + 14 + (plan == null ? 0 : ((plan.truncated() ? 10 : 0) + (plan.tools().isEmpty() ? 0 : 10)));
        int index = scroll + (int) ((event.y() - top) / ROW);
        if (event.x() >= x && event.y() >= top && index >= 0 && index < rows.size()) {
            Item item = rows.get(index).item();
            changed(() -> SlopmaticaConfig.get().planIgnored.add(BuiltInRegistries.ITEM.getKey(item).toString()), true);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.clamp(scroll + (scrollY > 0 ? -1 : 1), 0, Math.max(0, rows.size() - 1));
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /** A whole-number slider that saves and recalculates when released. */
    private class IntSlider extends AbstractSliderButton {
        private final String key;
        private final int min, max;
        private final Consumer<Integer> setter;

        IntSlider(int x, int y, int w, String key, int min, int max, int value, Consumer<Integer> setter) {
            super(x, y, w, 20, Component.empty(), (double) (Math.clamp(value, min, max) - min) / Math.max(1, max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int intValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, intValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(intValue());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            SlopmaticaConfig.get().save();
            recalculate();
        }
    }
}
