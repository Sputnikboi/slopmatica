package com.sputnikboi.slopmatica.gui;

import com.sputnikboi.slopmatica.cloud.SettingsProfiles;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Pick the cloud settings profile this instance follows, save the current settings as a new one,
 * or stop following. Opens by itself on the first cloud login in an instance (Skip leaves the
 * settings alone); afterwards from the Cloud screen.
 */
public class ProfilePickerScreen extends Screen {
    private static final int WIDTH = 300;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final @Nullable Screen parent;
    private boolean loaded;
    private String newName = "";

    public ProfilePickerScreen(@Nullable Screen parent) {
        super(Component.translatable("slopmatica.profiles.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SettingsProfiles profiles = SettingsProfiles.INSTANCE;
        if (!loaded) {
            loaded = true;
            profiles.refresh(this::rebuildWidgets);
        }
        int left = width / 2 - WIDTH / 2, y = 48;
        String current = SlopmaticaConfig.get().cloudProfile;
        for (SettingsProfiles.Profile p : profiles.profiles()) {
            boolean following = p.id().equals(current) && SlopmaticaConfig.get().cloudProfileSync;
            Component label = Component.literal((following ? "✔ " : "") + p.name()).withStyle(following ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
            addRenderableWidget(Button.builder(label, b -> profiles.choose(p, this::rebuildWidgets)).bounds(left, y, WIDTH - 24, 20)
                .tooltip(Tooltip.create(Component.translatable("slopmatica.profiles.use", p.updated() > 0 ? DATE.format(Instant.ofEpochSecond(p.updated())) : "-"))).build());
            addRenderableWidget(Button.builder(Component.literal("×").withStyle(ChatFormatting.RED), b -> profiles.delete(p, this::rebuildWidgets))
                .bounds(left + WIDTH - 20, y, 20, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.profiles.delete"))).build());
            y += 22;
        }
        y += 8;
        EditBox name = addRenderableWidget(new EditBox(font, left, y, WIDTH - 124, 20, Component.translatable("slopmatica.profiles.name")));
        name.setMaxLength(40);
        name.setHint(Component.translatable("slopmatica.profiles.name").withStyle(EditBox.SEARCH_HINT_STYLE));
        name.setValue(newName);
        name.setResponder(text -> newName = text);
        Integer limit = profiles.limit();
        Button save = addRenderableWidget(Button.builder(Component.translatable("slopmatica.profiles.save"), b -> {
            if (!newName.isBlank()) {
                profiles.create(newName.trim(), this::rebuildWidgets);
                newName = "";
            }
        }).bounds(left + WIDTH - 120, y, 120, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.profiles.save.tooltip"))).build());
        save.active = profiles.known() && (limit == null || profiles.profiles().size() < limit);
        y += 30;
        Button stop = addRenderableWidget(Button.builder(Component.translatable("slopmatica.profiles.stop"), b -> {
            profiles.stopFollowing();
            rebuildWidgets();
        }).bounds(left, y, WIDTH, 20).tooltip(Tooltip.create(Component.translatable("slopmatica.profiles.stop.tooltip"))).build());
        stop.active = SlopmaticaConfig.get().cloudProfileSync;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(font, title, width / 2, 14, -1);
        SettingsProfiles profiles = SettingsProfiles.INSTANCE;
        Integer limit = profiles.limit();
        Component sub = !profiles.known() ? Component.translatable("slopmatica.profiles.intro_unknown")
            : Component.translatable("slopmatica.profiles.intro", profiles.profiles().size(), limit == null ? "∞" : String.valueOf(limit));
        g.centeredText(font, sub, width / 2, 28, 0xFFA0A0A0);
        if (!profiles.status().isEmpty()) {
            g.centeredText(font, profiles.status(), width / 2, height - 42, 0xFFFFD060);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
