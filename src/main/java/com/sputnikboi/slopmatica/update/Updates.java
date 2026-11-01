package com.sputnikboi.slopmatica.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.Blaze3D;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online status and mod updates, from the Slopmatica Cloud server's releases.
 *
 * <p>Updating in place never risks two copies of the mod loading (which crashes Fabric): the new
 * jar is downloaded as {@code *.jar.pending}, which Fabric ignores, and swapped in by a small helper
 * process after the game exits (a running game keeps its own jar locked on Windows). If anything
 * fails, the releases page opens at that version instead.
 */
public final class Updates {
    public static final Updates INSTANCE = new Updates();

    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica/update");
    private static final long RECHECK_MS = 5 * 60_000;

    /** A release as the server describes it. */
    public record Release(String version, String download, String page, @Nullable String sha256) {
    }

    public enum Online { UNKNOWN, ONLINE, OFFLINE }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Online online = Online.UNKNOWN;
    private volatile @Nullable Release latest;
    private volatile boolean pending;
    private long lastCheck;

    private Updates() {
    }

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("slopmatica").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    public Online online() {
        return online;
    }

    /** The newest release if it is newer than this one, else null. */
    public @Nullable Release update() {
        Release r = latest;
        return r != null && newer(r.version(), modVersion()) ? r : null;
    }

    /** An update has been downloaded and will be installed when the game closes. */
    public boolean pending() {
        return pending;
    }

    /** Asks the server again if the last answer is older than a few minutes (or {@code force}). */
    public void check(boolean force) {
        if (!force && Util.getMillis() - lastCheck < RECHECK_MS && online != Online.UNKNOWN) {
            return;
        }
        lastCheck = Util.getMillis();
        String base = CloudSync.INSTANCE.account().url;
        Util.ioPool().execute(() -> {
            try {
                String mc = SharedConstants.getCurrentVersion().name();
                HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/releases/latest?mc=" + mc + "&loader=fabric"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                online = Online.ONLINE; // Any answer at all means the server is reachable.
                if (response.statusCode() == 200) {
                    JsonObject o = JsonParser.parseString(response.body()).getAsJsonObject();
                    latest = new Release(o.get("version").getAsString(), o.get("download").getAsString(), o.get("page").getAsString(),
                        o.has("sha256") && !o.get("sha256").isJsonNull() ? o.get("sha256").getAsString() : null);
                }
            } catch (Exception e) {
                online = Online.OFFLINE;
            }
        });
    }

    /** Downloads the update and arranges for it to replace this jar when the game closes. */
    public void install(Release release) {
        Path jar = ownJar();
        if (jar == null) {
            openPage(release, "this copy of the mod isn't a jar in the mods folder");
            return;
        }
        SlopmaticaActions.actionBar(Component.literal("Downloading Slopmatica " + release.version() + "...").withColor(SlopmaticaActions.ACCENT));
        Util.ioPool().execute(() -> {
            try {
                Path target = jar.resolveSibling("slopmatica-" + release.version() + ".jar");
                Path pendingJar = target.resolveSibling(target.getFileName() + ".pending");
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(release.download())).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("download failed: HTTP " + response.statusCode());
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, pendingJar, StandardCopyOption.REPLACE_EXISTING);
                }
                if (release.sha256() != null && !release.sha256().equalsIgnoreCase(sha256(pendingJar))) {
                    Files.deleteIfExists(pendingJar);
                    throw new IOException("the download was corrupted");
                }
                swapOnExit(jar, pendingJar, target);
                pending = true;
                Minecraft.getInstance().execute(() -> SlopmaticaActions.chat(SlopmaticaActions.prefix().append(
                    "Slopmatica " + release.version() + " is downloaded and will be installed when you close Minecraft.")));
            } catch (Exception e) {
                LOGGER.warn("Update failed", e);
                Minecraft.getInstance().execute(() -> openPage(release, e.getMessage()));
            }
        });
    }

    /**
     * After the JVM exits: delete the old jar, then rename the pending one into place. Done by a
     * detached shell process because the running game holds its own jar open.
     */
    private static void swapOnExit(Path oldJar, Path pendingJar, Path target) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd", "/c", "ping 127.0.0.1 -n 6 >nul & del /f /q \"" + oldJar + "\" & move /y \"" + pendingJar + "\" \"" + target + "\"")
                    : new ProcessBuilder("sh", "-c", "sleep 3; rm -f '" + oldJar + "'; mv -f '" + pendingJar + "' '" + target + "'");
                pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                LOGGER.error("Could not start the update helper", e);
            }
        }, "Slopmatica update"));
    }

    private static @Nullable Path ownJar() {
        return FabricLoader.getInstance().getModContainer("slopmatica")
            .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
            .filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p))
            .orElse(null);
    }

    /** The fallback: the releases page, scrolled to this version. */
    public static void openPage(Release release, @Nullable String why) {
        if (why != null) {
            SlopmaticaActions.chat(Component.literal("[Slopmatica] Couldn't update automatically (" + why + "); opening the downloads page.").withColor(SlopmaticaActions.ERROR));
        }
        Blaze3D.openUri(URI.create(release.page()));
    }

    /** Whether version a is newer than b, comparing dotted numbers ("0.10.0" > "0.9.2"). */
    /**
     * Semver order: dotted numbers first, then a release beats its own pre-releases
     * ({@code 0.7.0} is newer than {@code 0.7.0-beta.1}); build metadata after {@code +} is ignored.
     */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\+", 2)[0].split("-", 2), y = b.split("\\+", 2)[0].split("-", 2);
        String[] xc = x[0].split("\\."), yc = y[0].split("\\.");
        for (int i = 0; i < Math.max(xc.length, yc.length); i++) {
            int xi = i < xc.length ? parse(xc[i]) : 0, yi = i < yc.length ? parse(yc[i]) : 0;
            if (xi != yi) {
                return xi > yi;
            }
        }
        boolean xPre = x.length > 1, yPre = y.length > 1;
        if (xPre != yPre) {
            return !xPre;
        }
        return xPre && comparePre(x[1].split("\\."), y[1].split("\\.")) > 0;
    }

    /** Pre-release identifiers: numbers by value and below words, words by text, more identifiers wins a tie. */
    private static int comparePre(String[] x, String[] y) {
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean xn = x[i].matches("\\d+"), yn = y[i].matches("\\d+");
            int c = xn && yn ? Integer.compare(parse(x[i]), parse(y[i])) : xn != yn ? (xn ? -1 : 1) : x[i].compareTo(y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
