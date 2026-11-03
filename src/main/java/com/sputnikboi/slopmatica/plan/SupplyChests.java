package com.sputnikboi.slopmatica.plan;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.verify.BlockMatcher;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import com.sputnikboi.slopmatica.verify.MaterialList;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

/**
 * Creative with cheats: everything the shown builds still need, packed into shulker boxes in
 * chests placed around your feet. Blocks whose data matters (chest contents, sign text, loot
 * tables, banners, heads...) come as items that carry it, so they are placed exactly as in the
 * schematic. In singleplayer the chests are made on the integrated server; on a server they are
 * sent as /setblock commands.
 */
public final class SupplyChests {
    private static final int SLOTS = 27;
    private static final int MAX_COMMAND = 32000;
    private static final long MAX_POSITIONS = 64L * 1024 * 1024;

    private SupplyChests() {
    }

    /** Creative mode with commands allowed (cheats on in singleplayer, operator on a server). */
    public static boolean allowed(Minecraft mc) {
        return mc.player != null && mc.player.isCreative() && mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static void place(Minecraft mc) {
        if (!allowed(mc) || mc.level == null) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.supply.not_allowed").withColor(SlopmaticaActions.ERROR));
            return;
        }
        List<ItemStack> stacks = needed(mc, mc.level);
        if (stacks.isEmpty()) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.supply.nothing").withColor(SlopmaticaActions.ERROR));
            return;
        }
        var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        List<List<ItemStack>> chests = pack(stacks, chest -> {
            ListTag items = itemsTag(ops, chest);
            return items == null ? Integer.MAX_VALUE : items.toString().length() + 80;
        });
        List<BlockPos> spots = spots(mc, chests.size());
        if (spots.size() < chests.size()) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.supply.no_room", chests.size()).withColor(SlopmaticaActions.ERROR));
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        BlockPos feet = mc.player.blockPosition();
        int placed = 0;
        for (int i = 0; i < chests.size(); i++) {
            BlockPos pos = spots.get(i);
            Direction facing = Direction.getApproximateNearest(feet.getX() - pos.getX(), 0, feet.getZ() - pos.getZ());
            BlockState state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing.getAxis().isHorizontal() ? facing : Direction.NORTH);
            ListTag items = itemsTag(ops, chests.get(i));
            if (items == null) {
                continue;
            }
            CompoundTag data = new CompoundTag();
            data.put("Items", items);
            if (server != null) {
                ServerLevel level = server.getLevel(mc.level.dimension());
                if (level == null) {
                    continue;
                }
                server.submit(() -> {
                    level.setBlock(pos, state, Block.UPDATE_ALL);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
                        be.setChanged();
                        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
                    }
                }).join();
            } else {
                mc.player.connection.sendCommand(String.format(Locale.ROOT, "setblock %d %d %d %s%s keep",
                    pos.getX(), pos.getY(), pos.getZ(), BlockStateParser.serialize(state), data));
            }
            placed++;
        }
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.supply.done", placed, stacks.size()).withColor(SlopmaticaActions.ACCENT));
    }

    // ---- what is needed ------------------------------------------------------------------------

    /**
     * Every missing or wrong block in the shown builds, as stacks: plain items merged, items with
     * block data one per distinct data.
     */
    static List<ItemStack> needed(Minecraft mc, ClientLevel level) {
        Map<Item, Long> plain = new LinkedHashMap<>();
        Map<String, ItemStack> special = new LinkedHashMap<>(); // item + data -> stack (counted up)
        long looked = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Placement p : PlacementManager.INSTANCE.placements()) {
            if (!p.visible() || !p.inDimension(level)) {
                continue;
            }
            Placement.Transform t = p.transform();
            BoundingBox b = t.worldBounds();
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    for (int z = b.minZ(); z <= b.maxZ(); z++) {
                        if (++looked > MAX_POSITIONS) {
                            break;
                        }
                        BlockState want = t.stateAt(x, y, z);
                        if (want == null || want.isAir()) {
                            continue;
                        }
                        Item item = want.getBlock().asItem();
                        int n = MaterialList.itemCount(want);
                        if (item == Items.AIR || n == 0 || p.ignoredMaterials().contains(item)) {
                            continue;
                        }
                        boolean loaded = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                        BlockStatus status = loaded ? BlockMatcher.compare(want, level.getBlockState(pos.set(x, y, z))) : BlockStatus.MISSING;
                        if (status != BlockStatus.MISSING && status != BlockStatus.WRONG) {
                            continue;
                        }
                        ItemStack withData = withBlockData(want, t.tileEntityAt(x, y, z));
                        if (withData == null) {
                            plain.merge(item, (long) n, Long::sum);
                        } else {
                            String key = BuiltInRegistries.ITEM.getKey(item) + Objects.toString(withData.get(DataComponents.BLOCK_ENTITY_DATA));
                            ItemStack seen = special.get(key);
                            if (seen == null) {
                                special.put(key, withData.copyWithCount(n));
                            } else {
                                seen.grow(n);
                            }
                        }
                    }
                }
            }
        }
        List<ItemStack> out = new ArrayList<>();
        plain.forEach((item, count) -> {
            int max = new ItemStack(item).getMaxStackSize();
            for (long left = count; left > 0; left -= max) {
                out.add(new ItemStack(item, (int) Math.min(max, left)));
            }
        });
        for (ItemStack stack : special.values()) {
            for (int left = stack.getCount(); left > 0; left -= stack.getMaxStackSize()) {
                out.add(stack.copyWithCount(Math.min(stack.getMaxStackSize(), left)));
            }
        }
        return out;
    }

    /** The block's item carrying its schematic data, or null when the data says nothing worth keeping. */
    static @Nullable ItemStack withBlockData(BlockState state, @Nullable CompoundTag data) {
        if (data == null || !(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        CompoundTag tag = data.copy();
        for (String key : new String[] {"x", "y", "z", "id", "keepPacked", "components"}) {
            tag.remove(key);
        }
        if (tag.getListOrEmpty("Items").isEmpty()) {
            tag.remove("Items");
        }
        if (tag.isEmpty()) {
            return null;
        }
        BlockEntityType<?> type = Identifier.tryParse(data.getStringOr("id", "")) instanceof Identifier id
            ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null) : null;
        if (type == null) {
            BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, state);
            if (be == null) {
                return null;
            }
            type = be.getType();
        }
        ItemStack stack = new ItemStack(state.getBlock().asItem());
        stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(type, tag));
        return stack;
    }

    // ---- packing -------------------------------------------------------------------------------

    /**
     * Stacks into shulker boxes (27 stacks each, named after what they mostly hold) and those into
     * chests, as many per chest as fit in one command. Shulker boxes the build needs can't go in
     * shulker boxes, so they sit in the chests themselves.
     */
    static List<List<ItemStack>> pack(List<ItemStack> stacks, java.util.function.ToIntFunction<List<ItemStack>> size) {
        List<ItemStack> chestLevel = new ArrayList<>();
        List<ItemStack> loose = new ArrayList<>();
        for (ItemStack stack : stacks) {
            (stack.is(ItemTags.SHULKER_BOXES) ? chestLevel : loose).add(stack);
        }
        for (int i = 0; i < loose.size(); i += SLOTS) {
            List<ItemStack> contents = loose.subList(i, Math.min(loose.size(), i + SLOTS));
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            box.set(DataComponents.CUSTOM_NAME, contents.getFirst().getHoverName().copy()
                .append(contents.size() > 1 ? Component.literal(" …") : Component.empty()));
            chestLevel.add(box);
        }
        List<List<ItemStack>> chests = new ArrayList<>();
        List<ItemStack> chest = new ArrayList<>();
        for (ItemStack stack : chestLevel) {
            chest.add(stack);
            if (chest.size() > SLOTS || chest.size() > 1 && size.applyAsInt(chest) > MAX_COMMAND) {
                chest.removeLast();
                chests.add(chest);
                chest = new ArrayList<>(List.of(stack));
            }
        }
        if (!chest.isEmpty()) {
            chests.add(chest);
        }
        return chests;
    }

    private static @Nullable ListTag itemsTag(net.minecraft.resources.RegistryOps<Tag> ops, List<ItemStack> chest) {
        ListTag items = new ListTag();
        for (int slot = 0; slot < chest.size(); slot++) {
            Tag encoded = ItemStack.CODEC.encodeStart(ops, chest.get(slot)).result().orElse(null);
            if (!(encoded instanceof CompoundTag item)) {
                return null;
            }
            item.putByte("Slot", (byte) slot);
            items.add(item);
        }
        return items;
    }

    // ---- where ---------------------------------------------------------------------------------

    /** Free spots at foot level in rings around you, nearest first (never your own column). */
    private static List<BlockPos> spots(Minecraft mc, int count) {
        BlockPos feet = mc.player.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        for (int r = 1; r <= 6 && out.size() < count; r++) {
            for (int dx = -r; dx <= r && out.size() < count; dx++) {
                for (int dz = -r; dz <= r && out.size() < count; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos pos = feet.offset(dx, 0, dz);
                    if (mc.level.getBlockState(pos).canBeReplaced()) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }
}
