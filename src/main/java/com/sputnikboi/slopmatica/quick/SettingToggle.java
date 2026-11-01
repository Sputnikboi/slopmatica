package com.sputnikboi.slopmatica.quick;

import com.sputnikboi.slopmatica.SlopmaticaActions;
import com.sputnikboi.slopmatica.config.SlopmaticaConfig;
import com.sputnikboi.slopmatica.render.HologramRenderer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * A Quick Menu button that switches one setting between two values (on/off) or steps through a
 * list of them (outline distance 50%, 100%, 200%...). Stored as an id: {@code toggle:<setting>:<a>|<b>|...}.
 *
 * @param values the values in order, as text; empty for a boolean (on/off)
 */
public record SettingToggle(String setting, List<String> values) {
    public static final String PREFIX = "toggle:";

    /** The settings a toggle can switch, with the option label to show. */
    public static final Map<String, String> SETTINGS = new LinkedHashMap<>();

    static {
        SETTINGS.put("showGhosts", "slopmatica.options.show_ghosts");
        SETTINGS.put("solidGhosts", "slopmatica.options.solid_ghosts");
        SETTINGS.put("ghostOpacity", "slopmatica.options.ghost_opacity");
        SETTINGS.put("showOutlines", "slopmatica.options.show_outlines");
        SETTINGS.put("outlineWidth", "slopmatica.options.outline_width");
        SETTINGS.put("outlineDistance", "slopmatica.options.outline_distance");
        SETTINGS.put("fadeOutlines", "slopmatica.options.fade_outlines");
        SETTINGS.put("blockInfo", "slopmatica.options.block_info");
        SETTINGS.put("viewFreecam", "slopmatica.options.view_freecam");
        SETTINGS.put("viewCollision", "slopmatica.options.view_collision");
        SETTINGS.put("easyPlaceOrder", "slopmatica.options.easy_place_order");
        SETTINGS.put("easyPlaceRestrict", "slopmatica.options.easy_place_restrict");
        SETTINGS.put("easyPlaceAngel", "slopmatica.options.easy_place_angel");
        SETTINGS.put("includeNbt", "slopmatica.options.include_nbt");
        SETTINGS.put("pasteReplace", "slopmatica.options.paste_replace");
        SETTINGS.put("copyFormat", "slopmatica.options.copy_format");
        SETTINGS.put("cornerMode", "slopmatica.options.corner_mode");
        SETTINGS.put("planScope", "slopmatica.plan.scope");
        SETTINGS.put("planShowInInventory", "slopmatica.plan.show");
        SETTINGS.put("planSideList", "slopmatica.plan.side");
    }

    public String id() {
        return PREFIX + setting + ":" + String.join("|", values);
    }

    public static @Nullable SettingToggle parse(String id) {
        if (!id.startsWith(PREFIX)) {
            return null;
        }
        String rest = id.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        String setting = colon < 0 ? rest : rest.substring(0, colon);
        if (!SETTINGS.containsKey(setting) || field(setting) == null) {
            return null;
        }
        List<String> values = new ArrayList<>();
        if (colon >= 0) {
            for (String v : rest.substring(colon + 1).split("\\|")) {
                if (!v.isBlank()) {
                    values.add(v.trim());
                }
            }
        }
        return new SettingToggle(setting, values);
    }

    private static @Nullable Field field(String setting) {
        try {
            return SlopmaticaConfig.class.getField(setting);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** The values it steps through: on/off for booleans, every value of an enum unless chosen. */
    public List<String> steps() {
        Field f = field(setting);
        if (!values.isEmpty() || f == null) {
            return values;
        }
        if (f.getType() == boolean.class) {
            return List.of("true", "false");
        }
        if (f.getType().isEnum()) {
            List<String> all = new ArrayList<>();
            for (Object c : f.getType().getEnumConstants()) {
                all.add(((Enum<?>) c).name());
            }
            return all;
        }
        return values;
    }

    /** The current value, as text in the same form as {@link #steps()}. */
    public String current() {
        Field f = field(setting);
        try {
            Object v = f.get(SlopmaticaConfig.get());
            if (v instanceof Float x) {
                return String.valueOf(Math.round(x * 100)); // Opacity and width are shown as percentages.
            }
            return v instanceof Enum<?> e ? e.name() : String.valueOf(v);
        } catch (IllegalAccessException e) {
            return "";
        }
    }

    /** Moves to the next value (the first, if the current one isn't in the list). */
    public void apply() {
        List<String> steps = steps();
        if (steps.isEmpty()) {
            return;
        }
        int at = steps.indexOf(current());
        String next = steps.get((at + 1) % steps.size());
        Field f = field(setting);
        SlopmaticaConfig config = SlopmaticaConfig.get();
        try {
            Class<?> type = f.getType();
            if (type == boolean.class) {
                f.setBoolean(config, Boolean.parseBoolean(next));
            } else if (type == int.class) {
                f.setInt(config, Integer.parseInt(next));
            } else if (type == float.class) {
                f.setFloat(config, Integer.parseInt(next) / 100.0F);
            } else if (type.isEnum()) {
                f.set(config, enumValue(type, next));
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            SlopmaticaActions.actionBar(Component.literal("Can't set " + setting + " to " + next).withColor(SlopmaticaActions.ERROR));
            return;
        }
        config.save();
        HologramRenderer.INSTANCE.rebuildAll(); // Some settings are baked into the meshes.
        SlopmaticaActions.actionBar(label().copy().withColor(SlopmaticaActions.ACCENT));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name.toUpperCase(Locale.ROOT));
    }

    /** "Outline Distance: 100%" and the like. */
    public Component label() {
        Field f = field(setting);
        String v = current();
        Component value;
        if (f != null && f.getType() == boolean.class) {
            value = Component.translatable(Boolean.parseBoolean(v) ? "options.on" : "options.off");
        } else if (f != null && (f.getType() == float.class || setting.equals("outlineDistance"))) {
            value = Component.literal(v + "%");
        } else {
            value = Component.literal(v.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        return Component.translatable("slopmatica.quick.toggle_label", Component.translatable(SETTINGS.get(setting)), value);
    }

    public QuickAction action() {
        Field f = field(setting);
        boolean bool = f != null && f.getType() == boolean.class;
        return new QuickAction(id(), this::label, () -> new ItemStack(Items.LEVER), mc -> apply(), false, null,
            bool ? () -> Boolean.parseBoolean(current()) : null);
    }
}
