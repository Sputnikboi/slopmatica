package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.compat.LitematicaImport;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Imports Litematica settings from this instance or another one: pick a source on the left, check
 * what would change on the right, then Import. Another instance can be any folder or file path.
 */
public class LitematicaImportScreen extends Screen {
    private static final int COLUMN = 150;
    private static final int MAX_SOURCES = 6;

    private final @Nullable Screen parent;
    private boolean noneFound;
    private LitematicaImport.@Nullable Plan plan;
    private final List<Component> lines = new ArrayList<>();
    private EditBox path;
    private Button importButton;

    public LitematicaImportScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.import.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - COLUMN - 10;
        int y = 40;
        List<Path> sources = new ArrayList<>();
        Path local = LitematicaImport.local();
        if (local != null) {
            sources.add(local);
        }
        sources.addAll(LitematicaImport.otherInstances());
        noneFound = sources.isEmpty();
        for (Path file : sources.subList(0, Math.min(MAX_SOURCES, sources.size()))) {
            Component label = file == local ? Component.translatable("slopmatica.import.this_instance") : Component.literal(LitematicaImport.instanceName(file));
            addRenderableWidget(Button.builder(label, b -> select(file)).bounds(left, y, COLUMN, 20)
                .tooltip(Tooltip.create(Component.literal(file.toString()))).build());
            y += 24;
        }
        if (sources.isEmpty()) {
            y += 12; // Room for the "none found" line drawn below.
        }

        y += 8;
        path = addRenderableWidget(new EditBox(font, left, y + 10, COLUMN, 20, Component.translatable("slopmatica.import.path")));
        path.setMaxLength(1024);
        path.setHint(Component.translatable("slopmatica.import.path.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.import.read"), b -> readTyped()).bounds(left, y + 34, COLUMN, 20).build());

        int bottom = height - 28;
        importButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.import.apply"), b -> apply())
            .bounds(width / 2 - 154, bottom, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 4, bottom, 150, 20).build());
        importButton.active = plan != null && !plan.isEmpty();
    }

    private void readTyped() {
        String text = path.getValue().trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1); // Windows "Copy as path" adds quotes.
        }
        try {
            Path file = LitematicaImport.resolve(Path.of(text));
            if (file == null) {
                show(null, List.of(Component.translatable("slopmatica.import.not_found").withStyle(ChatFormatting.RED)));
                return;
            }
            select(file);
        } catch (InvalidPathException e) {
            show(null, List.of(Component.translatable("slopmatica.import.not_found").withStyle(ChatFormatting.RED)));
        }
    }

    private void select(Path file) {
        try {
            LitematicaImport.Plan read = LitematicaImport.read(file);
            List<Component> preview = new ArrayList<>();
            preview.add(Component.translatable("slopmatica.import.from", LitematicaImport.instanceName(file)).withStyle(ChatFormatting.GRAY));
            if (read.isEmpty()) {
                preview.add(Component.translatable("slopmatica.import.nothing"));
            }
            for (LitematicaImport.Change change : read.changes()) {
                preview.add(Component.translatable("slopmatica.import.change", Component.translatable(change.option()), change.value()));
            }
            read.keys().forEach((mapping, key) -> preview.add(Component.translatable("slopmatica.import.change",
                Component.translatable(mapping), Component.translatable(key))));
            for (String note : read.skipped()) {
                preview.add(Component.translatable("slopmatica.import.skipped", note).withStyle(ChatFormatting.DARK_GRAY));
            }
            show(read, preview);
        } catch (IOException e) {
            show(null, List.of(Component.translatable("slopmatica.import.unreadable", e.getMessage()).withStyle(ChatFormatting.RED)));
        }
    }

    private void show(LitematicaImport.@Nullable Plan read, List<Component> preview) {
        plan = read;
        lines.clear();
        lines.addAll(preview);
        importButton.active = plan != null && !plan.isEmpty();
    }

    private void apply() {
        if (plan == null) {
            return;
        }
        List<String> problems = LitematicaImport.apply(plan);
        HologramRenderer.INSTANCE.rebuildAll();
        lines.add(Component.translatable("slopmatica.import.done", plan.changes().size(), plan.keys().size() - problems.size()).withStyle(ChatFormatting.GREEN));
        for (String problem : problems) {
            lines.add(Component.translatable("slopmatica.import.skipped", problem).withStyle(ChatFormatting.DARK_GRAY));
        }
        plan = null;
        importButton.active = false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 14, -1);
        int left = width / 2 - COLUMN - 10;
        graphics.text(font, Component.translatable("slopmatica.import.sources").withStyle(ChatFormatting.BOLD), left, 28, -1);
        if (noneFound) {
            graphics.text(font, Component.translatable("slopmatica.import.none_found"), left, 40, 0xFFA0A0A0);
        }
        graphics.text(font, Component.translatable("slopmatica.import.path"), path.getX(), path.getY() - 10, 0xFFA0A0A0);

        int x = width / 2 + 10, y = 28, maxWidth = width - x - 10, last = height - 40;
        for (Component line : lines) {
            for (var part : font.split(line, maxWidth)) {
                if (y > last - 9) {
                    graphics.text(font, Component.literal("…"), x, y, 0xFFA0A0A0);
                    return;
                }
                graphics.text(font, part, x, y, -1);
                y += 10;
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
