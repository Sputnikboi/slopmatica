package com.sputnikboi.slopmatica.plan;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.verify.BlockMatcher;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import com.sputnikboi.slopmatica.verify.MaterialList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Works out what to bring for the next stretch of building: the blocks still missing in the
 * chosen part of the builds (the next ones bottom-up or top-down, the layers shown, the current
 * layer, or the selection), minus what you carry, packed by {@link InventoryPlan} into free slots
 * and shulker boxes. The result is shown in the plan screen, as ghost items in your inventory and
 * as highlights in containers. Recalculated on demand (button or key), not every tick.
 */
public final class InventoryPlanner {
    public static final InventoryPlanner INSTANCE = new InventoryPlanner();

    /** Positions looked at, at most; beyond this the plan says it stopped early. */
    private static final long MAX_POSITIONS = 4_000_000;
    private static final int MAX_CARRIED_SHULKERS = 27;
    private static final int MAX_ENDER_SHULKERS = 26;

    public enum Scope {
        NEXT_BOTTOM_UP, NEXT_TOP_DOWN, LAYERS, CURRENT_LAYER, SELECTION
    }

    public enum Sort {
        /** In the order the blocks are needed. */
        NEXT,
        AMOUNT
    }

    /**
     * A calculated plan.
     *
     * @param need        what is still missing, by item, in the order needed (after variants)
     * @param extra       tools and items the variants need (an axe, honeycomb)
     * @param result      how it is packed
     * @param truncated   the area was too big and only part of it was looked at
     */
    public record Plan(Map<Item, Long> need, Map<Item, Long> extra, Map<Item, Long> toBring, List<Tool> tools,
                       InventoryPlan.Result<Item> result, Scope scope, boolean truncated, String world) {
        public boolean needs(Item item) {
            return need.containsKey(item) || extra.containsKey(item);
        }

        /** How many more of an item the plan wants brought, beyond what is carried. */
        public long stillToBring(Item item) {
            return toBring.getOrDefault(item, 0L);
        }
    }

    /**
     * A tool worth bringing: one to break the wrong blocks in the way, or one the variants need.
     *
     * @param carried whether you already have one
     */
    public record Tool(String kind, Item icon, int uses, boolean carried) {
    }

    private @Nullable Plan plan;

    private InventoryPlanner() {
    }

    /** The plan for the world you're in; a plan made in another world stays hidden until you're back there. */
    public @Nullable Plan plan() {
        Plan p = plan;
        return p != null && p.world().equals(worldKey(Minecraft.getInstance())) ? p : null;
    }

