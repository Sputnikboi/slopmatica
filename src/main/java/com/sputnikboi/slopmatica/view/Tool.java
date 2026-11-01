package com.sputnikboi.slopmatica.view;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** What the mouse does in the placement view, Axiom-style. */
public enum Tool {
    /** Pick up / drag the build (the default). */
    MOVE,
    /** Click two blocks to set the selection's corners. */
    SELECT,
    /** Drag a face of the selection box to grow or shrink it. */
    MODIFY,
    /** Drag a face of the build's box so only part of it shows. */
    CROP;

    public Component label() {
        return Component.translatable("slopmatica.tool." + name().toLowerCase(Locale.ROOT));
    }

    public Component tooltip() {
        return Component.translatable("slopmatica.tool." + name().toLowerCase(Locale.ROOT) + ".tooltip");
    }

    public Tool next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
