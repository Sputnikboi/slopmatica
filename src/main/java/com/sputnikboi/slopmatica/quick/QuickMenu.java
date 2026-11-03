package com.sputnikboi.slopmatica.quick;

import com.mojang.blaze3d.platform.InputConstants;
import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.gui.QuickMenuEditScreen;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * The Quick Menu: one key swaps the hotbar for action icons. Pick a slot as usual (scroll or a
 * number key) and click to run it, or, by option, run it on letting go of the number key. It
 * stays open until the key is pressed again, unless set to close after use. E while it is open
 * customises it. The menu keeps its own selection: the item in your hand doesn't change.
 *
 * <p>Any action can also have a key of its own ({@link SlopmaticaConfig#quickKeys}), set in the
 * Keys in Controls screen; those are watched here too.
 */
public final class QuickMenu {
    public static final QuickMenu INSTANCE = new QuickMenu();

    private static final Identifier HOTBAR = Identifier.withDefaultNamespace("hud/hotbar");
    private static final Identifier SELECTION = Identifier.withDefaultNamespace("hud/hotbar_selection");

    /** What the Quick Menu key opens. */
    public enum Style {
        HOTBAR,
        WHEEL
    }

    public enum Mode {
        /** Pick a slot, then left or right click. */
        CLICK,
        /** Hold the Quick Menu key; letting go runs what is selected (on the wheel: nothing from the middle). */
        RELEASE
    }

    private boolean open;
    private int selected;
    private final Map<String, Boolean> boundDown = new HashMap<>();

    private QuickMenu() {
    }

    public boolean isOpen() {
        return open;
    }

    /** Whether clicks, scrolling and the inventory key belong to the Quick Menu or the wheel right now. */
    public static boolean capturing() {
        return INSTANCE.open || ActionWheel.INSTANCE.isOpen();
    }

    public int selected() {
        return selected;
    }

    /** Scrolling while open moves this instead of the hotbar slot (see MouseHandlerQuickMenuMixin). */
    public void select(int slot) {
        selected = Math.floorMod(slot, 9);
    }

    public static List<String> layout() {
        List<String> layout = SlopmaticaConfig.get().quickMenu;
        return layout == null || layout.size() != 9 ? QuickAction.DEFAULT_LAYOUT : layout;
    }

    public void toggle(Minecraft mc) {
        if (open) {
            close(mc);
        } else if (ActionWheel.INSTANCE.isOpen() || SlopmaticaConfig.get().quickStyle == Style.WHEEL) {
            ActionWheel.INSTANCE.toggle(mc);
        } else if (mc.player != null && mc.gui.screen() == null) {
            open = true;
            selected = mc.player.getInventory().getSelectedSlot();
        }
    }

    public void close(Minecraft mc) {
        if (!open) {
            return;
        }
        open = false;
    }

    /** Start of the client tick, before vanilla handles clicks and the inventory key. */
    public void tick(Minecraft mc) {
        if (!open) {
            return;
        }
        if (mc.player == null) {
            open = false;
            return;
        }
        if (mc.gui.screen() != null) {
            return;
        }
        // Number keys pick a slot here, before vanilla would switch the item in your hand.
        for (int i = 0; i < 9; i++) {
            while (mc.options.keyHotbarSlots[i].consumeClick()) {
                selected = i;
            }
        }
        while (mc.options.keyInventory.consumeClick()) {
            mc.gui.setScreen(new QuickMenuEditScreen(null, false));
            return;
        }
        if (SlopmaticaConfig.get().quickMode == Mode.RELEASE && !com.sputnikboi.slopmatica.SlopmaticaKeys.QUICK_MENU.isDown()) {
            // Hold to use: letting go of the key runs the selected slot.
            int slot = selected;
            close(mc);
            run(mc, QuickAction.get(layout().get(slot)));
            return;
        }
        boolean clicked = false;
        while (mc.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        while (mc.options.keyUse.consumeClick()) {
            clicked = true;
        }
        if (clicked) {
            trigger(mc, selected);
        }
    }

    /** Runs an action now (or says why it can't); the caller has dealt with closing. */
    static void run(Minecraft mc, @org.jspecify.annotations.Nullable QuickAction action) {
        if (action == null) {
            return;
        }
        if (!action.availableNow(mc)) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.quick.unavailable", action.label().get()).withColor(SlopmaticaActions.ERROR));
            return;
        }
        action.run().accept(mc);
    }

    private void trigger(Minecraft mc, int slot) {
        QuickAction action = QuickAction.get(layout().get(slot));
        if (action == null) {
            return;
        }
        if (!action.availableNow(mc)) {
            SlopmaticaActions.actionBar(Component.translatable("slopmatica.quick.unavailable", action.label().get()).withColor(SlopmaticaActions.ERROR));
            return;
        }
        if (SlopmaticaConfig.get().quickCloseAfterUse) {
            close(mc); // First, so a screen the action opens isn't fighting the menu.
        }
        action.run().accept(mc);
    }

    /** End of the client tick: keys bound to single actions (Keys in Controls). */
    public void tickBindings(Minecraft mc) {
        Map<String, String> keys = SlopmaticaConfig.get().quickKeys;
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : keys.entrySet()) {
            InputConstants.Key key;
            try {
                key = InputConstants.getKey(e.getValue());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            boolean down = key.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(key.getValue());
            boolean was = boundDown.getOrDefault(e.getKey(), false);
            boundDown.put(e.getKey(), down);
            if (down && !was && mc.gui.screen() == null && mc.player != null) {
                QuickAction action = QuickAction.get(e.getKey());
                if (action != null && action.availableNow(mc)) {
                    action.run().accept(mc);
                }
            }
        }
    }

    /** Drawn instead of the hotbar while open. */
    public void extractHotbar(GuiGraphicsExtractor g, Minecraft mc) {
        int cx = g.guiWidth() / 2, y = g.guiHeight() - 22;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, HOTBAR, cx - 91, y, 182, 22);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, cx - 92 + selected * 20, y - 1, 24, 23);
        List<String> layout = layout();
        for (int i = 0; i < Inventory.getSelectionSize() && i < layout.size(); i++) {
            QuickAction action = QuickAction.get(layout.get(i));
            if (action == null) {
                continue;
            }
            int ix = cx - 88 + i * 20, iy = y + 3;
            g.item(action.icon().get(), ix, iy);
            if (!action.availableNow(mc)) {
                g.fill(ix, iy, ix + 16, iy + 16, 0xB0303030);
            }
            if (action.state() != null) {
                g.fill(ix + 12, iy, ix + 16, iy + 4, action.state().get() ? 0xFF50E050 : 0xFFE05050);
            }
        }
        // One line above the hearts and armour: the selected action, then how to use the menu.
        QuickAction current = QuickAction.get(layout.get(selected));
        Component name = current == null ? Component.translatable("slopmatica.quick.empty") : current.label().get();
        Component hint = switch (SlopmaticaConfig.get().quickMode) {
            case CLICK -> Component.translatable("slopmatica.quick.hint.click", mc.options.keyInventory.getTranslatedKeyMessage());
            case RELEASE -> Component.translatable("slopmatica.quick.hint.release", com.sputnikboi.slopmatica.SlopmaticaKeys.QUICK_MENU.getTranslatedKeyMessage(),
                mc.options.keyInventory.getTranslatedKeyMessage());
        };
        Component line = Component.empty().append(name.copy().withColor(current != null && current.availableNow(mc) ? 0xFFFFFFFF : 0xFFA0A0A0))
            .append(Component.literal("   ")).append(hint.copy().withColor(0xFFA0A0A0));
        int w = mc.font.width(line), ly = y - 38;
        g.fill(cx - w / 2 - 3, ly - 2, cx + w / 2 + 3, ly + 10, 0x90000000);
        g.centeredText(mc.font, line, cx, ly, 0xFFFFFFFF);
    }
}
