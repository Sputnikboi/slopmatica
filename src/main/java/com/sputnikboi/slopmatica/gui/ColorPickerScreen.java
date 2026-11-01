package com.sputnikboi.slopmatica.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import com.sputnikboi.slopmatica.verify.BlockStatus;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Picks one outline colour: a hue/saturation wheel, a brightness slider, a hex field, and a row of
 * colourblind-safe presets (the Okabe-Ito palette), so statuses can be told apart by anyone.
 */
public class ColorPickerScreen extends Screen {
    private static final Identifier WHEEL = Identifier.fromNamespaceAndPath("slopmatica", "color_wheel");
    private static final int WHEEL_TEXTURE = 128;
    private static final int WHEEL_SIZE = 110;
    /** Okabe-Ito: distinguishable with every common kind of colour blindness. */
    private static final int[] PRESETS = {0x0072B2, 0x56B4E9, 0x009E73, 0xF0E442, 0xE69F00, 0xD55E00, 0xCC79A7, 0xFFFFFF, 0x000000};
    private static boolean wheelRegistered;

    private final @Nullable Screen parent;
    private final BlockStatus status;
    private float hue, saturation, brightness;
    private int wheelX, wheelY;
    private boolean draggingWheel;
    private EditBox hex;
    private BrightnessSlider slider;

    public ColorPickerScreen(@Nullable Screen parent, BlockStatus status) {
        super(Component.translatable("slopmatica.color.title", Component.translatable("slopmatica.options.outline_" + status.name().toLowerCase(Locale.ROOT))));
        this.parent = parent;
        this.status = status;
        setRgb(status.color() & 0xFFFFFF);
    }

