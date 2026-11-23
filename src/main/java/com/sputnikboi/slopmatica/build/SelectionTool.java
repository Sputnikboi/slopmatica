package com.sputnikboi.slopmatica.build;

import java.util.Locale;
import net.minecraft.network.chat.Component;

/** What the selection key (the corner key) does with the block it points at. */
public enum SelectionTool {
    /** Two corners (the original selection). */
    BOX,
    /** A centre, then a block on the edge. */
    SPHERE,
    /** A centre at the bottom or top, then a block on the rim at the other end's height. */
    CYLINDER,
    /** Points around a floor outline; the first point again closes it. Heights span the points'. */
    PRISM,
    /** Hold the key and paint; surface blocks only unless that's switched off. */
    BRUSH,
    /** Every touching block of the same kind. */
    MAGIC;

    public Component label() {
        return Component.translatable("slopmatica.seltool." + name().toLowerCase(Locale.ROOT));
    }

    public Component tooltip() {
        return Component.translatable("slopmatica.seltool." + name().toLowerCase(Locale.ROOT) + ".tooltip");
    }

    public SelectionTool next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** How a new shape goes into the selection. */
    public enum Mode {
        /** It becomes the selection. */
        REPLACE,
        /** It's added to the selection. */
        ADD,
        /** It's cut out of the selection. */
        SUBTRACT;

        public Component label() {
            return Component.translatable("slopmatica.selmode." + name().toLowerCase(Locale.ROOT));
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum BrushShape {
        SPHERE,
        CUBE
    }
}
