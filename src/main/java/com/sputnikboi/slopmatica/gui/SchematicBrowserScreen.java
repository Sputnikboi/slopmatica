package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.Blaze3D;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import net.minecraft.client.gui.components.Tooltip;
import com.sputnikboi.slopmatica.render.SchematicThumbnails;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Browses {@code <gamedir>/schematics} (Litematica's folder) and loads the chosen file at the
 * player's feet. Typing in the search box searches every subfolder, not just the current one.
 */
public class SchematicBrowserScreen extends Screen {
    private static final int DIR_COLOR = 0xFFFFE070;
    private static final int ROW_HEIGHT = 28;
    private static final int ICON = 24;
    private static final int DETAIL_COLOR = 0xFF909090;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** File info is read off-thread once per file and kept for the session. */
    private static final Map<Path, Component> INFO_CACHE = new ConcurrentHashMap<>();

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 60, 53);
    private final Path root;
    private Path current;
    private String filter = "";
    private FileList list;
    private StringWidget pathLabel;
    private StringWidget infoLabel;
    private Button loadButton;
    private Button uploadButton;

    public SchematicBrowserScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.browser.title"));
        this.parent = parent;
        this.root = SlopmaticaActions.schematicsDir();
        this.current = root;
    }

    @Override
    protected void init() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        pathLabel = header.addChild(new StringWidget(Math.min(width - 20, 400), 9, Component.empty(), font));
        EditBox search = header.addChild(new EditBox(font, 0, 0, 200, 15, Component.translatable("slopmatica.browser.search")));
        search.setHint(Component.translatable("slopmatica.browser.search").withStyle(EditBox.SEARCH_HINT_STYLE));
        search.setValue(filter);
        search.setResponder(text -> {
            filter = text.trim();
            refresh();
        });

        list = layout.addToContents(new FileList(minecraft));

        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(6));
        footer.defaultCellSetting().alignHorizontallyCenter();
        infoLabel = footer.addChild(new StringWidget(Math.min(width - 20, 400), 9, Component.empty(), font));
        LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(8));
        loadButton = buttons.addChild(Button.builder(Component.translatable("slopmatica.browser.load"), b -> activate(list.getSelected())).width(100).build());
        uploadButton = buttons.addChild(Button.builder(Component.translatable("slopmatica.browser.upload"), b -> {
            FileEntry selected = list.getSelected();
            if (selected != null && selected.kind == Kind.FILE) {
                CloudSync.INSTANCE.upload(selected.path);
            }
        }).width(100).tooltip(Tooltip.create(Component.translatable("slopmatica.browser.upload.tooltip"))).build());
        buttons.addChild(Button.builder(Component.translatable("slopmatica.browser.open_folder"), b -> Blaze3D.openPath(current)).width(100).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(100).build());

        // Added in reading order rather than via layout.visitWidgets (header, footer, contents), so
        // Tab goes search box -> file list -> buttons.
        header.visitWidgets(this::addRenderableWidget);
        addRenderableWidget(list);
        footer.visitWidgets(this::addRenderableWidget);
        setInitialFocus(search);
        refresh();
        repositionElements();
    }

    /** A bigger icon of the selected schematic beside the list, when the window is wide enough. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        FileEntry selected = list == null ? null : list.getSelected();
        if (selected == null || selected.kind != Kind.FILE) {
            return;
        }
        int left = list.getRowRight() + 12;
        int size = Math.min(SchematicThumbnails.SIZE, Math.min(width - left - 8, layout.getContentHeight() - 16));
        if (size < 48) {
            return;
        }
        Identifier icon = SchematicThumbnails.INSTANCE.get(selected.path);
        if (icon != null) {
            int full = SchematicThumbnails.SIZE;
            graphics.blit(RenderPipelines.GUI_TEXTURED, icon, left, layout.getHeaderHeight() + 8, 0, 0, size, size, full, full, full, full);
        }
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

    private void refresh() {
        List<FileEntry> entries = new ArrayList<>();
        if (filter.isEmpty()) {
            if (!current.equals(root)) {
                entries.add(new FileEntry(current.getParent(), Kind.PARENT));
            }
            try (Stream<Path> children = Files.list(current)) {
                children.filter(p -> Files.isDirectory(p) || isSchematic(p))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> entries.add(new FileEntry(p, Files.isDirectory(p) ? Kind.DIR : Kind.FILE)));
            } catch (IOException e) {
                // Unreadable folder: show it empty.
            }
        } else {
            String needle = filter.toLowerCase(Locale.ROOT);
            try (Stream<Path> files = Files.walk(root, 8)) {
                files.filter(p -> isSchematic(p) && root.relativize(p).toString().toLowerCase(Locale.ROOT).contains(needle))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> entries.add(new FileEntry(p, Kind.FILE)));
            } catch (IOException e) {
                // Partial results are fine.
            }
        }
        list.replaceEntries(entries);
        list.setScrollAmount(0);
        setLabel(pathLabel, Component.literal(filter.isEmpty() ? "schematics/" + pathFromRoot(current) : "Searching all folders").withColor(DETAIL_COLOR));
        onSelectionChanged(null);
    }

    /** Labels size to their text, so the layout must re-center them whenever the text changes. */
    private void setLabel(StringWidget label, Component text) {
        label.setMessage(text);
        layout.arrangeElements();
    }

    private String pathFromRoot(Path dir) {
        String rel = root.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "" : rel + "/";
    }

    private static boolean isSchematic(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic") && Files.isRegularFile(p);
    }

    private void onSelectionChanged(@Nullable FileEntry entry) {
        loadButton.active = entry != null && entry.kind == Kind.FILE;
        uploadButton.active = loadButton.active && CloudSync.INSTANCE.loggedIn();
        if (entry == null || entry.kind != Kind.FILE) {
            setLabel(infoLabel, Component.empty());
            return;
        }
        Component cached = INFO_CACHE.get(entry.path);
        if (cached != null) {
            setLabel(infoLabel, cached);
            return;
        }
        setLabel(infoLabel, Component.literal("...").withColor(DETAIL_COLOR));
        Path path = entry.path;
        Util.ioPool().execute(() -> {
            Component info = readInfo(path);
            INFO_CACHE.put(path, info);
            minecraft.execute(() -> {
                FileEntry selected = list.getSelected();
                if (selected != null && selected.path.equals(path)) {
                    setLabel(infoLabel, info);
                }
            });
        });
    }

    /** Litematica stores size, block count and author in the file's Metadata tag. */
    private static Component readInfo(Path path) {
        try {
            CompoundTag meta = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Metadata");
            CompoundTag size = meta.getCompoundOrEmpty("EnclosingSize");
            String author = meta.getStringOr("Author", "");
            String text = String.format(Locale.ROOT, "%d x %d x %d  ·  %,d blocks  ·  %d region%s%s",
                size.getIntOr("x", 0), size.getIntOr("y", 0), size.getIntOr("z", 0), meta.getIntOr("TotalBlocks", 0),
                meta.getIntOr("RegionCount", 1), meta.getIntOr("RegionCount", 1) == 1 ? "" : "s", author.isBlank() ? "" : "  ·  by " + author);
            return Component.literal(text).withColor(DETAIL_COLOR);
        } catch (IOException | RuntimeException e) {
            return Component.literal("Not a readable .litematic file").withColor(SlopmaticaActions.ERROR);
        }
    }

    private void activate(@Nullable FileEntry entry) {
        if (entry == null) {
            return;
        }
        switch (entry.kind) {
            case PARENT, DIR -> {
                current = entry.path;
                refresh();
            }
            case FILE -> {
                if (minecraft.player != null) {
                    SlopmaticaActions.load(entry.path, minecraft.player.blockPosition(), SlopmaticaActions::chat);
                }
                minecraft.gui.setScreen(null);
            }
        }
    }

    private enum Kind { PARENT, DIR, FILE }

    private class FileList extends ObjectSelectionList<FileEntry> {
        FileList(Minecraft minecraft) {
            super(minecraft, SchematicBrowserScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), ROW_HEIGHT);
        }

        @Override
        public void setSelected(@Nullable FileEntry entry) {
            super.setSelected(entry);
            onSelectionChanged(entry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, SchematicBrowserScreen.this.width - 40);
        }
    }

    private class FileEntry extends ObjectSelectionList.Entry<FileEntry> {
        final Path path;
        final Kind kind;
        private final String label;
        private final String detail;

        FileEntry(Path path, Kind kind) {
            this.path = path;
            this.kind = kind;
            this.label = switch (kind) {
                case PARENT -> "../";
                case DIR -> path.getFileName() + "/";
                case FILE -> filter.isEmpty() ? path.getFileName().toString() : root.relativize(path).toString().replace('\\', '/');
            };
            this.detail = kind == Kind.FILE ? fileDetail(path) : "";
        }

        private static String fileDetail(Path path) {
            try {
                long kb = Math.max(1, Files.size(path) / 1024);
                return kb + " KB   " + DATE.format(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
            } catch (IOException e) {
                return "";
            }
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int y = getContentYMiddle() - 4;
            int detailWidth = font.width(detail);
            int textX = getContentX() + ICON + 6;
            if (kind == Kind.FILE) {
                Identifier icon = SchematicThumbnails.INSTANCE.get(path);
                if (icon != null) {
                    int size = SchematicThumbnails.SIZE;
                    graphics.blit(RenderPipelines.GUI_TEXTURED, icon, getContentX() + 1, getContentYMiddle() - ICON / 2, 0, 0, ICON, ICON, size, size, size, size);
                }
            }
            String shown = font.plainSubstrByWidth(label, getContentRight() - textX - detailWidth - 10);
            graphics.text(font, shown, textX, y, kind == Kind.FILE ? -1 : DIR_COLOR);
            if (!detail.isEmpty()) {
                graphics.text(font, detail, getContentRight() - detailWidth - 2, y, DETAIL_COLOR);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (doubleClick) {
                activate(this);
            }
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                activate(this);
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(label);
        }
    }
}
