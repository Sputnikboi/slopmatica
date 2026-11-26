package com.sputnikboi.slopmatica.split;

import com.sputnikboi.slopmatica.schematic.BlockVolume;
import com.sputnikboi.slopmatica.schematic.EntityData;
import com.sputnikboi.slopmatica.schematic.Schematic;
import com.sputnikboi.slopmatica.split.SplitAnalysis.Group;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Util;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Splits a schematic (experimental): reads it into {@link SplitAnalysis}, deciding what each kind
 * of block is (natural, plant, built...), and turns chosen parts back into blocks to save or place.
 * Works in the schematic's own coordinates, on the whole of it or the part a crop leaves.
 */
public final class SchematicSplitter {
    /** Bigger boxes would take too much memory (4 bytes a block): crop first. */
    public static final long MAX_CELLS = 24_000_000L;

    private static final Map<Schematic, Split> CACHE = new WeakHashMap<>();

    private SchematicSplitter() {
    }

    /** A schematic (or a box of it) split up. */
    public static final class Split {
        public final Schematic schematic;
        /** The box looked at, in schematic coordinates. */
        public final BoundingBox box;
        public final SplitAnalysis.Result result;
        private final List<BlockState> palette;
        /** Each group's most common block. */
        private final Map<Integer, Block> dominant;

        private Split(Schematic schematic, BoundingBox box, SplitAnalysis.Result result, List<BlockState> palette, Map<Integer, Block> dominant) {
            this.schematic = schematic;
            this.box = box;
            this.result = result;
            this.palette = palette;
            this.dominant = dominant;
        }

        public Component name(Group g) {
            Block main = dominant.get(g.id());
            Component block = main == null ? Component.empty() : main.getName();
            return switch (g.type()) {
                case STRUCTURE -> Component.translatable("slopmatica.split.structure", g.number(), block);
                case SMALL -> Component.translatable("slopmatica.split.small");
                case PEAK -> Component.translatable("slopmatica.split.peak", g.number(), g.b());
                case GROUND -> Component.translatable("slopmatica.split.ground");
                case WATER -> Component.translatable("slopmatica.split.water");
                case TREES -> Component.translatable("slopmatica.split.trees");
                case PLANTS -> Component.translatable("slopmatica.split.plants");
                case PATTERN -> Component.translatable("slopmatica.split.pattern", g.number(), block, g.a(), g.b());
            };
        }

        /** A file-name-friendly name: "structure-2", "peak-1"... */
        public String fileName(Group g) {
            String type = g.type().name().toLowerCase(java.util.Locale.ROOT);
            return g.type() == SplitAnalysis.Type.SMALL || g.type() == SplitAnalysis.Type.GROUND || g.type() == SplitAnalysis.Type.WATER
                || g.type() == SplitAnalysis.Type.TREES || g.type() == SplitAnalysis.Type.PLANTS ? type : type + "-" + g.number();
        }

        /** A mask in schematic coordinates, for showing just part of a placement. */
        public SplitMask mask(BitSet cells) {
            return new SplitMask(box.minX(), box.minY(), box.minZ(), result.sx(), result.sy(), result.sz(), cells);
        }

        /** Where a part's box starts, in schematic coordinates (null when it's empty). */
        public BlockPos partMin(BitSet cells) {
            int[] b = bounds(cells);
            return b == null ? null : new BlockPos(box.minX() + b[0], box.minY() + b[1], box.minZ() + b[2]);
        }

        private int[] bounds(BitSet cells) {
            int sx = result.sx(), sz = result.sz(), layer = sx * sz;
            int[] b = null;
            for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
                int x = i % sx, z = (i / sx) % sz, y = i / layer;
                if (b == null) {
                    b = new int[] {x, y, z, x, y, z};
                } else {
                    b[0] = Math.min(b[0], x);
                    b[1] = Math.min(b[1], y);
                    b[2] = Math.min(b[2], z);
                    b[3] = Math.max(b[3], x);
                    b[4] = Math.max(b[4], y);
                    b[5] = Math.max(b[5], z);
                }
            }
            return b;
        }

