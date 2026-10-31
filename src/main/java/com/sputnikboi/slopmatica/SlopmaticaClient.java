package com.sputnikboi.slopmatica;

import com.sputnikboi.slopmatica.command.SlopmaticaCommands;
import com.sputnikboi.slopmatica.dev.DevHarness;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

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
        DevHarness.install();
    }
}
