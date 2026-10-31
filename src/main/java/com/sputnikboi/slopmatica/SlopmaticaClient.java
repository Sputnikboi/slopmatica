package com.sputnikboi.slopmatica;

import com.sputnikboi.slopmatica.command.SlopmaticaCommands;
import com.sputnikboi.slopmatica.dev.DevHarness;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.view.PlacementView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

public final class SlopmaticaClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HologramRenderer renderer = HologramRenderer.INSTANCE;
        PlacementManager.INSTANCE.addListener(renderer::onPlacementsChanged);

        LevelRenderEvents.END_MAIN.register(renderer::render);
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::collectSubmits);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> renderer.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> renderer.onChunkUnloaded(chunk.getPos().x(), chunk.getPos().z()));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> renderer.reset());
        ClientCommandRegistrationCallback.EVENT.register(SlopmaticaCommands::register);
        SlopmaticaKeys.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "placement_view"), (graphics, delta) -> PlacementView.INSTANCE.extractHud(
            graphics, SlopmaticaKeys.PLACEMENT_VIEW, SlopmaticaKeys.VIEW_ROTATE, SlopmaticaKeys.VIEW_MIRROR, SlopmaticaKeys.VIEW_NUDGES));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> PlacementView.INSTANCE.exit(mc));
        DevHarness.install();
    }
}
