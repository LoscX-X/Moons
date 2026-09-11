package com.blanoir.moons.client.module.framework;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.utils.render.ColorCodec;
import com.blanoir.moons.client.utils.text.NumberText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Central module store and settings API shared by commands, the GUI and the HUD. */
public final class ModuleRegistry {
    private static final List<Module> MODULES = new ArrayList<>();
    private static final Map<String, Module> BY_ID = new LinkedHashMap<>();
    private static final Set<String> HUD_HIDDEN_MODULES =
            Set.of("clickgui", "hud", "xraytargets", "chatprefix");
    private static Runnable catalogRegistration;
    private static boolean catalogInitializing;
    private static volatile boolean catalogInitialized;
    private static List<Module> catalogSnapshot = List.of();
    private static List<HudCandidate> hudCandidates = List.of();
    private static volatile List<Module> enabledSnapshot = List.of();

    private ModuleRegistry() {}

    /** Installs the feature-owned catalog without forcing descriptor creation. */
    public static synchronized void installCatalog(Runnable registration) {
        Objects.requireNonNull(registration, "registration");
        if (catalogInitialized) return;
        if (catalogRegistration != null) {
            throw new IllegalStateException("Module catalog is already installed");
        }
        catalogRegistration = registration;
    }

    /** Registers descriptors on the first catalog-backed API access. */
    public static void ensureCatalog() {
        if (!catalogInitialized) initializeCatalog();
    }

    private static synchronized void initializeCatalog() {
        if (catalogInitialized) {
            return;
        }
        if (catalogInitializing) {
            throw new IllegalStateException("Recursive module catalog access");
        }
        if (catalogRegistration == null) {
            throw new IllegalStateException("Module catalog has not been installed");
        }

        catalogInitializing = true;
        try {
            catalogRegistration.run();
            catalogSnapshot = List.copyOf(MODULES);
            hudCandidates =
                    MODULES.stream()
                            .filter(module -> !HUD_HIDDEN_MODULES.contains(module.id()))
                            .sorted(
                                    Comparator.comparingInt(
                                                    (Module module) -> module.name().length())
                                            .reversed())
                            .map(
                                    module ->
                                            new HudCandidate(
                                                    module, "module." + module.id() + ".hide"))
                            .toList();
            catalogInitialized = true;
            catalogRegistration = null;
        } catch (RuntimeException | Error failure) {
            MODULES.clear();
            BY_ID.clear();
            throw failure;
        } finally {
            catalogInitializing = false;
        }
    }

    public static List<Module> modules() {
        ensureCatalog();
        return catalogSnapshot;
    }

    public static JsonObject snapshot() {
        ensureCatalog();
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonArray categories = new JsonArray();
        for (String name : ModuleCategories.ordered()) {
            categories.add(name);
        }
        root.add("categories", categories);
        JsonArray modules = new JsonArray();
        MODULES.forEach(module -> modules.add(module.toJson()));
        root.add("modules", modules);
        return root;
    }

    public static JsonObject setEnabled(String id, boolean enabled) {
        ensureCatalog();
        Module module = BY_ID.get(normalizeId(id));
        if (module == null) {
            return error("Unknown module: " + id);
        }
        try {
            ClientChat.withoutNoticesOnCurrentThread(
                    () -> module.toggle().apply(Minecraft.getInstance(), enabled));
            return success(module.toJson());
        } catch (RuntimeException exception) {
            return error(messageOf(exception));
        }
    }

    public static JsonObject setValue(String moduleId, String settingId, JsonElement value) {
        ensureCatalog();
        Module module = BY_ID.get(normalizeId(moduleId));
        if (module == null) {
            return error("Unknown module: " + moduleId);
        }
        Setting setting =
                module.settings().stream()
                        .filter(candidate -> candidate.id().equals(normalizeId(settingId)))
                        .findFirst()
                        .orElse(null);
        if (setting == null) {
            return error("Unknown setting: " + settingId);
        }
        if (!setting.isVisible() || !setting.isEnabled()) {
            return error("Setting is not available in the current mode: " + settingId);
        }
        try {
            ClientChat.withoutNoticesOnCurrentThread(
                    () -> setting.apply().apply(Minecraft.getInstance(), value));
            return success(module.toJson());
        } catch (RuntimeException exception) {
            return error(messageOf(exception));
        }
    }

