package com.sputnikboi.slopmatica.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes the block colour table the Slopmatica Cloud website's 3D view uses: every block's map
 * colour (what vanilla maps draw it as), or null for blocks maps leave clear (glass, air...).
 * Run with {@code ./gradlew test --tests '*BlockColorTableTool*' -Dslopmatica.colors=<out.json>}.
 */
class BlockColorTableTool {
    @Test
    @EnabledIfSystemProperty(named = "slopmatica.colors", matches = ".+")
    void write() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, String> colors = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            MapColor color = block.defaultBlockState().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            colors.put(id, color == MapColor.NONE ? null : String.format("#%06x", color.col & 0xFFFFFF));
        }
        StringBuilder json = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : colors.entrySet()) {
            json.append("  \"").append(e.getKey()).append("\": ").append(e.getValue() == null ? "null" : "\"" + e.getValue() + "\"");
            json.append(++i < colors.size() ? ",\n" : "\n");
        }
        json.append("}\n");
        Files.writeString(Path.of(System.getProperty("slopmatica.colors")), json);
    }
}
