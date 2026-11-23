package com.sputnikboi.slopmatica.gui;

import com.mojang.serialization.Codec;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.RemovalMarks;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.build.SelectionTool;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The selection tools: which one the selection key uses (box, sphere, cylinder, prism, brush,
 * magic select), how new shapes combine with the selection, surface-only per tool, brush size; and
 * what to do with a selection: copy, clear, put its blocks on the break list.
 */
public class SelectionToolsScreen extends OptionsSubScreen {
    private static final int WIDE = 150;

    public SelectionToolsScreen(@Nullable Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("slopmatica.seltools.title"));
    }

    /** Switches to the next tool (a key and a Quick Menu action). */
    public static void nextTool() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        Selection.INSTANCE.endStroke();
        c.selectionTool = c.selectionTool.next();
        c.save();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.seltools.now", c.selectionTool.label(), c.selectionMode.label())
            .withColor(SlopmaticaActions.ACCENT));
    }

    /** Replace, Add, Subtract, Replace... */
    public static void nextMode() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        c.selectionMode = c.selectionMode.next();
        c.save();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.seltools.now", c.selectionTool.label(), c.selectionMode.label())
            .withColor(SlopmaticaActions.ACCENT));
    }

    @Override
    protected void addOptions() {
        SlopmaticaConfig c = SlopmaticaConfig.get();
        list.addHeader(Component.translatable("slopmatica.seltools.how"));
        list.addSmall(cycle("slopmatica.seltools.tool", "slopmatica.seltool", SelectionTool.values(), c.selectionTool, v -> {
                c.selectionTool = v;
                rebuildWidgets(); // The tool's own options below change.
            }),
            cycle("slopmatica.seltools.mode", "slopmatica.selmode", SelectionTool.Mode.values(), c.selectionMode, v -> c.selectionMode = v));
        SelectionTool tool = c.selectionTool;
        OptionInstance<Boolean> surface = OptionInstance.createBoolean("slopmatica.seltools.surface",
            OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.seltools.surface.tooltip")),
            c.surfaceTools.contains(tool.name()), v -> {
                if (v) {
                    c.surfaceTools.add(tool.name());
                } else {
                    c.surfaceTools.remove(tool.name());
                }
            });
        if (tool == SelectionTool.BRUSH) {
            list.addSmall(surface, new OptionInstance<>("slopmatica.seltools.brush_radius",
                OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.seltools.brush_radius.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(v))),
                new OptionInstance.IntRange(0, 16), Math.clamp(c.brushRadius, 0, 16), v -> c.brushRadius = v));
            list.addSmall(cycle("slopmatica.seltools.brush_shape", "slopmatica.seltools.brush_shape", SelectionTool.BrushShape.values(), c.brushShape,
                v -> c.brushShape = v), null);
        } else if (tool == SelectionTool.MAGIC) {
            list.addSmall(surface, OptionInstance.createBoolean("slopmatica.seltools.magic_exact",
                OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.seltools.magic_exact.tooltip")), c.magicExact, v -> c.magicExact = v));
        } else {
            list.addSmall(surface, null);
        }
        list.addSmall(new net.minecraft.client.gui.components.MultiLineTextWidget(tool.tooltip().copy().withColor(0xFFA0A0A0), font)
            .setMaxWidth(WIDE * 2 + 10), null);

        list.addHeader(Component.translatable("slopmatica.seltools.selection"));
        boolean any = Selection.INSTANCE.box() != null;
        Button copy = button("slopmatica.tool.selection.copy", b -> {
            Clipboard.INSTANCE.copy(minecraft);
            onClose();
        });
        Button clear = button("slopmatica.tool.selection.clear", b -> {
            Selection.INSTANCE.clear();
            rebuildWidgets();
        });
        Button save = button("slopmatica.tool.selection.save", b -> minecraft.gui.setScreen(new SaveSelectionScreen(this)));
        copy.active = clear.active = save.active = any;
        list.addSmall(copy, save);
        list.addSmall(clear, null);
        list.addSmall(OptionInstance.createBoolean("slopmatica.lock.option",
            OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.lock.tooltip")), c.lockToSelection, v -> c.lockToSelection = v),
            null);
        list.addSmall(OptionInstance.createBoolean("slopmatica.lock.interactables",
                OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.lock.interactables.tooltip")), c.lockInteractables, v -> c.lockInteractables = v),
            OptionInstance.createBoolean("slopmatica.lock.containers",
                OptionInstance.cachedConstantTooltip(Component.translatable("slopmatica.lock.containers.tooltip")), c.lockContainers, v -> c.lockContainers = v));

        list.addHeader(Component.translatable("slopmatica.seltools.marks", String.format("%,d", RemovalMarks.INSTANCE.count())));
        // One toggle: anything marked comes off (all of it); otherwise the selection is marked.
        boolean marked = RemovalMarks.INSTANCE.count() > 0;
        Button mark = button(marked ? "slopmatica.seltools.unmark" : "slopmatica.seltools.mark", b -> {
            RemovalMarks.INSTANCE.toggleSelection(minecraft);
            rebuildWidgets();
        });
        mark.active = marked || any && minecraft.level != null;
        list.addSmall(mark, null);
    }

    private static Button button(String key, Button.OnPress press) {
        return Button.builder(Component.translatable(key), press).width(WIDE)
            .tooltip(Tooltip.create(Component.translatable(key + ".tooltip"))).build();
    }

    /** A cycle button over an enum: labels from {@code values.<value>}, tooltips from {@code values.<value>.tooltip}. */
    private static <E extends Enum<E>> OptionInstance<E> cycle(String key, String values, E[] all, E value, Consumer<E> setter) {
        Class<E> type = all[0].getDeclaringClass();
        String prefix = values + ".";
        return new OptionInstance<E>(
            key,
            v -> Tooltip.create(Component.translatable(prefix + v.name().toLowerCase(Locale.ROOT) + ".tooltip")),
            (caption, v) -> Component.translatable(prefix + v.name().toLowerCase(Locale.ROOT)),
            new OptionInstance.Enum<E>(List.of(all), Codec.STRING.xmap(name -> Enum.valueOf(type, name), Enum::name)),
            value,
            setter::accept
        );
    }

    @Override
    public void removed() {
        super.removed();
        Selection.INSTANCE.endStroke();
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
