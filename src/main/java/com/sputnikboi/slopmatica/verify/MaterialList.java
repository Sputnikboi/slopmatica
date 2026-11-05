package com.sputnikboi.slopmatica.verify;

import com.sputnikboi.slopmatica.placement.Placement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The items a placement needs: how many of each in total, how many are still missing from the
 * world, and how many the player is carrying. Counted in items, not blocks: a double slab is two
 * slabs, three candles in one block are three candles, a door or bed counts once. Fluid sources
 * (including the water in waterlogged blocks) count as their bucket.
 */
public final class MaterialList {
    /** Blocks that hold several of their item, by the property that says how many. */
    private static final IntegerProperty[] STACKED = {
        BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
        BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT,
    };

    /** {@code icon}: the item as shown, e.g. a player head with its skin. */
    public record Entry(Item item, int total, int missing, int have, ItemStack icon) {
    }

    private final List<Entry> entries;
    private final int totalItems;
    private final int missingItems;
    /** Blocks in chunks the client hasn't loaded; their status is unknown, so they count as missing. */
    private final int unloaded;

    private MaterialList(List<Entry> entries, int totalItems, int missingItems, int unloaded) {
        this.entries = entries;
        this.totalItems = totalItems;
        this.missingItems = missingItems;
        this.unloaded = unloaded;
    }

    /** Client thread: reads the live world. */
    public static MaterialList count(Placement placement, ClientLevel level, Inventory inventory) {
        Placement.Transform t = placement.transform();
        BoundingBox box = t.worldBounds();
        Map<Item, int[]> counts = new LinkedHashMap<>();
        Map<Item, ItemStack> icons = new java.util.HashMap<>();
        int unloaded = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                boolean loaded = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockState want = t.stateAt(x, y, z);
                    if (want == null || want.isAir()) {
                        continue;
                    }
                    Item bucket = bucketFor(want);
                    if (bucket != null) {
                        int[] c = counts.computeIfAbsent(bucket, k -> new int[2]);
                        c[0]++;
                        if (!loaded || fluidMissing(want, level.getBlockState(pos.set(x, y, z)))) {
                            c[1]++;
                        }
                    }
                    Item item = want.getBlock().asItem();
                    int n = itemCount(want);
                    if (item == Items.AIR || n == 0) {
                        continue;
                    }
                    if (want.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock && !icons.containsKey(item)) {
                        // Heads look like their owner: take the skin from the first one's data.
                        icons.put(item, iconWithData(item, want, t.tileEntityAt(x, y, z), pos.set(x, y, z), level));
                    }
                    int[] c = counts.computeIfAbsent(item, k -> new int[2]);
                    c[0] += n;
                    if (!loaded) {
                        c[1] += n;
                        unloaded++;
                        continue;
                    }
                    BlockStatus status = BlockMatcher.compare(want, level.getBlockState(pos.set(x, y, z)));
                    if (status == BlockStatus.MISSING || status == BlockStatus.WRONG) {
                        c[1] += n;
                    }
                }
            }
        }

        Map<Item, Integer> carried = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        List<Entry> entries = new ArrayList<>();
        int total = 0, missing = 0;
        for (Map.Entry<Item, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            entries.add(new Entry(e.getKey(), c[0], c[1], carried.getOrDefault(e.getKey(), 0), icons.getOrDefault(e.getKey(), new ItemStack(e.getKey()))));
            total += c[0];
            missing += c[1];
        }
        entries.sort(Comparator.comparingInt(Entry::missing).reversed().thenComparing(Comparator.comparingInt(Entry::total).reversed()));
        return new MaterialList(List.copyOf(entries), total, missing, unloaded);
    }

    /** The item with the components its block entity data gives it (a head's skin, a banner's pattern). */
    private static ItemStack iconWithData(Item item, BlockState state, net.minecraft.nbt.@org.jspecify.annotations.Nullable CompoundTag data,
                                         BlockPos pos, ClientLevel level) {
        ItemStack stack = new ItemStack(item);
        if (data == null) {
            return stack;
        }
        try {
            net.minecraft.nbt.CompoundTag tag = data.copy();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            var entity = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos.immutable(), state, tag, level.registryAccess());
            if (entity != null) {
                stack.applyComponents(entity.collectComponents());
            }
        } catch (RuntimeException e) {
            // Unreadable data: the plain item will do.
        }
        return stack;
    }

    /**
     * The bucket a block's fluid comes in: water and lava sources, and the water in waterlogged
     * blocks, kelp and the like. Null for flowing fluid, which runs out of the sources by itself.
     */
    public static @Nullable Item bucketFor(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isSource()) {
            return null;
        }
        Item bucket = fluid.getType().getBucket();
        return bucket == Items.AIR ? null : bucket;
    }

    /** True when the world doesn't hold the source the schematic's block has (see {@link #bucketFor}). */
    public static boolean fluidMissing(BlockState want, BlockState have) {
        return !have.getFluidState().isSourceOfType(want.getFluidState().getType());
    }

    public static int itemCount(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return 0;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return 0;
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            return 2;
        }
        for (IntegerProperty property : STACKED) {
            if (state.hasProperty(property)) {
                return state.getValue(property);
            }
        }
        return 1;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int totalItems() {
        return totalItems;
    }

    public int missingItems() {
        return missingItems;
    }

    public int unloaded() {
        return unloaded;
    }
}
