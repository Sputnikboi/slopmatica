package com.sputnikboi.slopmatica.quick;

import java.util.List;

/**
 * The default Quick Menu layout, kept apart from {@link QuickAction} (which loads items and
 * screens) so the settings can be created before the game's registries exist, as in tests.
 */
public final class QuickLayout {
    public static final List<String> DEFAULT = List.of("build_view", "easy_place", "plan", "undo", "menu", "materials", "", "", "");

    /** Slots on each page of the action wheel, clockwise from the top. */
    public static final int WHEEL_SLOTS = 8;

    public static final List<List<String>> WHEEL_DEFAULT = List.of(
        List.of("build_view", "easy_place", "plan", "undo", "menu", "materials", "hologram", "spawn_check"),
        List.of("layer_mode", "layer_up", "layer_down", "corner", "fit_selection", "copy", "paste", "save_selection"));

    private QuickLayout() {
    }
}
