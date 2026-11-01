package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.Blaze3D;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Slopmatica Cloud: server address, login with the Minecraft account, storage use, sync. Synced
 * schematics live in {@code schematics/cloud/}; the browser's Upload button copies files there.
 */
public class CloudScreen extends Screen {
    private static final int WIDTH = 240;
    private static final int DETAIL_COLOR = 0xFFA0A0A0;

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private EditBox url;
    private StringWidget account;
    private StringWidget usage;
    private StringWidget status;
    private Button loginButton;
    private Button websiteButton;
    private Button passwordButton;
    private Button syncButton;
    private Button autosyncButton;

    public CloudScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.cloud.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        CloudSync cloud = CloudSync.INSTANCE;
        layout.addTitleHeader(title, font);
        LinearLayout column = layout.addToContents(LinearLayout.vertical().spacing(6));
        column.defaultCellSetting().alignHorizontallyCenter();

        // The server address is a technical setting: shown only with advanced settings on.
        url = new EditBox(font, 0, 0, WIDTH, 18, Component.translatable("slopmatica.cloud.server"));
        url.setMaxLength(256);
        url.setValue(cloud.account().url);
        url.setHint(Component.literal("https://...").withStyle(EditBox.SEARCH_HINT_STYLE));
        url.setResponder(text -> refresh());
        if (SlopmaticaConfig.get().showAdvanced) {
            column.addChild(new StringWidget(Component.translatable("slopmatica.cloud.server").withColor(DETAIL_COLOR), font));
            column.addChild(url);
        }

        account = column.addChild(new StringWidget(WIDTH, 9, Component.empty(), font));
        usage = column.addChild(new StringWidget(WIDTH, 9, Component.empty(), font));
        status = column.addChild(new StringWidget(WIDTH, 9, Component.empty(), font));

        LinearLayout row1 = column.addChild(LinearLayout.horizontal().spacing(6));
        loginButton = row1.addChild(Button.builder(Component.empty(), b -> {
            if (cloud.loggedIn()) {
                cloud.logout();
            } else {
                cloud.setUrl(url.getValue());
                cloud.login();
            }
            refresh();
        }).width((WIDTH - 6) / 2).build());
        websiteButton = row1.addChild(Button.builder(Component.translatable("slopmatica.cloud.website"), b -> cloud.openWebsite()).width((WIDTH - 6) / 2).build());

        LinearLayout row2 = column.addChild(LinearLayout.horizontal().spacing(6));
        syncButton = row2.addChild(Button.builder(Component.translatable("slopmatica.cloud.sync"), b -> cloud.syncNow()).width((WIDTH - 6) / 2).build());
        autosyncButton = row2.addChild(Button.builder(Component.empty(), b -> {
            cloud.setAutosync(!cloud.account().autosync);
            refresh();
        }).width((WIDTH - 6) / 2).build());

        passwordButton = column.addChild(Button.builder(Component.translatable("slopmatica.cloud.password"), b -> cloud.openWebsite("/account"))
            .width(WIDTH).tooltip(Tooltip.create(Component.translatable("slopmatica.cloud.password.tooltip"))).build());
        column.addChild(Button.builder(Component.translatable("slopmatica.cloud.folder"), b -> {
            try {
                Files.createDirectories(CloudSync.cloudDir());
            } catch (IOException ignored) {
                // Opening will just fail visibly.
            }
            Blaze3D.openPath(CloudSync.cloudDir());
        }).width(WIDTH).build());
        column.addChild(new StringWidget(Component.translatable("slopmatica.cloud.hint").withColor(DETAIL_COLOR), font));

        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        refresh();
        repositionElements();
    }

    @Override
    public void tick() {
        refresh();
    }

    private void refresh() {
        CloudSync cloud = CloudSync.INSTANCE;
        boolean in = cloud.loggedIn();
        boolean urlChanged = !url.getValue().trim().replaceAll("/+$", "").equals(cloud.account().url);
        account.setMessage(in && !urlChanged
            ? Component.translatable("slopmatica.cloud.logged_in", cloud.account().name)
            : Component.translatable("slopmatica.cloud.logged_out").withColor(DETAIL_COLOR));
        long used = cloud.usedBytes(), quota = cloud.quotaBytes();
        usage.setMessage(!in || used < 0 ? Component.empty() : Component.literal(quota < 0
            ? size(used) + " used (unlimited)"
            : String.format(Locale.ROOT, "%s of %s used (%d%%)", size(used), size(quota), used * 100 / Math.max(1, quota))).withColor(DETAIL_COLOR));
        status.setMessage(Component.literal(cloud.syncing() ? "Syncing..." : cloud.status()).withColor(DETAIL_COLOR));
        loginButton.setMessage(Component.translatable(in && !urlChanged ? "slopmatica.cloud.logout" : "slopmatica.cloud.login"));
        loginButton.active = !url.getValue().isBlank();
        websiteButton.active = in && !urlChanged;
        passwordButton.active = in && !urlChanged;
        syncButton.active = in && !urlChanged && !cloud.syncing();
        autosyncButton.setMessage(Component.translatable(cloud.account().autosync ? "slopmatica.cloud.autosync_on" : "slopmatica.cloud.autosync_off"));
        layout.arrangeElements();
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
        }
        return String.format(Locale.ROOT, "%d KB", Math.max(bytes > 0 ? 1 : 0, bytes / 1024));
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        // Keep an edited address even without logging in.
        if (!url.getValue().trim().replaceAll("/+$", "").equals(CloudSync.INSTANCE.account().url) && !CloudSync.INSTANCE.loggedIn()) {
            CloudSync.INSTANCE.setUrl(url.getValue());
        }
        minecraft.gui.setScreen(parent);
    }
}
