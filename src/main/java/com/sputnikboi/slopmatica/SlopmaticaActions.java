package com.sputnikboi.slopmatica;

import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.schematic.Schematic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Everything the player can do to placements, shared by the commands, the keybinds and the
 * screens so all three behave the same. Client thread only. Each action reports through a
 * feedback sink: chat for commands, the action bar for keybinds.
 */
public final class SlopmaticaActions {
    public static final int ACCENT = 0xFF55CCFF;
    public static final int ERROR = 0xFFFF5555;

    private SlopmaticaActions() {
    }

    /** {@code <gamedir>/schematics}, the same folder Litematica uses, so existing files just appear. */
    public static Path schematicsDir() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // Listing will simply come back empty.
        }
        return dir;
    }

    public static void load(Path file, BlockPos origin, Consumer<Component> feedback) {
        feedback.accept(prefix().append("Loading " + file.getFileName() + "..."));
        CompletableFuture.supplyAsync(() -> {
            try {
                return Schematic.load(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool()).whenCompleteAsync((schematic, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                feedback.accept(Component.literal("[Slopmatica] Could not load " + file.getFileName() + ": " + cause.getMessage()).withColor(ERROR));
                return;
            }
            Placement placement = PlacementManager.INSTANCE.add(schematic, origin);
            var size = schematic.bounds();
            feedback.accept(prefix().append(String.format(Locale.ROOT, "Placed %s at %d %d %d (%d x %d x %d, %,d blocks)",
                placement.name(), origin.getX(), origin.getY(), origin.getZ(), size.getXSpan(), size.getYSpan(), size.getZSpan(), schematic.blockCount())));
            if (!schematic.unknownBlocks().isEmpty()) {
                feedback.accept(Component.literal("[Slopmatica] Unknown blocks shown as air: " + String.join(", ", schematic.unknownBlocks())).withColor(ERROR));
            }
        }, Minecraft.getInstance());
    }

    public static void moveTo(Placement p, BlockPos origin, Consumer<Component> feedback) {
        p.setOrigin(origin);
        PlacementManager.INSTANCE.changed();
        feedback.accept(prefix().append(String.format(Locale.ROOT, "%s at %d %d %d", p.name(), origin.getX(), origin.getY(), origin.getZ())));
    }

    public static void nudge(Placement p, Direction direction, int amount, Consumer<Component> feedback) {
        moveTo(p, p.transform().origin().relative(direction, amount), feedback);
    }

    public static void rotate(Placement p, Rotation by, Consumer<Component> feedback) {
        p.setRotation(p.transform().rotation().getRotated(by));
        PlacementManager.INSTANCE.changed();
        feedback.accept(prefix().append(p.name() + " rotated " + rotationLabel(p.transform().rotation())));
    }

    public static void setMirror(Placement p, Mirror mirror, Consumer<Component> feedback) {
        p.setMirror(mirror);
        PlacementManager.INSTANCE.changed();
        feedback.accept(prefix().append(p.name() + " mirror " + mirrorLabel(mirror)));
    }

    /** None, then flip along X, then flip along Z. */
    public static Mirror nextMirror(Mirror mirror) {
        return switch (mirror) {
            case NONE -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.NONE;
        };
    }

    public static void setVisible(Placement p, boolean visible, Consumer<Component> feedback) {
        p.setVisible(visible);
        PlacementManager.INSTANCE.changed();
        feedback.accept(prefix().append(p.name() + (visible ? " shown" : " hidden")));
    }

    public static void unload(Placement p, Consumer<Component> feedback) {
        PlacementManager.INSTANCE.remove(p);
        feedback.accept(prefix().append("Unloaded " + p.name()));
    }

    public static String rotationLabel(Rotation rotation) {
        return switch (rotation) {
            case NONE -> "0°";
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "270°";
        };
    }

    public static String mirrorLabel(Mirror mirror) {
        return switch (mirror) {
            case NONE -> "none";
            case FRONT_BACK -> "X";
            case LEFT_RIGHT -> "Z";
        };
    }

    public static MutableComponent prefix() {
        return Component.literal("[Slopmatica] ").withColor(ACCENT);
    }

    public static void chat(Component message) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
    }

    /** Short feedback for keybinds: the action bar, without the chat prefix. */
    public static void actionBar(Component message) {
        Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
    }
}
