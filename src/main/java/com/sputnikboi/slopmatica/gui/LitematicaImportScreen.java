package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.compat.LitematicaImport;
import com.sputnikboi.slopmatica.compat.LitematicaImport.Item;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Imports Litematica settings from this instance or another one. Pick a source on the left; the
 * right lists every setting it would change, with the Litematica setting it comes from. Untick or
 * adjust any of them, then Import. Settings still at Litematica's defaults are only listed when
 * "Include unchanged" is ticked. Settings the caller asks to keep (the welcome screen's) are shown
 * but left alone.
 */
public class LitematicaImportScreen extends Screen {
    /** In {@code keep}: every setting (not keys), e.g. after taking another instance's settings. */
    public static final String ALL_SETTINGS = "*";

    private static final int COLUMN = 130, ROW = 28, VALUE_W = 96;

    private final @Nullable Screen parent;
    private final Set<String> keep;
    private boolean noneFound;
    private LitematicaImport.@Nullable Plan plan;
    private String sourceName = "";
    private boolean includeDefaults;
    private final Map<Item, Boolean> ticked = new IdentityHashMap<>();
    private final List<Component> notes = new ArrayList<>();
    private int scroll;
    private @Nullable Item listening;
    private EditBox path;
    private Button importButton;

    public LitematicaImportScreen(@Nullable Screen parent) {
        this(parent, Set.of());
    }

    /** @param keep config field or key mapping names to leave alone ({@link #ALL_SETTINGS} for every setting) */
    public LitematicaImportScreen(@Nullable Screen parent, Set<String> keep) {
        super(Component.translatable("slopmatica.import.title"));
        this.parent = parent;
        this.keep = new HashSet<>(keep);
    }

    private boolean kept(Item item) {
        return keep.contains(item.target) || keep.contains(ALL_SETTINGS) && item.kind != LitematicaImport.Kind.KEY;
    }

    private List<Item> rows() {
        return plan == null ? List.of() : plan.shown(includeDefaults);
    }

    private int listTop() {
        return 40;
    }

    private int listBottom() {
        return height - 60;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW);
    }

    @Override
    protected void init() {
        int left = 10;
        int y = 40;
        List<Path> sources = new ArrayList<>();
        Path local = LitematicaImport.local();
        if (local != null) {
            sources.add(local);
        }
        sources.addAll(LitematicaImport.otherInstances());
        noneFound = sources.isEmpty();
        for (Path file : sources.subList(0, Math.min(6, sources.size()))) {
            Component label = file == local ? Component.translatable("slopmatica.import.this_instance") : Component.literal(LitematicaImport.instanceName(file));
            addRenderableWidget(Button.builder(label, b -> select(file)).bounds(left, y, COLUMN, 20)
                .tooltip(Tooltip.create(Component.literal(file.toString()))).build());
            y += 24;
        }
        if (sources.isEmpty()) {
            y += 12; // Room for the "none found" line drawn below.
        }
        y += 8;
        String typed = path == null ? "" : path.getValue();
        path = addRenderableWidget(new EditBox(font, left, y + 10, COLUMN, 20, Component.translatable("slopmatica.import.path")));
        path.setMaxLength(1024);
        path.setValue(typed);
        path.setHint(Component.translatable("slopmatica.import.path.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.import.read"), b -> readTyped()).bounds(left, y + 34, COLUMN, 20).build());

        // The list: one row per setting, the visible ones only (scrolling rebuilds).
        List<Item> rows = rows();
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visibleRows()));
        int x0 = COLUMN + 24, x1 = width - 10;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
            Item item = rows.get(i);
            int ry = listTop() + (i - scroll) * ROW;
            Checkbox box = addRenderableWidget(Checkbox.builder(Component.empty(), font).pos(x0, ry + 2)
                .selected(!kept(item) && ticked.getOrDefault(item, true))
                .onValueChange((cb, v) -> ticked.put(item, v)).build());
            box.active = !kept(item);
            if (!kept(item)) {
                addValueWidget(item, x1 - VALUE_W, ry + 2);
            }
        }

        int bottom = height - 28;
        addRenderableWidget(Checkbox.builder(Component.translatable("slopmatica.import.defaults"), font).pos(width / 2 - 230, bottom)
            .selected(includeDefaults).tooltip(Tooltip.create(Component.translatable("slopmatica.import.defaults.tooltip")))
            .onValueChange((cb, v) -> {
                includeDefaults = v;
                rebuildWidgets();
            }).build());
        importButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.import.apply"), b -> apply())
            .bounds(width / 2 - 34, bottom, 130, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 100, bottom, 130, 20).build());
        importButton.active = rows.stream().anyMatch(this::selected);
    }

    private boolean selected(Item item) {
        return !kept(item) && ticked.getOrDefault(item, true);
    }

    /** The value, editable: a toggle or list button, a number or colour field, or a key to press. */
    private void addValueWidget(Item item, int x, int y) {
        switch (item.kind) {
            case ON_OFF -> addRenderableWidget(CycleButton.onOffBuilder((Boolean) item.value).displayOnlyValue()
                .create(x, y, VALUE_W, 20, Component.empty(), (b, v) -> item.value = v));
            case CHOICE -> addRenderableWidget(CycleButton.<Object>builder(v -> Component.literal(String.valueOf(v)), item.value)
                .withValues(item.choices).displayOnlyValue()
                .create(x, y, VALUE_W, 20, Component.empty(), (b, v) -> item.value = v));
            case PERCENT -> {
                EditBox box = addRenderableWidget(new EditBox(font, x, y, VALUE_W - 14, 20, Component.empty()));
                box.setValue(String.valueOf(item.value));
                box.setResponder(text -> {
                    try {
                        item.value = Integer.parseInt(text.trim().replace("%", ""));
                    } catch (NumberFormatException ignored) {
                        // Keeps the last good value.
                    }
                });
            }
            case COLOR -> {
                EditBox box = addRenderableWidget(new EditBox(font, x + 16, y, VALUE_W - 16, 20, Component.empty()));
                box.setValue(item.display());
                box.setResponder(text -> {
                    String hex = text.trim().replace("#", "");
                    if (hex.length() == 6) {
                        try {
                            item.value = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException ignored) {
                            // Keeps the last good value.
                        }
                    }
                });
            }
            case KEY -> addRenderableWidget(Button.builder(Component.literal(item == listening ? "> ? <" : item.display()), b -> {
                listening = item;
                rebuildWidgets();
            }).bounds(x, y, VALUE_W, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.import.key.tooltip"))).build());
        }
    }

    private void readTyped() {
        String text = path.getValue().trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            text = text.substring(1, text.length() - 1); // Windows "Copy as path" adds quotes.
        }
        try {
            Path file = LitematicaImport.resolve(Path.of(text));
            if (file != null) {
                select(file);
                return;
            }
        } catch (InvalidPathException ignored) {
            // Shown below.
        }
        show(null, "", List.of(Component.translatable("slopmatica.import.not_found").withStyle(ChatFormatting.RED)));
    }

    private void select(Path file) {
        try {
            LitematicaImport.Plan read = LitematicaImport.read(file);
            List<Component> skipped = new ArrayList<>();
            for (String note : read.skipped()) {
                skipped.add(Component.translatable("slopmatica.import.skipped", note).withStyle(ChatFormatting.DARK_GRAY));
            }
            show(read, LitematicaImport.instanceName(file), skipped);
        } catch (IOException e) {
            show(null, "", List.of(Component.translatable("slopmatica.import.unreadable", e.getMessage()).withStyle(ChatFormatting.RED)));
        }
    }

    private void show(LitematicaImport.@Nullable Plan read, String name, List<Component> lines) {
        plan = read;
        sourceName = name;
        ticked.clear();
        notes.clear();
        notes.addAll(lines);
        scroll = 0;
        listening = null;
        rebuildWidgets();
    }

    private void apply() {
        if (plan == null) {
            return;
        }
        List<Item> chosen = rows().stream().filter(this::selected).toList();
        List<String> problems = LitematicaImport.apply(chosen);
        HologramRenderer.INSTANCE.rebuildAll();
        long keys = chosen.stream().filter(i -> i.kind == LitematicaImport.Kind.KEY).count();
        notes.clear();
        notes.add(Component.translatable("slopmatica.import.done", chosen.size() - keys, keys - problems.size()).withStyle(ChatFormatting.GREEN));
        for (String problem : problems) {
            notes.add(Component.translatable("slopmatica.import.skipped", problem).withStyle(ChatFormatting.DARK_GRAY));
        }
        plan = null;
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (x > COLUMN + 20 && plan != null) {
            int before = scroll;
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, rows().size() - visibleRows()));
            if (scroll != before) {
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            if (!event.isEscape()) {
                listening.value = InputConstants.getKey(event).getName();
            }
            listening = null;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 10, -1);
        graphics.text(font, Component.translatable("slopmatica.import.sources").withStyle(ChatFormatting.BOLD), 10, 28, -1);
        if (noneFound) {
            graphics.text(font, Component.translatable("slopmatica.import.none_found"), 10, 40, 0xFFA0A0A0);
        }
        graphics.text(font, Component.translatable("slopmatica.import.path"), path.getX(), path.getY() - 10, 0xFFA0A0A0);

        int x0 = COLUMN + 24, x1 = width - 10;
        List<Item> rows = rows();
        if (plan != null) {
            graphics.text(font, Component.translatable(includeDefaults ? "slopmatica.import.from_all" : "slopmatica.import.from", sourceName)
                .withStyle(ChatFormatting.GRAY), x0, 28, -1);
            if (rows.isEmpty()) {
                graphics.text(font, Component.translatable("slopmatica.import.nothing"), x0, listTop() + 4, 0xFFE0E0E0);
            }
        }
        for (int i = scroll; i < Math.min(rows.size(), scroll + visibleRows()); i++) {
            Item item = rows.get(i);
            int ry = listTop() + (i - scroll) * ROW, tx = x0 + 24, room = x1 - VALUE_W - 6 - tx;
            boolean kept = kept(item);
            graphics.text(font, font.plainSubstrByWidth(Component.translatable(item.option).getString(), room), tx, ry + 2, kept ? 0xFF909090 : 0xFFFFFFFF);
            String from = kept ? Component.translatable("slopmatica.import.kept").getString()
                : Component.translatable(item.atDefault ? "slopmatica.import.source_default" : "slopmatica.import.source", item.source, item.raw).getString();
            graphics.text(font, font.plainSubstrByWidth(from, room), tx, ry + 13, kept ? 0xFFC0A060 : 0xFF909090);
            if (kept) {
                graphics.text(font, item.display(), x1 - font.width(item.display()), ry + 8, 0xFF909090);
            } else if (item.kind == LitematicaImport.Kind.COLOR) {
                graphics.fill(x1 - VALUE_W, ry + 4, x1 - VALUE_W + 12, ry + 18, 0xFF000000 | (Integer) item.value);
            } else if (item.kind == LitematicaImport.Kind.PERCENT) {
                graphics.text(font, "%", x1 - 10, ry + 8, 0xFFA0A0A0);
            }
        }
        if (rows.size() > visibleRows()) {
            graphics.text(font, (scroll + 1) + "–" + Math.min(rows.size(), scroll + visibleRows()) + " / " + rows.size(), x1 - 50, 28, 0xFF909090);
        }
        int y = listBottom() + 4;
        for (Component line : notes) {
            if (y > height - 40) {
                break;
            }
            graphics.text(font, font.plainSubstrByWidth(line.getString(), x1 - x0), x0, y, line.getStyle().getColor() == null ? -1 : 0xFF000000 | line.getStyle().getColor().getValue());
            y += 10;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
