package com.sputnikboi.slopmatica;

import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.build.Selection;
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
import com.sputnikboi.slopmatica.spawn.SpawnCheck;
import com.sputnikboi.slopmatica.gui.WelcomeScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
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
        renderer.restoreHidden();
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
        LevelRenderEvents.COLLECT_SUBMITS.register(SpawnCheck.INSTANCE::submit);
        LevelRenderEvents.COLLECT_SUBMITS.register(com.sputnikboi.slopmatica.render.IncompleteBoxes::submit);
        ClientTickEvents.END_CLIENT_TICK.register(SpawnCheck.INSTANCE::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "spawn_check"), (graphics, delta) -> SpawnCheck.INSTANCE.extractHud(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "block_info"), (graphics, delta) -> BlockInfoHud.INSTANCE.extract(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "material_panels"), (graphics, delta) -> MaterialPanels.INSTANCE.extractHud(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(BlockInfoHud.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementBuilder.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(com.sputnikboi.slopmatica.build.EasyPlace.INSTANCE::tick);
        ClientTickEvents.END_CLIENT_TICK.register(SchematicThumbnails.INSTANCE::tick);
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> PlacementView.INSTANCE.exit(mc));
        // First run: the welcome screen, once, over the title screen.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!SlopmaticaConfig.get().welcomeShown && mc.gui.screen() instanceof TitleScreen title) {
                mc.gui.setScreen(new WelcomeScreen(title));
            }
        });
        com.sputnikboi.slopmatica.plan.InventoryOverlay.register();
        // The Quick Menu reads clicks and E before vanilla does, and takes the hotbar's place while open.
        ClientTickEvents.START_CLIENT_TICK.register(com.sputnikboi.slopmatica.quick.QuickMenu.INSTANCE::tick);
        ClientTickEvents.START_CLIENT_TICK.register(com.sputnikboi.slopmatica.quick.ActionWheel.INSTANCE::tick);
        HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("slopmatica", "tour"),
            (graphics, delta) -> com.sputnikboi.slopmatica.tour.FeatureTour.INSTANCE.extract(graphics, Minecraft.getInstance()));
        HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("slopmatica", "action_wheel"), (graphics, delta) -> {
            if (com.sputnikboi.slopmatica.quick.ActionWheel.INSTANCE.isOpen()) {
                com.sputnikboi.slopmatica.quick.ActionWheel.INSTANCE.extract(graphics, Minecraft.getInstance());
            }
        });
        HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HOTBAR, hotbar -> (graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (com.sputnikboi.slopmatica.quick.QuickMenu.INSTANCE.isOpen() && mc.player != null) {
                com.sputnikboi.slopmatica.quick.QuickMenu.INSTANCE.extractHotbar(graphics, mc);
            } else {
                hotbar.extractRenderState(graphics, delta);
            }
        });
// Syncing, profiles and usage reports (none of it in the local-only edition).
        com.sputnikboi.slopmatica.online.Online.register();
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("slopmatica", "dev_overlay"), (graphics, delta) -> com.sputnikboi.slopmatica.dev.StressTest.INSTANCE.extractHud(graphics));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> com.sputnikboi.slopmatica.plan.InventoryPlanner.INSTANCE.onLeave());
        ClientTickEvents.END_CLIENT_TICK.register(com.sputnikboi.slopmatica.plan.InventoryPlanner.INSTANCE::tick);
        DevHarness.install();
    }
}
