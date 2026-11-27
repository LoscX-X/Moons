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
import java.util.LinkedHashMap;
import java.util.Map;

/** Parameter-only interchange shared with the launcher's embedded configuration bundle. */
final class ParameterArchive {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private ParameterArchive() {}

    static void exportFile(Path target) throws IOException {
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
            throw new IOException("Choose a JSON file for the parameters");
        JsonObject root = new JsonObject(), profiles = new JsonObject(), extras = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("selected", ConfigProfiles.selected());
        for (String name : ConfigProfiles.list()) profiles.add(name, ConfigStore.read(ConfigProfiles.path(name)));
        root.add("profiles", profiles);
        Path home = Settings.directory().getParent();
        Path ysm = home.resolve("data/ysm");
        if (Files.isRegularFile(ysm.resolve("ysm.properties")))
            extras.addProperty("data/ysm/ysm.properties", Files.readString(ysm.resolve("ysm.properties")));
        Path parameters = ysm.resolve("parameters");
        if (Files.isDirectory(parameters)) try (var files = Files.list(parameters)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = "data/ysm/parameters/" + file.getFileName();
                if (extraPath(home, relative) != null) extras.addProperty(relative, Files.readString(file));
            }
        }
        Path legacy = Settings.directory().resolve("legacy");
        if (Files.isDirectory(legacy)) try (var files = Files.list(legacy)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = "config/legacy/" + file.getFileName();
                if (extraPath(home, relative) != null) extras.addProperty(relative, Files.readString(file, StandardCharsets.ISO_8859_1));
            }
        }
        root.add("extras", extras);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Parameters are too large");
        publish(target.toAbsolutePath().normalize(), bytes);
    }

    static void importFile(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_BYTES)
            throw new IOException("Invalid parameter file");
        final JsonObject root;
        try (var reader = Files.newBufferedReader(source)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid parameter JSON", failure);
        }
        if (!root.has("profiles")) {
            JsonObject document = ConfigStore.read(source);
            try { ConfigProfiles.validateDocument(document); }
            catch (RuntimeException invalid) { throw new IOException("Invalid parameters: " + invalid.getMessage(), invalid); }
            Path verified = Files.createTempFile(Settings.directory(), ".parameter-import-", ".json");
            Path target = Settings.file();
            byte[] previous = Files.readAllBytes(target);
            try {
                ConfigStore.write(verified, document, true);
                ConfigProfiles.loadDocument(verified, target);
            } catch (IOException | RuntimeException failure) {
                try { publish(target, previous); }
                catch (IOException restore) { failure.addSuppressed(restore); }
                throw failure;
            } finally { Files.deleteIfExists(verified); }
            return;
        }
        Path staging = Files.createTempDirectory(Settings.directory(), ".parameter-import-");
        Map<Path, byte[]> writes = new LinkedHashMap<>(), previous = new LinkedHashMap<>();
        try {
            if (root.get("format").getAsInt() != 1 || !root.get("profiles").isJsonObject())
                throw new IOException("Unsupported parameter bundle");
            String selected = root.get("selected").getAsString();
            JsonObject profiles = root.getAsJsonObject("profiles");
            if (!ConfigProfiles.validName(selected) || !profiles.has(selected))
                throw new IOException("Missing selected parameter profile");
            for (var entry : profiles.entrySet()) {
                Path target = ConfigProfiles.path(entry.getKey());
                if (writes.containsKey(target)) throw new IOException("Duplicate parameter profile name");
                Path file = staging.resolve(target.getFileName());
                ConfigStore.write(file, entry.getValue().getAsJsonObject(), false);
                JsonObject document = ConfigStore.read(file);
                ConfigProfiles.validateDocument(document);
                ConfigStore.write(file, document, true);
                writes.put(target, Files.readAllBytes(file));
            }
            Path home = Settings.directory().getParent();
            if (root.has("extras")) for (var entry : root.getAsJsonObject("extras").entrySet()) {
                Path target = extraPath(home, entry.getKey());
                if (target == null || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IOException("Invalid parameter attachment");
                String value = entry.getValue().getAsString();
                if (entry.getKey().endsWith(".json")) JsonParser.parseString(value).getAsJsonObject();
                writes.put(target, value.getBytes(entry.getKey().startsWith("config/legacy/")
                        ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
            }
            for (Path target : writes.keySet())
                previous.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
            try {
                for (var write : writes.entrySet()) publish(write.getKey(), write.getValue());
                ConfigProfiles.loadDocument(staging.resolve(selected + ".json"), ConfigProfiles.path(selected));
            } catch (IOException | RuntimeException failure) {
                for (var old : previous.entrySet()) try {
                    if (old.getValue() == null) Files.deleteIfExists(old.getKey());
                    else publish(old.getKey(), old.getValue());
                } catch (IOException restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        } catch (RuntimeException failure) {
            throw new IOException("Invalid parameter bundle: " + failure.getMessage(), failure);
        } finally {
            try (var files = Files.list(staging)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(staging);
        }
    }

    private static Path extraPath(Path home, String relative) {
        if (!relative.equals("data/ysm/ysm.properties")
                && !relative.matches("data/ysm/parameters/[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.json")
                && !relative.matches("config/legacy/(?:moons|tempos)-(?:moons|tempos)\\.properties")) return null;
        return home.resolve(relative);
    }

    private static void publish(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".parameters-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