    /** Which world or server a plan belongs to. */
    private static String worldKey(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server != null) {
            return "sp:" + server.getWorldData().getLevelName();
        }
        var data = mc.getCurrentServer();
        return data != null ? "mp:" + data.ip : "";
    }

    /** Leaving a world: forget the plan only if asked to (advanced option); a restart always does. */
    public void onLeave() {
        if (SlopmaticaConfig.get().planForgetOnLeave) {
            plan = null;
        }
    }

    private int lastInventory;
    private int ticks;

    /** Client tick: when what you carry changes, re-plan quietly so the plan follows your pickups. */
    public void tick(Minecraft mc) {
        if (plan() == null || mc.player == null || ++ticks % 10 != 0) {
            return;
        }
        int signature = inventorySignature(mc);
        if (signature != lastInventory) {
            lastInventory = signature;
            calculate(mc, true);
        }
    }

    private static int inventorySignature(Minecraft mc) {
        int h = 1;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            h = 31 * h + (stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem()) * 97 + stack.getCount());
        }
        ItemStack cursor = mc.player.containerMenu.getCarried();
        return 31 * h + (cursor.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(cursor.getItem()) * 97 + cursor.getCount());
    }

    public void clear() {
        plan = null;
    }

    /** Recalculates from the world as it is now, and reports a one-line summary. */
    public void calculate(Minecraft mc) {
        calculate(mc, false);
    }

    /** {@code quiet}: no summary on the action bar (automatic re-plans). */
    public void calculate(Minecraft mc, boolean quiet) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        SlopmaticaConfig config = SlopmaticaConfig.get();
        ClientLevel level = mc.level;
        List<Placement> placements = PlacementManager.INSTANCE.placements().stream().filter(p -> p.visible() && p.inDimension(level)).toList();
        if (placements.isEmpty()) {
            if (!quiet) {
                SlopmaticaActions.actionBar(Component.translatable("slopmatica.plan.no_builds").withColor(SlopmaticaActions.ERROR));
            }
            return;
        }
        int playerY = mc.player.getBlockY();
        @Nullable BoundingBox area = switch (config.planScope) {
            case SELECTION -> level.dimension().identifier().toString().equals(Selection.INSTANCE.dimension()) ? Selection.INSTANCE.box() : null;
            default -> null;
        };
        if (config.planScope == Scope.SELECTION && area == null) {
            if (quiet) {
                return;
            }
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.plan.no_selection").withColor(SlopmaticaActions.ERROR));
            return;
        }
        int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
        if (config.planScope == Scope.LAYERS) {
            minY = Layers.INSTANCE.minY();
            maxY = Layers.INSTANCE.maxY();
        } else if (config.planScope == Scope.CURRENT_LAYER) {
            minY = maxY = Layers.INSTANCE.mode() != Layers.Mode.ALL ? Layers.INSTANCE.layer() : Layers.startHeight(playerY);
        }
        boolean topDown = config.planScope == Scope.NEXT_TOP_DOWN;

        Set<Item> ignored = new java.util.HashSet<>();
        for (String id : config.planIgnored) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                BuiltInRegistries.ITEM.getOptional(parsed).ifPresent(ignored::add);
            }
        }

        // What is missing, in the order it will be needed: layer by layer in the chosen direction.
        Map<Item, Long> missing = new LinkedHashMap<>();
        Map<String, Integer> toBreak = new LinkedHashMap<>();
        BoundingBox all = null;
        for (Placement p : placements) {
            BoundingBox b = p.transform().worldBounds();
            all = all == null ? b : BoundingBox.encapsulatingBoxes(List.of(all, b)).orElse(all);
        }
        if (area != null) {
            all = intersect(all, area);
        }
        boolean truncated = false;
        if (all != null) {
            int y0 = Math.max(all.minY(), minY), y1 = Math.min(all.maxY(), maxY);
            long looked = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i <= y1 - y0 && !truncated; i++) {
                int y = topDown ? y1 - i : y0 + i;
                for (Placement p : placements) {
                    BoundingBox b = intersect(p.transform().worldBounds(), all);
                    if (b == null || y < b.minY() || y > b.maxY()) {
                        continue;
                    }
                    Placement.Transform t = p.transform();
                    for (int x = b.minX(); x <= b.maxX(); x++) {
                        for (int z = b.minZ(); z <= b.maxZ(); z++) {
                            if (++looked > MAX_POSITIONS) {
                                truncated = true;
                                break;
                            }
                            BlockState want = t.stateAt(x, y, z);
                            if (want == null || want.isAir()) {
                                continue;
                            }
                            Item item = want.getBlock().asItem();
                            int n = MaterialList.itemCount(want);
                            if (item == Items.AIR || n == 0 || ignored.contains(item) || p.ignoredMaterials().contains(item)) {
                                continue;
                            }
                            boolean loaded = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                            BlockStatus status = loaded ? BlockMatcher.compare(want, level.getBlockState(pos.set(x, y, z))) : BlockStatus.MISSING;
                            if (status == BlockStatus.MISSING || status == BlockStatus.WRONG) {
                                missing.merge(item, (long) n, Long::sum);
                            }
                            if (status == BlockStatus.WRONG) {
                                String tool = toolFor(level.getBlockState(pos));
                                if (tool != null) {
                                    toBreak.merge(tool, 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Variants: bring the plain block and the tool or item that turns it into the wanted one.
        Map<Item, Long> need = new LinkedHashMap<>();
        Map<Item, Long> extra = new LinkedHashMap<>();
        for (Map.Entry<Item, Long> e : missing.entrySet()) {
            Item item = e.getKey();
            if (config.planVariants) {
                Optional<Item> plain = prefixed(item, "stripped_");
                if (plain.isPresent()) {
                    item = plain.get();
                    extra.put(Items.IRON_AXE, 1L);
                }
                plain = prefixed(item, "waxed_");
                if (plain.isPresent()) {
                    item = plain.get();
                    extra.merge(Items.HONEYCOMB, e.getValue(), Long::sum);
                }
                // Copper only ages one way with time, but an axe scrapes it back a stage at a
                // time: fully oxidized copper covers every stage.
                Optional<Item> oldest = agedBack(item);
                if (oldest.isPresent()) {
                    item = oldest.get();
                    extra.put(Items.IRON_AXE, 1L);
                }
            }
            need.merge(item, e.getValue(), Long::sum);
        }
        Inventory inventory = mc.player.getInventory();
        if (extra.containsKey(Items.IRON_AXE) && carriesAny(inventory, ItemTags.AXES)) {
            extra.remove(Items.IRON_AXE); // Any axe will do, and you have one.
        }

        // Minus what you carry; partly filled stacks can take more.
        Map<Item, Long> carried = new HashMap<>();
        Map<Item, Integer> room = new HashMap<>();
        int free = 0, carriedShulkerRoom = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                free++;
                continue;
            }
            if (config.planUseCarriedShulkers && stack.is(ItemTags.SHULKER_BOXES)) {
                ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                int used = 0;
                for (var ignoredItem : contents.nonEmptyItems()) {
                    used++;
                }
                carriedShulkerRoom += Math.max(0, InventoryPlan.SHULKER_SLOTS - used);
            }
            if (!config.planIgnoreNbt && !stack.getComponentsPatch().isEmpty()) {
                continue; // Renamed or otherwise special: not counted as building material.
            }
            carried.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
            room.merge(stack.getItem(), stack.getMaxStackSize() - stack.getCount(), Integer::sum);
        }
        Map<Item, Long> toBring = new LinkedHashMap<>();
        Map<Item, Long> wanted = new LinkedHashMap<>(need);
        extra.forEach((k, v) -> wanted.merge(k, v, Long::sum));
        for (Map.Entry<Item, Long> e : wanted.entrySet()) {
            long left = e.getValue() - carried.getOrDefault(e.getKey(), 0L);
            if (left > 0) {
                toBring.put(e.getKey(), left);
            }
        }
        if (config.planSort == Sort.AMOUNT) {
            Map<Item, Long> sorted = new LinkedHashMap<>();
            toBring.entrySet().stream().sorted(Map.Entry.<Item, Long>comparingByValue().reversed()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
            toBring = sorted;
        }

        int slots = config.planAutoSlots ? free : Math.min(config.planSlots, free);
        // At most 27 boxes ride in the inventory; the rest wait in the ender chest (26 fit beside
        // the carried ones), or with unlimited boxes, wherever you keep them.
        int maxCarried = Math.min(config.planShulkers, MAX_CARRIED_SHULKERS);
        int maxEnder = Math.max(0, config.planShulkers - maxCarried);
        if (!config.planUnlimitedShulkers) {
            maxEnder = Math.min(maxEnder, MAX_ENDER_SHULKERS);
        }
        InventoryPlan.Result<Item> result = InventoryPlan.plan(toBring, item -> new ItemStack(item).getMaxStackSize(), room,
            slots, config.planUseCarriedShulkers ? carriedShulkerRoom : 0, maxCarried, maxEnder);
        // Tools: for breaking what's in the way, and for the variants (the axe).
        if (extra.containsKey(Items.IRON_AXE)) {
            toBreak.merge("axe", 0, Integer::sum);
        }
        List<Tool> tools = new java.util.ArrayList<>();
        toBreak.forEach((kind, uses) -> tools.add(new Tool(kind, TOOL_ICONS.get(kind), uses, carriesTool(inventory, kind))));
        plan = new Plan(need, extra, toBring, List.copyOf(tools), result, config.planScope, truncated, worldKey(mc));
        lastInventory = inventorySignature(mc);
        if (quiet) {
            return;
        }

        long total = toBring.values().stream().mapToLong(Long::longValue).sum();
        Component summary = total == 0
            ? Component.translatable("slopmatica.plan.nothing")
            : Component.translatable(result.overflow().isEmpty() ? "slopmatica.plan.summary" : "slopmatica.plan.summary_overflow",
                result.stacks().size(), result.shulkers() + result.enderShulkers(), result.overflow().size());
        SlopmaticaActions.actionBar(summary.copy().withColor(result.overflow().isEmpty() ? SlopmaticaActions.ACCENT : SlopmaticaActions.WARNING));
    }

    private static final Map<String, Item> TOOL_ICONS = Map.of("pickaxe", Items.IRON_PICKAXE, "axe", Items.IRON_AXE,
        "shovel", Items.IRON_SHOVEL, "hoe", Items.IRON_HOE, "shears", Items.SHEARS);

    /** The tool that breaks a block fastest, or null when a hand will do just as well. */
    private static @Nullable String toolFor(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE)) {
            return "pickaxe";
        }
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)) {
            return "axe";
        }
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)) {
            return "shovel";
        }
        if (state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)) {
            return "hoe";
        }
        if (state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(net.minecraft.tags.BlockTags.WOOL) || state.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
            return "shears";
        }
        return null;
    }

    private static boolean carriesTool(Inventory inventory, String kind) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            boolean match = switch (kind) {
                case "pickaxe" -> stack.is(ItemTags.PICKAXES);
                case "axe" -> stack.is(ItemTags.AXES);
                case "shovel" -> stack.is(ItemTags.SHOVELS);
                case "hoe" -> stack.is(ItemTags.HOES);
                default -> stack.is(Items.SHEARS);
            };
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** {@code minecraft:stripped_oak_log} -> {@code minecraft:oak_log}, if that item exists. */
    private static Optional<Item> prefixed(Item item, String prefix) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (!id.getPath().startsWith(prefix)) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath().substring(prefix.length())));
    }

    /** {@code exposed_cut_copper} or {@code weathered_cut_copper} -> {@code oxidized_cut_copper}, if that exists. */
    private static Optional<Item> agedBack(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        for (String stage : new String[] {"exposed_", "weathered_"}) {
            if (id.getPath().startsWith(stage)) {
                return BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), "oxidized_" + id.getPath().substring(stage.length())));
            }
        }
        return Optional.empty();
    }

    /**
     * The block family an item belongs to, for "variant" highlights: stripped, waxed and aged
     * copper forms all lead back to the same root ({@code waxed_weathered_cut_copper} -> {@code cut_copper}).
     */
    public static String family(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        for (String prefix : new String[] {"waxed_", "stripped_", "exposed_", "weathered_", "oxidized_"}) {
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            }
        }
        return path;
    }

    private static boolean carriesAny(Inventory inventory, net.minecraft.tags.TagKey<Item> tag) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable BoundingBox intersect(@Nullable BoundingBox a, @Nullable BoundingBox b) {
        if (a == null || b == null || !a.intersects(b)) {
            return null;
        }
        return new BoundingBox(Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()), Math.max(a.minZ(), b.minZ()),
            Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()), Math.min(a.maxZ(), b.maxZ()));
    }
}
