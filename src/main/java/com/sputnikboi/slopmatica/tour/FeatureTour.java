package com.sputnikboi.slopmatica.tour;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.SlopmaticaKeys;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A guided walk through the mod (Options → Feature Tour). It loads a demo build next to you and
 * shows one step at a time in a panel; the tour keys ([ and ] by default) go back and forward.
 *
 * <p>Scaffold: the steps below are placeholders until the tour is planned (see the feature
 * breakdown in the notes). A step is a translation key pair {@code slopmatica.tour.<id>.title} /
 * {@code .text}; steps that should wait for the player to do something can grow a check later.
 */
public final class FeatureTour {
    public static final FeatureTour INSTANCE = new FeatureTour();

    /** The demo build, shipped in the jar and copied to schematics/tour/ to load. */
    private static final String DEMO = "/assets/slopmatica/tour/demo.slopmatic";

    private static final List<String> STEPS = List.of("welcome", "hologram", "menu", "build_view", "quick_menu", "materials", "done");

    private static final int WIDTH = 220;

    private boolean active;
    private int step;

    private FeatureTour() {
    }

    public boolean active() {
        return active;
    }

    public void start(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Path file = SlopmaticaActions.schematicsDir().resolve("tour").resolve("demo.slopmatic");
        try (InputStream in = FeatureTour.class.getResourceAsStream(DEMO)) {
            if (in == null) {
                throw new IOException("demo build missing from the jar");
            }
            Files.createDirectories(file.getParent());
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            SlopmaticaActions.actionBar(Component.literal("Couldn't start the tour: " + e.getMessage()).withColor(SlopmaticaActions.ERROR));
            return;
        }
        // A few blocks in front of you, on your level.
        BlockPos at = mc.player.blockPosition().relative(mc.player.getDirection(), 4);
        SlopmaticaActions.load(file, at, SlopmaticaActions::actionBar);
        active = true;
        step = 0;
    }

    public void next() {
        if (active && ++step >= STEPS.size()) {
            stop();
        }
    }

    public void back() {
        if (active) {
            step = Math.max(0, step - 1);
        }
    }

    public void stop() {
        active = false;
    }

    /** The step panel, top centre. */
    public void extract(GuiGraphicsExtractor g, Minecraft mc) {
        if (!active || mc.gui.screen() != null) {
            return;
        }
        String id = STEPS.get(step);
        Component title = Component.translatable("slopmatica.tour.step", step + 1, STEPS.size(), Component.translatable("slopmatica.tour." + id + ".title"));
        List<FormattedCharSequence> text = mc.font.split(Component.translatable("slopmatica.tour." + id + ".text"), WIDTH - 12);
        Component keys = Component.translatable("slopmatica.tour.keys", SlopmaticaKeys.TOUR_BACK.getTranslatedKeyMessage(), SlopmaticaKeys.TOUR_NEXT.getTranslatedKeyMessage());
        int x = g.guiWidth() / 2 - WIDTH / 2, y = 4, h = 30 + text.size() * 10;
        g.fill(x, y, x + WIDTH, y + h, 0xC0101820);
        g.fill(x, y, x + WIDTH, y + 1, 0xFF55CCFF);
        g.text(mc.font, title, x + 6, y + 5, 0xFF55CCFF);
        for (int i = 0; i < text.size(); i++) {
            g.text(mc.font, text.get(i), x + 6, y + 17 + i * 10, 0xFFE0E0E0);
        }
        g.text(mc.font, keys, x + 6, y + h - 11, 0xFF909090);
    }
}
