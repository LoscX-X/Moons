package com.blanoir.moons.client.config;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local named module presets. The GUI and commands use the same runtime application path. */
public final class ConfigProfiles {
    public static final String DEFAULT_NAME = "default";
    private static final String SELECTED_KEY = "config.profile";
    private static final String EXTENSION = ".json";

    private ConfigProfiles() {}

    public static Path directory() {
        return Settings.file().getParent().resolve("profiles");
    }

    public static String selected() {
        String name = Settings.getString(SELECTED_KEY, "").trim();
        return name.isEmpty() || name.equalsIgnoreCase(DEFAULT_NAME) ? DEFAULT_NAME : name;
    }

    public static List<String> list() throws IOException {
        ensureDefault();
        List<String> result = new ArrayList<>();
        result.add(DEFAULT_NAME);
        try (var files = Files.list(directory())) {
            result.addAll(
                    files.filter(Files::isRegularFile)
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.endsWith(EXTENSION))
                            .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                            .filter(ConfigProfiles::validName)
                            .filter(name -> !name.equalsIgnoreCase(DEFAULT_NAME))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList());
        }
        return List.copyOf(result);
    }

    public static void create(String name) throws IOException {
        Path target = path(name);
        if (Files.exists(target)) throw new IOException("Config already exists: " + name);
        writeSnapshot(target, defaults(), false);
        load(name);
    }

    public static void save(String name) throws IOException {
        Path target = path(name);
        ensureDefault();
        if (!Files.isRegularFile(target)) throw new IOException("Config does not exist: " + name);
        writeSnapshot(target, capture(), true);
        Settings.setString(SELECTED_KEY, canonicalName(name));
    }

    /** The built-in preset is created from declared defaults, never live state. */
    private static void ensureDefault() throws IOException {
        Path target = path(DEFAULT_NAME);
        if (!Files.exists(target)) writeSnapshot(target, defaults(), false);
    }

    private static void writeSnapshot(Path target, JsonObject snapshot, boolean replace)
            throws IOException {
        Files.createDirectories(directory());
        Path temporary = Files.createTempFile(directory(), ".config-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    new GsonBuilder().setPrettyPrinting().create().toJson(snapshot),
                    StandardCharsets.UTF_8);
            if (replace) {
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void load(String name) throws IOException {
        Path source = path(name);
        ensureDefault();
        if (!Files.isRegularFile(source)) throw new IOException("Config does not exist: " + name);
        if (Files.size(source) > 2_000_000) throw new IOException("Config is too large");
        final Plan target;
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            target = plan(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("Invalid config: " + failure.getMessage(), failure);
        }
        JsonObject snapshot = capture();
        Plan previous = plan(snapshot);
        Settings.beginBatch();
        try {
            apply(target);
            Settings.setString(SELECTED_KEY, canonicalName(name));
        } catch (RuntimeException failure) {
            try {
                apply(previous);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new IOException(
                        "Config failed; some settings could not be restored", failure);
            }
            throw new IOException(
                    "Config failed; previous settings restored: " + failure.getMessage(), failure);
        } finally {
            Settings.endBatch();
        }
    }

    private static JsonObject capture() {
        return capture(false);
    }

    private static JsonObject defaults() {
        return capture(true);
    }

    private static JsonObject capture(boolean defaults) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonObject modules = new JsonObject();
        for (Module module : ModuleRegistry.modules()) {
            // Opening a preset must not close the editor or change its current layout.
            if (module.id().equals("clickgui")) continue;
            JsonObject state = new JsonObject();
            state.addProperty(
                    "enabled",
                    defaults ? module.defaultEnabled() : module.enabled().getAsBoolean());
            JsonObject values = new JsonObject();
            for (Setting setting : module.settings())
                values.add(
                        setting.id(),
                        (defaults ? setting.defaultValue() : setting.value().get()).deepCopy());
            state.add("settings", values);
            modules.add(module.id(), state);
        }
        root.add("modules", modules);
        JsonObject bindings = new JsonObject();
        if (!defaults) Settings.snapshotPrefix("keybind.").forEach(bindings::addProperty);
        root.add("bindings", bindings);
        return root;
    }

    private record Change(Setting setting, JsonElement value, boolean configured) {}

    private record ModuleState(Module module, boolean enabled, List<Change> settings) {}

    private record Plan(List<ModuleState> modules, Map<String, String> bindings) {}

    private static Plan plan(JsonObject root) {
        if (!root.has("format") || root.get("format").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported config format");
        }
        JsonObject modules = root.getAsJsonObject("modules");
        JsonObject bindings = root.getAsJsonObject("bindings");
        if (modules == null || bindings == null)
            throw new IllegalArgumentException("Missing modules or bindings");
        List<ModuleState> states = new ArrayList<>();
        for (Module module : ModuleRegistry.modules()) {
            if (module.id().equals("clickgui")) continue;
            JsonElement stored = modules.get(module.id());
            JsonObject state =
                    stored != null && stored.isJsonObject()
                            ? stored.getAsJsonObject()
                            : new JsonObject();
            JsonElement enabled = state.get("enabled");
            boolean active =
                    enabled != null
                                    && enabled.isJsonPrimitive()
                                    && enabled.getAsJsonPrimitive().isBoolean()
                            ? enabled.getAsBoolean()
                            : module.defaultEnabled();
            JsonElement settings = state.get("settings");
            JsonObject values =
                    settings != null && settings.isJsonObject()
                            ? settings.getAsJsonObject()
                            : new JsonObject();
            List<Change> changes = new ArrayList<>();
            for (Setting setting : module.settings()) {
                JsonElement value = values.get(setting.id());
                if (module.id().equals("cheststealer")
                        && setting.id().equals("delay_ms")
                        && value != null
                        && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isNumber()) {
                    var range = new com.google.gson.JsonArray();
                    range.add(value.deepCopy());
                    range.add(value.deepCopy());
                    value = range;
                }
                boolean configured = valid(setting, value);
                changes.add(
                        new Change(
                                setting,
                                (configured ? value : setting.defaultValue()).deepCopy(),
                                configured));
            }
            states.add(new ModuleState(module, active, changes));
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (var entry : bindings.entrySet()) {
            if (!entry.getKey().startsWith("keybind.")
                    || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }
            keys.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new Plan(states, keys);
    }

    private static boolean valid(Setting setting, JsonElement value) {
        boolean valid = value != null && !value.isJsonNull();
        if (valid) {
            valid =
                    switch (setting.type()) {
                        case "boolean" ->
                                value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                        case "number", "integer" ->
                                validNumber(setting, value)
                                        && (!setting.type().equals("integer")
                                                || value.getAsDouble()
                                                        == Math.rint(value.getAsDouble()));
                        case "range" ->
                                value.isJsonArray()
                                        && value.getAsJsonArray().size() == 2
                                        && validNumber(setting, value.getAsJsonArray().get(0))
                                        && validNumber(setting, value.getAsJsonArray().get(1))
                                        && value.getAsJsonArray().get(0).getAsDouble()
                                                <= value.getAsJsonArray().get(1).getAsDouble();
                        case "choice" ->
                                value.isJsonPrimitive()
                                        && value.getAsJsonPrimitive().isString()
                                        && setting.options().contains(value.getAsString());
                        case "color" ->
                                value.isJsonPrimitive()
                                        && value.getAsJsonPrimitive().isString()
                                        && value.getAsString().matches("#[0-9a-fA-F]{6}");
                        case "text" ->
                                value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                        case "item_list", "block_list", "entity_list", "mob_list" ->
                                RegistryLists.valid(setting.type(), value);
                        default -> false;
                    };
        }
        return valid;
    }

    private static boolean validNumber(Setting setting, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        double number = value.getAsDouble();
        return Double.isFinite(number)
                && (setting.min() == null || number >= setting.min())
                && (setting.max() == null || number <= setting.max());
    }

    private static void apply(Plan plan) {
        Minecraft client = Minecraft.getInstance();
        ClientChat.withoutNoticesOnCurrentThread(
                () -> {
                    for (ModuleState state : plan.modules()) {
                        if (state.module().enabled().getAsBoolean())
                            state.module().toggle().apply(client, false);
                    }
                    // Restore modes first, then all their options, including currently hidden
                    // options.
                    for (boolean choices : new boolean[] {true, false}) {
                        // A stored value wins over defaults for settings shared by modules.
                        for (boolean configured : new boolean[] {false, true}) {
                            for (ModuleState state : plan.modules()) {
                                for (Change change : state.settings()) {
                                    if (change.setting().type().equals("choice") == choices
                                            && change.configured() == configured) {
                                        change.setting().apply().apply(client, change.value());
                                    }
                                }
                            }
                        }
                    }
                    Settings.replacePrefix("keybind.", plan.bindings());
                    for (ModuleState state : plan.modules()) {
                        if (state.module().enabled().getAsBoolean() != state.enabled())
                            state.module().toggle().apply(client, state.enabled());
                    }
                });
    }

    private static Path path(String name) {
        if (!validName(name))
            throw new IllegalArgumentException(
                    "Use 1–48 letters, numbers, spaces, _ or - for the config name");
        return directory().resolve(canonicalName(name) + EXTENSION);
    }

    private static String canonicalName(String name) {
        return name.trim().equalsIgnoreCase(DEFAULT_NAME) ? DEFAULT_NAME : name.trim();
    }

    private static boolean validName(String name) {
        if (name == null || !name.trim().matches("[\\p{L}\\p{N}_-][\\p{L}\\p{N} _-]{0,47}"))
            return false;
        String upper = name.trim().toUpperCase(Locale.ROOT);
        return !upper.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }
}
