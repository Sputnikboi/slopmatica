package com.sputnikboi.slopmatica.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.share.SharedFolder;
import com.sputnikboi.slopmatica.update.Updates;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in usage reports ("Share Usage Data", off unless chosen), sent only to the Slopmatica cloud
 * server. Nothing is recorded while it is off. A report covers one play session and holds:
 * rendering performance while builds are shown (frame times, hologram size) with the computer's
 * specs (GPU name, cores, memory, OS); where the loaded builds came from (a board post's id, or
 * just "cloud", "shared" or "local"; the server keeps a post id only if the post is public);
 * the names (not values) of settings changed from their defaults; and how often each of the
 * mod's keys was used. Reports carry a random id for this install, nothing about the player.
 * They queue on disk and are sent when the server is reachable.
 */
public final class Telemetry {
    public static final Telemetry INSTANCE = new Telemetry();

    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    private static final Gson GSON = new GsonBuilder().create();
    private static final long REPORT_EVERY_MS = 30 * 60 * 1000;
    private static final int MAX_QUEUED = 20;
    /** Settings that are personal or meaningless as statistics. */
    private static final Set<String> NOT_REPORTED = Set.of("sharedFolder", "welcomeShown", "telemetry", "planIgnored", "controlsKeys",
        "quickMenu", "quickToggles", "quickKeys", "cloudProfile", "cloudProfilePulled", "profilePromptShown");

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    /** Frame times while builds were shown, in 1 ms buckets (the last one is "100 ms or more"). */
    private final long[] frameBuckets = new long[101];
    private long frames;
    private double frameMsSum;
    private int maxSections;
    private final Map<String, Integer> keys = new LinkedHashMap<>();
    private final Set<String> sources = new HashSet<>();
    private long sessionStart = System.currentTimeMillis();
    private long lastReport = Util.getMillis();

    private Telemetry() {
    }

    private static boolean on() {
        return SlopmaticaConfig.get().telemetry;
    }

    /** A rendered frame, from the HUD. Counted only while some build is on screen. */
    public void frame(long nanos) {
        if (!on() || HologramRenderer.INSTANCE.hidden() || PlacementManager.INSTANCE.placements().isEmpty()) {
            return;
        }
        double ms = nanos / 1e6;
        frames++;
        frameMsSum += ms;
        frameBuckets[(int) Math.min(100, ms)]++;
        maxSections = Math.max(maxSections, HologramRenderer.INSTANCE.sectionCount());
    }

    public void key(String name) {
        if (on()) {
            keys.merge(name, 1, Integer::sum);
        }
    }

