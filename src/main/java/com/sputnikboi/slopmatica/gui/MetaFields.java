package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.schematic.SchematicMeta;
import com.sputnikboi.slopmatica.view.Recommendations;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * A schematic's optional settings as form rows (biome, position, rotation, versions, origin point),
 * shared by Schematic Settings and Save Selection. Coordinates left empty show their default in
 * grey: what's used unless something is typed in.
 */
final class MetaFields {
    static final int W = 300, ROW = 24;
    private static final String[] LABELS = {"slopmatica.info.biome", "slopmatica.info.position", "slopmatica.info.rotation", "slopmatica.info.versions"};

    private EditBox biome, px, py, pz, minV, maxV, ox, oy, oz;
    private @Nullable Rotation rotation;
    private int top, originY;

    /**
     * Adds the rows from {@code y}; returns the next free row.
     *
     * @param defaultPosition what Position shows greyed out when empty (null: nothing)
     * @param current         what "Current" fills Position with
     * @param defaultOrigin   the origin point used when none is set (schematic coordinates), shown greyed out
     */
    int add(Function<AbstractWidget, AbstractWidget> adder, Font font, int left, int y, SchematicMeta meta,
                                       @Nullable BlockPos defaultPosition, Supplier<@Nullable BlockPos> current, @Nullable BlockPos defaultOrigin) {
        Minecraft mc = Minecraft.getInstance();
        top = y;
        rotation = meta.rotation;
        biome = (EditBox) adder.apply(new EditBox(font, left + 90, y, 140, 20, Component.translatable("slopmatica.info.biome")));
        biome.setValue(meta.biome == null ? "" : meta.biome);
        biome.setHint(Component.translatable("slopmatica.info.none").withStyle(EditBox.SEARCH_HINT_STYLE));
        adder.apply(Button.builder(Component.translatable("slopmatica.info.here"), b -> {
            if (mc.level != null && mc.player != null) {
                biome.setValue(mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse(""));
            }
        }).bounds(left + 234, y, 66, 20).build());
        y += ROW;
        BlockPos pos = meta.position;
        px = coord(adder, font, left + 90, y, pos == null ? null : pos.getX(), defaultPosition == null ? null : defaultPosition.getX());
        py = coord(adder, font, left + 138, y, pos == null ? null : pos.getY(), defaultPosition == null ? null : defaultPosition.getY());
        pz = coord(adder, font, left + 186, y, pos == null ? null : pos.getZ(), defaultPosition == null ? null : defaultPosition.getZ());
        adder.apply(Button.builder(Component.translatable("slopmatica.info.current"), b -> {
            BlockPos at = current.get();
            if (at != null) {
                px.setValue(String.valueOf(at.getX()));
                py.setValue(String.valueOf(at.getY()));
                pz.setValue(String.valueOf(at.getZ()));
            }
        }).bounds(left + 234, y, 66, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.info.current.tooltip"))).build());
        y += ROW;
        // Cycle buttons can't hold null, so -1 stands for "no recommendation".
        adder.apply(CycleButton.<Integer>builder(i -> i < 0 ? Component.translatable("slopmatica.info.none") : Recommendations.rotationName(Rotation.values()[i]),
                rotation == null ? -1 : rotation.ordinal())
            .withValues(List.of(-1, 0, 1, 2, 3))
            .create(left + 90, y, 210, 20, Component.translatable("slopmatica.info.rotation"), (b, v) -> rotation = v < 0 ? null : Rotation.values()[v]));
        y += ROW;
        minV = (EditBox) adder.apply(new EditBox(font, left + 90, y, 66, 20, Component.translatable("slopmatica.info.min")));
        minV.setValue(meta.minVersion == null ? "" : meta.minVersion);
        minV.setHint(Component.literal("min").withStyle(EditBox.SEARCH_HINT_STYLE));
        maxV = (EditBox) adder.apply(new EditBox(font, left + 162, y, 66, 20, Component.translatable("slopmatica.info.max")));
        maxV.setValue(meta.maxVersion == null ? "" : meta.maxVersion);
        maxV.setHint(Component.literal("max").withStyle(EditBox.SEARCH_HINT_STYLE));
        adder.apply(Button.builder(Component.translatable("slopmatica.info.this_version"), b -> minV.setValue(SharedConstants.getCurrentVersion().name()))
            .bounds(left + 234, y, 66, 20).build());
        y += ROW + 8;
        originY = y;
        BlockPos o = meta.origin;
        ox = coord(adder, font, left + 90, y, o == null ? null : o.getX(), defaultOrigin == null ? null : defaultOrigin.getX());
        oy = coord(adder, font, left + 138, y, o == null ? null : o.getY(), defaultOrigin == null ? null : defaultOrigin.getY());
        oz = coord(adder, font, left + 186, y, o == null ? null : o.getZ(), defaultOrigin == null ? null : defaultOrigin.getZ());
        // Back to the default: empty boxes, showing the middle of the bottom layer in grey.
        adder.apply(Button.builder(Component.translatable("slopmatica.info.middle"), b -> {
            ox.setValue("");
            oy.setValue("");
            oz.setValue("");
        }).bounds(left + 234, y, 66, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.info.middle.tooltip"))).build());
        return y + ROW;
    }

    private static EditBox coord(Function<AbstractWidget, AbstractWidget> adder, Font font, int x, int y, @Nullable Integer value, @Nullable Integer hint) {
        EditBox box = (EditBox) adder.apply(new EditBox(font, x, y, 44, 20, Component.empty()));
        box.setValue(value == null ? "" : String.valueOf(value));
        if (hint != null) {
            box.setHint(Component.literal(String.valueOf(hint)).withStyle(EditBox.SEARCH_HINT_STYLE));
        }
        return box;
    }

    private static @Nullable BlockPos pos(EditBox x, EditBox y, EditBox z) {
        try {
            return new BlockPos(Integer.parseInt(x.getValue().trim()), Integer.parseInt(y.getValue().trim()), Integer.parseInt(z.getValue().trim()));
        } catch (NumberFormatException e) {
            return null; // Any field empty: no value (the default).
        }
    }

    /** What the form says, into {@code meta} (nothing before the rows are added). */
    void apply(SchematicMeta meta) {
        if (biome == null) {
            return;
        }
        meta.biome = biome.getValue().isBlank() ? null : biome.getValue().trim();
        meta.position = pos(px, py, pz);
        meta.rotation = rotation;
        meta.minVersion = minV.getValue().isBlank() ? null : minV.getValue().trim();
        meta.maxVersion = maxV.getValue().isBlank() ? null : maxV.getValue().trim();
        meta.origin = pos(ox, oy, oz);
    }

    /** The row labels, left of the fields. */
    void drawLabels(GuiGraphicsExtractor g, Font font, int left) {
        int y = top + 6;
        for (String key : LABELS) {
            g.text(font, Component.translatable(key), left, y, 0xFFE0E0E0);
            y += ROW;
        }
        g.text(font, Component.translatable("slopmatica.info.origin"), left, originY + 6, 0xFFE0E0E0);
    }

    int originRowY() {
        return originY;
    }
}
