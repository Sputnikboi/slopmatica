package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.cloud.BoardClient;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The website's board in the game: search and sort public builds (or your favourites), see their
 * icons, and download one (to schematics/board/) or add it to your cloud. Adding to the cloud or
 * favouriting doesn't use your storage: the build counts against its uploader's.
 */
public class BoardBrowserScreen extends Screen {
    private static final int ROW = 44, ICON = 40;
    private static final List<String> SORTS = List.of("top", "new", "downloads");

    private final @Nullable Screen parent;
    private String query = "";
    private String sort = "top";
    /** What is browsed: the board ("" ), your favourites ("*"), or a group (its id). */
    private String source = "";
    /** Groups to offer, fetched once; the source button lists them after the first two. */
    private List<BoardClient.Group> groups = List.of();
    private boolean groupsAsked;
    private int page = 1, pages = 1, scroll;
    private List<BoardClient.Post> posts = List.of();
    private BoardClient.@Nullable Post selected;
    private String status = "";
    private final Map<String, Identifier> icons = new HashMap<>();
    private int listTop, listBottom, left, right;

    public BoardBrowserScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.board.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        left = Math.max(10, width / 2 - 220);
        right = Math.min(width - 10, width / 2 + 220);
        EditBox search = addRenderableWidget(new EditBox(font, left, 26, 190, 20, Component.translatable("slopmatica.board.search")));
        search.setValue(query);
        search.setHint(Component.translatable("slopmatica.board.search").withStyle(EditBox.SEARCH_HINT_STYLE));
        search.setResponder(v -> query = v);
        addRenderableWidget(CycleButton.<String>builder(s -> Component.translatable("slopmatica.board.sort." + s), sort)
            .withValues(SORTS).create(left + 194, 26, 110, 20, Component.translatable("slopmatica.board.sort"), (b, v) -> {
                sort = v;
                page = 1;
                load();
            }));
        List<String> sources = new ArrayList<>(List.of("", "*"));
        groups.forEach(g -> sources.add(g.id()));
        if (!sources.contains(source)) {
            source = "";
        }
        addRenderableWidget(CycleButton.<String>builder(this::sourceName, source).withValues(sources).displayOnlyValue()
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.board.source.tooltip")))
            .create(left + 308, 26, 80, 20, Component.empty(), (b, v) -> {
                source = v;
                page = 1;
                load();
            }));
        if (!groupsAsked && CloudSync.INSTANCE.loggedIn()) {
            groupsAsked = true;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return BoardClient.INSTANCE.groups();
                } catch (Exception e) {
                    return List.<BoardClient.Group>of();
                }
            }, Util.ioPool()).thenAcceptAsync(found -> {
                groups = found;
                rebuildWidgets();
            }, minecraft);
        }
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.board.go"), b -> {
            page = 1;
            load();
        }).bounds(left + 392, 26, Math.max(40, right - left - 392), 20).build());
        listTop = 52;
        listBottom = height - 56;

        int by = height - 50, bw = (right - left - 16) / 5;
        Button download = addRenderableWidget(Button.builder(Component.translatable("slopmatica.board.download"), b -> download())
            .bounds(left, by, bw, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.board.download.tooltip"))).build());
        Button save = addRenderableWidget(Button.builder(Component.translatable("slopmatica.board.save"), b -> saveToCloud())
            .bounds(left + (bw + 4), by, bw, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.board.save.tooltip"))).build());
        Button favorite = addRenderableWidget(Button.builder(Component.translatable(selected != null && selected.favorited() ? "slopmatica.board.unfavorite" : "slopmatica.board.favorite"),
            b -> toggleFavorite()).bounds(left + 2 * (bw + 4), by, bw, 20).build());
        Button web = addRenderableWidget(Button.builder(Component.translatable("slopmatica.board.website"), b -> {
            if (selected != null && !selected.shareUrl().isEmpty()) {
                ConfirmLinkScreen.confirmLinkNow(this, URI.create(selected.shareUrl()));
            }
        }).bounds(left + 3 * (bw + 4), by, bw, 20).build());
        boolean chosen = selected != null, in = CloudSync.INSTANCE.loggedIn();
        download.active = chosen;
        save.active = chosen && in;
        favorite.active = chosen && in;
        web.active = chosen;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> turn(-1)).bounds(left + 4 * (bw + 4), by, (bw - 4) / 2, 20).build()).active = page > 1;
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> turn(1)).bounds(left + 4 * (bw + 4) + (bw + 4) / 2, by, (bw - 4) / 2, 20).build()).active = page < pages;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 75, height - 26, 150, 20).build());
        if (posts.isEmpty() && status.isEmpty()) {
            load();
        } else {
            posts.forEach(this::loadIcon);
        }
    }

    private void turn(int by) {
        page = Math.clamp(page + by, 1, pages);
        load();
    }

    private void load() {
        status = Component.translatable("slopmatica.board.loading").getString();
        String q = query, s = sort, from = source;
        int p = page;
        CompletableFuture.supplyAsync(() -> {
            try {
                return from.equals("*") ? BoardClient.INSTANCE.favorites()
                    : from.isEmpty() ? BoardClient.INSTANCE.browse(q, s, p) : BoardClient.INSTANCE.browseGroup(from, q, s, p);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((result, error) -> {
            if (error != null) {
                status = Component.translatable("slopmatica.board.failed", error.getCause() != null ? error.getCause().getMessage() : error.getMessage()).getString();
                return;
            }
            posts = result.posts();
            pages = Math.max(1, result.pages());
            scroll = 0;
            selected = null;
            status = posts.isEmpty() ? Component.translatable("slopmatica.board.nothing").getString() : "";
            posts.forEach(this::loadIcon);
            rebuildWidgets();
        }, minecraft);
    }

    private void loadIcon(BoardClient.Post post) {
        if (!post.hasThumbnail() || icons.containsKey(post.id())) {
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath("slopmatica", "board/" + post.id().toLowerCase(Locale.ROOT) + "_" + Integer.toHexString(post.id().hashCode()));
        icons.put(post.id(), null); // Loading.
        CompletableFuture.supplyAsync(() -> {
            try {
                return BoardClient.INSTANCE.thumbnail(post.id());
            } catch (Exception e) {
                return null;
            }
        }, Util.ioPool()).thenAcceptAsync(bytes -> {
            if (bytes == null) {
                return;
            }
            try {
                NativeImage image = NativeImage.read(bytes);
                minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Board icon " + post.id(), image));
                icons.put(post.id(), id);
            } catch (Exception ignored) {
                // Not an image: no icon.
            }
        }, minecraft);
    }

    private void download() {
        BoardClient.Post post = selected;
        if (post == null) {
            return;
        }
        status = Component.translatable("slopmatica.board.downloading", post.title()).getString();
        Path dir = SlopmaticaActions.schematicsDir().resolve("board");
        CompletableFuture.supplyAsync(() -> {
            try {
                return BoardClient.INSTANCE.download(post, dir);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((file, error) -> status = error != null
            ? Component.translatable("slopmatica.board.failed", error.getCause() != null ? error.getCause().getMessage() : error.getMessage()).getString()
            : Component.translatable("slopmatica.board.downloaded", "schematics/board/" + file.getFileName()).getString(), minecraft);
    }

    private void saveToCloud() {
        BoardClient.Post post = selected;
        if (post == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                BoardClient.INSTANCE.saveToCloud(post.id());
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                status = Component.translatable("slopmatica.board.failed", error.getCause() != null ? error.getCause().getMessage() : error.getMessage()).getString();
            } else {
                status = Component.translatable("slopmatica.board.saved", post.title()).getString();
                CloudSync.INSTANCE.syncNow(); // Bring it down to the cloud folder now.
            }
        }, minecraft);
    }

    private void toggleFavorite() {
        BoardClient.Post post = selected;
        if (post == null) {
            return;
        }
        boolean on = !post.favorited();
        CompletableFuture.supplyAsync(() -> {
            try {
                return BoardClient.INSTANCE.setFavorite(post.id(), on);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((count, error) -> {
            if (error != null) {
                status = Component.translatable("slopmatica.board.failed", error.getCause() != null ? error.getCause().getMessage() : error.getMessage()).getString();
                return;
            }
            BoardClient.Post updated = new BoardClient.Post(post.id(), post.title(), post.author(), post.sizeClass(), post.complexity(), post.blocks(),
                post.downloads(), count, on, post.tags(), post.hasThumbnail(), post.shareUrl());
            List<BoardClient.Post> list = new ArrayList<>(posts);
            list.replaceAll(p -> p.id().equals(post.id()) ? updated : p);
            posts = list;
            selected = updated;
            rebuildWidgets();
        }, minecraft);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 10, -1);
        int visible = (listBottom - listTop) / ROW;
        for (int i = 0; i < visible && scroll + i < posts.size(); i++) {
            BoardClient.Post post = posts.get(scroll + i);
            int y = listTop + i * ROW;
            boolean over = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW - 2;
            g.fill(left, y, right, y + ROW - 2, post == selected ? 0x803060C0 : over ? 0x40FFFFFF : 0x40000000);
            Identifier icon = icons.get(post.id());
            if (icon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, icon, left + 2, y + 1, 0, 0, ICON, ICON, ICON, ICON);
            } else {
                g.fill(left + 2, y + 1, left + 2 + ICON, y + 1 + ICON, 0x60202020);
            }
            int tx = left + ICON + 8;
            g.text(font, font.plainSubstrByWidth(post.title(), right - tx - 4), tx, y + 4, 0xFFFFFFFF);
            String size = post.sizeClass().isEmpty() ? "" : " · " + post.sizeClass().replace('-', ' ');
            g.text(font, Component.translatable("slopmatica.board.line", post.author(), size, String.format(Locale.ROOT, "%,d", post.blocks())), tx, y + 16, 0xFFB0B0B0);
            String tags = String.join(", ", post.tags());
            Component stats = Component.literal("♥ " + post.favorites() + "   ⬇ " + post.downloads() + (tags.isEmpty() ? "" : "   " + tags))
                .withStyle(post.favorited() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY);
            g.text(font, font.plainSubstrByWidth(stats.getString(), right - tx - 4), tx, y + 28, post.favorited() ? 0xFFFF80C0 : 0xFF909090);
        }
        if (posts.size() > visible) {
            g.text(font, (scroll + 1) + "-" + Math.min(posts.size(), scroll + visible) + " / " + posts.size(), right - 60, listTop - 10, 0xFF909090);
        }
        if (!status.isEmpty()) {
            g.centeredText(font, status, width / 2, listBottom - 10, 0xFFFFD060);
        }
        g.text(font, Component.translatable("slopmatica.board.page", page, pages), left, height - 22, 0xFF909090);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.x() >= left && event.x() < right && event.y() >= listTop && event.y() < listBottom) {
            int index = scroll + (int) ((event.y() - listTop) / ROW);
            if (index >= 0 && index < posts.size()) {
                selected = posts.get(index);
                rebuildWidgets();
                if (doubleClick) {
                    download();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int visible = (listBottom - listTop) / ROW;
        scroll = Math.clamp(scroll + (scrollY > 0 ? -1 : 1), 0, Math.max(0, posts.size() - visible));
        return true;
    }

    @Override
    public void removed() {
        super.removed();
        icons.values().forEach(id -> {
            if (id != null) {
                minecraft.getTextureManager().release(id);
            }
        });
        icons.clear(); // Reloaded if the screen comes back (after the website link, say).
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private Component sourceName(String id) {
        if (id.isEmpty()) {
            return Component.translatable("slopmatica.board.everything");
        }
        if (id.equals("*")) {
            return Component.translatable("slopmatica.board.favorites");
        }
        return groups.stream().filter(g -> g.id().equals(id)).findFirst()
            .map(g -> (Component) Component.literal(g.name())).orElse(Component.literal("?"));
    }
}
