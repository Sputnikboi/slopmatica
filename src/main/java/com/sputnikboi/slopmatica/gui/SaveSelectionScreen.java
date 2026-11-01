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
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Save the selection as a schematic with a name and format of your choosing (Ctrl+S in the
 * build view). Saved to the schematics folder, unlike the copy key, which saves to copies/.
 */
public class SaveSelectionScreen extends Screen {
    private static final int WIDTH = 260;

    private final @Nullable Screen parent;
    private EditBox name;
    private SchematicFormats format = SlopmaticaConfig.get().copyFormat;

    public SaveSelectionScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.save.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - WIDTH / 2, y = height / 2 - 40;
        name = addRenderableWidget(new EditBox(font, left, y, WIDTH, 20, Component.translatable("slopmatica.save.name")));
        name.setMaxLength(100);
        name.setHint(Component.translatable("slopmatica.save.name").withStyle(EditBox.SEARCH_HINT_STYLE));
        setInitialFocus(name);
        y += 26;
        addRenderableWidget(CycleButton.<SchematicFormats>builder(f -> Component.literal(f.extension), format)
            .withValues(List.of(SchematicFormats.values()))
            .withTooltip(f -> Tooltip.create(Component.translatable("slopmatica.options.copy_format." + f.name().toLowerCase(java.util.Locale.ROOT) + ".tooltip")))
            .create(left, y, WIDTH, 20, Component.translatable("slopmatica.options.copy_format"), (b, v) -> format = v));
        y += 26;
        addRenderableWidget(CycleButton.onOffBuilder(SlopmaticaConfig.get().includeNbt)
            .create(left, y, WIDTH, 20, Component.translatable("slopmatica.options.include_nbt"), (b, v) -> {
                SlopmaticaConfig.get().includeNbt = v;
                SlopmaticaConfig.get().save();
            }));
        y += 32;
        Button save = addRenderableWidget(Button.builder(Component.translatable("slopmatica.save.save"), b -> save())
            .bounds(left, y, WIDTH / 2 - 3, 20).build());
        save.active = Selection.INSTANCE.box() != null;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + WIDTH / 2 + 3, y, WIDTH / 2 - 3, 20).build());
    }

    private void save() {
        String chosen = name.getValue().isBlank() ? null : name.getValue();
        Clipboard.INSTANCE.save(minecraft, chosen, format, SlopmaticaActions.schematicsDir());
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
        g.centeredText(font, title, width / 2, height / 2 - 70, -1);
        BoundingBox box = Selection.INSTANCE.box();
        Component info = box == null ? Component.translatable("slopmatica.save.no_selection")
            : Component.translatable("slopmatica.save.size", box.getXSpan(), box.getYSpan(), box.getZSpan());
        g.centeredText(font, info, width / 2, height / 2 - 56, box == null ? 0xFFFF6060 : 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
