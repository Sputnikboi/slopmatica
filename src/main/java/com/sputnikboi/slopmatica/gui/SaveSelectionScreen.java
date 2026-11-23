package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Save the selection as a schematic with a name and format of your choosing (the save key, V by default, in the
 * build view). Saved to the schematics folder, unlike the copy key, which saves to copies/.
 */
public class SaveSelectionScreen extends Screen {

    private final @Nullable Screen parent;
    private EditBox name;
    private String chosenName = "";
    private MetaFields fields = new MetaFields();
    /** The settings typed so far (kept when the screen is rebuilt, say on a resize). */
    private final com.sputnikboi.slopmatica.schematic.SchematicMeta meta = new com.sputnikboi.slopmatica.schematic.SchematicMeta();
    private SchematicFormats format = SlopmaticaConfig.get().copyFormat;

    public SaveSelectionScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.save.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - MetaFields.W / 2, y = 44;
        name = addRenderableWidget(new EditBox(font, left, y, MetaFields.W, 20, Component.translatable("slopmatica.save.name")));
        name.setMaxLength(100);
        name.setValue(chosenName);
        name.setResponder(v -> chosenName = v);
        name.setHint(Component.translatable("slopmatica.save.name").withStyle(EditBox.SEARCH_HINT_STYLE));
        setInitialFocus(name);
        y += 24;
        int half = (MetaFields.W - 4) / 2;
        addRenderableWidget(CycleButton.<SchematicFormats>builder(f -> Component.literal(f.extension), format)
            .withValues(List.of(SchematicFormats.values()))
            .withTooltip(f -> Tooltip.create(Component.translatable("slopmatica.options.copy_format." + f.name().toLowerCase(java.util.Locale.ROOT) + ".tooltip")))
            .create(left, y, half, 20, Component.translatable("slopmatica.save.format"), (b, v) -> format = v));
        addRenderableWidget(CycleButton.onOffBuilder(SlopmaticaConfig.get().includeNbt)
            .create(left + half + 4, y, MetaFields.W - half - 4, 20, Component.translatable("slopmatica.options.include_nbt"), (b, v) -> {
                SlopmaticaConfig.get().includeNbt = v;
                SlopmaticaConfig.get().save();
            }));
        y += 32;
        // The schematic's settings, all optional. Position and origin show their defaults in grey:
        // where the selection is, and the middle of its bottom layer.
        BoundingBox box = Selection.INSTANCE.box();
        BlockPos origin = box == null ? null : new BlockPos(Math.floorDiv(box.getXSpan() - 1, 2), 0, Math.floorDiv(box.getZSpan() - 1, 2));
        BlockPos here = box == null ? null : new BlockPos(box.minX(), box.minY(), box.minZ()).offset(origin);
        fields.apply(meta); // Rebuilt (a resize): keep what was typed.
        fields = new MetaFields();
        y = fields.add(this::addRenderableWidget, font, left, y, meta, here, () -> here, origin);
        y += 12;
        Button save = addRenderableWidget(Button.builder(Component.translatable("slopmatica.save.save"), b -> save())
            .bounds(left, y, half, 20).build());
        save.active = box != null;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + half + 4, y, MetaFields.W - half - 4, 20).build());
    }

    private void save() {
        fields.apply(meta);
        String chosen = name.getValue().isBlank() ? null : name.getValue();
        Clipboard.INSTANCE.save(minecraft, chosen, format, SlopmaticaActions.schematicsDir(), meta.copy());
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && Selection.INSTANCE.box() != null) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 14, -1);
        BoundingBox box = Selection.INSTANCE.box();
        Component info = box == null ? Component.translatable("slopmatica.save.no_selection")
            : Component.translatable("slopmatica.save.size", box.getXSpan(), box.getYSpan(), box.getZSpan());
        g.centeredText(font, info, width / 2, 28, box == null ? 0xFFFF6060 : 0xFFA0A0A0);
        fields.drawLabels(g, font, width / 2 - MetaFields.W / 2);
        g.centeredText(font, Component.translatable("slopmatica.save.settings_help"), width / 2, fields.originRowY() + 26, 0xFF909090);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
