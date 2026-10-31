package com.sputnikboi.slopmatica.cloud;

import com.google.gson.Gson;
import com.mojang.blaze3d.Blaze3D;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.render.SchematicThumbnails;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slopmatica Cloud: schematics stored per Minecraft account on a Slopmatica Cloud server and kept
 * in sync with {@code schematics/cloud/} in every game instance logged in to that account.
 *
 * <p>Login is the same check servers use: the game proves to Mojang that it holds the account's
 * session for a one-off id, and the cloud server asks Mojang to confirm. The access token itself
 * never leaves the game. The cloud server then hands out its own API token, stored in
 * {@code config/slopmatica-cloud.json}.
 *
 * <p>Sync is two-way against the state of the last sync: a file changed on one side is copied to
 * the other; a file deleted on one side is deleted on the other (locally it goes to
 * {@code schematics/.cloud-trash/}, never straight to nothing); if both sides changed, the cloud
 * version wins and the local one is kept as a "(conflict ...)" copy, which then uploads too.
 */
public final class CloudSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica/cloud");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long AUTOSYNC_INTERVAL_MS = 60_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");
    /** Declared after the statics the constructor uses (GSON), so they exist when it runs. */
    public static final CloudSync INSTANCE = new CloudSync();

    /** The public Slopmatica Cloud; any other server can be entered on the Cloud screen. */
    public static final String DEFAULT_URL = "https://homeserver.taila182df.ts.net/slopmatica";

    /** Stored settings and credentials. */
    public static final class Account {
        public String url = DEFAULT_URL;
        public @Nullable String token;
        public @Nullable String uuid;
        public @Nullable String name;
        public boolean autosync = true;
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Slopmatica cloud");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean syncing = new AtomicBoolean();
    private Account account;
    private volatile String status = "";
    private volatile long usedBytes = -1, quotaBytes = -1;
    private long lastAutosync;

    private CloudSync() {
        account = loadAccount();
    }

    public Account account() {
        return account;
    }

    public boolean loggedIn() {
        return account.token != null && !account.url.isBlank();
    }

    public String status() {
        return status;
    }

    /** Storage used and limit in bytes; limit -1 = unlimited, used -1 = unknown. */
    public long usedBytes() {
        return usedBytes;
    }

    public long quotaBytes() {
        return quotaBytes;
    }

    public boolean syncing() {
        return syncing.get();
    }

    public static Path cloudDir() {
        return SlopmaticaActions.schematicsDir().resolve("cloud");
    }

    public void setUrl(String url) {
        String clean = url.trim().replaceAll("/+$", "");
        if (!clean.equals(account.url)) {
            account.url = clean;
            // A different server knows nothing of the old token.
            account.token = null;
            saveAccount();
        }
    }

    public void setAutosync(boolean on) {
        account.autosync = on;
        saveAccount();
    }

    // ---- login ----------------------------------------------------------------------------

    public void login() {
        if (account.url.isBlank()) {
            status = "Enter the cloud server's address first";
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var user = mc.getUser();
        status = "Logging in...";
        worker.execute(() -> {
            try {
                JsonObject challenge = postJson("/api/auth/challenge", new JsonObject(), false);
                String serverId = challenge.get("serverId").getAsString();
                if (!Boolean.getBoolean("slopmatica.dev.skipJoin")) {
                    // Tell Mojang this session is joining "serverId"; the cloud server checks with Mojang.
                    mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
                }
                JsonObject body = new JsonObject();
                body.addProperty("username", user.getName());
                body.addProperty("serverId", serverId);
                JsonObject result = postJson("/api/auth/verify", body, false);
                account.token = result.get("token").getAsString();
                account.uuid = result.get("uuid").getAsString();
                account.name = result.get("name").getAsString();
                readUsage(result);
                saveAccount();
                status = "Logged in as " + account.name;
                syncNow();
            } catch (Exception e) {
                status = "Login failed: " + message(e);
                LOGGER.warn("Cloud login failed", e);
            }
        });
    }

    public void logout() {
        account.token = null;
        account.uuid = null;
        account.name = null;
        saveAccount();
        usedBytes = quotaBytes = -1;
        status = "Logged out";
    }

    /** Opens the website in the browser, already logged in (a one-time link). */
    public void openWebsite() {
        if (!loggedIn()) {
            status = "Log in first";
            return;
        }
        worker.execute(() -> {
            try {
                String url = postJson("/api/weblogin", new JsonObject(), true).get("url").getAsString();
                Minecraft.getInstance().execute(() -> Blaze3D.openUri(URI.create(url)));
            } catch (Exception e) {
                status = "Could not open the website: " + message(e);
            }
        });
    }

    /** Copies a schematic into the cloud folder (the next sync uploads it) and syncs. */
    public void upload(Path file) {
        if (!loggedIn()) {
            SlopmaticaActions.actionBar(Component.literal("Log in to the cloud first (menu → Cloud)").withColor(SlopmaticaActions.ERROR));
            return;
        }
        worker.execute(() -> {
            try {
                Path dir = cloudDir();
                Path abs = file.toAbsolutePath().normalize();
                if (!abs.startsWith(dir.toAbsolutePath().normalize())) {
                    Files.createDirectories(dir);
                    Path target = dir.resolve(file.getFileName().toString());
                    for (int i = 2; Files.exists(target) && !sameContent(target, file); i++) {
                        target = dir.resolve(stripExtension(file.getFileName().toString()) + " (" + i + ").litematic");
                    }
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                notify("Uploading " + file.getFileName() + "...");
            } catch (IOException e) {
                status = "Upload failed: " + message(e);
                return;
            }
            syncNow();
        });
    }

    // ---- sync -----------------------------------------------------------------------------

    /** Client tick: autosync every minute while logged in. */
    public void tick() {
        if (loggedIn() && account.autosync && Util.getMillis() - lastAutosync > AUTOSYNC_INTERVAL_MS) {
            lastAutosync = Util.getMillis();
            syncNow();
        }
    }

    public void syncNow() {
        if (!loggedIn() || !syncing.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                sync();
            } catch (Exception e) {
                status = "Sync failed: " + message(e);
                LOGGER.warn("Cloud sync failed", e);
            } finally {
                syncing.set(false);
            }
        });
    }

    private void sync() throws Exception {
        Path dir = cloudDir();
        Files.createDirectories(dir);
        SyncState state = SyncState.load(account.uuid);

        JsonObject listing = getJson("/api/files");
        readUsage(listing);
        Map<String, String> remote = new HashMap<>();
        for (JsonElement e : listing.getAsJsonArray("files")) {
            JsonObject f = e.getAsJsonObject();
            remote.put(f.get("path").getAsString(), f.get("sha256").getAsString());
        }
        Map<String, String> local = scanLocal(dir, state);

        Set<String> paths = new HashSet<>(remote.keySet());
        paths.addAll(local.keySet());
        int up = 0, down = 0, removed = 0;
        boolean quotaHit = false;
        Path root = dir.toAbsolutePath().normalize();
        for (String path : paths) {
            if (!root.resolve(path).normalize().startsWith(root)) {
                LOGGER.warn("Ignoring cloud path outside the cloud folder: {}", path);
                continue;
            }
            String l = local.get(path), r = remote.get(path), base = state.synced.get(path);
            if (l != null && l.equals(r)) {
                state.synced.put(path, l);
            } else if (l != null && r != null) {
                if (l.equals(base)) {
                    download(dir, path, r);
                    state.synced.put(path, r);
                    down++;
                } else if (r.equals(base)) {
                    if (quotaHit || !upload(dir, path, l)) {
                        quotaHit = true;
                    } else {
                        state.synced.put(path, l);
                        up++;
                    }
                } else {
                    // Both changed: keep the cloud's, save ours beside it (it uploads next sync).
                    Path ours = dir.resolve(path);
                    String conflict = stripExtension(ours.getFileName().toString()) + " (conflict " + LocalDateTime.now().format(STAMP) + ").litematic";
                    Files.move(ours, ours.resolveSibling(conflict));
                    download(dir, path, r);
                    state.synced.put(path, r);
                    down++;
                }
            } else if (l != null) {
                if (l.equals(base)) {
                    // Synced before, gone from the cloud now: deleted there (website or another instance).
                    trash(dir, path);
                    state.synced.remove(path);
                    removed++;
                } else if (quotaHit || !upload(dir, path, l)) {
                    quotaHit = true;
                } else {
                    state.synced.put(path, l);
                    up++;
                }
            } else if (r.equals(base)) {
                // Synced before, deleted here: delete in the cloud too.
                request("DELETE", "/api/files/" + encodePath(path), HttpRequest.BodyPublishers.noBody(), Map.of(), true);
                state.synced.remove(path);
                removed++;
            } else {
                download(dir, path, r);
                state.synced.put(path, r);
                down++;
            }
        }
        state.save();
        // Icons the website is missing: upload them once they have been rendered here.
        for (JsonElement e : listing.getAsJsonArray("files")) {
            JsonObject f = e.getAsJsonObject();
            Path file = dir.resolve(f.get("path").getAsString());
            if (!f.get("hasThumbnail").getAsBoolean() && Files.isRegularFile(file)) {
                var png = SchematicThumbnails.INSTANCE.cachedPng(file);
                if (png.isPresent()) {
                    request("PUT", "/api/thumbnails/" + encodePath(f.get("path").getAsString()), HttpRequest.BodyPublishers.ofFile(png.get()),
                        Map.of("Content-Type", "image/png"), false);
                } else {
                    Minecraft.getInstance().execute(() -> SchematicThumbnails.INSTANCE.get(file));
                }
            }
        }
        if (up + down + removed > 0) {
            JsonObject me = getJson("/api/me");
            readUsage(me);
            notify(String.format(Locale.ROOT, "Cloud: %d uploaded, %d downloaded, %d removed", up, down, removed));
        }
        status = quotaHit ? "Cloud storage is full: some files were not uploaded" : "Synced " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Uploads one file and its icon; false if the account's storage is full. */
    private boolean upload(Path dir, String path, String sha) throws Exception {
        Path file = dir.resolve(path);
        HttpResponse<String> response = request("PUT", "/api/files/" + encodePath(path), HttpRequest.BodyPublishers.ofFile(file),
            Map.of("X-Sha256", sha, "Content-Type", "application/octet-stream"), false);
        if (response.statusCode() == 413) {
            return false;
        }
        check(response);
        SchematicThumbnails.INSTANCE.cachedPng(file).ifPresent(png -> {
            try {
                request("PUT", "/api/thumbnails/" + encodePath(path), HttpRequest.BodyPublishers.ofFile(png), Map.of("Content-Type", "image/png"), true);
            } catch (Exception e) {
                LOGGER.debug("Icon upload failed for {}", path, e);
            }
        });
        return true;
    }

    private void download(Path dir, String path, String sha) throws Exception {
        HttpRequest request = authorized(HttpRequest.newBuilder(uri("/api/files/" + encodePath(path)))).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("download of " + path + " failed: HTTP " + response.statusCode());
        }
        Path target = dir.resolve(path);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        try (InputStream in = response.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!sha.equals(sha256(tmp))) {
            Files.deleteIfExists(tmp);
            throw new IOException("download of " + path + " was corrupted");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void trash(Path dir, String path) throws IOException {
        Path file = dir.resolve(path);
        Path trash = SlopmaticaActions.schematicsDir().resolve(".cloud-trash").resolve(path);
        Files.createDirectories(trash.getParent());
        Files.move(file, trash, StandardCopyOption.REPLACE_EXISTING);
    }

    /** sha256 of every schematic in the cloud folder, reusing hashes of files that haven't changed. */
    private static Map<String, String> scanLocal(Path dir, SyncState state) throws IOException {
        Map<String, String> out = new HashMap<>();
        Map<String, SyncState.Hash> hashes = new HashMap<>();
        try (Stream<Path> files = Files.walk(dir, 8)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !name.toLowerCase(Locale.ROOT).endsWith(".litematic")) {
                    continue;
                }
                String rel = dir.relativize(p).toString().replace('\\', '/');
                long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
                SyncState.Hash known = state.hashes.get(rel);
                String sha = known != null && known.size == size && known.mtime == mtime ? known.sha : sha256(p);
                hashes.put(rel, new SyncState.Hash(size, mtime, sha));
                out.put(rel, sha);
            }
        }
        state.hashes = hashes;
        return out;
    }

    // ---- HTTP -----------------------------------------------------------------------------

    private JsonObject getJson(String path) throws Exception {
        return parse(check(request("GET", path, HttpRequest.BodyPublishers.noBody(), Map.of(), true)));
    }

    private JsonObject postJson(String path, JsonObject body, boolean auth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (auth) {
            authorized(builder);
        }
        return parse(check(http.send(builder.build(), HttpResponse.BodyHandlers.ofString())));
    }

    private HttpResponse<String> request(String method, String path, HttpRequest.BodyPublisher body, Map<String, String> headers, boolean checked) throws Exception {
        HttpRequest.Builder builder = authorized(HttpRequest.newBuilder(uri(path))).timeout(Duration.ofMinutes(5)).method(method, body);
        headers.forEach(builder::header);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return checked ? check(response) : response;
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        String token = account.token;
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> check(HttpResponse<String> response) throws IOException {
        int code = response.statusCode();
        if (code == 401 && account.token != null) {
            logout();
            throw new IOException("the cloud server no longer accepts this login; log in again");
        }
        if (code / 100 != 2) {
            String error = "HTTP " + code;
            try {
                error = JsonParser.parseString(response.body()).getAsJsonObject().get("error").getAsString();
            } catch (RuntimeException ignored) {
                // Not our JSON error shape.
            }
            throw new IOException(error);
        }
        return response;
    }

    private static JsonObject parse(HttpResponse<String> response) {
        return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private URI uri(String path) {
        return URI.create(account.url + path);
    }

    private void readUsage(JsonObject o) {
        if (o.has("usedBytes")) {
            usedBytes = o.get("usedBytes").getAsLong();
        }
        if (o.has("quotaBytes")) {
            quotaBytes = o.get("quotaBytes").isJsonNull() ? -1 : o.get("quotaBytes").getAsLong();
        }
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean sameContent(Path a, Path b) throws IOException {
        return Files.size(a) == Files.size(b) && sha256(a).equals(sha256(b));
    }

    private static String stripExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".litematic") ? name.substring(0, name.length() - ".litematic".length()) : name;
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static void notify(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                SlopmaticaActions.actionBar(Component.literal(message).withColor(SlopmaticaActions.ACCENT));
            }
        });
    }

    private static Path accountFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("slopmatica-cloud.json");
    }

    private static Account loadAccount() {
        try {
            Account a = GSON.fromJson(Files.readString(accountFile()), Account.class);
            if (a != null) {
                if (a.url == null || a.url.isBlank()) {
                    a.url = DEFAULT_URL;
                }
                return a;
            }
        } catch (IOException | RuntimeException ignored) {
            // First run, or unreadable: start logged out.
        }
        return new Account();
    }

    private void saveAccount() {
        try {
            Files.createDirectories(accountFile().getParent());
            Files.writeString(accountFile(), GSON.toJson(account));
        } catch (IOException e) {
            LOGGER.error("Could not save cloud settings", e);
        }
    }

    /** What the last sync saw, per account: synced content per path, plus a hash cache. */
    private static final class SyncState {
        Map<String, String> synced = new HashMap<>();
        Map<String, Hash> hashes = new HashMap<>();
        transient Path file;

        record Hash(long size, long mtime, String sha) {
        }

        static SyncState load(@Nullable String uuid) {
            Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("slopmatica").resolve("cloud-state-" + uuid + ".json");
            SyncState state = null;
            try {
                state = GSON.fromJson(Files.readString(file), SyncState.class);
            } catch (IOException | RuntimeException ignored) {
                // Never synced with this account here.
            }
            if (state == null) {
                state = new SyncState();
            }
            if (state.synced == null) {
                state.synced = new HashMap<>();
            }
            if (state.hashes == null) {
                state.hashes = new HashMap<>();
            }
            state.file = file;
            return state;
        }

        void save() throws IOException {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
