package com.sputnikboi.slopmatica.quick;

import java.util.List;

/**
 * The default Quick Menu layout, kept apart from {@link QuickAction} (which loads items and
 * screens) so the settings can be created before the game's registries exist, as in tests.
 */
public final class QuickLayout {
    public static final List<String> DEFAULT = List.of("build_view", "easy_place", "plan", "undo", "menu", "materials", "", "", "");

    private QuickLayout() {
    }
}
