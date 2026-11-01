package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.compat.LitematicaImport;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Opens once, over the title screen, the first time the mod runs: the two keys everyone needs,
 * cloud login, a Litematica settings import and the telemetry choice. Keys and telemetry apply on
 * Done and are dropped by Skip; login and import are actions of their own and happen right away.
 * Options has a button to bring this back.
 */
public class WelcomeScreen extends Screen {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("slopmatica", "icon.png");
    private static final int WIDTH = 310;
    private static final int HALF = (WIDTH - 10) / 2;

    private final @Nullable Screen parent;
    private final List<KeyButton> keyButtons = new ArrayList<>();
    private @Nullable KeyButton listening;
    private boolean telemetry = SlopmaticaConfig.get().telemetry;
    private Button loginButton, websiteButton;
    private int top;

    public WelcomeScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.welcome.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = width / 2 - WIDTH / 2;
        top = Math.max(6, (height - 250) / 2);
        int y = top + 48;

        // Keys made here survive re-init (resizing) because the buttons are rebuilt from pending values.
        List<InputConstants.@Nullable Key> pending = keyButtons.stream().map(b -> b.pending).toList();
        keyButtons.clear();
        keyButtons.add(addRenderableWidget(new KeyButton(left, y, SlopmaticaKeys.openMenu(), "slopmatica.welcome.key.menu", pending.isEmpty() ? null : pending.get(0))));
        keyButtons.add(addRenderableWidget(new KeyButton(left + HALF + 10, y, SlopmaticaKeys.PLACEMENT_VIEW, "slopmatica.welcome.key.view", pending.size() < 2 ? null : pending.get(1))));

        y += 44;
        CloudSync cloud = CloudSync.INSTANCE;
        loginButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.welcome.login"), b -> cloud.login())
            .bounds(left, y, HALF, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.welcome.login.tooltip"))).build());
        websiteButton = addRenderableWidget(Button.builder(Component.translatable("slopmatica.cloud.website"), b -> cloud.openWebsite())
            .bounds(left + HALF + 10, y, HALF, 20).build());

        y += 44;
        boolean found = LitematicaImport.local() != null;
        addRenderableWidget(Button.builder(Component.translatable(found ? "slopmatica.welcome.import.found" : "slopmatica.welcome.import"),
                b -> minecraft.gui.setScreen(new LitematicaImportScreen(this)))
            .bounds(left, y, WIDTH, 20)
            .tooltip(Tooltip.create(Component.translatable(found ? "slopmatica.welcome.import.found.tooltip" : "slopmatica.welcome.import.tooltip"))).build());

        y += 32;
        addRenderableWidget(CycleButton.onOffBuilder(telemetry)
            .withTooltip(v -> Tooltip.create(Component.translatable("slopmatica.options.telemetry.tooltip")))
            .create(left, y, WIDTH, 20, Component.translatable("slopmatica.options.telemetry"), (b, v) -> telemetry = v));

        int bottom = Math.max(y + 32, Math.min(height - 28, y + 50));
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.welcome.skip"), b -> skip())
            .bounds(left, bottom, HALF, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.welcome.skip.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> done()).bounds(left + HALF + 10, bottom, HALF, 20).build());
        refreshCloud();
    }

    @Override
    public void tick() {
        refreshCloud();
    }

    private void refreshCloud() {
        CloudSync cloud = CloudSync.INSTANCE;
        boolean in = cloud.loggedIn();
        loginButton.active = !in;
        loginButton.setMessage(in
            ? Component.translatable("slopmatica.welcome.logged_in", cloud.account().name)
            : Component.translatable("slopmatica.welcome.login"));
        websiteButton.active = in;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int left = width / 2 - WIDTH / 2;
        int titleX = width / 2 - font.width(title) / 2;
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, titleX - 24, top, 0, 0, 18, 18, 128, 128, 128, 128);
        graphics.text(font, title, titleX, top + 5, -1);
        graphics.centeredText(font, Component.translatable("slopmatica.welcome.intro"), width / 2, top + 22, 0xFFA0A0A0);

        int y = top + 48;
        graphics.text(font, Component.translatable("slopmatica.welcome.keys").withStyle(ChatFormatting.BOLD), left, y - 11, -1);
        y += 44;
        graphics.text(font, Component.translatable("slopmatica.welcome.cloud").withStyle(ChatFormatting.BOLD), left, y - 11, -1);
        String status = CloudSync.INSTANCE.status();
        if (status != null && !status.isBlank() && !CloudSync.INSTANCE.loggedIn()) {
            graphics.text(font, font.plainSubstrByWidth(status, WIDTH), left, y + 23, 0xFFA0A0A0);
        }
        y += 44;
        graphics.text(font, Component.translatable("slopmatica.welcome.litematica").withStyle(ChatFormatting.BOLD), left, y - 11, -1);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            if (!event.isEscape()) {
                listening.pending = InputConstants.getKey(event);
            }
            listening.stopListening();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (listening != null) {
            listening.pending = InputConstants.Type.MOUSE.getOrCreate(event.button());
            listening.stopListening();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void done() {
        boolean keysChanged = false;
        for (KeyButton button : keyButtons) {
            if (button.pending != null) {
                button.mapping.setKey(button.pending);
                keysChanged = true;
            }
        }
        if (keysChanged) {
            KeyMapping.resetMapping();
            minecraft.options.save();
        }
        SlopmaticaConfig config = SlopmaticaConfig.get();
        config.telemetry = telemetry;
        close(config);
    }

    private void skip() {
        close(SlopmaticaConfig.get());
    }

    private void close(SlopmaticaConfig config) {
        config.welcomeShown = true;
        config.save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        skip();
    }

    /** Click, then press a key (or click a mouse button); Escape cancels. */
    private class KeyButton extends Button.Plain {
        final KeyMapping mapping;
        /** A short name; the full key names don't fit half a row. */
        final String label;
        InputConstants.@Nullable Key pending;

        KeyButton(int x, int y, KeyMapping mapping, String label, InputConstants.@Nullable Key pending) {
            super(x, y, HALF, 20, Component.empty(), b -> {
            }, DEFAULT_NARRATION);
            this.mapping = mapping;
            this.label = label;
            this.pending = pending;
            setTooltip(Tooltip.create(Component.translatable("slopmatica.welcome.key.tooltip", Component.translatable(mapping.getName()))));
            refresh();
        }

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            if (listening != null && listening != this) {
                listening.stopListening();
            }
            listening = this;
            refresh();
        }

        void stopListening() {
            listening = null;
            refresh();
        }

        private void refresh() {
            Component key = (pending != null ? pending : InputConstants.getKey(mapping.saveString())).getDisplayName();
            Component name = Component.translatable(label);
            setMessage(listening == this
                ? Component.translatable("slopmatica.welcome.key_listening", name).withStyle(ChatFormatting.YELLOW)
                : Component.translatable("slopmatica.welcome.key", name, key));
        }
    }
}
