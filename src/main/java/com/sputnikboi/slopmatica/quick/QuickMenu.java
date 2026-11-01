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

    public enum Mode {
        /** Pick a slot, then left or right click. */
        CLICK,
        /** Runs when you let go of a hotbar number key (scrolling only moves the selection). */
        RELEASE
    }

    private boolean open;
    private int selected;
    private final boolean[] hotbarDown = new boolean[9];
    private final Map<String, Boolean> boundDown = new HashMap<>();

    private QuickMenu() {
    }

    public boolean isOpen() {
        return open;
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
        } else if (mc.player != null && mc.gui.screen() == null) {
            open = true;
            selected = mc.player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                hotbarDown[i] = mc.options.keyHotbarSlots[i].isDown();
            }
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
            mc.gui.setScreen(new QuickMenuEditScreen(null));
            return;
        }
        Mode mode = SlopmaticaConfig.get().quickMode;
        boolean clicked = false;
        while (mc.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        while (mc.options.keyUse.consumeClick()) {
            clicked = true;
        }
        if (clicked && mode != Mode.RELEASE) {
            trigger(mc, selected);
            return;
        }
        if (mode == Mode.RELEASE) {
            for (int i = 0; i < 9; i++) {
                boolean down = mc.options.keyHotbarSlots[i].isDown();
                if (hotbarDown[i] && !down) {
                    trigger(mc, i);
                    return;
                }
                hotbarDown[i] = down;
            }
        }
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
        Component hint = Component.translatable(switch (SlopmaticaConfig.get().quickMode) {
            case CLICK -> "slopmatica.quick.hint.click";
            case RELEASE -> "slopmatica.quick.hint.release";
        }, mc.options.keyInventory.getTranslatedKeyMessage());
        Component line = Component.empty().append(name.copy().withColor(current != null && current.availableNow(mc) ? 0xFFFFFFFF : 0xFFA0A0A0))
            .append(Component.literal("   ")).append(hint.copy().withColor(0xFFA0A0A0));
        int w = mc.font.width(line), ly = y - 38;
        g.fill(cx - w / 2 - 3, ly - 2, cx + w / 2 + 3, ly + 10, 0x90000000);
        g.centeredText(mc.font, line, cx, ly, 0xFFFFFFFF);
    }
}
