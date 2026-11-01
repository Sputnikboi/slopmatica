package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.cloud.BoardClient;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.update.Updates;
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
 * schematic are attached unless unticked, so problems can be reproduced. Goes to the website's
 * feedback, where only the admin sees the attachments.
 */
public class BugReportScreen extends Screen {
    private static final int W = 320;

    private final @Nullable Screen parent;
    private String type = "bug";
    private String message = "";
    private boolean attachLog = true, attachSchematic = true;
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
        y += 96;
        addRenderableWidget(Checkbox.builder(Component.translatable("slopmatica.report.attach_log"), font).pos(left, y).selected(attachLog)
            .tooltip(Tooltip.create(Component.translatable("slopmatica.report.attach_log.tooltip")))
            .onValueChange((c, v) -> attachLog = v).build());
        y += 22;
        Path file = schematic();
        Checkbox schem = addRenderableWidget(Checkbox.builder(file == null ? Component.translatable("slopmatica.report.no_schematic")
                : Component.translatable("slopmatica.report.attach_schematic", file.getFileName().toString()), font)
            .pos(left, y).selected(attachSchematic && file != null).onValueChange((c, v) -> attachSchematic = v).build());
        schem.active = file != null;
        y += 30;
        Button send = addRenderableWidget(Button.builder(Component.translatable("slopmatica.report.send"), b -> send())
            .bounds(left, y, W / 2 - 3, 20).build());
        send.active = !sending;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(left + W / 2 + 3, y, W / 2 - 3, 20).build());
    }

    private void send() {
        if (message.isBlank()) {
            status = Component.translatable("slopmatica.report.empty").getString();
            return;
        }
        sending = true;
        status = Component.translatable("slopmatica.report.sending").getString();
        rebuildWidgets();
        Path log = attachLog ? minecraft.gameDirectory.toPath().resolve("logs").resolve("latest.log") : null;
        Path file = attachSchematic ? schematic() : null;
        String t = type, m = message;
        CompletableFuture.runAsync(() -> {
            try {
                BoardClient.INSTANCE.sendReport(t, m, Updates.modVersion(), SharedConstants.getCurrentVersion().name(), log, file);
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
