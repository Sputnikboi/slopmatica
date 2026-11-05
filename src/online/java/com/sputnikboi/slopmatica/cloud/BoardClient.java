package com.sputnikboi.slopmatica.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The website's board from inside the game: browsing, favourites, downloading, adding to the
 * cloud (free for you: it counts against the uploader's storage), and sending bug reports with
 * the game log and a schematic attached. Blocking calls: run them off the client thread.
 */
public final class BoardClient {
    public static final BoardClient INSTANCE = new BoardClient();

    /** One board post, the parts the in-game browser shows. */
    public record Post(String id, String title, String author, String sizeClass, String complexity, long blocks, int downloads,
                       int favorites, boolean favorited, List<String> tags, boolean hasThumbnail, String shareUrl) {
        static Post of(JsonObject p) {
            JsonObject author = p.getAsJsonObject("author");
            List<String> tags = new ArrayList<>();
            if (p.has("tags") && p.get("tags").isJsonArray()) {
                p.getAsJsonArray("tags").forEach(t -> tags.add(t.getAsString()));
            }
            // A credited author (e.g. Mojang for vanilla structures) is shown instead of the uploader.
            String credit = str(p, "credit");
            return new Post(str(p, "id"), str(p, "title"),
                !credit.isEmpty() ? credit : author == null ? "?" : str(author, author.has("displayName") ? "displayName" : "name"),
                str(p, "sizeClass"), str(p, "complexity"), p.has("blockCount") ? p.get("blockCount").getAsLong() : 0,
                p.has("downloads") ? p.get("downloads").getAsInt() : 0, p.has("favorites") ? p.get("favorites").getAsInt() : 0,
                p.has("favorited") && !p.get("favorited").isJsonNull() && p.get("favorited").getAsBoolean(), tags,
                p.has("hasThumbnail") && p.get("hasThumbnail").getAsBoolean(), str(p, "shareUrl"));
        }
    }

    /** A group you can browse: public ones and those you're in ({@code role} null if not a member). */
    public record Group(String id, String name, @Nullable String role) {
    }

    /**
     * A folder inside a group: its name, its whole path there ("Overworld/Village"), how many builds
     * are in it and below it, and a build whose thumbnail stands for it (or null).
     */
    public record Folder(String name, String path, int count, @Nullable String cover) {
    }

    /** A page of results; {@code folders}: the folders in the group folder browsed (none elsewhere). */
    public record Page(List<Post> posts, int page, int pages, List<Folder> folders) {
        Page(List<Post> posts, int page, int pages) {
            this(posts, page, pages, List.of());
        }
    }

    private BoardClient() {
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Public builds: search text, sort (top, new, downloads), page from 1. */
    public Page browse(String query, String sort, int page) throws Exception {
        String path = "/api/board?limit=20&page=" + page + "&sort=" + enc(sort) + (query.isBlank() ? "" : "&q=" + enc(query.trim()));
        JsonObject json = CloudSync.INSTANCE.getJson(path);
        List<Post> posts = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("posts")) {
            posts.add(Post.of(e.getAsJsonObject()));
        }
        return new Page(posts, json.has("page") ? json.get("page").getAsInt() : page, json.has("pages") ? json.get("pages").getAsInt() : 1);
    }

    /** Groups to browse: the public ones and your own. */
    public List<Group> groups() throws Exception {
        JsonObject json = CloudSync.INSTANCE.getJson("/api/groups");
        List<Group> out = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            out.add(new Group(str(g, "id"), str(g, "name"), g.has("role") && !g.get("role").isJsonNull() ? g.get("role").getAsString() : null));
        }
        return out;
    }

    /**
     * A group's builds in one of its folders ("" for the top), like {@link #browse}, with the folders
     * inside it. A search looks through the folder and every folder below it.
     */
    public Page browseGroup(String group, String folder, String query, String sort, int page) throws Exception {
        String path = "/api/groups/" + enc(group) + "/posts?limit=20&page=" + page + "&sort=" + enc(sort) + (query.isBlank() ? "" : "&q=" + enc(query.trim()))
            + "&folder=" + enc(folder); // Always sent: without it the server lists the whole group flat (older mods).
        JsonObject json = CloudSync.INSTANCE.getJson(path);
        List<Post> posts = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("posts")) {
            posts.add(Post.of(e.getAsJsonObject()));
        }
        List<Folder> folders = new ArrayList<>();
        if (json.has("folders") && json.get("folders").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("folders")) {
                JsonObject f = e.getAsJsonObject();
                folders.add(new Folder(str(f, "name"), str(f, "path"), f.has("count") ? f.get("count").getAsInt() : 0,
                    f.has("cover") && !f.get("cover").isJsonNull() ? f.get("cover").getAsString() : null));
            }
        }
        return new Page(posts, json.has("page") ? json.get("page").getAsInt() : page, json.has("pages") ? json.get("pages").getAsInt() : 1, folders);
    }

    /** Your favourites (needs a login). */
    public Page favorites() throws Exception {
        JsonObject json = CloudSync.INSTANCE.getJson("/api/favorites");
        List<Post> posts = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("posts")) {
            posts.add(Post.of(e.getAsJsonObject()));
        }
        return new Page(posts, 1, 1);
    }

    /** Favourites or un-favourites; returns the new count. */
    public int setFavorite(String id, boolean on) throws Exception {
        HttpResponse<String> response = CloudSync.INSTANCE.request(on ? "POST" : "DELETE", "/api/board/" + id + "/favorite",
            HttpRequest.BodyPublishers.noBody(), Map.of(), true);
        return JsonParser.parseString(response.body()).getAsJsonObject().get("favorites").getAsInt();
    }

    /** Adds the build to your cloud (it arrives with the next sync). */
    public void saveToCloud(String id) throws Exception {
        CloudSync.INSTANCE.request("POST", "/api/board/" + id + "/save", HttpRequest.BodyPublishers.noBody(), Map.of(), true);
    }

    public byte[] thumbnail(String id) throws Exception {
        return CloudSync.INSTANCE.requestBytes("/api/board/" + id + "/thumbnail");
    }

    /** Downloads the build's file into {@code dir}, named after it; returns the file. */
    public Path download(Post post, Path dir) throws Exception {
        byte[] data = CloudSync.INSTANCE.requestBytes("/api/board/" + post.id() + "/file");
        Files.createDirectories(dir);
        String base = com.sputnikboi.slopmatica.build.Clipboard.safeFileName(post.title());
        Path file = dir.resolve(base + ".slopmatic");
        for (int i = 2; Files.exists(file); i++) {
            file = dir.resolve(base + " (" + i + ").slopmatic");
        }
        Files.write(file, data);
        return file;
    }

    /**
     * Sends a bug report (or suggestion) to the website, with the game log and a schematic if
     * given. Works without a login too.
     */
    public void sendReport(String type, String message, String modVersion, String mcVersion, @Nullable Path log, @Nullable Path schematic) throws Exception {
        String boundary = "slopmatica-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "type", type);
        field(body, boundary, "message", message);
        field(body, boundary, "modVersion", modVersion);
        field(body, boundary, "mcVersion", mcVersion);
        if (log != null && Files.isRegularFile(log)) {
            // Gzipped: logs shrink to a tenth, and the server accepts either.
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
                Files.copy(log, out);
            }
            file(body, boundary, "log", "latest.log.gz", gz.toByteArray());
        }
        if (schematic != null && Files.isRegularFile(schematic)) {
            file(body, boundary, "schematic", schematic.getFileName().toString(), Files.readAllBytes(schematic));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        CloudSync.INSTANCE.request("POST", "/api/feedback", HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()),
            // Without a login the server asks for this header (its guard against forged cross-site posts).
            Map.of("Content-Type", "multipart/form-data; boundary=" + boundary, "X-Requested-With", "slopmatica"), true);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void file(ByteArrayOutputStream out, String boundary, String name, String filename, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename.replace("\"", "")
            + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
