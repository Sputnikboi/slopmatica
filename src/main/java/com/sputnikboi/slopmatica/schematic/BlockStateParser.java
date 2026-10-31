package com.sputnikboi.slopmatica.schematic;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Turns palette entries into block states. Entries from older game versions are run through the
 * vanilla DataFixer first, so renamed blocks and properties (e.g. grass -> short_grass) still resolve.
 */
public final class BlockStateParser {
    private final int fromVersion;
    private final int toVersion;
    private final DataFixer fixer;
    private final Set<String> unknownBlocks = new TreeSet<>();

    public BlockStateParser(int fromVersion) {
        this.fromVersion = fromVersion;
        this.toVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        this.fixer = DataFixers.getDataFixer();
    }

    public BlockState parse(CompoundTag entry) {
        CompoundTag tag = upgrade(entry);
        // Litematica writes Name/Properties; 26.3's own block-state codec uses id/properties.
        String name = tag.getString("Name").or(() -> tag.getString("id")).orElse("minecraft:air");
        Identifier id = Identifier.tryParse(name);
        Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            unknownBlocks.add(name);
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = block.get().defaultBlockState();
        Optional<CompoundTag> props = tag.getCompound("Properties").or(() -> tag.getCompound("properties"));
        if (props.isPresent()) {
            StateDefinition<Block, BlockState> definition = block.get().getStateDefinition();
            for (String key : props.get().keySet()) {
                Property<?> property = definition.getProperty(key);
                String value = props.get().getStringOr(key, "");
                if (property != null) {
                    state = withValue(state, property, value);
                }
            }
        }
        return state;
    }

    /** Block ids that exist in the file but not in this game version; they load as air. */
    public Set<String> unknownBlocks() {
        return unknownBlocks;
    }

    private CompoundTag upgrade(CompoundTag entry) {
        if (fromVersion <= 0 || fromVersion >= toVersion) {
            return entry;
        }
        try {
            Tag fixed = fixer.update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, entry), fromVersion, toVersion).getValue();
            return fixed instanceof CompoundTag compound ? compound : entry;
        } catch (RuntimeException e) {
            return entry;
        }
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }
}
