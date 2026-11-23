package com.sputnikboi.slopmatica.render;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.RemovalMarks;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.gui.MaterialPanels;
import com.sputnikboi.slopmatica.placement.Placement;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import net.minecraft.network.chat.Component;

/**
 * Reload Outlines: throws away every hologram mesh and outline and builds them again from what is
 * there now. For leftovers, ghost blocks or outlines that stayed behind after something changed.
 */
public final class ReloadOutlines {
    private ReloadOutlines() {
    }

    public static void run() {
        PlacementManager.INSTANCE.placements().forEach(Placement::refresh);
        HologramRenderer.INSTANCE.reset();
        PlacementManager.INSTANCE.changed();
        Selection.INSTANCE.redraw();
        RemovalMarks.INSTANCE.redraw();
        MaterialPanels.INSTANCE.invalidate();
        SlopmaticaActions.actionBar(Component.translatable("slopmatica.reload_outlines.done").withColor(SlopmaticaActions.ACCENT));
    }
}
