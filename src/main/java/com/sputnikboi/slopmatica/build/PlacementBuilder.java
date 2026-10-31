package com.sputnikboi.slopmatica.build;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Placement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import com.sputnikboi.slopmatica.schematic.EntityData;
import java.util.List;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

/**
 * Builds a placement into the world, for creative mode. In singleplayer the blocks are set
 * straight on the integrated server, a slice per tick, block entity data included. On a server it
 * sends {@code /setblock} and {@code /fill} commands, which needs operator rights; runs of the same
 * block along X become one {@code /fill}. Blocks are set without physics (no falling sand, no
 * shape updates), so the result matches the schematic exactly.
 */
public final class PlacementBuilder {
    public static final PlacementBuilder INSTANCE = new PlacementBuilder();

    /** Positions handled per tick on the integrated server. */
    private static final int SERVER_BATCH = 32768;
    /** Commands sent per tick on a remote server. */
    private static final int COMMANDS_PER_TICK = 40;
    /**
     * Commands longer than this are not sent (block entity data is dropped instead). The command
     * packet takes up to 32767 characters; 256 is only the chat box's limit.
     */
    private static final int MAX_COMMAND = 32000;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_ON_PLACE;

    private @Nullable Job job;

    private PlacementBuilder() {
    }

    public boolean running() {
        return job != null;
    }

