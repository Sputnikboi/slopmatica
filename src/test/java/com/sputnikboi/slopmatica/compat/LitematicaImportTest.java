package com.sputnikboi.slopmatica.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sputnikboi.slopmatica.build.PlacementBuilder;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LitematicaImportTest {
    /** Shaped like what malilib writes, with every value at Litematica's default. */
    private static final String DEFAULTS = """
        {
          "Colors": {"schematicOverlayColorMissing": "#2C33B3E6", "schematicOverlayColorWrongBlock": "#4CFF3333", "schematicOverlayColorWrongState": "#4CFF9010"},
          "Generic": {"easyPlaceMode": false, "placementRestriction": false, "pasteReplaceBehavior": "NONE"},
          "Hotkeys": {"openGuiMainMenu": {"keys": "M"}, "toggleAllRendering": {"keys": "M,R"}, "easyPlaceToggle": {"keys": ""}},
          "Visuals": {"enableSchematicBlocksRendering": {"enabled": true, "hotkey": {"keys": ""}}, "ghostBlockAlpha": 0.5,
                      "renderBlocksAsTranslucent": {"enabled": false, "hotkey": {"keys": ""}}, "schematicOverlayOutlineWidth": 1.0,
                      "schematicOverlayTypeMissing": {"enabled": true, "hotkey": {"keys": ""}}}
        }
        """;

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void defaultsAreListedButNotShownUnlessAskedFor() {
        LitematicaImport.Plan plan = LitematicaImport.read(json(DEFAULTS));
        assertTrue(plan.shown(false).isEmpty(), plan.shown(false).toString());
        assertTrue(plan.skipped().size() == 1, plan.skipped().toString()); // the M,R combination
        assertTrue(plan.items().stream().allMatch(i -> i.atDefault));
        assertTrue(plan.shown(true).stream().anyMatch(i -> i.target.equals("ghostOpacity") && i.value.equals(50)
            && i.source.equals("Visuals › ghostBlockAlpha") && i.raw.equals("0.5")));
    }

    @Test
    void changedValuesCarryOver() {
        JsonObject root = json(DEFAULTS);
        root.getAsJsonObject("Generic").addProperty("easyPlaceMode", true);
        root.getAsJsonObject("Generic").addProperty("pasteReplaceBehavior", "WITH_NON_AIR");
        root.getAsJsonObject("Visuals").getAsJsonObject("renderBlocksAsTranslucent").addProperty("enabled", true);
        root.getAsJsonObject("Visuals").addProperty("ghostBlockAlpha", 0.3);
        root.getAsJsonObject("Visuals").getAsJsonObject("schematicOverlayTypeMissing").addProperty("enabled", false);
        root.getAsJsonObject("Colors").addProperty("schematicOverlayColorWrongBlock", "#80D55E00");
        root.getAsJsonObject("Hotkeys").add("openGuiMainMenu", json("{\"keys\": \"KP_5\"}"));
        root.getAsJsonObject("Hotkeys").addProperty("easyPlaceToggle", "LEFT_ALT"); // Old files: a bare string.

        LitematicaImport.Plan plan = LitematicaImport.read(root);
        SlopmaticaConfig config = new SlopmaticaConfig();
        config.solidGhosts = true;
        java.util.List<LitematicaImport.Item> changed = plan.shown(false);
        changed.stream().filter(i -> i.kind != LitematicaImport.Kind.KEY).forEach(i -> i.applyTo(config));

        assertTrue(config.easyPlace);
        assertEquals(PlacementBuilder.Replace.WITH_NON_AIR, config.pasteReplace);
        assertFalse(config.solidGhosts);
        assertEquals(0.3F, config.ghostOpacity, 1e-6);
        assertFalse(config.outlineMissing);
        assertEquals(0xFFD55E00, config.colorFor(BlockStatus.WRONG));
        Map<String, Object> keys = new java.util.HashMap<>();
        changed.stream().filter(i -> i.kind == LitematicaImport.Kind.KEY).forEach(i -> keys.put(i.target, i.value));
        assertEquals(Map.of("key.slopmatica.open_menu", "key.keyboard.keypad.5", "key.slopmatica.toggle_easy_place", "key.keyboard.left.alt"), keys);
        assertTrue(changed.stream().noneMatch(i -> i.atDefault));
    }

    @Test
    void keyCombinationsAreSkippedWithANote() {
        JsonObject root = json(DEFAULTS);
        root.getAsJsonObject("Hotkeys").add("toggleSchematicBlockRendering", json("{\"keys\": \"LEFT_CONTROL,G\"}"));
        LitematicaImport.Plan plan = LitematicaImport.read(root);
        assertTrue(plan.items().stream().noneMatch(i -> i.target.equals("key.slopmatica.toggle_ghosts")));
        assertTrue(plan.skipped().stream().anyMatch(n -> n.contains("LEFT_CONTROL + G")), plan.skipped().toString());
    }

    @Test
    void missingSectionsAreFine() {
        assertTrue(LitematicaImport.read(json("{}")).items().isEmpty());
        assertTrue(LitematicaImport.read(json("{\"Visuals\": 3, \"Generic\": {\"easyPlaceMode\": \"yes\"}}")).items().isEmpty());
    }

    @Test
    void malilibKeyNamesMapToVanilla() {
        assertEquals("key.keyboard.m", LitematicaImport.vanillaKeyName("M"));
        assertEquals("key.keyboard.page.up", LitematicaImport.vanillaKeyName("PAGE_UP"));
        assertEquals("key.keyboard.left.control", LitematicaImport.vanillaKeyName("LEFT_CONTROL"));
        assertEquals("key.keyboard.keypad.multiply", LitematicaImport.vanillaKeyName("KP_MULTIPLY"));
        assertEquals("key.keyboard.keypad.subtract", LitematicaImport.vanillaKeyName("KP_MINUS"));
        assertEquals("key.keyboard.enter", LitematicaImport.vanillaKeyName("RETURN"));
        assertEquals("key.mouse.right", LitematicaImport.vanillaKeyName("MOUSE_RIGHT"));
        assertEquals("key.keyboard.f6", LitematicaImport.vanillaKeyName("F6"));
    }
}
