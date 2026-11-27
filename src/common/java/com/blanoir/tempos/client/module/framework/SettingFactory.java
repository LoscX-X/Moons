package com.blanoir.tempos.client.module.framework;

import static com.blanoir.tempos.client.module.framework.ModuleModel.normalizeId;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleModel.BoolSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.ColorSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.DoubleRangeSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.DoubleSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.IntRangeSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.IntSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.NumericSetter;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.ModuleModel.SettingApply;
import com.blanoir.tempos.client.module.framework.ModuleModel.TextSetter;
import com.blanoir.tempos.client.utils.render.ColorCodec;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Builds settings with their stored defaults, ranges, and presentation. */
public final class SettingFactory {
    private SettingFactory() {}

    public static Setting bool(
            String id, String name, String key, boolean fallback, BoolSetter setter) {
        return customBool(id, name, () -> Settings.getBoolean(key, fallback), setter)
                .withDefault(fallback);
    }

    public static Setting customBool(
            String id, String name, BooleanSupplier getter, BoolSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                "boolean",
                () -> new JsonPrimitive(getter.getAsBoolean()),
                null,
                null,
                null,
                List.of(),
                (client, value) -> setter.apply(client, value.getAsBoolean()));
    }

    public static Setting number(
            String id,
            String name,
            String key,
            double fallback,
            double min,
            double max,
            double step,
            DoubleSetter setter) {
        return numeric(
                        id,
                        name,
                        "number",
                        () -> Settings.getDouble(key, fallback),
                        min,
                        max,
                        step,
                        (client, value) -> setter.apply(client, value))
                .withDefault(fallback);
    }

    public static Setting numberText(
            String id,
            String name,
            String key,
            double fallback,
            double min,
            double max,
            double step,
            TextSetter setter) {
        return numeric(
                        id,
                        name,
                        "number",
                        () -> Settings.getDouble(key, fallback),
                        min,
                        max,
                        step,
                        (client, value) -> setter.apply(client, format(value)))
                .withDefault(fallback);
    }

    public static Setting integer(
            String id,
            String name,
            String key,
            int fallback,
            int min,
            int max,
            int step,
            IntSetter setter) {
        return numeric(
                        id,
                        name,
                        "integer",
                        () -> Settings.getInt(key, fallback),
                        min,
                        max,
                        step,
                        (client, value) -> setter.apply(client, (int) Math.round(value)))
                .withDefault(fallback);
    }

    public static Setting integerText(
            String id,
            String name,
            String key,
            int fallback,
            int min,
            int max,
            int step,
            TextSetter setter) {
        return numeric(
                        id,
                        name,
                        "integer",
                        () -> Settings.getInt(key, fallback),
                        min,
                        max,
                        step,
                        (client, value) ->
                                setter.apply(client, Integer.toString((int) Math.round(value))))
                .withDefault(fallback);
    }

    public static Setting numeric(
            String id,
            String name,
            String type,
            Supplier<Number> getter,
            double min,
            double max,
            double step,
            NumericSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                type,
                () -> new JsonPrimitive(getter.get()),
                min,
                max,
                step,
                List.of(),
                (client, value) -> {
                    double parsed = value.getAsDouble();
                    if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
                        throw new IllegalArgumentException(
                                name + " must be between " + min + " and " + max);
                    }
                    setter.apply(client, parsed);
                });
    }

    public static DoubleRangeSetter formattedRange(TextSetter setter) {
        return (client, low, high) -> setter.apply(client, format(low) + "-" + format(high));
    }

    public static Setting rangeInts(
            String id,
            String name,
            String minKey,
            String maxKey,
            int minFallback,
            int maxFallback,
            int min,
            int max,
            int step,
            IntRangeSetter setter) {
        return rangeValue(
                        id,
                        name,
                        () -> Settings.getInt(minKey, minFallback),
                        () -> Settings.getInt(maxKey, maxFallback),
                        min,
                        max,
                        step,
                        (client, low, high) -> setter.apply(client, (int) low, (int) high))
                .withDefault(minFallback, maxFallback);
    }

    public static Setting rangeDoubles(
            String id,
            String name,
            String minKey,
            String maxKey,
            double minFallback,
            double maxFallback,
            double min,
            double max,
            double step,
            DoubleRangeSetter setter) {
        return rangeValue(
                        id,
                        name,
                        () -> Settings.getDouble(minKey, minFallback),
                        () -> Settings.getDouble(maxKey, maxFallback),
                        min,
                        max,
                        step,
                        setter::apply)
                .withDefault(minFallback, maxFallback);
    }

    public static Setting rangeValue(
            String id,
            String name,
            Supplier<Number> lowGetter,
            Supplier<Number> highGetter,
            double min,
            double max,
            double step,
            DoubleRangeSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                "range",
                () -> {
                    JsonArray array = new JsonArray();
                    array.add(lowGetter.get());
                    array.add(highGetter.get());
                    return array;
                },
                min,
                max,
                step,
                List.of(),
                (client, value) -> {
                    if (!value.isJsonArray() || value.getAsJsonArray().size() != 2) {
                        throw new IllegalArgumentException(name + " requires [min,max]");
                    }
                    double low = value.getAsJsonArray().get(0).getAsDouble();
                    double high = value.getAsJsonArray().get(1).getAsDouble();
                    if (!Double.isFinite(low)
                            || !Double.isFinite(high)
                            || low > high
                            || low < min
                            || high > max) {
                        throw new IllegalArgumentException("Invalid " + name + " range");
                    }
                    setter.apply(client, low, high);
                });
    }

    public static Setting choice(
            String id,
            String name,
            String key,
            String fallback,
            List<String> choices,
            TextSetter setter) {
        return customChoice(
                        id,
                        name,
                        () -> {
                            String value =
                                    Settings.getString(key, fallback).toLowerCase(Locale.ROOT);
                            return choices.contains(value) ? value : fallback;
                        },
                        choices,
                        setter)
                .withDefault(fallback);
    }

    public static Setting customChoice(
            String id,
            String name,
            Supplier<String> getter,
            List<String> choices,
            TextSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                "choice",
                () -> new JsonPrimitive(getter.get().toLowerCase(Locale.ROOT)),
                null,
                null,
                null,
                choices,
                (client, value) -> {
                    String parsed = value.getAsString().toLowerCase(Locale.ROOT);
                    if (!choices.contains(parsed)) {
                        throw new IllegalArgumentException("Invalid " + name + ": " + parsed);
                    }
                    setter.apply(client, parsed);
                });
    }

    public static Setting text(
            String id, String name, String key, String fallback, TextSetter setter) {
        return new Setting(
                        normalizeId(id),
                        name,
                        "text",
                        () -> new JsonPrimitive(Settings.getString(key, fallback)),
                        null,
                        null,
                        null,
                        List.of(),
                        (client, value) -> setter.apply(client, value.getAsString()))
                .withDefault(fallback);
    }

    public static Setting text(String id, String name, Supplier<String> getter, TextSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                "text",
                () -> new JsonPrimitive(getter.get()),
                null,
                null,
                null,
                List.of(),
                (client, value) -> setter.apply(client, value.getAsString()));
    }

    public static Setting colorText(
            String id, String name, String key, String fallback, TextSetter setter) {
        return new Setting(
                        normalizeId(id),
                        name,
                        "color",
                        () -> new JsonPrimitive(Settings.getString(key, fallback)),
                        null,
                        null,
                        null,
                        List.of(),
                        (client, value) -> setter.apply(client, value.getAsString()))
                .withDefault(fallback);
    }

    public static Setting color(
            String id, String name, Supplier<String> getter, ColorSetter setter) {
        return new Setting(
                normalizeId(id),
                name,
                "color",
                () -> new JsonPrimitive(toHex(getter.get())),
                null,
                null,
                null,
                List.of(),
                (client, value) -> setter.apply(client, parseColor(value.getAsString())));
    }

    public static BooleanSupplier cfgBool(String key, boolean fallback) {
        return new ConfiguredBoolean(key, fallback);
    }

    private record ConfiguredBoolean(String key, boolean defaultValue) implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return Settings.getBoolean(key, defaultValue);
        }
    }

    static boolean defaultEnabled(BooleanSupplier enabled) {
        return enabled instanceof ConfiguredBoolean configured && configured.defaultValue();
    }

    /** Formats serialized choice IDs for UI display without changing their persisted value. */
    public static String displayChoice(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder(value.length());
        boolean capitalize = true;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetterOrDigit(codePoint)) {
                capitalize = true;
                continue;
            }
            result.appendCodePoint(capitalize ? Character.toTitleCase(codePoint) : codePoint);
            capitalize = false;
        }
        return result.toString();
    }

    public static String rangeText(String minKey, String maxKey, double min, double max) {
        return format(Settings.getDouble(minKey, min))
                + "-"
                + format(Settings.getDouble(maxKey, max));
    }

    private static String format(double value) {
        return Math.rint(value) == value
                ? Long.toString(Math.round(value))
                : NumberText.trimmedDecimal(value, 2);
    }

    public static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String part : id.split("_")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static int[] parseColor(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT).replace("#", "");
        if (value.length() != 6 || !value.matches("[0-9a-f]{6}")) {
            throw new IllegalArgumentException("Color must be #RRGGBB");
        }
        int rgb = Integer.parseInt(value, 16);
        return ColorCodec.rgbChannels(rgb);
    }

    private static String toHex(String raw) {
        String digits = raw.replaceAll("[^0-9,]", "");
        String[] parts = digits.split(",");
        if (parts.length == 3) {
            try {
                return ColorCodec.formatRgb(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        return "#ffffff";
    }

    public static Setting registryList(
            String id,
            String name,
            String registry,
            Supplier<JsonElement> getter,
            SettingApply setter,
            JsonArray defaults) {
        return new Setting(
                        id, name, registry + "_list", getter, null, null, null, List.of(), setter)
                .withDefault(defaults);
    }

    public static JsonObject entry(String id, String color) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        if (color != null) {
            entry.addProperty("color", color);
            entry.addProperty("enabled", true);
        }
        return entry;
    }

    public static JsonArray entityIds(java.util.Collection<Identifier> ids) {
        JsonArray result = new JsonArray();
        ids.forEach(id -> result.add(entry(id.toString(), null)));
        return result;
    }

    public static java.util.Set<Identifier> readEntityIds(JsonElement value) {
        if (!valid("mob_list", value)) throw new IllegalArgumentException("Invalid entity list");
        var result = new java.util.LinkedHashSet<Identifier>();
        value.getAsJsonArray()
                .forEach(
                        entry ->
                                result.add(
                                        Identifier.parse(
                                                entry.getAsJsonObject().get("id").getAsString())));
        return result;
    }

    public static java.util.Set<Identifier> allMobIds() {
        var result = new java.util.LinkedHashSet<Identifier>();
        BuiltInRegistries.ENTITY_TYPE.forEach(
                type -> {
                    if (type.getCategory() != net.minecraft.world.entity.MobCategory.MISC)
                        result.add(BuiltInRegistries.ENTITY_TYPE.getKey(type));
                });
        return result;
    }

    public static boolean valid(String type, JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        var ids = new java.util.HashSet<Identifier>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) return false;
            JsonObject entry = element.getAsJsonObject();
            JsonElement id = entry.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString())
                return false;
            Identifier key = Identifier.tryParse(id.getAsString());
            if (key == null || !ids.add(key)) return false;
            boolean exists =
                    switch (type) {
                        case "item_list" -> BuiltInRegistries.ITEM.getOptional(key).isPresent();
                        case "block_list" -> BuiltInRegistries.BLOCK.getOptional(key).isPresent();
                        case "entity_list", "mob_list" ->
                                BuiltInRegistries.ENTITY_TYPE.getOptional(key).isPresent();
                        default -> false;
                    };
            if (!exists) return false;
            if (type.equals("mob_list") && key.toString().equals("minecraft:player")) return false;
            if (!type.equals("item_list") && !type.equals("mob_list")) {
                JsonElement color = entry.get("color");
                JsonElement enabled = entry.get("enabled");
                if (color == null
                        || !color.isJsonPrimitive()
                        || !color.getAsJsonPrimitive().isString()
                        || !color.getAsString().matches("#[0-9a-fA-F]{6}")
                        || enabled == null
                        || !enabled.isJsonPrimitive()
                        || !enabled.getAsJsonPrimitive().isBoolean()) return false;
            }
        }
        return true;
    }
}
