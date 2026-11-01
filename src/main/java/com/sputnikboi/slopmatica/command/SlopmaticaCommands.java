package com.sputnikboi.slopmatica.command;

import com.sputnikboi.slopmatica.spawn.SpawnCheck;
import static com.mojang.brigadier.arguments.FloatArgumentType.floatArg;
import static com.mojang.brigadier.arguments.FloatArgumentType.getFloat;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.schematic.SchematicFormats;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Client-side {@code /slopmatica} commands. The GUI and keybinds cover the same actions. */
public final class SlopmaticaCommands {
    private static final SuggestionProvider<FabricClientCommandSource> FILES = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String file : listSchematicFiles()) {
            if (file.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(file);
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> NAMES = (ctx, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (p.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(p.name());
            }
        }
        return builder.buildFuture();
    };

    private SlopmaticaCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("slopmatica");

        root.then(literal("menu").executes(c -> {
            // Defer: the chat screen closes after the command runs and would replace ours.
            Minecraft mc = Minecraft.getInstance();
            mc.schedule(() -> mc.gui.setScreen(new SlopmaticaMenuScreen(null)));
            return 1;
        }));

        root.then(literal("load").then(argument("file", greedyString()).suggests(FILES)
            .executes(c -> load(c.getSource(), getString(c, "file")))));

        root.then(literal("unload")
            .executes(c -> withSelected(c, p -> SlopmaticaActions.unload(p, feedback(c))))
            .then(literal("all").executes(c -> {
                PlacementManager.INSTANCE.placements().forEach(PlacementManager.INSTANCE::remove);
                c.getSource().sendFeedback(SlopmaticaActions.prefix().append("Unloaded everything"));
                return 1;
            })));

        root.then(literal("list").executes(c -> list(c.getSource())));

        root.then(literal("select").then(argument("name", greedyString()).suggests(NAMES).executes(c -> {
            String name = getString(c, "name");
            return PlacementManager.INSTANCE.byName(name).map(p -> {
                PlacementManager.INSTANCE.select(p);
                c.getSource().sendFeedback(SlopmaticaActions.prefix().append("Selected " + p.name()));
                return 1;
            }).orElseGet(() -> fail(c.getSource(), "No placement called " + name));
        })));

        root.then(literal("move")
            .then(literal("here").executes(c -> withSelected(c, p -> SlopmaticaActions.moveTo(p, c.getSource().getPlayer().blockPosition(), feedback(c)))))
            .then(argument("x", integer()).then(argument("y", integer()).then(argument("z", integer()).executes(c -> withSelected(c, p ->
                SlopmaticaActions.moveTo(p, new BlockPos(getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z")), feedback(c))))))));

        LiteralArgumentBuilder<FabricClientCommandSource> nudge = literal("nudge");
        for (Direction d : Direction.values()) {
            nudge.then(literal(d.getSerializedName())
                .executes(c -> withSelected(c, p -> SlopmaticaActions.nudge(p, d, 1, feedback(c))))
                .then(argument("amount", integer()).executes(c -> withSelected(c, p -> SlopmaticaActions.nudge(p, d, getInteger(c, "amount"), feedback(c))))));
        }
        root.then(nudge);

        root.then(literal("rotate")
            .then(literal("cw").executes(c -> withSelected(c, p -> SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_90, feedback(c)))))
            .then(literal("ccw").executes(c -> withSelected(c, p -> SlopmaticaActions.rotate(p, Rotation.COUNTERCLOCKWISE_90, feedback(c)))))
            .then(literal("180").executes(c -> withSelected(c, p -> SlopmaticaActions.rotate(p, Rotation.CLOCKWISE_180, feedback(c))))));

        root.then(literal("mirror")
            .then(literal("none").executes(c -> withSelected(c, p -> SlopmaticaActions.setMirror(p, Mirror.NONE, feedback(c)))))
            .then(literal("x").executes(c -> withSelected(c, p -> SlopmaticaActions.setMirror(p, Mirror.FRONT_BACK, feedback(c)))))
            .then(literal("z").executes(c -> withSelected(c, p -> SlopmaticaActions.setMirror(p, Mirror.LEFT_RIGHT, feedback(c))))));

        root.then(literal("show").executes(c -> withSelected(c, p -> SlopmaticaActions.setVisible(p, true, feedback(c)))));
        root.then(literal("hide").executes(c -> withSelected(c, p -> SlopmaticaActions.setVisible(p, false, feedback(c)))));

        root.then(literal("opacity").then(argument("value", floatArg(0.05F, 1.0F)).executes(c -> {
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.ghostOpacity = getFloat(c, "value");
            config.save();
            c.getSource().sendFeedback(SlopmaticaActions.prefix().append(String.format(Locale.ROOT, "Ghost opacity %.0f%%", config.ghostOpacity * 100)));
            return 1;
        })));

        root.then(literal("dev")
            .then(literal("stress").then(argument("x", integer(1, 512)).then(argument("y", integer(1, 384)).then(argument("z", integer(1, 512)).executes(c -> {
                com.sputnikboi.slopmatica.dev.StressTest.INSTANCE.spawn(Minecraft.getInstance(), getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z"));
                return 1;
            })))))
            .then(literal("copies").then(argument("count", integer(1, 400)).executes(c -> {
                com.sputnikboi.slopmatica.dev.StressTest.INSTANCE.copies(Minecraft.getInstance(), getInteger(c, "count"));
                return 1;
            })))
            .then(literal("rebuild").executes(c -> {
                com.sputnikboi.slopmatica.dev.StressTest.INSTANCE.rebuild();
                return 1;
            }))
            .then(literal("clear").executes(c -> {
                com.sputnikboi.slopmatica.dev.StressTest.INSTANCE.clear();
                return 1;
            }))
            .then(literal("overlay").executes(c -> {
                var config = com.sputnikboi.slopmatica.config.SlopmaticaConfig.get();
                config.devOverlay = !config.devOverlay;
                config.save();
                return 1;
            })));
        root.then(literal("spawncheck").executes(c -> {
            SpawnCheck.INSTANCE.toggle(Minecraft.getInstance());
            return 1;
        }));
        root.then(literal("corner").executes(c -> {
            Selection.INSTANCE.setCorner(Minecraft.getInstance());
            return 1;
        }));
        root.then(literal("copy").executes(c -> {
            Clipboard.INSTANCE.copy(Minecraft.getInstance());
            return 1;
        }));
        root.then(literal("paste").executes(c -> {
            // After the chat screen has closed, since paste opens the placement view.
            Minecraft mc = Minecraft.getInstance();
            mc.schedule(() -> Clipboard.INSTANCE.paste(mc));
            return 1;
        }));
        root.then(literal("build")
            .executes(c -> withSelected(c, p -> PlacementBuilder.INSTANCE.start(Minecraft.getInstance(), p, feedback(c))))
            .then(literal("stop").executes(c -> {
                PlacementBuilder.INSTANCE.stop(feedback(c));
                return 1;
            })));

        dispatcher.register(root);
    }

    private static int load(FabricClientCommandSource source, String file) {
        Path dir = SlopmaticaActions.schematicsDir();
        List<String> candidates = new java.util.ArrayList<>(List.of(file));
        SchematicFormats.EXTENSIONS.forEach(ext -> candidates.add(file + ext));
        for (String candidate : candidates) {
            Path path = dir.resolve(candidate).normalize();
            if (Files.isRegularFile(path)) {
                SlopmaticaActions.load(path, source.getPlayer().blockPosition(), SlopmaticaActions::chat);
                return 1;
            }
        }
        return fail(source, "No schematic " + file + " in " + dir);
    }

    private static int list(FabricClientCommandSource source) {
        var placements = PlacementManager.INSTANCE.placements();
        if (placements.isEmpty()) {
            source.sendFeedback(SlopmaticaActions.prefix().append("Nothing loaded. Files go in " + SlopmaticaActions.schematicsDir()));
            return 0;
        }
        Placement selected = PlacementManager.INSTANCE.selected().orElse(null);
        for (Placement p : placements) {
            BlockPos o = p.transform().origin();
            source.sendFeedback(Component.literal((p == selected ? "> " : "  ") + p.name()
                + String.format(Locale.ROOT, " @ %d %d %d, rotation %s, mirror %s%s", o.getX(), o.getY(), o.getZ(),
                    SlopmaticaActions.rotationLabel(p.transform().rotation()), SlopmaticaActions.mirrorLabel(p.transform().mirror()), p.visible() ? "" : " (hidden)")));
        }
        return placements.size();
    }

    private static Consumer<Component> feedback(CommandContext<FabricClientCommandSource> c) {
        return c.getSource()::sendFeedback;
    }

    private static int withSelected(CommandContext<FabricClientCommandSource> c, Consumer<Placement> action) {
        return PlacementManager.INSTANCE.selected().map(p -> {
            action.accept(p);
            return 1;
        }).orElseGet(() -> fail(c.getSource(), "No placement loaded; use /slopmatica load <file>"));
    }

    private static int fail(FabricClientCommandSource source, String message) {
        source.sendError(Component.literal(message));
        return 0;
    }

    private static List<String> listSchematicFiles() {
        Path dir = SlopmaticaActions.schematicsDir();
        try (Stream<Path> files = Files.walk(dir, 4)) {
            return files.filter(SchematicFormats::isSchematic)
                .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