    public static List<Module> enabledModules() {
        ensureCatalog();
        List<Module> previous = enabledSnapshot;
        List<Module> changed = null;
        int count = 0;
        // Keep enabled/visibility reads live, but allocate only when membership changes.
        // Names and catalog order are immutable, so they need sorting only once.
        for (int index = 0; index < hudCandidates.size(); index++) {
            HudCandidate candidate = hudCandidates.get(index);
            Module module = candidate.module();
            if (!safeEnabled(module.enabled()) || Settings.getBoolean(candidate.hiddenKey(), false))
                continue;
            if (changed == null && (count >= previous.size() || previous.get(count) != module)) {
                changed = new ArrayList<>(previous.size() + 1);
                changed.addAll(previous.subList(0, count));
            }
            if (changed != null) changed.add(module);
            count++;
        }
        if (changed != null) {
            List<Module> next = List.copyOf(changed);
            enabledSnapshot = next;
            return next;
        }
        if (count != previous.size()) {
            List<Module> next = List.copyOf(previous.subList(0, count));
            enabledSnapshot = next;
            return next;
        }
        return previous;
    }

    public static Module module(
            String id,
            String name,
            String category,
            BooleanSupplier enabled,
            Toggle toggle,
            Supplier<String> tag,
            Setting... settings) {
        String normalized = normalizeId(id);
        List<Setting> allSettings = new ArrayList<>(List.of(settings));
        allSettings.add(
                bool(
                        "hide",
                        "Hide from HUD",
                        "module." + normalized + ".hide",
                        false,
                        (client, value) -> {
                            Settings.setBoolean("module." + normalized + ".hide", value);
                            return 1;
                        }));
        return new Module(
                normalized, name, category, enabled, toggle, tag, List.copyOf(allSettings));
    }

    private record HudCandidate(Module module, String hiddenKey) {}

    private static boolean hudVisible(Module module) {
        return !HUD_HIDDEN_MODULES.contains(module.id())
                && !Settings.getBoolean("module." + module.id() + ".hide", false);
    }

    public static synchronized void add(Module module) {
        if (!catalogInitializing) {
            throw new IllegalStateException(
                    "Modules may only be added while the catalog initializes");
        }
        if (BY_ID.putIfAbsent(module.id(), module) != null) {
            throw new IllegalStateException("Duplicate module id " + module.id());
        }
        MODULES.add(module);
    }

