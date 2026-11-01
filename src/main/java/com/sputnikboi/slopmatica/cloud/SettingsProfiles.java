package com.sputnikboi.slopmatica.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.ProfilePickerScreen;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings profiles kept on the cloud server: named sets of settings (up to 6, more for admins)
 * that instances can follow. Each instance chooses separately whether it follows one, and which;
 * the first cloud login in an instance asks. While following, changes here are uploaded a few
 * seconds after they are saved, and the profile is taken from the cloud on every login.
 *
 * <p>Server API: {@code GET /api/profiles} -> {@code {profiles: [{id, name, updated}], limit}}
 * ({@code limit} null for unlimited); {@code GET /api/profiles/{id}} -> {@code {id, name, updated,
 * settings}}; {@code POST /api/profiles {name, settings}} -> the new profile (409 at the limit);
 * {@code PUT /api/profiles/{id} {settings}}; {@code DELETE /api/profiles/{id}}.
 */
public final class SettingsProfiles {
    public static final SettingsProfiles INSTANCE = new SettingsProfiles();

    private static final Logger LOGGER = LoggerFactory.getLogger("Slopmatica");
    private static final long PUSH_DELAY_MS = 3000;

    public record Profile(String id, String name, long updated) {
    }

    private volatile List<Profile> profiles = List.of();
    private volatile @Nullable Integer limit;
    private volatile String status = "";
    /** The list has been loaded from the server at least once. */
    private volatile boolean known;
    private long pushDue;

    private SettingsProfiles() {
    }

    public List<Profile> profiles() {
        return profiles;
    }

    public @Nullable Integer limit() {
        return limit;
    }

    public boolean known() {
        return known;
    }

    public String status() {
        return status;
    }

    public @Nullable Profile current() {
        String id = SlopmaticaConfig.get().cloudProfile;
        return id == null ? null : profiles.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** After a successful login: ask once per instance, otherwise take the followed profile. */
    void onLogin() {
        Minecraft mc = Minecraft.getInstance();
        SlopmaticaConfig config = SlopmaticaConfig.get();
        if (!config.profilePromptShown) {
            mc.execute(() -> {
                config.profilePromptShown = true;
                config.save();
                mc.gui.setScreen(new ProfilePickerScreen(mc.gui.screen()));
            });
        } else if (config.cloudProfileSync && config.cloudProfile != null) {
            pull(config.cloudProfile, false, () -> { });
        }
    }

    public void refresh(Runnable done) {
        Util.ioPool().execute(() -> {
            try {
                JsonObject json = CloudSync.INSTANCE.getJson("/api/profiles");
                List<Profile> list = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray("profiles")) {
                    JsonObject p = e.getAsJsonObject();
                    list.add(new Profile(p.get("id").getAsString(), p.get("name").getAsString(), p.has("updated") ? p.get("updated").getAsLong() : 0));
                }
                profiles = List.copyOf(list);
                limit = json.has("limit") && !json.get("limit").isJsonNull() ? json.get("limit").getAsInt() : null;
                known = true;
                status = "";
            } catch (Exception e) {
                status = "Couldn't load profiles: " + e.getMessage();
            }
            Minecraft.getInstance().execute(done);
        });
    }

    /** Saves the current settings as a new profile and follows it. */
    public void create(String name, Runnable done) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.add("settings", SlopmaticaConfig.get().shareableJson());
        Util.ioPool().execute(() -> {
            try {
                JsonObject created = CloudSync.INSTANCE.postJson("/api/profiles", body, true);
                String id = created.get("id").getAsString();
                Minecraft.getInstance().execute(() -> follow(id, created.has("updated") ? created.get("updated").getAsLong() : 0));
                status = "Saved as \"" + name + "\"";
            } catch (Exception e) {
                status = "Couldn't save: " + e.getMessage();
            }
            refresh(done);
        });
    }

    /** Takes a profile's settings and follows it. */
    public void choose(Profile profile, Runnable done) {
        pull(profile.id(), true, done);
    }

    private void pull(String id, boolean always, Runnable done) {
        Util.ioPool().execute(() -> {
            try {
                JsonObject json = CloudSync.INSTANCE.getJson("/api/profiles/" + id);
                long updated = json.has("updated") ? json.get("updated").getAsLong() : 0;
                JsonObject settings = json.getAsJsonObject("settings");
                Minecraft.getInstance().execute(() -> {
                    SlopmaticaConfig config = SlopmaticaConfig.get();
                    if (always || updated > config.cloudProfilePulled) {
                        config.applyShared(settings);
                        com.sputnikboi.slopmatica.render.HologramRenderer.INSTANCE.rebuildAll();
                    }
                    follow(id, updated);
                    done.run();
                });
                status = "Using \"" + json.get("name").getAsString() + "\"";
            } catch (Exception e) {
                status = "Couldn't load the profile: " + e.getMessage();
                Minecraft.getInstance().execute(done);
            }
        });
    }

    private void follow(String id, long updated) {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        config.cloudProfile = id;
        config.cloudProfileSync = true;
        config.cloudProfilePulled = updated;
        pushDue = 0; // What we have now came from the cloud: nothing to send back.
        saveQuietly(config);
    }

    public void stopFollowing() {
        SlopmaticaConfig config = SlopmaticaConfig.get();
        config.cloudProfileSync = false;
        config.cloudProfile = null;
        saveQuietly(config);
    }

    public void delete(Profile profile, Runnable done) {
        Util.ioPool().execute(() -> {
            try {
                CloudSync.INSTANCE.request("DELETE", "/api/profiles/" + profile.id(), HttpRequest.BodyPublishers.noBody(), Map.of(), true);
                if (profile.id().equals(SlopmaticaConfig.get().cloudProfile)) {
                    Minecraft.getInstance().execute(this::stopFollowing);
                }
            } catch (Exception e) {
                status = "Couldn't delete: " + e.getMessage();
            }
            refresh(done);
        });
    }

    /** Saves without scheduling an upload (the saved state came from the cloud). */
    private void saveQuietly(SlopmaticaConfig config) {
        suppress = true;
        try {
            config.save();
        } finally {
            suppress = false;
        }
    }

    private boolean suppress;

    /** From {@link SlopmaticaConfig#save}: upload the changes in a moment, once edits settle. */
    public void onConfigSaved() {
        if (!suppress && CloudSync.INSTANCE.loggedIn()) {
            pushDue = Util.getMillis() + PUSH_DELAY_MS;
        }
    }

    public void tick() {
        if (pushDue == 0 || Util.getMillis() < pushDue) {
            return;
        }
        pushDue = 0;
        SlopmaticaConfig config = SlopmaticaConfig.get();
        String id = config.cloudProfile;
        if (id == null || !config.cloudProfileSync) {
            return;
        }
        JsonObject body = new JsonObject();
        body.add("settings", config.shareableJson());
        Util.ioPool().execute(() -> {
            try {
                var response = CloudSync.INSTANCE.request("PUT", "/api/profiles/" + id, HttpRequest.BodyPublishers.ofString(body.toString()),
                    Map.of("Content-Type", "application/json"), true);
                JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("updated")) {
                    long updated = json.get("updated").getAsLong();
                    Minecraft.getInstance().execute(() -> config.cloudProfilePulled = updated);
                }
            } catch (Exception e) {
                LOGGER.warn("Could not upload settings to the cloud profile", e);
                status = "Couldn't upload settings: " + e.getMessage();
            }
        });
    }
}
