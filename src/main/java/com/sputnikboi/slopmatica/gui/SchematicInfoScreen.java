package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.schematic.SchematicMeta;
import com.sputnikboi.slopmatica.view.Recommendations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import org.jspecify.annotations.Nullable;

/**
 * A schematic's settings, saved into its file: the recommended biome, position, rotation and game
 * versions (all optional; the build view warns when a placement doesn't match), and the origin
 * point, the block that lands where you place it.
 */
public class SchematicInfoScreen extends Screen {
    private static final int W = 300;

    private final @Nullable Screen parent;
    private final Path file;
    private final @Nullable Placement placement;
    private final SchematicMeta meta;
    private String status = "";
    private EditBox biome, px, py, pz, minV, maxV, ox, oy, oz;
    private @Nullable Rotation rotation;

    public SchematicInfoScreen(@Nullable Screen parent, Path file, @Nullable Placement placement) {
        super(Component.translatable("slopmatica.info.title", file.getFileName().toString()));
        this.parent = parent;
        this.file = file;
        this.placement = placement;
        this.meta = (placement != null ? placement.schematic().meta() : SchematicMeta.read(file)).copy();
        this.rotation = meta.rotation;
    }

    @Override
    protected void init() {
        int left = width / 2 - W / 2, y = 36, row = 24;
        // Biome
        biome = addRenderableWidget(new EditBox(font, left + 90, y, 140, 20, Component.translatable("slopmatica.info.biome")));
        biome.setValue(meta.biome == null ? "" : meta.biome);
        biome.setHint(Component.translatable("slopmatica.info.none").withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.here"), b -> {
            if (minecraft.level != null && minecraft.player != null) {
                biome.setValue(minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse(""));
            }
        }).bounds(left + 234, y, 66, 20).build());
        y += row;
        // Position
        BlockPos pos = meta.position;
        px = coord(left + 90, y, pos == null ? "" : String.valueOf(pos.getX()));
        py = coord(left + 138, y, pos == null ? "" : String.valueOf(pos.getY()));
        pz = coord(left + 186, y, pos == null ? "" : String.valueOf(pos.getZ()));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.current"), b -> {
            BlockPos at = placement != null ? placement.worldOriginPoint() : minecraft.player != null ? minecraft.player.blockPosition() : null;
            if (at != null) {
                px.setValue(String.valueOf(at.getX()));
                py.setValue(String.valueOf(at.getY()));
                pz.setValue(String.valueOf(at.getZ()));
            }
        }).bounds(left + 234, y, 66, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.info.current.tooltip"))).build());
        y += row;
        // Rotation
        // Cycle buttons can't hold null, so -1 stands for "no recommendation".
        List<Integer> rotations = List.of(-1, 0, 1, 2, 3);
        addRenderableWidget(CycleButton.<Integer>builder(i -> i < 0 ? Component.translatable("slopmatica.info.none") : Recommendations.rotationName(Rotation.values()[i]),
                rotation == null ? -1 : rotation.ordinal())
            .withValues(rotations)
            .create(left + 90, y, 210, 20, Component.translatable("slopmatica.info.rotation"), (b, v) -> rotation = v < 0 ? null : Rotation.values()[v]));
        y += row;
        // Versions
        minV = addRenderableWidget(new EditBox(font, left + 90, y, 66, 20, Component.translatable("slopmatica.info.min")));
        minV.setValue(meta.minVersion == null ? "" : meta.minVersion);
        minV.setHint(Component.literal("min").withStyle(EditBox.SEARCH_HINT_STYLE));
        maxV = addRenderableWidget(new EditBox(font, left + 162, y, 66, 20, Component.translatable("slopmatica.info.max")));
        maxV.setValue(meta.maxVersion == null ? "" : meta.maxVersion);
        maxV.setHint(Component.literal("max").withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.this_version"), b -> minV.setValue(SharedConstants.getCurrentVersion().name()))
            .bounds(left + 234, y, 66, 20).build());
        y += row + 8;
        // Origin point
        BlockPos o = meta.origin;
        ox = coord(left + 90, y, o == null ? "" : String.valueOf(o.getX()));
        oy = coord(left + 138, y, o == null ? "" : String.valueOf(o.getY()));
        oz = coord(left + 186, y, o == null ? "" : String.valueOf(o.getZ()));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.middle"), b -> {
            ox.setValue("");
            oy.setValue("");
            oz.setValue("");
        }).bounds(left + 234, y, 66, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.info.middle.tooltip"))).build());
        y += row + 12;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.save"), b -> save()).bounds(left, y, W / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + W / 2 + 3, y, W / 2 - 3, 20).build());
    }

    private EditBox coord(int x, int y, String value) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 44, 20, Component.empty()));
        box.setValue(value);
        return box;
    }

    private static @Nullable BlockPos pos(EditBox x, EditBox y, EditBox z) {
        try {
            return new BlockPos(Integer.parseInt(x.getValue()), Integer.parseInt(y.getValue()), Integer.parseInt(z.getValue()));
        } catch (NumberFormatException e) {
            return null; // Any field empty: no value.
        }
    }

    private void save() {
        meta.biome = biome.getValue().isBlank() ? null : biome.getValue().trim();
        meta.position = pos(px, py, pz);
        meta.rotation = rotation;
        meta.minVersion = minV.getValue().isBlank() ? null : minV.getValue().trim();
        meta.maxVersion = maxV.getValue().isBlank() ? null : maxV.getValue().trim();
        meta.origin = pos(ox, oy, oz);
        try {
            meta.write(file);
        } catch (IOException e) {
            status = "Couldn't save: " + e.getMessage();
            return;
        }
        // Builds already loaded from this file pick it up straight away (without moving).
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (p.schematic().source().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
                p.schematic().setMeta(meta.copy());
            }
        }
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.info.saved").withColor(SlopmaticaActions.ACCENT));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 14, -1);
        int left = width / 2 - W / 2, y = 42, row = 24;
        for (String key : new String[] {"slopmatica.info.biome", "slopmatica.info.position", "slopmatica.info.rotation", "slopmatica.info.versions"}) {
            g.text(font, Component.translatable(key), left, y, 0xFFE0E0E0);
            y += row;
        }
        y += 8;
        g.text(font, Component.translatable("slopmatica.info.origin"), left, y, 0xFFE0E0E0);
        g.centeredText(font, Component.translatable("slopmatica.info.help"), width / 2, y + 22, 0xFF909090);
        if (!status.isEmpty()) {
            g.centeredText(font, status, width / 2, height - 20, 0xFFFF6060);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
