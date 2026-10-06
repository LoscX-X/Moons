package com.blanoir.moons.client.config;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module;
import com.blanoir.moons.client.module.framework.ModuleRegistry.Setting;
import com.blanoir.moons.client.utils.registry.RegistryLists;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Complete named configurations. Only load changes which JSON receives subsequent edits. */
public final class ConfigProfiles {
    public static final String DEFAULT_NAME = "default";
    private static final String EXTENSION = ".json";

    private ConfigProfiles() {}

    public static Path directory() {
        return Settings.directory().resolve("profiles");
    }

    public static String selected() {
        String file = Settings.file().getFileName().toString();
        return file.substring(0, file.length() - EXTENSION.length());
    }

    /** Called after feature initialization, before remote connections and GUI warmup. */
    public static void initialize() {
        ModuleRegistry.ensureCatalog();
        JsonObject startup = Settings.startupSnapshot();
        Plan initial = startup != null && startup.has("modules") ? plan(startup) : null;
        Settings.attachProfileSnapshot(ConfigProfiles::capture);
        Settings.beginBatch();
        try {
            if (initial != null) apply(initial);
        } finally {
            Settings.endBatch();
        }
        // Populate module metadata for migrated or newly created values-only defaults.
        if (initial == null || ConfigStore.format(startup) == 1) Settings.save();
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
        ConfigStore.write(target, capture(), false);
    }

    public static void save(String name) throws IOException {
        Path target = path(name);
        ensureDefault();
        if (!Files.isRegularFile(target)) throw new IOException("Config does not exist: " + name);
        if (target.equals(Settings.file())) {
            Settings.save();
            requireSaved();
        } else ConfigStore.write(target, capture(), true);
    }

    private static void ensureDefault() throws IOException {
        Settings.load();
        if (!Files.exists(path(DEFAULT_NAME)))
            throw new IOException(
                    "Default config could not be saved: " + Settings.saveResult().error());
    }

    private static void requireSaved() throws IOException {
        var result = Settings.saveResult();
        if (!result.saved())
            throw new IOException("Settings applied, but config was not saved: " + result.error());
    }

    public static void load(String name) throws IOException {
        Path source = path(name);
        ensureDefault();
        Settings.flush();
        requireSaved();
        if (!Files.isRegularFile(source)) throw new IOException("Config does not exist: " + name);
        final Plan target;
        try {
            target = plan(ConfigStore.read(source));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid config: " + failure.getMessage(), failure);
        }
        JsonObject snapshot = capture();
        Plan previous = plan(snapshot);
        Path previousFile = Settings.file();
        Settings.beginBatch();
        try {
            apply(target);
            Settings.activate(source);
        } catch (RuntimeException failure) {
            try {
                Settings.activate(previousFile);
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
        requireSaved();
    }

    private static JsonObject capture() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.add("values", Settings.snapshotValues());
        JsonObject modules = new JsonObject();
        for (Module module : ModuleRegistry.modules()) {
            // ClickGUI is a live screen action; its persistent layout lives in values.
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
        return root;
    }

    private record Change(Setting setting, JsonElement value, boolean configured) {}

    private record ModuleState(Module module, boolean enabled, List<Change> settings) {}

    private record Plan(
            List<ModuleState> modules, Map<String, String> bindings, JsonObject values) {}

    private static Plan plan(JsonObject root) {
        int format = ConfigStore.format(root);
        if (format != 1 && format != 2) {
            throw new IllegalArgumentException("Unsupported config format");
        }
        JsonObject modules = root.getAsJsonObject("modules");
        JsonObject raw = format == 2 ? ConfigStore.values(root) : null;
        JsonObject bindings = format == 1 ? root.getAsJsonObject("bindings") : raw;
        if (modules == null || bindings == null)
            throw new IllegalArgumentException("Missing modules or bindings");
        // Older profiles stored the armor feature inside InvManager.
        if (!modules.has("autoarmor")
                && modules.has("invmanager")
                && modules.get("invmanager").isJsonObject()) {
            var previous = modules.getAsJsonObject("invmanager");
            var previousSettings = previous.get("settings");
            if (previousSettings != null && previousSettings.isJsonObject()) {
                var values = previousSettings.getAsJsonObject();
                var armor = new JsonObject();
                var armorValues = new JsonObject();
                boolean inventoryEnabled =
                        previous.has("enabled")
                                && previous.get("enabled").isJsonPrimitive()
                                && previous.getAsJsonPrimitive("enabled").isBoolean()
                                && previous.get("enabled").getAsBoolean();
                boolean armorEnabled =
                        values.has("auto_armor")
                                && values.get("auto_armor").isJsonPrimitive()
                                && values.getAsJsonPrimitive("auto_armor").isBoolean()
                                && values.get("auto_armor").getAsBoolean();
                armor.addProperty("enabled", inventoryEnabled && armorEnabled);
                for (String key :
                        List.of(
                                "keep_elytra",
                                "protect_special",
                                "open_delay",
                                "manual_delay",
                                "delay_ms",
                                "armor_lock_0",
                                "armor_lock_1",
                                "armor_lock_2",
                                "armor_lock_3"))
                    if (values.has(key)) armorValues.add(key, values.get(key).deepCopy());
                armor.add("settings", armorValues);
                modules = modules.deepCopy();
                modules.add("autoarmor", armor);
            }
        }
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
        return new Plan(states, keys, raw);
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
                    // Release the old runtime before replacing values read by enabled() getters.
                    if (plan.values() != null) {
                        Settings.replaceValues(plan.values());
                        // Raw enabled flags may refresh cached getters. Route activation through
                        // the module callbacks after its parameters have been restored.
                        for (ModuleState state : plan.modules()) {
                            if (state.module().enabled().getAsBoolean())
                                state.module().toggle().apply(client, false);
                        }
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