    @Override
    protected void init() {
        ensureWheel();
        int center = width / 2;
        wheelX = center - WHEEL_SIZE - 10;
        wheelY = 40;
        int right = center + 10;

        slider = addRenderableWidget(new BrightnessSlider(right, wheelY, 150, 20));
        hex = addRenderableWidget(new EditBox(font, right, wheelY + 30, 80, 18, Component.literal("hex")));
        hex.setMaxLength(7);
        hex.setResponder(text -> {
            String t = text.startsWith("#") ? text.substring(1) : text;
            if (t.length() == 6) {
                try {
                    setRgb(Integer.parseInt(t, 16));
                    slider.sync();
                } catch (NumberFormatException ignored) {
                    // Keep typing.
                }
            }
        });
        updateHex();

        int px = right, py = wheelY + 66;
        for (int i = 0; i < PRESETS.length; i++) {
            int rgb = PRESETS[i];
            addRenderableWidget(Button.builder(Component.empty(), b -> {
                setRgb(rgb);
                slider.sync();
                updateHex();
            }).bounds(px + (i % 5) * 26, py + (i / 5) * 26, 22, 22).tooltip(Tooltip.create(Component.literal(String.format("#%06X", rgb)))).build());
        }

        int by = wheelY + WHEEL_SIZE + 24;
        addRenderableWidget(Button.builder(Component.translatable("slopmatica.color.reset"), b -> {
            setRgb(status.defaultColor() & 0xFFFFFF);
            slider.sync();
            updateHex();
        }).bounds(center - 154, by, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> minecraft.gui.setScreen(parent)).bounds(center - 50, by, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            int rgb = rgb();
            SlopmaticaConfig config = SlopmaticaConfig.get();
            config.setColor(status, rgb == (status.defaultColor() & 0xFFFFFF) ? null : 0xFF000000 | rgb);
            config.save();
            HologramRenderer.INSTANCE.rebuildAll();
            minecraft.gui.setScreen(parent);
        }).bounds(center + 54, by, 100, 20).build());
    }

    /** The wheel at full brightness, drawn once; brightness is applied as a tint when blitting. */
    private void ensureWheel() {
        if (wheelRegistered) {
            return;
        }
        NativeImage image = new NativeImage(WHEEL_TEXTURE, WHEEL_TEXTURE, true);
        float r = WHEEL_TEXTURE / 2.0F;
        for (int y = 0; y < WHEEL_TEXTURE; y++) {
            for (int x = 0; x < WHEEL_TEXTURE; x++) {
                float dx = x + 0.5F - r, dy = y + 0.5F - r;
                float dist = (float) Math.sqrt(dx * dx + dy * dy) / r;
                if (dist > 1) {
                    image.setPixel(x, y, 0);
                    continue;
                }
                float h = (float) (Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1;
                image.setPixel(x, y, 0xFF000000 | Mth.hsvToRgb(h, dist, 1.0F));
            }
        }
        minecraft.getTextureManager().register(WHEEL, new DynamicTexture(() -> "Slopmatica colour wheel", image));
        wheelRegistered = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(font, title, width / 2, 16, -1);
        int v = Math.round(brightness * 255);
        graphics.blit(RenderPipelines.GUI_TEXTURED, WHEEL, wheelX, wheelY, 0, 0, WHEEL_SIZE, WHEEL_SIZE, WHEEL_TEXTURE, WHEEL_TEXTURE, WHEEL_TEXTURE, WHEEL_TEXTURE,
            0xFF000000 | v << 16 | v << 8 | v);
        // Marker where the current colour sits on the wheel.
        float rad = WHEEL_SIZE / 2.0F;
        int mx = Math.round(wheelX + rad + Mth.cos(hue * Mth.TWO_PI) * saturation * rad);
        int my = Math.round(wheelY + rad + Mth.sin(hue * Mth.TWO_PI) * saturation * rad);
        graphics.fill(mx - 3, my - 3, mx + 3, my + 3, 0xFF000000);
        graphics.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFF000000 | rgb());

        // Swatches on the presets, then the before/after preview.
        int right = width / 2 + 10, py = wheelY + 66;
        for (int i = 0; i < PRESETS.length; i++) {
            int x = right + (i % 5) * 26, y = py + (i / 5) * 26;
            graphics.fill(x + 3, y + 3, x + 19, y + 19, 0xFF000000 | PRESETS[i]);
        }
        int sx = right + 90, sy = wheelY + 30;
        graphics.fill(sx, sy, sx + 30, sy + 18, status.color());
        graphics.fill(sx + 30, sy, sx + 60, sy + 18, 0xFF000000 | rgb());
        graphics.text(font, Component.translatable("slopmatica.color.before_after"), sx, sy + 21, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (pickFromWheel(event.x(), event.y())) {
            draggingWheel = true;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingWheel) {
            pickFromWheel(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingWheel = false;
        return super.mouseReleased(event);
    }

    private boolean pickFromWheel(double x, double y) {
        double rad = WHEEL_SIZE / 2.0;
        double dx = x - (wheelX + rad), dy = y - (wheelY + rad);
        double dist = Math.sqrt(dx * dx + dy * dy) / rad;
        if (!draggingWheel && dist > 1) {
            return false;
        }
        hue = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1);
        saturation = (float) Math.min(1, dist);
        if (brightness < 0.05F) {
            brightness = 1; // Picking a hue on a black colour should show it.
            slider.sync();
        }
        updateHex();
        return true;
    }

    private int rgb() {
        return Mth.hsvToRgb(hue, saturation, brightness) & 0xFFFFFF;
    }

    private void setRgb(int rgb) {
        float r = (rgb >> 16 & 255) / 255F, g = (rgb >> 8 & 255) / 255F, b = (rgb & 255) / 255F;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        brightness = max;
        saturation = max == 0 ? 0 : (max - min) / max;
        if (max == min) {
            hue = 0;
        } else if (max == r) {
            hue = ((g - b) / (max - min) / 6 + 1) % 1;
        } else if (max == g) {
            hue = ((b - r) / (max - min) + 2) / 6;
        } else {
            hue = ((r - g) / (max - min) + 4) / 6;
        }
    }

    private void updateHex() {
        if (hex != null) {
            String text = String.format("#%06X", rgb());
            if (!hex.getValue().equalsIgnoreCase(text)) {
                hex.setValue(text);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private class BrightnessSlider extends AbstractSliderButton {
        BrightnessSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), brightness);
            updateMessage();
        }

        void sync() {
            value = brightness;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("slopmatica.color.brightness", Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            brightness = (float) value;
            updateHex();
        }
    }
}
