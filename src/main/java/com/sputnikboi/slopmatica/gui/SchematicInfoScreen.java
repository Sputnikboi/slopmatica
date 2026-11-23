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
    private MetaFields fields = new MetaFields();

    public SchematicInfoScreen(@Nullable Screen parent, Path file, @Nullable Placement placement) {
        super(Component.translatable("slopmatica.info.title", file.getFileName().toString()));
        this.parent = parent;
        this.file = file;
        this.placement = placement;
        this.meta = (placement != null ? placement.schematic().meta() : SchematicMeta.read(file)).copy();
    }

    @Override
    protected void init() {
        int left = width / 2 - W / 2, y = 36;
        fields.apply(meta); // Rebuilt (a resize): keep what was typed.
        fields = new MetaFields();
        y = fields.add(this::addRenderableWidget, font, left, y, meta,
            placement != null ? placement.worldOriginPoint() : null,
            () -> placement != null ? placement.worldOriginPoint() : minecraft.player != null ? minecraft.player.blockPosition() : null,
            placement != null ? placement.schematic().defaultOriginPoint() : null);
        y += 12;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.info.save"), b -> save()).bounds(left, y, W / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + W / 2 + 3, y, W / 2 - 3, 20).build());
    }

    private void save() {
        fields.apply(meta);
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
        int left = width / 2 - W / 2;
        fields.drawLabels(g, font, left);
        g.centeredText(font, Component.translatable("slopmatica.info.help"), width / 2, fields.originRowY() + 26, 0xFF909090);
        if (!status.isEmpty()) {
            g.centeredText(font, status, width / 2, height - 20, 0xFFFF6060);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
