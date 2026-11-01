package com.sputnikboi.slopmatica.quick;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.build.Clipboard;
import com.sputnikboi.slopmatica.build.EasyPlace;
import com.sputnikboi.slopmatica.build.Selection;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.CloudScreen;
import com.sputnikboi.slopmatica.gui.InventoryPlanScreen;
import com.sputnikboi.slopmatica.gui.MaterialListScreen;
import com.sputnikboi.slopmatica.gui.PlacementsScreen;
import com.sputnikboi.slopmatica.gui.SaveSelectionScreen;
import com.sputnikboi.slopmatica.gui.SchematicBrowserScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaKeyBindsScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaMenuScreen;
import com.sputnikboi.slopmatica.gui.SlopmaticaOptionsScreen;
import com.sputnikboi.slopmatica.placement.Layers;
import com.sputnikboi.slopmatica.placement.PlacementManager;
import com.sputnikboi.slopmatica.plan.InventoryPlanner;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.spawn.SpawnCheck;
import com.sputnikboi.slopmatica.view.PlacementView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Something the Quick Menu (or a key of its own) can do. Built-in actions have fixed ids; setting
 * toggles are made by the player ({@link SettingToggle}, id {@code toggle:...}). New features
 * should add an action here so they can be put in the Quick Menu.
 *
 * @param standard  part of the default Quick Menu
 * @param available null when it can always run; otherwise whether it can now (greyed out if not)
 * @param state     optional on/off to show on the icon (Easy Place, spawn check...)
 */
public record QuickAction(String id, Supplier<Component> label, Supplier<ItemStack> icon, Consumer<Minecraft> run, boolean standard,
                          @Nullable Predicate<Minecraft> available, @Nullable Supplier<Boolean> state) {

    private static final Map<String, QuickAction> BUILT_IN = new LinkedHashMap<>();

    public static final List<String> DEFAULT_LAYOUT = QuickLayout.DEFAULT;

    static {
        // The default set.
        add("build_view", Items.SPYGLASS, mc -> PlacementView.INSTANCE.toggle(mc), true, null, () -> PlacementView.INSTANCE.active());
        add("easy_place", Items.BRICKS, mc -> EasyPlace.INSTANCE.toggle(), true, null, () -> EasyPlace.INSTANCE.enabled());
        add("plan", Items.CHEST, mc -> InventoryPlanner.INSTANCE.calculate(mc), true, mc -> !PlacementManager.INSTANCE.placements().isEmpty(), null);
        add("undo", Items.CLOCK, mc -> PlacementView.INSTANCE.undo(mc), true, null, null);
        add("menu", Items.BOOK, mc -> mc.gui.setScreen(new SlopmaticaMenuScreen(null)), true, null, null);
        add("materials", Items.PAPER, mc -> PlacementManager.INSTANCE.selected().ifPresent(p -> mc.gui.setScreen(new MaterialListScreen(null, p))), true,
            mc -> PlacementManager.INSTANCE.selected().isPresent(), null);
        // Optional ones, for the player to add.
        add("load", Items.MAP, mc -> mc.gui.setScreen(new SchematicBrowserScreen(null, false)), false, null, null);
        add("cloud_browser", Items.FILLED_MAP, mc -> mc.gui.setScreen(new SchematicBrowserScreen(null, true)), false, null, null);
        add("placements", Items.ITEM_FRAME, mc -> mc.gui.setScreen(new PlacementsScreen(null)), false, null, null);
        add("plan_screen", Items.ENDER_CHEST, mc -> mc.gui.setScreen(new InventoryPlanScreen(null)), false, null, null);
        add("options", Items.COMPARATOR, mc -> mc.gui.setScreen(new SlopmaticaOptionsScreen(null)), false, null, null);
        add("keybinds", Items.TRIPWIRE_HOOK, mc -> mc.gui.setScreen(new SlopmaticaKeyBindsScreen(null)), false, null, null);
        add("cloud", Items.PHANTOM_MEMBRANE, mc -> mc.gui.setScreen(new CloudScreen(null)), false, null, null);
        add("spawn_check", Items.ZOMBIE_HEAD, mc -> SpawnCheck.INSTANCE.toggle(mc), false, null, () -> SpawnCheck.INSTANCE.active());
        add("hologram", Items.GLASS, mc -> HologramRenderer.INSTANCE.setHidden(!HologramRenderer.INSTANCE.hidden()), false, null,
            () -> !HologramRenderer.INSTANCE.hidden());
        add("layer_mode", Items.SANDSTONE_SLAB, mc -> {
            Layers.INSTANCE.cycleMode(Layers.startHeight(mc.player != null ? mc.player.getBlockY() : 0));
            SlopmaticaActions.actionBar(com.sputnikboi.slopmatica.SlopmaticaKeys.layersMessage());
        }, false, null, () -> Layers.INSTANCE.mode() != Layers.Mode.ALL);
        add("layer_up", Items.LADDER, mc -> {
            Layers.INSTANCE.move(1);
            SlopmaticaActions.actionBar(com.sputnikboi.slopmatica.SlopmaticaKeys.layersMessage());
        }, false, mc -> Layers.INSTANCE.mode() != Layers.Mode.ALL, null);
        add("layer_down", Items.SCAFFOLDING, mc -> {
            Layers.INSTANCE.move(-1);
            SlopmaticaActions.actionBar(com.sputnikboi.slopmatica.SlopmaticaKeys.layersMessage());
        }, false, mc -> Layers.INSTANCE.mode() != Layers.Mode.ALL, null);
        add("corner", Items.STONE_BUTTON, Selection.INSTANCE::setCorner, false, null, null);
        add("clear_selection", Items.BARRIER, mc -> Selection.INSTANCE.clear(), false, mc -> Selection.INSTANCE.box() != null, null);
        add("fit_selection", Items.SHEARS, mc -> PlacementView.INSTANCE.fitSelection(mc), false, mc -> Selection.INSTANCE.box() != null, null);
        add("copy", Items.WRITABLE_BOOK, mc -> Clipboard.INSTANCE.copy(mc), false, mc -> Selection.INSTANCE.box() != null, null);
        add("save_selection", Items.WRITTEN_BOOK, mc -> mc.gui.setScreen(new SaveSelectionScreen(null)), false, mc -> Selection.INSTANCE.box() != null, null);
        add("paste", Items.SLIME_BALL, mc -> Clipboard.INSTANCE.paste(mc), false, null, null);
    }

    private static void add(String id, net.minecraft.world.item.Item icon, Consumer<Minecraft> run, boolean standard,
                            @Nullable Predicate<Minecraft> available, @Nullable Supplier<Boolean> state) {
        BUILT_IN.put(id, new QuickAction(id, () -> Component.translatable("slopmatica.quick." + id), () -> new ItemStack(icon), run, standard, available, state));
    }

    /** Every action there is: the built-in ones and the player's setting toggles. */
    public static List<QuickAction> all() {
        List<QuickAction> out = new ArrayList<>(BUILT_IN.values());
        for (String id : SlopmaticaConfig.get().quickToggles) {
            QuickAction toggle = get(id);
            if (toggle != null) {
                out.add(toggle);
            }
        }
        return out;
    }

    public static @Nullable QuickAction get(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.startsWith(SettingToggle.PREFIX)) {
            SettingToggle toggle = SettingToggle.parse(id);
            return toggle == null ? null : toggle.action();
        }
        return BUILT_IN.get(id);
    }

    public boolean availableNow(Minecraft mc) {
        return available == null || available.test(mc);
    }
}