        /**
         * The blocks of a part, boxed tight: everything else in the box is structure void ("leave
         * whatever is there"), so the parts of one schematic can be built in any order. Block
         * entity data comes along; so do entities standing in or next to the part's blocks.
         */
        public BlockVolume volume(BitSet cells, String name, String author) {
            int[] b = bounds(cells);
            if (b == null) {
                return null;
            }
            int sx = result.sx(), sz = result.sz(), layer = sx * sz;
            int vx = b[3] - b[0] + 1, vy = b[4] - b[1] + 1, vz = b[5] - b[2] + 1;
            List<BlockState> outPalette = new ArrayList<>();
            Map<BlockState, Integer> ids = new IdentityHashMap<>();
            outPalette.add(Blocks.AIR.defaultBlockState());
            ids.put(Blocks.AIR.defaultBlockState(), 0);
            BlockState voidState = Blocks.STRUCTURE_VOID.defaultBlockState();
            outPalette.add(voidState);
            ids.put(voidState, 1);
            int[] indices = new int[Math.multiplyExact(Math.multiplyExact(vx, vy), vz)];
            java.util.Arrays.fill(indices, 1);
            Map<Long, CompoundTag> tiles = new HashMap<>();
            Lookup lookup = new Lookup(schematic);
            for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
                int x = i % sx, z = (i / sx) % sz, y = i / layer;
                int wx = box.minX() + x, wy = box.minY() + y, wz = box.minZ() + z;
                BlockState state = palette.get(lookup.id(wx, wy, wz));
                Integer id = ids.get(state);
                if (id == null) {
                    id = outPalette.size();
                    outPalette.add(state);
                    ids.put(state, id);
                }
                int rx = x - b[0], ry = y - b[1], rz = z - b[2];
                indices[(ry * vz + rz) * vx + rx] = id;
                for (Schematic.Region region : schematic.regions()) {
                    CompoundTag tag = region.tileEntityAt(wx, wy, wz);
                    if (tag != null) {
                        tiles.put(BlockPos.asLong(rx, ry, rz), tag.copy());
                        break;
                    }
                }
            }
            List<EntityData> entities = new ArrayList<>();
            for (Schematic.Region region : schematic.regions()) {
                for (EntityData e : region.entities()) {
                    if (near(cells, e.blockPos())) {
                        entities.add(e.offset(-(box.minX() + b[0]), -(box.minY() + b[1]), -(box.minZ() + b[2])));
                    }
                }
            }
            return new BlockVolume(name, author, vx, vy, vz, outPalette, indices, tiles, entities);
        }

        /** Whether a schematic position is one of the part's blocks or right next to one. */
        private boolean near(BitSet cells, BlockPos p) {
            int sx = result.sx(), sy = result.sy(), sz = result.sz();
            for (int[] d : new int[][] {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                int x = p.getX() - box.minX() + d[0], y = p.getY() - box.minY() + d[1], z = p.getZ() - box.minZ() + d[2];
                if (x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz && cells.get((y * sz + z) * sx + x)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The split of {@code box} of a schematic, worked out in the background (or remembered from last time). */
    public static synchronized CompletableFuture<Split> split(Schematic schematic, BoundingBox box, DoubleConsumer progress) {
        Split cached = CACHE.get(schematic);
        if (cached != null && cached.box.equals(box)) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            Split split = run(schematic, box, progress);
            synchronized (SchematicSplitter.class) {
                CACHE.put(schematic, split);
            }
            return split;
        }, Util.backgroundExecutor());
    }

    public static long cells(BoundingBox box) {
        return (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    }

    private static Split run(Schematic schematic, BoundingBox box, DoubleConsumer progress) {
        Lookup lookup = new Lookup(schematic);
        int ox = box.minX(), oy = box.minY(), oz = box.minZ();
        SplitAnalysis.Result result = SplitAnalysis.analyse(box.getXSpan(), box.getYSpan(), box.getZSpan(), new SplitAnalysis.Source() {
            @Override
            public byte classAt(int x, int y, int z) {
                return lookup.classes[lookup.id(ox + x, oy + y, oz + z)];
            }

            @Override
            public int stateAt(int x, int y, int z) {
                return lookup.id(ox + x, oy + y, oz + z);
            }
        }, progress);
        // Each group's most common block, for its name.
        Map<Integer, Map<Block, Integer>> counts = new HashMap<>();
        int sx = result.sx(), sz = result.sz(), layer = sx * sz;
        for (int i = 0; i < result.cells().length; i++) {
            if (result.kind(i) == null) {
                continue;
            }
            Block block = lookup.palette.get(lookup.id(ox + i % sx, oy + i / layer, oz + (i / sx) % sz)).getBlock();
            counts.computeIfAbsent(result.object(i), k -> new HashMap<>()).merge(block, 1, Integer::sum);
            int col = i % layer;
            if (result.kind(i).ordinal() >= SplitAnalysis.Kind.WALLS.ordinal()) {
                for (int pattern : new int[] {result.patternX()[col], result.patternZ()[col]}) {
                    if (pattern >= 0) {
                        counts.computeIfAbsent(pattern, k -> new HashMap<>()).merge(block, 1, Integer::sum);
                    }
                }
            }
        }
        Map<Integer, Block> dominant = new HashMap<>();
        counts.forEach((id, byBlock) -> byBlock.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(e -> dominant.put(id, e.getKey())));
        return new Split(schematic, box, result, lookup.palette, dominant);
    }

    /** The schematic's blocks as one palette (index 0: air, also for nothing and structure void), and a class for each. */
    private static final class Lookup {
        final List<BlockState> palette = new ArrayList<>();
        final List<Schematic.Region> regions;
        final int[][] toGlobal;
        byte[] classes;

        Lookup(Schematic schematic) {
            regions = schematic.regions();
            Map<BlockState, Integer> ids = new IdentityHashMap<>();
            palette.add(Blocks.AIR.defaultBlockState());
            ids.put(Blocks.AIR.defaultBlockState(), 0);
            toGlobal = new int[regions.size()][];
            for (int r = 0; r < regions.size(); r++) {
                BlockState[] local = regions.get(r).palette();
                toGlobal[r] = new int[local.length];
                for (int i = 0; i < local.length; i++) {
                    BlockState s = local[i];
                    if (s.isAir() || s.is(Blocks.STRUCTURE_VOID)) {
                        toGlobal[r][i] = s.isAir() ? 0 : -1;
                        continue;
                    }
                    Integer id = ids.get(s);
                    if (id == null) {
                        id = palette.size();
                        palette.add(s);
                        ids.put(s, id);
                    }
                    toGlobal[r][i] = id;
                }
            }
            classes = new byte[palette.size()];
            for (int i = 0; i < classes.length; i++) {
                classes[i] = classify(palette.get(i));
            }
        }

        /** As a placement reads it: the first region with a block there wins; structure void is nothing. */
        int id(int x, int y, int z) {
            for (int r = 0; r < regions.size(); r++) {
                int local = regions.get(r).paletteIndexAt(x, y, z);
                if (local >= 0) {
                    int id = toGlobal[r][local];
                    if (id > 0) {
                        return id;
                    }
                }
            }
            return 0;
        }
    }

    private static final Set<Block> PLANTS = Set.of(Blocks.VINE, Blocks.CACTUS, Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.KELP, Blocks.KELP_PLANT,
        Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.GLOW_LICHEN, Blocks.MOSS_CARPET, Blocks.HANGING_ROOTS, Blocks.BIG_DRIPLEAF,
        Blocks.BIG_DRIPLEAF_STEM, Blocks.SPORE_BLOSSOM, Blocks.COCOA, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT, Blocks.PALE_HANGING_MOSS, Blocks.PALE_MOSS_CARPET);
    /** Natural and hardly ever built with. */
    private static final Set<Block> NATURAL = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.BEDROCK, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
        Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
        Blocks.POWDER_SNOW, Blocks.MOSS_BLOCK, Blocks.PALE_MOSS_BLOCK, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.SCULK, Blocks.SNOW, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM);
    /** Natural, but also common building material: decided by where and how thick (see SplitAnalysis). */
    private static final Set<Block> AMBIGUOUS = Set.of(Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.END_STONE, Blocks.CALCITE, Blocks.SNOW_BLOCK, Blocks.OBSIDIAN);
    private static final Set<Block> LEAFY = Set.of(Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK,
        Blocks.SHROOMLIGHT);
    private static final Set<Block> STEMS = Set.of(Blocks.MUSHROOM_STEM, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM);

    static byte classify(BlockState s) {
        if (s.isAir() || s.is(Blocks.STRUCTURE_VOID)) {
            return SplitAnalysis.AIR;
        }
        Block b = s.getBlock();
        if (b instanceof LiquidBlock || b == Blocks.BUBBLE_COLUMN) {
            return SplitAnalysis.FLUID;
        }
        if (s.is(BlockTags.LEAVES) || LEAFY.contains(b)) {
            return SplitAnalysis.LEAVES;
        }
        if (s.is(BlockTags.OVERWORLD_NATURAL_LOGS) || STEMS.contains(b)) {
            return SplitAnalysis.LOG;
        }
        if (b instanceof VegetationBlock || PLANTS.contains(b) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CORALS)) {
            return SplitAnalysis.PLANT;
        }
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.BADLANDS_TERRACOTTA) || s.is(BlockTags.ICE)
            || AMBIGUOUS.contains(b)) {
            return SplitAnalysis.AMBIGUOUS;
        }
        if (s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.ORES) || s.is(BlockTags.NYLIUM) || s.is(BlockTags.CORAL_BLOCKS)
            || NATURAL.contains(b)) {
            return SplitAnalysis.NATURAL;
        }
        if (s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS)) {
            return SplitAnalysis.SHAPE;
        }
        return s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? SplitAnalysis.FULL : SplitAnalysis.DETAIL;
    }
}