    public static Setting bool(
            String id, String name, String key, boolean fallback, BoolSetter setter) {
        return customBool(id, name, () -> Settings.getBoolean(key, fallback), setter);
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
                (client, value) -> setter.apply(client, value));
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
                (client, value) -> setter.apply(client, format(value)));
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
                (client, value) -> setter.apply(client, (int) Math.round(value)));
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
                (client, value) -> setter.apply(client, Integer.toString((int) Math.round(value))));
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

    public static Setting range(
            String id,
            String name,
            String minKey,
            String maxKey,
            double minFallback,
            double maxFallback,
            double min,
            double max,
            double step,
            TextSetter setter) {
        return rangeDoubles(
                id,
                name,
                minKey,
                maxKey,
                minFallback,
                maxFallback,
                min,
                max,
                step,
                (client, low, high) -> setter.apply(client, format(low) + "-" + format(high)));
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
                (client, low, high) -> setter.apply(client, (int) low, (int) high));
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
                setter::apply);
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
                () -> Settings.getString(key, fallback).toLowerCase(Locale.ROOT),
                choices,
                setter);
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
                (client, value) -> setter.apply(client, value.getAsString()));
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
                (client, value) -> setter.apply(client, value.getAsString()));
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
        return () -> Settings.getBoolean(key, fallback);
    }

    private static boolean safeEnabled(BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static JsonObject success(JsonElement value) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("value", value);
        return result;
    }

    private static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message);
        return result;
    }

    private static String messageOf(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
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

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
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

    public record Module(
            String id,
            String name,
            String category,
            BooleanSupplier enabled,
            Toggle toggle,
            Supplier<String> tag,
            List<Setting> settings) {
        public String displayText() {
            String currentTag = tag.get();
            return currentTag == null || currentTag.isBlank() ? name : name + " " + currentTag;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("name", name);
            json.addProperty("category", category);
            json.addProperty("enabled", safeEnabled(enabled));
            json.addProperty("hudVisible", hudVisible(this));
            String currentTag = tag.get();
            json.addProperty("tag", currentTag == null ? "" : currentTag);
            JsonArray values = new JsonArray();
            settings.forEach(setting -> values.add(setting.toJson()));
            json.add("settings", values);
            return json;
        }
    }

    public record Setting(
            String id,
            String name,
            String type,
            Supplier<JsonElement> value,
            Double min,
            Double max,
            Double step,
            List<String> options,
            BooleanSupplier visibleWhen,
            BooleanSupplier enabledWhen,
            SettingApply apply) {
        public Setting(
                String id,
                String name,
                String type,
                Supplier<JsonElement> value,
                Double min,
                Double max,
                Double step,
                List<String> options,
                SettingApply apply) {
            this(id, name, type, value, min, max, step, options, () -> true, () -> true, apply);
        }

        public Setting visibleWhen(BooleanSupplier condition) {
            return new Setting(
                    id, name, type, value, min, max, step, options, condition, enabledWhen, apply);
        }

        public Setting enabledWhen(BooleanSupplier condition) {
            return new Setting(
                    id, name, type, value, min, max, step, options, visibleWhen, condition, apply);
        }

        public boolean isVisible() {
            return safeEnabled(visibleWhen);
        }

        public boolean isEnabled() {
            return isVisible() && safeEnabled(enabledWhen);
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("name", name);
            json.addProperty("type", type);
            json.add("value", value.get());
            json.addProperty("visible", isVisible());
            json.addProperty("enabled", isEnabled());
            if (min != null) json.addProperty("min", min);
            if (max != null) json.addProperty("max", max);
            if (step != null) json.addProperty("step", step);
            if (!options.isEmpty()) {
                JsonArray choices = new JsonArray();
                options.forEach(choices::add);
                json.add("options", choices);
            }
            return json;
        }
    }

    @FunctionalInterface
    public interface Toggle {
        int apply(Minecraft ignoredClient, boolean value);
    }

    @FunctionalInterface
    public interface BoolSetter {
        int apply(Minecraft ignoredClient, boolean value);
    }

    @FunctionalInterface
    public interface DoubleSetter {
        int apply(Minecraft ignoredClient, double value);
    }

    @FunctionalInterface
    public interface IntSetter {
        int apply(Minecraft ignoredClient, int value);
    }

    @FunctionalInterface
    public interface TextSetter {
        int apply(Minecraft ignoredClient, String value);
    }

    @FunctionalInterface
    public interface NumericSetter {
        void apply(Minecraft ignoredClient, double value);
    }

    @FunctionalInterface
    public interface IntRangeSetter {
        int apply(Minecraft ignoredClient, int min, int max);
    }

    @FunctionalInterface
    public interface DoubleRangeSetter {
        int apply(Minecraft ignoredClient, double min, double max);
    }

    @FunctionalInterface
    public interface ColorSetter {
        void apply(Minecraft ignoredClient, int[] rgb);
    }

    @FunctionalInterface
    public interface SettingApply {
        void apply(Minecraft ignoredClient, JsonElement value);
    }
}
