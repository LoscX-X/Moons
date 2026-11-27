package com.blanoir.tempos.client.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads complete JSON documents and publishes snapshots without exposing partial writes. */
final class ConfigStore {
    private ConfigStore() {}

    static JsonObject read(Path source) throws IOException {
        return read(source, true);
    }

    static JsonObject read(Path source, boolean migrateLegacyNames) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Config is not a file: " + source);
        if (Files.size(source) > 2_000_000) throw new IOException("Config is too large");
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int format = format(root);
            if (format == 2) {
                values(root);
                if (root.has("modules") && !root.get("modules").isJsonObject())
                    throw new IllegalArgumentException("Expected module states");
                if (migrateLegacyNames) migrateNames(root.getAsJsonObject("values"));
            } else if (format == 1) {
                if (!root.has("modules")
                        || !root.get("modules").isJsonObject()
                        || !root.has("bindings")
                        || !root.get("bindings").isJsonObject())
                    throw new IllegalArgumentException("Missing modules or bindings");
            } else throw new IllegalArgumentException("Unsupported config format");
            if (migrateLegacyNames && root.has("modules")) {
                JsonObject modules = root.getAsJsonObject("modules");
                migrateModuleName(modules, "chatprefix", "prefix");
                migrateModuleName(modules, "hud", "title");
                migrateModuleName(modules, "scoreboardchanger", "title");
            }
            return root;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid config: " + failure.getMessage(), failure);
        }
    }

    static int format(JsonObject root) {
        return root.has("format") ? root.get("format").getAsInt() : -1;
    }

    static void migrateNames(JsonObject values) {
        JsonObject renamed = new JsonObject();
        for (var entries = values.entrySet().iterator(); entries.hasNext();) {
            var entry = entries.next();
            if (!entry.getKey().startsWith("moons.")) continue;
            String key = "tempos." + entry.getKey().substring("moons.".length());
            if (!values.has(key)) renamed.add(key, entry.getValue());
            entries.remove();
        }
        renamed.entrySet().forEach(e -> values.add(e.getKey(), e.getValue()));
        migrateDisplayName(values, "client.name");
        migrateDisplayName(values, "featurehud.title");
        migrateDisplayName(values, "scoreboardchanger.title");
    }
    private static void migrateModuleName(JsonObject modules, String id, String key) {
        var module = modules.get(id);
        if (module == null || !module.isJsonObject()) return;
        var settings = module.getAsJsonObject().get("settings");
        if (settings != null && settings.isJsonObject()) migrateDisplayName(settings.getAsJsonObject(), key);
    }
    private static void migrateDisplayName(JsonObject values, String key) {
        var value = values.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && "Moons".equalsIgnoreCase(value.getAsString())) values.addProperty(key, ClientBranding.DEFAULT_NAME);
    }

    static JsonObject values(JsonObject root) {
        if (format(root) == 1) return new JsonObject();
        JsonObject values = root.getAsJsonObject("values");
        validateValues(values);
        return values.deepCopy();
    }

    static void validateValues(JsonObject values) {
        if (values == null) throw new IllegalArgumentException("Missing config values");
        for (var entry : values.entrySet()) {
            if (!entry.getValue().isJsonPrimitive())
                throw new IllegalArgumentException("Expected a scalar setting: " + entry.getKey());
            var value = entry.getValue().getAsJsonPrimitive();
            if (value.isNumber() && !Double.isFinite(value.getAsDouble()))
                throw new IllegalArgumentException("Expected a finite setting: " + entry.getKey());
        }
    }

    static void write(Path target, JsonObject snapshot, boolean replace) throws IOException {
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), ".config-", ".tmp");
        try {
            Files.writeString(
                    staging,
                    new GsonBuilder()
                                    .setPrettyPrinting()
                                    .disableHtmlEscaping()
                                    .create()
                                    .toJson(snapshot)
                            + "\n",
                    StandardCharsets.UTF_8);
            if (!replace) Files.move(staging, target);
            else {
                try {
                    Files.move(
                            staging,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}