    /** Starts building; false (with a message) if it can't. */
    public boolean start(Minecraft mc, Placement placement, Consumer<Component> feedback) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return false;
        }
        if (job != null) {
            feedback.accept(Component.literal("Already pasting " + job.name + "; stop it first (/slopmatica build stop)").withColor(SlopmaticaActions.ERROR));
            return false;
        }
        if (mc.gameMode.getPlayerMode() != GameType.CREATIVE) {
            feedback.accept(Component.literal("Pasting into the world needs creative mode").withColor(SlopmaticaActions.ERROR));
            return false;
        }
        if (!placement.inDimension(mc.level)) {
            feedback.accept(Component.literal(placement.name() + " is in another dimension").withColor(SlopmaticaActions.ERROR));
            return false;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null && !mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            feedback.accept(Component.literal("Pasting on a server needs operator rights (it uses /setblock)").withColor(SlopmaticaActions.ERROR));
            return false;
        }
        job = new Job(placement.name(), placement.transform(), placement.dimension(), SlopmaticaConfig.get().pasteReplace, SlopmaticaConfig.get().includeNbt, server);
        feedback.accept(SlopmaticaActions.prefix().append(String.format(Locale.ROOT, "Pasting %s (%,d positions)...", placement.name(), job.total)));
        return true;
    }

    public void stop(Consumer<Component> feedback) {
        if (job == null) {
            feedback.accept(Component.literal("Nothing is being pasted").withColor(SlopmaticaActions.ERROR));
            return;
        }
        feedback.accept(SlopmaticaActions.prefix().append("Stopped pasting " + job.name));
        job = null;
    }

    /** Client tick. */
    public void tick(Minecraft mc) {
        Job j = job;
        if (j == null) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            job = null;
            return;
        }
        boolean done = j.server != null ? j.tickIntegrated() : j.tickCommands(mc);
        if (done) {
            job = null;
            String entities = j.entities.isEmpty() ? "" : String.format(Locale.ROOT, ", %d of %d entities", j.placedEntities.get(), j.entities.size());
            SlopmaticaActions.chat(SlopmaticaActions.prefix().append(String.format(Locale.ROOT, "Pasted %s: %,d blocks set%s", j.name, j.placed.get(), entities)));
        } else if (mc.level.getGameTime() % 10 == 0) {
            SlopmaticaActions.actionBar(Component.literal(String.format(Locale.ROOT, "Pasting %s: %d%%", j.name, j.done * 100 / Math.max(1, j.total))).withColor(SlopmaticaActions.ACCENT));
        }
    }

    public enum Replace {
        /** Only fill air; existing blocks stay (Litematica's default). */
        NONE,
        /** The schematic's blocks overwrite what is there; its air leaves things alone. */
        WITH_NON_AIR,
        /** An exact copy: the schematic's air clears what is there too. */
        ALL
    }

    private static final class Job {
        final String name;
        final Placement.Transform t;
        final String dimension;
        final Replace replace;
        /** Block entity data and entities are applied (Options: Copy & Paste NBT Data). */
        final boolean nbt;
        final List<EntityData> entities;
        final AtomicLong placedEntities = new AtomicLong();
        boolean entitiesStarted;
        int nextEntity;
        final @Nullable IntegratedServer server;
        final BoundingBox box;
        final long total;
        /** Positions handed out so far, in x-fastest order over the box. */
        long done;
        final AtomicLong placed = new AtomicLong();
        final AtomicBoolean serverBusy = new AtomicBoolean();

        Job(String name, Placement.Transform t, String dimension, Replace replace, boolean nbt, @Nullable IntegratedServer server) {
            this.name = name;
            this.t = t;
            this.dimension = dimension;
            this.replace = replace;
            this.nbt = nbt;
            this.entities = nbt ? t.worldEntities() : List.of();
            this.server = server;
            this.box = t.worldBounds();
            this.total = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        }

        BlockPos at(long index) {
            int sx = box.getXSpan(), sz = box.getZSpan();
            int x = (int) (index % sx);
            long rest = index / sx;
            int z = (int) (rest % sz);
            int y = (int) (rest / sz);
            return new BlockPos(box.minX() + x, box.minY() + y, box.minZ() + z);
        }

        /** What to place at a position, or null to leave it alone. */
        @Nullable BlockState wanted(BlockPos pos, BlockState current) {
            BlockState want = t.stateAt(pos.getX(), pos.getY(), pos.getZ());
            if (want == null || want == current) {
                return null;
            }
            if (want.isAir() && replace != Replace.ALL) {
                return null;
            }
            if (!current.isAir() && replace == Replace.NONE) {
                return null;
            }
            return want;
        }

        boolean tickIntegrated() {
            if (serverBusy.get()) {
                return false;
            }
            if (done >= total) {
                // Blocks first, so hanging entities have something to hang on.
                if (entitiesStarted || entities.isEmpty()) {
                    return true;
                }
                entitiesStarted = true;
                serverBusy.set(true);
                server.execute(() -> {
                    try {
                        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
                        if (level != null) {
                            spawnEntities(level);
                        }
                    } finally {
                        serverBusy.set(false);
                    }
                });
                return false;
            }
            long from = done, to = Math.min(total, done + SERVER_BATCH);
            done = to;
            serverBusy.set(true);
            server.execute(() -> {
                try {
                    ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
                    if (level != null) {
                        placeSlice(level, from, to);
                    }
                } finally {
                    serverBusy.set(false);
                }
            });
            return false;
        }

        private void placeSlice(ServerLevel level, long from, long to) {
            for (long i = from; i < to; i++) {
                BlockPos pos = at(i);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState want = wanted(pos, level.getBlockState(pos));
                if (want == null) {
                    continue;
                }
                level.setBlock(pos, want, FLAGS);
                placed.incrementAndGet();
                CompoundTag data = nbt ? t.tileEntityAt(pos.getX(), pos.getY(), pos.getZ()) : null;
                if (data != null) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        CompoundTag tag = data.copy();
                        tag.putInt("x", pos.getX());
                        tag.putInt("y", pos.getY());
                        tag.putInt("z", pos.getZ());
                        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
                        be.setChanged();
                        level.sendBlockUpdated(pos, want, want, Block.UPDATE_ALL);
                    }
                }
            }
        }

        /** As vanilla places a structure's entities: fresh UUIDs, turned and mirrored with the build. */
        private void spawnEntities(ServerLevel level) {
            for (EntityData e : entities) {
                CompoundTag tag = e.nbt().copy();
                tag.put("Pos", EntityData.doubles(e.pos().x, e.pos().y, e.pos().z));
                tag.remove("UUID");
                if (tag.contains(EntityData.BLOCK_POS)) {
                    tag.putIntArray(EntityData.BLOCK_POS, new int[] {e.blockPos().getX(), e.blockPos().getY(), e.blockPos().getZ()});
                }
                try {
                    EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level,
                        new EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false)).ifPresent(entity -> {
                        float yRot = entity.rotate(t.rotation());
                        yRot += entity.mirror(t.mirror()) - entity.getYRot();
                        entity.snapTo(e.pos().x, e.pos().y, e.pos().z, yRot, entity.getXRot());
                        entity.setYBodyRot(yRot);
                        entity.setYHeadRot(yRot);
                        level.addFreshEntityWithPassengers(entity);
                        placedEntities.incrementAndGet();
                    });
                } catch (RuntimeException ignored) {
                    // An entity type this version doesn't know: skip it, like vanilla does.
                }
            }
        }

        boolean tickCommands(Minecraft mc) {
            Level level = mc.level;
            int sent = 0;
            if (done >= total) {
                // Then entities, as /summon (hanging entities keep their stored facing).
                while (sent < COMMANDS_PER_TICK && nextEntity < entities.size()) {
                    String command = summon(entities.get(nextEntity++));
                    if (command != null) {
                        mc.player.connection.sendCommand(command);
                        placedEntities.incrementAndGet();
                        sent++;
                    }
                }
                return nextEntity >= entities.size();
            }
            while (sent < COMMANDS_PER_TICK && done < total) {
                BlockPos pos = at(done);
                if (!level.isLoaded(pos)) {
                    done++;
                    continue;
                }
                BlockState want = wanted(pos, level.getBlockState(pos));
                if (want == null) {
                    done++;
                    continue;
                }
                // Extend along X while the same block keeps being wanted and the row continues.
                int run = 1;
                while (done + run < total && pos.getX() + run <= box.maxX() && run < 256) {
                    BlockPos next = pos.offset(run, 0, 0);
                    if (wanted(next, level.getBlockState(next)) != want || t.tileEntityAt(next.getX(), next.getY(), next.getZ()) != null) {
                        break;
                    }
                    run++;
                }
                String mode = replace == Replace.NONE ? "keep" : "replace";
                String state = BlockStateParser.serialize(want);
                CompoundTag data = nbt ? t.tileEntityAt(pos.getX(), pos.getY(), pos.getZ()) : null;
                String command;
                if (run == 1) {
                    command = String.format(Locale.ROOT, "setblock %d %d %d %s %s", pos.getX(), pos.getY(), pos.getZ(), state + nbt(data), mode);
                    if (command.length() > MAX_COMMAND) {
                        command = String.format(Locale.ROOT, "setblock %d %d %d %s %s", pos.getX(), pos.getY(), pos.getZ(), state, mode);
                    }
                } else {
                    command = String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s %s", pos.getX(), pos.getY(), pos.getZ(), pos.getX() + run - 1, pos.getY(), pos.getZ(), state, mode);
                }
                if (command.length() <= MAX_COMMAND) {
                    mc.player.connection.sendCommand(command);
                    placed.addAndGet(run);
                    sent++;
                }
                done += run;
            }
            return false;
        }

        /** A /summon for an entity, or null if its data makes the command too long to send. */
        private @Nullable String summon(EntityData e) {
            CompoundTag tag = e.nbt().copy();
            String id = tag.getStringOr("id", "");
            if (id.isEmpty()) {
                return null;
            }
            for (String key : new String[] {"id", "Pos", "UUID", "Motion"}) {
                tag.remove(key);
            }
            if (tag.contains(EntityData.BLOCK_POS)) {
                tag.putIntArray(EntityData.BLOCK_POS, new int[] {e.blockPos().getX(), e.blockPos().getY(), e.blockPos().getZ()});
            }
            ListTag rotation = tag.getListOrEmpty("Rotation");
            if (rotation.size() == 2) {
                // Entity.rotate and Entity.mirror, done on the stored angle.
                float yaw = Mth.wrapDegrees(rotation.getFloatOr(0, 0));
                float turned = switch (t.rotation()) {
                    case CLOCKWISE_90 -> yaw + 90;
                    case CLOCKWISE_180 -> yaw + 180;
                    case COUNTERCLOCKWISE_90 -> yaw + 270;
                    default -> yaw;
                };
                float mirrored = switch (t.mirror()) {
                    case FRONT_BACK -> -yaw;
                    case LEFT_RIGHT -> 180 - yaw;
                    default -> yaw;
                };
                ListTag newRotation = new ListTag();
                newRotation.add(FloatTag.valueOf(turned + mirrored - yaw));
                newRotation.add(FloatTag.valueOf(rotation.getFloatOr(1, 0)));
                tag.put("Rotation", newRotation);
            }
            String command = String.format(Locale.ROOT, "summon %s %.4f %.4f %.4f %s", id, e.pos().x, e.pos().y, e.pos().z, tag);
            return command.length() <= MAX_COMMAND ? command : null;
        }

        private static String nbt(@Nullable CompoundTag data) {
            if (data == null) {
                return "";
            }
            CompoundTag tag = data.copy();
            for (String key : new String[] {"x", "y", "z", "id"}) {
                tag.remove(key);
            }
            return tag.isEmpty() ? "" : tag.toString();
        }
    }
}
