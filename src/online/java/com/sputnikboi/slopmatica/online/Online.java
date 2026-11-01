package com.sputnikboi.slopmatica.online;

import com.sputnikboi.slopmatica.cloud.CloudPresence;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.cloud.SettingsProfiles;
import com.sputnikboi.slopmatica.gui.BoardBrowserScreen;
import com.sputnikboi.slopmatica.gui.BugReportScreen;
import com.sputnikboi.slopmatica.gui.CloudScreen;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.telemetry.Telemetry;
import com.sputnikboi.slopmatica.update.Updates;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The only door from the rest of the mod to anything online: the cloud, settings profiles,
 * update checks, usage reports, the board browser and bug reports. The local-only edition
 * builds with a stand-in of this class that has none of it (src/local), and without the online
 * code at all, so it can't reach the network even by accident.
 */
public final class Online {
    private Online() {
    }

    /** Whether this edition has online features. */
    public static boolean available() {
        return true;
    }

    /** Client start: syncing, profiles, usage reports. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> CloudSync.INSTANCE.tick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> SettingsProfiles.INSTANCE.tick());
        Telemetry telemetry = Telemetry.INSTANCE;
        PlacementManager.INSTANCE.addListener(telemetry::placementsChanged);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> telemetry.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(telemetry::finishSession));
        // Joining starts a fresh session (and sends anything left from the last one).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mc.execute(telemetry::finishSession));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> telemetry.finishSession());
    }

    // ---- usage reports and profiles ----------------------------------------------------

    public static void keyUsed(String name) {
        Telemetry.INSTANCE.key(name);
    }

    public static void frame(long nanos) {
        Telemetry.INSTANCE.frame(nanos);
    }

    /** Settings were saved: upload them to the followed cloud profile soon. */
    public static void settingsSaved() {
        SettingsProfiles.INSTANCE.onConfigSaved();
    }

    // ---- account ----------------------------------------------------------------------

    public static boolean loggedIn() {
        return CloudSync.INSTANCE.loggedIn();
    }

    public static @Nullable String accountName() {
        return CloudSync.INSTANCE.account().name;
    }

    public static void login() {
        CloudSync.INSTANCE.login();
    }

    public static String status() {
        return CloudSync.INSTANCE.status();
    }

    public static void openWebsite() {
        CloudSync.INSTANCE.openWebsite();
    }

    /** The website's address (bug/feedback page, credits). */
    public static String websiteUrl() {
        return CloudSync.INSTANCE.account().url;
    }

    public static String cloudUrl() {
        return CloudSync.INSTANCE.account().url;
    }

    public static String defaultCloudUrl() {
        return CloudSync.DEFAULT_URL;
    }

    public static void setCloudUrl(String url) {
        CloudSync.INSTANCE.setUrl(url);
    }

    // ---- updates ----------------------------------------------------------------------

    public static void checkUpdates() {
        Updates.INSTANCE.check(false);
    }

    /** The newer version on offer, or null. */
    public static @Nullable String updateVersion() {
        Updates.Release r = Updates.INSTANCE.update();
        return r == null ? null : r.version();
    }

    public static boolean updatePending() {
        return Updates.INSTANCE.pending();
    }

    public static void installUpdate() {
        Updates.Release r = Updates.INSTANCE.update();
        if (r != null && !Updates.INSTANCE.pending()) {
            Updates.INSTANCE.install(r);
        }
    }

    /** "online", "offline" or "checking". */
    public static String connection() {
        return Updates.INSTANCE.online().name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- screens ----------------------------------------------------------------------

    public static @Nullable Screen cloudScreen(@Nullable Screen parent) {
        return new CloudScreen(parent);
    }

    public static @Nullable Screen boardBrowser(@Nullable Screen parent) {
        return new BoardBrowserScreen(parent);
    }

    public static @Nullable Screen profiles(@Nullable Screen parent) {
        return new com.sputnikboi.slopmatica.gui.ProfilePickerScreen(parent);
    }

    public static @Nullable Screen bugReport(@Nullable Screen parent) {
        return new BugReportScreen(parent);
    }

    // ---- cloud folder (the schematic browser's Cloud tab) ---------------------------

    public static @Nullable Path cloudDir() {
        return CloudSync.cloudDir();
    }

    public static void refreshPresence() {
        CloudPresence.INSTANCE.refresh();
    }

    public static boolean inCloud(Path file) {
        return CloudPresence.INSTANCE.inCloud(file);
    }

    public static boolean onDisk(Path file) {
        return CloudPresence.INSTANCE.onDisk(file);
    }

    public static void upload(Path file) {
        CloudSync.INSTANCE.upload(file);
    }

    /** Who made it, for a cloud file that came from the board. */
    public static @Nullable String boardAuthor(Path file) {
        CloudSync.Source source = CloudSync.INSTANCE.sourceOf(file);
        return source == null ? null : source.author();
    }

    public static @Nullable String listingOf(Path file) {
        return CloudSync.INSTANCE.listingOf(file);
    }

    // ---- dev harness --------------------------------------------------------------------

    /** Dev script steps "cloud login|sync|upload <file>" and "status". */
    public static String devCloud(String what, @Nullable Path file) {
        switch (what) {
            case "login" -> CloudSync.INSTANCE.login();
            case "sync" -> CloudSync.INSTANCE.syncNow();
            case "upload" -> {
                if (file != null) {
                    CloudSync.INSTANCE.upload(file);
                }
            }
            default -> {
            }
        }
        return CloudSync.INSTANCE.status() + " (used " + CloudSync.INSTANCE.usedBytes() + " of " + CloudSync.INSTANCE.quotaBytes() + ")";
    }
}
