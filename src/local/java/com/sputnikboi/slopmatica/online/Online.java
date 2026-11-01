package com.sputnikboi.slopmatica.online;

import java.nio.file.Path;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * The local-only edition's stand-in for the online features: none of them exist in this build
 * (the cloud, settings profiles, update checks, usage reports, the board browser and bug
 * reports are left out entirely), so nothing here ever touches the network.
 */
public final class Online {
    private Online() {
    }

    public static boolean available() {
        return false;
    }

    public static void register() {
    }

    public static void keyUsed(String name) {
    }

    public static void frame(long nanos) {
    }

    public static void settingsSaved() {
    }

    public static boolean loggedIn() {
        return false;
    }

    public static @Nullable String accountName() {
        return null;
    }

    public static void login() {
    }

    public static String status() {
        return "";
    }

    public static void openWebsite() {
    }

    /** Credits link to the source code instead of the website. */
    public static String websiteUrl() {
        return "https://github.com/Sputnikboi/slopmatica";
    }

    public static String cloudUrl() {
        return "";
    }

    public static String defaultCloudUrl() {
        return "";
    }

    public static void setCloudUrl(String url) {
    }

    public static void checkUpdates() {
    }

    public static @Nullable String updateVersion() {
        return null;
    }

    public static boolean updatePending() {
        return false;
    }

    public static void installUpdate() {
    }

    public static String connection() {
        return "local";
    }

    public static @Nullable Screen cloudScreen(@Nullable Screen parent) {
        return null;
    }

    public static @Nullable Screen boardBrowser(@Nullable Screen parent) {
        return null;
    }

    public static @Nullable Screen profiles(@Nullable Screen parent) {
        return null;
    }

    public static @Nullable Screen bugReport(@Nullable Screen parent) {
        return null;
    }

    public static @Nullable Path cloudDir() {
        return null;
    }

    public static void refreshPresence() {
    }

    public static boolean inCloud(Path file) {
        return false;
    }

    public static boolean onDisk(Path file) {
        return false;
    }

    public static void upload(Path file) {
    }

    public static @Nullable String boardAuthor(Path file) {
        return null;
    }

    public static @Nullable String listingOf(Path file) {
        return null;
    }

    public static String devCloud(String what, @Nullable Path file) {
        return "local-only edition: no cloud";
    }
}
