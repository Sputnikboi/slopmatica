package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.Blaze3D;
import com.sputnikboi.slopmatica.cloud.BoardClient;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.telemetry.LogRedactor;
import com.sputnikboi.slopmatica.update.Updates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Report a bug (or suggest something) from the game. The game log and the selected build's
 * schematic are attached unless unticked, so problems can be reproduced; the log goes through
 * {@link LogRedactor} first, and "View" shows exactly what of it is sent. Anonymous (the default)
 * sends without the cloud login. Goes to the website's feedback, where only the admin sees the
 * attachments.
 */
public class BugReportScreen extends Screen {
    private static final int W = 320;
    /** The last choice, for the rest of the session. */
    private static boolean anonymousChoice = true;

    private final @Nullable Screen parent;
    private String type = "bug";
    private String message = "";
    private boolean attachLog = true, attachSchematic = true;
    private boolean anonymous = anonymousChoice || !CloudSync.INSTANCE.loggedIn();
    private String status = "";
    private boolean sending;

    public BugReportScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.report.title"));
        this.parent = parent;
    }

    private @Nullable Path schematic() {
        return PlacementManager.INSTANCE.selected().map(Placement::schematic).map(s -> s.source()).orElse(null);
    }

    @Override
    protected void init() {
        int left = width / 2 - W / 2, y = 30;
        addRenderableWidget(CycleButton.<String>builder(t -> Component.translatable("slopmatica.report.type." + t), type)
            .withValues(List.of("bug", "suggestion", "other"))
            .create(left, y, W, 20, Component.translatable("slopmatica.report.type"), (b, v) -> type = v));
        y += 26;
        MultiLineEditBox box = addRenderableWidget(MultiLineEditBox.builder().setX(left).setY(y)
            .setPlaceholder(Component.translatable("slopmatica.report.placeholder"))
            .build(font, W, 90, Component.translatable("slopmatica.report.message")));
        box.setCharacterLimit(4000);
        box.setValue(message);
        box.setValueListener(v -> message = v);
        setInitialFocus(box);
        y += 104; // under the box's character count
        addRenderableWidget(Checkbox.builder(Component.translatable("slopmatica.report.attach_log"), font).pos(left, y).selected(attachLog)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.report.attach_log.tooltip")))
            .onValueChange((c, v) -> attachLog = v).build());
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.report.view_log"), b -> viewLog())
            .tooltip(Tooltip.create(Component.translatable("slopmatica.report.view_log.tooltip")))
            .bounds(left + W - 60, y, 60, 20).build());
        y += 22;
        Path file = schematic();
        Checkbox schem = addRenderableWidget(Checkbox.builder(file == null ? Component.translatable("slopmatica.report.no_schematic")
                : Component.translatable("slopmatica.report.attach_schematic", file.getFileName().toString()), font)
            .pos(left, y).selected(attachSchematic && file != null)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.report.attach_schematic.tooltip")))
            .onValueChange((c, v) -> attachSchematic = v).build());
        schem.active = file != null;
        y += 22;
        boolean loggedIn = CloudSync.INSTANCE.loggedIn();
        Checkbox anon = addRenderableWidget(Checkbox.builder(Component.translatable("slopmatica.report.anonymous"), font).pos(left, y)
            .selected(anonymous)
            .tooltip(Tooltip.create(Component.translatable(loggedIn ? "slopmatica.report.anonymous.tooltip" : "slopmatica.report.anonymous.logged_out")))
            .onValueChange((c, v) -> anonymous = anonymousChoice = v).build());
        anon.active = loggedIn;
        y += 30;
        Button send = addRenderableWidget(Button.builder(Component.translatable("slopmatica.report.send"), b -> send())
            .bounds(left, y, W / 2 - 3, 20).build());
        send.active = !sending;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + W / 2 + 3, y, W / 2 - 3, 20).build());
    }

    private Path logFile() {
        return minecraft.gameDirectory.toPath().resolve("logs").resolve("latest.log");
    }

    /** The log as it would be sent, or null if there is none. Reads the file: call off the render thread. */
    private @Nullable String redactedLog() throws IOException {
        Path log = logFile();
        if (!Files.isRegularFile(log)) {
            return null;
        }
        var user = minecraft.getUser();
        return LogRedactor.redact(new String(Files.readAllBytes(log), StandardCharsets.UTF_8), LogRedactor.secrets(
            System.getProperty("user.home", ""), System.getProperty("user.name", ""), user.getName(), user.getProfileId().toString()));
    }

    /** Writes what would be sent of the log next to it and opens it. */
    private void viewLog() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String log = redactedLog();
                Path out = minecraft.gameDirectory.toPath().resolve("slopmatica").resolve("bug-report-log.txt");
                Files.createDirectories(out.getParent());
                Files.writeString(out, log == null ? "" : log, StandardCharsets.UTF_8);
                return out;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((out, error) -> {
            if (error != null) {
                status = Component.translatable("slopmatica.report.view_failed").getString();
            } else {
                Blaze3D.openPath(out);
            }
        }, minecraft);
    }

    private void send() {
        if (message.isBlank()) {
            status = Component.translatable("slopmatica.report.empty").getString();
            return;
        }
        sending = true;
        status = Component.translatable("slopmatica.report.sending").getString();
        rebuildWidgets();
        Path file = attachSchematic ? schematic() : null;
        String t = type, m = message;
        boolean withLog = attachLog, anon = anonymous || !CloudSync.INSTANCE.loggedIn();
        CompletableFuture.runAsync(() -> {
            try {
                String log = withLog ? redactedLog() : null;
                BoardClient.INSTANCE.sendReport(t, m, Updates.modVersion(), SharedConstants.getCurrentVersion().name(), log, file, anon);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, Util.ioPool()).whenCompleteAsync((ok, error) -> {
            sending = false;
            if (error != null) {
                status = Component.translatable("slopmatica.report.failed", error.getCause() != null ? error.getCause().getMessage() : error.getMessage()).getString();
                rebuildWidgets();
            } else {
                com.sputnikboi.slopmatica.SlopmaticaActions.actionBar(Component.translatable("slopmatica.report.sent").withColor(com.sputnikboi.slopmatica.SlopmaticaActions.ACCENT));
                onClose();
            }
        }, minecraft);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 12, -1);
        if (!status.isEmpty()) {
            g.centeredText(font, status, width / 2, height - 24, 0xFFFFD060);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
