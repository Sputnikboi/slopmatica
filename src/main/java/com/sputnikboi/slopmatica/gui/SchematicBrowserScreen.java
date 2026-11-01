package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.Blaze3D;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import net.minecraft.nbt.ListTag;
import com.sputnikboi.slopmatica.online.Online;
import net.minecraft.client.gui.components.Tooltip;
import com.sputnikboi.slopmatica.render.SchematicThumbnails;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.share.SharedFolder;

/**
 * Browses {@code <gamedir>/schematics} (Litematica's folder) and loads the chosen file at the
 * player's feet. Typing in the search box searches every subfolder, not just the current one.
 */
public class SchematicBrowserScreen extends Screen {
    private static final int DIR_COLOR = 0xFFFFE070;
    private static final int ROW_HEIGHT = 52;
    private static final int ICON = 48;
    private static final int MAX_PREVIEW = 192;
    private static final ItemStack FOLDER_ICON = new ItemStack(Items.CHEST);
    private static final ItemStack PARENT_ICON = new ItemStack(Items.ARROW);
    private static final int DETAIL_COLOR = 0xFF909090;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** File info is read off-thread once per file and kept for the session. */
    private static final Map<Path, Component> INFO_CACHE = new ConcurrentHashMap<>();

    private final @Nullable Screen parent;
    private static final int BADGE_COLOR = 0xFF55CCFF;
    /** The tab the browser last showed, so reopening it lands in the same place. */
    private static boolean lastCloudTab;
    private static boolean lastSharedTab;

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 82, 53);
    private final Path schematics;
    private final Path cloudDir;
    /** Cloud tab: browse {@code schematics/cloud/}; otherwise everything else in {@code schematics/}. */
    private boolean cloudTab;
    /** Shared tab: the folder shared by every instance on this computer (when this one uses it). */
    private boolean sharedTab;
    private Path root;
    private Path current;
    private Button localTabButton;
    private @Nullable Button cloudTabButton;
    private @Nullable Button sharedTabButton;
    private String filter = "";
    private FileList list;
    private StringWidget pathLabel;
    private StringWidget infoLabel;
    private Button loadButton;
    private Button uploadButton;

    public SchematicBrowserScreen(@Nullable Screen parent) {
        this(parent, lastCloudTab);
        if (lastSharedTab && SlopmaticaConfig.get().shareSchematics) {
            showShared();
        }
    }

    public SchematicBrowserScreen(@Nullable Screen parent, boolean cloud) {
        super(Component.translatable("slopmatica.browser.title"));
        this.parent = parent;
        this.schematics = SlopmaticaActions.schematicsDir();
        Path cd = Online.cloudDir();
        // The local edition has no cloud tab; its "cloud" folder (if any) is just another folder.
        this.cloudDir = cd != null ? cd : schematics.resolve(".no-cloud");
        this.cloudTab = lastCloudTab = cloud && cd != null;
        this.root = cloudTab ? cloudDir : schematics;
        this.current = root;
        Online.refreshPresence();
    }

    private void showTab(boolean cloud) {
        cloudTab = lastCloudTab = cloud;
        sharedTab = lastSharedTab = false;
        root = cloud ? cloudDir : schematics;
        current = root;
        if (list != null) {
            refresh();
        }
    }

    private void showShared() {
        cloudTab = lastCloudTab = false;
        sharedTab = lastSharedTab = true;
        root = SharedFolder.schematicsDir();
        current = root;
        if (list != null) {
            refresh();
        }
    }

    @Override
    protected void init() {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(title, font));
        LinearLayout tabs = header.addChild(LinearLayout.horizontal().spacing(4));
        localTabButton = tabs.addChild(Button.builder(Component.translatable("slopmatica.browser.tab.local"), b -> showTab(false)).width(110).build());
        cloudTabButton = Online.available()
            ? tabs.addChild(Button.builder(Component.translatable("slopmatica.browser.tab.cloud"), b -> showTab(true)).width(110).build())
            : null;
        sharedTabButton = SlopmaticaConfig.get().shareSchematics
            ? tabs.addChild(Button.builder(Component.translatable("slopmatica.browser.tab.shared"), b -> showShared()).width(110)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.browser.tab.shared.tooltip", SharedFolder.display(SharedFolder.schematicsDir())))).build())
            : null;
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
                Online.upload(selected.path);
            }
        }).width(100).tooltip(Tooltip.create(Component.translatable("slopmatica.browser.upload.tooltip"))).build());
        buttons.addChild(Button.builder(Component.translatable("slopmatica.info.button"), b -> {
            FileEntry selected = list.getSelected();
            if (selected != null && selected.kind == Kind.FILE) {
                minecraft.gui.setScreen(new SchematicInfoScreen(this, selected.path, null));
            }
        }).width(100).tooltip(Tooltip.create(Component.translatable("slopmatica.info.button.tooltip"))).build());
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
        int size = Math.min(MAX_PREVIEW, Math.min(width - left - 8, layout.getContentHeight() - 16));
        if (size < ICON + 16) {
            return;
        }
        // Its own high-resolution render; the list icon stands in until that is ready.
        int full = SchematicThumbnails.PREVIEW_SIZE;
        Identifier icon = SchematicThumbnails.INSTANCE.get(selected.path, full);
        if (icon == null) {
            full = SchematicThumbnails.SIZE;
            icon = SchematicThumbnails.INSTANCE.get(selected.path);
        }
        if (icon != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, icon, left, layout.getHeaderHeight() + 8, 0, 0, size, size, full, full, full, full);
        }
        // Modded schematics: say which mods, and that they stay off the cloud and the board.
        java.util.Set<String> mods = com.sputnikboi.slopmatica.schematic.ModCheck.namespacesIfReady(selected.path);
        if (mods != null && !mods.isEmpty()) {
            int y = layout.getHeaderHeight() + 8 + size + 4;
            for (var line : font.split(Component.translatable("slopmatica.browser.modded", String.join(", ", mods)), Math.max(60, width - left - 8))) {
                graphics.text(font, line, left, y, 0xFFFFA030);
                y += 10;
            }
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
            try {
                Files.createDirectories(current); // the cloud folder before the first sync
            } catch (IOException ignored) {
                // Listing below reports it as empty.
            }
            try (Stream<Path> children = Files.list(current)) {
                children.filter(p -> !hidden(root, p) && !p.equals(cloudDir) && (Files.isDirectory(p) || isSchematic(p)))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> entries.add(new FileEntry(p, Files.isDirectory(p) ? Kind.DIR : Kind.FILE)));
            } catch (IOException e) {
                // Unreadable folder: show it empty.
            }
        } else {
            String needle = filter.toLowerCase(Locale.ROOT);
            try (Stream<Path> files = Files.walk(root, 8)) {
                files.filter(p -> isSchematic(p) && !hidden(root, p) && (cloudTab || !p.startsWith(cloudDir)) && root.relativize(p).toString().toLowerCase(Locale.ROOT).contains(needle))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> entries.add(new FileEntry(p, Kind.FILE)));
            } catch (IOException e) {
                // Partial results are fine.
            }
        }
        list.replaceEntries(entries);
        list.setScrollAmount(0);
        localTabButton.active = cloudTab || sharedTab;
        if (cloudTabButton != null) {
            cloudTabButton.active = !cloudTab;
        }
        if (sharedTabButton != null) {
            sharedTabButton.active = !sharedTab;
        }
        uploadButton.visible = !cloudTab && Online.available();
        String where = cloudTab ? "cloud/" : sharedTab ? "shared/" : "schematics/";
        String label = filter.isEmpty() ? where + pathFromRoot(current) : cloudTab ? "Searching the cloud" : "Searching all folders";
        if (cloudTab && !Online.loggedIn()) {
            label = "Not logged in to the cloud: menu → Cloud";
        }
        setLabel(pathLabel, Component.literal(label).withColor(DETAIL_COLOR));
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

    /** Dot folders (the cloud trash, for one) are housekeeping, not schematics to browse. */
    private static boolean hidden(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSchematic(Path p) {
        return SchematicFormats.isSchematic(p);
    }

    private void onSelectionChanged(@Nullable FileEntry entry) {
        loadButton.active = entry != null && entry.kind == Kind.FILE;
        uploadButton.active = loadButton.active && Online.loggedIn() && !Online.inCloud(entry.path);
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

    /** Size, block count and author, from each format's header where it keeps one. */
    private static Component readInfo(Path path) {
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            int sx, sy, sz, regions = 1;
            long blocks;
            String author;
            switch (SchematicFormats.of(path)) {
                case SLOPMATIC -> {
                    int[] size = root.getIntArray("Size").orElse(new int[3]);
                    sx = size[0];
                    sy = size[1];
                    sz = size[2];
                    blocks = root.getLongOr("BlockCount", 0);
                    regions = root.getListOrEmpty("Regions").size();
                    author = root.getStringOr("Author", "");
                }
                case NBT -> {
                    ListTag size = root.getListOrEmpty("size");
                    sx = size.getIntOr(0, 0);
                    sy = size.getIntOr(1, 0);
                    sz = size.getIntOr(2, 0);
                    // Count stored blocks that aren't air.
                    ListTag palette = root.getListOrEmpty("palette");
                    ListTag list = root.getListOrEmpty("blocks");
                    long n = 0;
                    for (int i = 0; i < list.size(); i++) {
                        CompoundTag state = palette.getCompoundOrEmpty(list.getCompoundOrEmpty(i).getIntOr("state", 0));
                        String name = state.getStringOr("Name", state.getStringOr("id", ""));
                        if (!name.endsWith(":air") && !name.endsWith("cave_air") && !name.endsWith("void_air")) {
                            n++;
                        }
                    }
                    blocks = n;
                    author = root.getStringOr("author", "");
                }
                default -> {
                    CompoundTag meta = root.getCompoundOrEmpty("Metadata");
                    CompoundTag size = meta.getCompoundOrEmpty("EnclosingSize");
                    sx = size.getIntOr("x", 0);
                    sy = size.getIntOr("y", 0);
                    sz = size.getIntOr("z", 0);
                    blocks = meta.getIntOr("TotalBlocks", 0);
                    regions = meta.getIntOr("RegionCount", 1);
                    author = meta.getStringOr("Author", "");
                }
            }
            String text = String.format(Locale.ROOT, "%d x %d x %d  ·  %,d blocks  ·  %d region%s%s",
                Math.abs(sx), Math.abs(sy), Math.abs(sz), blocks, regions, regions == 1 ? "" : "s", author.isBlank() ? "" : "  ·  by " + author);
            return Component.literal(text).withColor(DETAIL_COLOR);
        } catch (IOException | RuntimeException e) {
            return Component.literal("Not a readable schematic file").withColor(SlopmaticaActions.ERROR);
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
            if (kind != Kind.FILE) {
                // A chest for folders, an arrow for "up", drawn at icon size.
                graphics.pose().pushMatrix();
                graphics.pose().translate(getContentX() + 1 + ICON / 2.0F - 16, getContentYMiddle() - 16);
                graphics.pose().scale(2.0F, 2.0F);
                graphics.item(kind == Kind.PARENT ? PARENT_ICON : FOLDER_ICON, 0, 0);
                graphics.pose().popMatrix();
            } else {
                Identifier icon = SchematicThumbnails.INSTANCE.get(path);
                if (icon != null) {
                    int size = SchematicThumbnails.SIZE;
                    graphics.blit(RenderPipelines.GUI_TEXTURED, icon, getContentX() + 1, getContentYMiddle() - ICON / 2, 0, 0, ICON, ICON, size, size, size, size);
                }
            }
            // "in cloud" / "on disk": the same schematic exists on the other side too.
            String badge = kind != Kind.FILE ? null
                : cloudTab ? (Online.onDisk(path) ? "on disk" : null)
                : (Online.inCloud(path) ? "in cloud" : null);
            int badgeWidth = badge == null ? 0 : font.width(badge) + 8;
            String shown = font.plainSubstrByWidth(label, getContentRight() - textX - detailWidth - badgeWidth - 14);
            // Files added from the schematics board name their author underneath.
            String author = kind == Kind.FILE && cloudTab ? Online.boardAuthor(path) : null;
            String listed = kind == Kind.FILE && cloudTab ? Online.listingOf(path) : null;
            if (author != null) {
                y -= 5;
                String by = font.plainSubstrByWidth("by " + author + "  ·  from the board", getContentRight() - textX - detailWidth - 14);
                graphics.text(font, by, textX, y + 11, DETAIL_COLOR);
            } else if (listed != null) {
                // One of your own files that is posted on the board, and how visible it is.
                y -= 5;
                graphics.text(font, Component.translatable("slopmatica.browser.listed." + listed), textX, y + 11, "public".equals(listed) ? 0xFF60E060 : DETAIL_COLOR);
            }
            graphics.text(font, shown, textX, y, kind == Kind.FILE ? -1 : DIR_COLOR);
            if (badge != null) {
                int bx = textX + font.width(shown) + 6;
                graphics.fill(bx, y - 2, bx + badgeWidth, y + 9, 0x6055CCFF);
                graphics.text(font, badge, bx + 4, y, BADGE_COLOR);
            }
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
