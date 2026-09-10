package com.blanoir.moons.client.config;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting;
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
    private static final String SELECTED_KEY = "config.profile";
    private static final String EXTENSION = ".json";

    private ConfigProfiles() {}

    public static Path directory() {
        return Settings.file().getParent().resolve("profiles");
    }

    public static String selected() {
        return Settings.getString(SELECTED_KEY, "");
    }

    public static List<String> list() throws IOException {
        if (!Files.isDirectory(directory())) return List.of();
        try (var files = Files.list(directory())) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                    .filter(ConfigProfiles::validName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public static void create(String name) throws IOException {
        write(name, false);
    }

    public static void save(String name) throws IOException {
        write(name, true);
    }

    private static void write(String name, boolean replace) throws IOException {
        Path target = path(name);
        Files.createDirectories(directory());
        if (replace && !Files.isRegularFile(target))
            throw new IOException("Config does not exist: " + name);
        if (!replace && Files.exists(target))
            throw new IOException("Config already exists: " + name);
        JsonObject snapshot = capture();
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
        Settings.setString(SELECTED_KEY, name.trim());
    }

    public static void load(String name) throws IOException {
        Path source = path(name);
        if (!Files.isRegularFile(source)) throw new IOException("Config does not exist: " + name);
        if (Files.size(source) > 2_000_000) throw new IOException("Config is too large");
        final Plan target;
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            target = plan(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("Invalid config: " + failure.getMessage(), failure);
        }
        Plan previous = plan(capture());
        Settings.beginBatch();
        try {
            apply(target);
            Settings.setString(SELECTED_KEY, name.trim());
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
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonObject modules = new JsonObject();
        for (Module module : ModuleRegistry.modules()) {
            // Opening a preset must not close the editor or change its current layout.
            if (module.id().equals("clickgui")) continue;
            JsonObject state = new JsonObject();
            state.addProperty("enabled", module.enabled().getAsBoolean());
            JsonObject values = new JsonObject();
            for (Setting setting : module.settings())
                values.add(setting.id(), setting.value().get().deepCopy());
            state.add("settings", values);
            modules.add(module.id(), state);
        }
        root.add("modules", modules);
        JsonObject bindings = new JsonObject();
        Settings.snapshotPrefix("keybind.").forEach(bindings::addProperty);
        root.add("bindings", bindings);
        return root;
    }

    private record Change(Setting setting, JsonElement value) {}

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
            if (module.id().equals("clickgui") || !modules.has(module.id())) continue;
            JsonObject state = modules.getAsJsonObject(module.id());
            JsonElement enabled = state.get("enabled");
            if (enabled == null
                    || !enabled.isJsonPrimitive()
                    || !enabled.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("Invalid enabled state: " + module.name());
            }
            JsonObject values = state.getAsJsonObject("settings");
            if (values == null)
                throw new IllegalArgumentException("Missing settings: " + module.name());
            List<Change> changes = new ArrayList<>();
            for (Setting setting : module.settings()) {
                if (!values.has(setting.id())) continue;
                JsonElement value = values.get(setting.id());
                validate(setting, value);
                changes.add(new Change(setting, value.deepCopy()));
            }
            states.add(new ModuleState(module, enabled.getAsBoolean(), changes));
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (var entry : bindings.entrySet()) {
            if (!entry.getKey().startsWith("keybind.")
                    || !entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Invalid key binding");
            }
            keys.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new Plan(states, keys);
    }

    private static void validate(Setting setting, JsonElement value) {
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
                        default -> false;
                    };
        }
        if (!valid) throw new IllegalArgumentException("Invalid value for " + setting.name());
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
                        for (ModuleState state : plan.modules()) {
                            for (Change change : state.settings()) {
                                if (change.setting().type().equals("choice") == choices) {
                                    change.setting().apply().apply(client, change.value());
                                }
                            }
                        }
                    }
                    Settings.replacePrefix("keybind.", plan.bindings());
                    for (ModuleState state : plan.modules()) {
                        state.module().toggle().apply(client, state.enabled());
                    }
                });
    }

    private static Path path(String name) {
        if (!validName(name))
            throw new IllegalArgumentException(
                    "Use 1–48 letters, numbers, spaces, _ or - for the config name");
        return directory().resolve(name.trim() + EXTENSION);
    }

    private static boolean validName(String name) {
        if (name == null || !name.trim().matches("[\\p{L}\\p{N}_-][\\p{L}\\p{N} _-]{0,47}"))
            return false;
        String upper = name.trim().toUpperCase(Locale.ROOT);
        return !upper.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }
}