    /** PlacementManager listener: remember where the loaded builds came from. */
    public void placementsChanged() {
        if (!on()) {
            return;
        }
        maxSections = Math.max(maxSections, HologramRenderer.INSTANCE.sectionCount());
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            sources.add(sourceOf(p.schematic().source()));
        }
    }

    private static String sourceOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(CloudSync.cloudDir().toAbsolutePath().normalize())) {
            CloudSync.Source source = CloudSync.INSTANCE.sourceOf(file);
            return source != null && !source.postId().isEmpty() ? "board:" + source.postId() : "cloud";
        }
        if (abs.startsWith(SharedFolder.folder().toAbsolutePath().normalize())) {
            return "shared";
        }
        return "local";
    }

    /** Every 30 minutes of play. */
    public void tick() {
        if (Util.getMillis() - lastReport > REPORT_EVERY_MS) {
            lastReport = Util.getMillis();
            finishSession();
        }
    }

    /** Leaving a world, or quitting: close the session's report and send what's queued. */
    public void finishSession() {
        if (!on()) {
            reset();
            clearQueue();
            return;
        }
        if (frames > 0 || !keys.isEmpty() || !sources.isEmpty()) {
            queue(report());
        }
        reset();
        sendQueued();
    }

    private void reset() {
        java.util.Arrays.fill(frameBuckets, 0);
        frames = 0;
        frameMsSum = 0;
        maxSections = 0;
        keys.clear();
        sources.clear();
        sessionStart = System.currentTimeMillis();
    }

    private JsonObject report() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject r = new JsonObject();
        r.addProperty("install", installId());
        r.addProperty("mod", Updates.modVersion());
        r.addProperty("mc", SharedConstants.getCurrentVersion().name());
        r.addProperty("loader", "fabric");
        r.addProperty("start", sessionStart / 1000);
        r.addProperty("end", System.currentTimeMillis() / 1000);

        JsonObject specs = new JsonObject();
        specs.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        specs.addProperty("cpuCores", Runtime.getRuntime().availableProcessors());
        specs.addProperty("maxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        try {
            var device = RenderSystem.getDevice().getDeviceInfo();
            specs.addProperty("gpu", device.name());
            specs.addProperty("gpuVendor", device.vendorName());
            specs.addProperty("backend", device.backendName());
        } catch (RuntimeException e) {
            // Not available (no render device yet): leave it out.
        }
        specs.addProperty("renderDistance", mc.options.getEffectiveRenderDistance());
        specs.addProperty("width", mc.getWindow().getWidth());
        specs.addProperty("height", mc.getWindow().getHeight());
        r.add("specs", specs);

        JsonObject render = new JsonObject();
        render.addProperty("frames", frames);
        if (frames > 0) {
            render.addProperty("avgMs", Math.round(frameMsSum / frames * 10) / 10.0);
            render.addProperty("p50Ms", percentile(0.50));
            render.addProperty("p99Ms", percentile(0.99));
            long slow = 0;
            for (int i = 33; i < frameBuckets.length; i++) {
                slow += frameBuckets[i];
            }
            render.addProperty("over33Ms", slow);
        }
        render.addProperty("maxSections", maxSections);
        r.add("render", render);

        JsonArray from = new JsonArray();
        sources.stream().sorted().limit(50).forEach(from::add);
        r.add("schematics", from);

        JsonArray changed = new JsonArray();
        JsonObject now = GSON.toJsonTree(SlopmaticaConfig.get()).getAsJsonObject();
        JsonObject defaults = GSON.toJsonTree(new SlopmaticaConfig()).getAsJsonObject();
        for (var e : now.entrySet()) {
            JsonElement d = defaults.get(e.getKey());
            if (!NOT_REPORTED.contains(e.getKey()) && (d == null || !d.equals(e.getValue()))) {
                changed.add(e.getKey());
            }
        }
        r.add("settingsChanged", changed);

        JsonObject used = new JsonObject();
        keys.forEach(used::addProperty);
        r.add("keys", used);
        // The Quick Menu layout, only when changed from the default: which actions people want at hand.
        java.util.List<String> layout = com.sputnikboi.slopmatica.quick.QuickMenu.layout();
        if (!layout.equals(com.sputnikboi.slopmatica.quick.QuickAction.DEFAULT_LAYOUT)) {
            JsonArray quick = new JsonArray();
            layout.forEach(quick::add);
            r.add("quickMenu", quick);
            r.addProperty("quickMode", SlopmaticaConfig.get().quickMode.name());
            r.addProperty("quickCloseAfterUse", SlopmaticaConfig.get().quickCloseAfterUse);
        }
        // The wheel: which style people pick, and its pages when changed from the default.
        SlopmaticaConfig c = SlopmaticaConfig.get();
        r.addProperty("quickStyle", c.quickStyle.name());
        if (c.quickStyle == com.sputnikboi.slopmatica.quick.QuickMenu.Style.WHEEL || !c.wheelPages.equals(com.sputnikboi.slopmatica.quick.QuickLayout.WHEEL_DEFAULT)) {
            JsonArray pages = new JsonArray();
            for (java.util.List<String> page : c.wheelPages) {
                JsonArray slots = new JsonArray();
                page.forEach(slots::add);
                pages.add(slots);
            }
            r.add("wheelPages", pages);
            r.addProperty("wheelCloseAfterUse", c.wheelCloseAfterUse);
            r.addProperty("wheelResetPage", c.wheelResetPage);
        }
        return r;
    }

    private double percentile(double p) {
        long target = (long) Math.ceil(frames * p), seen = 0;
        for (int i = 0; i < frameBuckets.length; i++) {
            seen += frameBuckets[i];
            if (seen >= target) {
                return i + 0.5;
            }
        }
        return 100;
    }

    // ---- queue and sending -------------------------------------------------------------

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("slopmatica").resolve("telemetry");
    }

    /** A random id for this install, made on first use. Not tied to the player or account. */
    private static String installId() {
        Path file = dir().resolveSibling("install-id");
        try {
            if (Files.isRegularFile(file)) {
                String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            String id = UUID.randomUUID().toString();
            Files.createDirectories(file.getParent());
            Files.writeString(file, id, StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            return "unknown";
        }
    }

    private void queue(JsonObject report) {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve(System.currentTimeMillis() + ".json"), GSON.toJson(report), StandardCharsets.UTF_8);
            List<Path> files = queued();
            for (int i = 0; i < files.size() - MAX_QUEUED; i++) {
                Files.deleteIfExists(files.get(i)); // Oldest first: an unreachable server can't fill the disk.
            }
        } catch (IOException e) {
            LOGGER.warn("Could not queue a usage report", e);
        }
    }

    private static List<Path> queued() throws IOException {
        if (!Files.isDirectory(dir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Comparator.comparing(Path::getFileName)).toList();
        }
    }

    private void clearQueue() {
        try {
            for (Path p : queued()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // Nothing queued, or nothing we can do.
        }
    }

    private void sendQueued() {
        String url = CloudSync.INSTANCE.account().url;
        if (url == null || url.isBlank()) {
            return;
        }
        List<Path> files;
        try {
            files = new ArrayList<>(queued());
        } catch (IOException e) {
            return;
        }
        if (files.isEmpty()) {
            return;
        }
        Util.ioPool().execute(() -> {
            for (Path file : files) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/telemetry"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofFile(file)).build();
                    HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() / 100 == 2 || response.statusCode() == 400) {
                        Files.deleteIfExists(file); // Sent, or refused for good: either way done with it.
                    } else {
                        return; // Server not ready for reports yet (or down): keep them for later.
                    }
                } catch (IOException | InterruptedException | RuntimeException e) {
                    return;
                }
            }
        });
    }
}
