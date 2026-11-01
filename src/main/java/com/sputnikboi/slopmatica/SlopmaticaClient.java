package com.sputnikboi.slopmatica;

import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.cloud.CloudSync;
import com.sputnikboi.slopmatica.command.SlopmaticaCommands;
import com.sputnikboi.slopmatica.dev.DevHarness;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.placement.PlacementStore;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.render.SchematicThumbnails;
import com.sputnikboi.slopmatica.verify.BlockInfoHud;
import com.sputnikboi.slopmatica.view.PlacementView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.MaterialPanels;
import com.sputnikboi.slopmatica.gui.WelcomeScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

public final class SlopmaticaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HologramRenderer renderer = HologramRenderer.INSTANCE;
        PlacementManager.INSTANCE.addListener(renderer::onPlacementsChanged);
        PlacementManager.INSTANCE.addListener(PlacementStore.INSTANCE::markDirty);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> PlacementStore.INSTANCE.onJoin(mc));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            PlacementView.INSTANCE.exit(mc);
            PlacementStore.INSTANCE.onDisconnect();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> PlacementStore.INSTANCE.tick());

        LevelRenderEvents.END_MAIN.register(renderer::render);
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::collectSubmits);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> renderer.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> renderer.onChunkUnloaded(chunk.getPos().x(), chunk.getPos().z()));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> renderer.reset());
        ClientCommandRegistrationCallback.EVENT.register(SlopmaticaCommands::register);
        SlopmaticaKeys.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "placement_view"), (graphics, delta) -> PlacementView.INSTANCE.extractHud(graphics));
        LevelRenderEvents.COLLECT_SUBMITS.register(Selection.INSTANCE::submit);
        LevelRenderEvents.COLLECT_SUBMITS.register(PlacementView.INSTANCE::submitToolBoxes);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "block_info"), (graphics, delta) -> BlockInfoHud.INSTANCE.extract(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "material_panels"), (graphics, delta) -> MaterialPanels.INSTANCE.extractHud(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(BlockInfoHud.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementBuilder.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SchematicThumbnails.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> CloudSync.INSTANCE.tick());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> PlacementView.INSTANCE.exit(mc));
        // First run: the welcome screen, once, over the title screen.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!SlopmaticaConfig.get().welcomeShown && mc.gui.screen() instanceof TitleScreen title) {
                mc.gui.setScreen(new WelcomeScreen(title));
            }
        });
        DevHarness.install();
    }
}
