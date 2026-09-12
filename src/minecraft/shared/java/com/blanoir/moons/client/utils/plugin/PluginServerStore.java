package com.blanoir.moons.client.utils.plugin;

import com.blanoir.moons.client.config.Settings;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/** Atomic, server-scoped plugin metadata storage independent of module profiles. */
public final class PluginServerStore {
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private PluginServerStore() {}

    public static Path file(String scope) {
        if (scope == null
                || !(scope.startsWith("server:") || scope.startsWith("local:"))
                || scope.substring(scope.indexOf(':') + 1).isBlank()) {
            throw new IllegalArgumentException("A server or world identity is required");
        }
        String label = scope.substring(scope.indexOf(':') + 1).replaceAll("[^a-zA-Z0-9._-]", "_");
        if (label.length() > 48) label = label.substring(0, 48);
        String digest;
        try {
            digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(scope.getBytes(StandardCharsets.UTF_8)),
                                    0,
                                    8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return Settings.file()
                .getParent()
                .resolve("server")
                .resolve("server-" + label + "-" + digest + ".json");
    }

    public static synchronized JsonObject read(String scope) {
        Path path = file(scope);
        if (!Files.exists(path)) {
            JsonObject empty = new JsonObject();
            empty.addProperty("format", 1);
            empty.addProperty("scope", scope);
            return empty;
        }
        try {
            if (Files.size(path) > MAX_BYTES)
                throw new IOException("Server file is too large: " + path);
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!root.has("format")
                    || root.get("format").getAsInt() != 1
                    || !root.has("scope")
                    || !scope.equals(root.get("scope").getAsString())) {
                throw new IOException("Server file identity or format mismatch: " + path);
            }
            return root;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid server file: " + path, exception);
        }
    }

    public static synchronized void update(
            String scope, String section, Consumer<JsonObject> change) {
        JsonObject root = read(scope);
        JsonObject before = root.deepCopy();
        JsonObject value = root.has(section) ? root.getAsJsonObject(section) : new JsonObject();
        change.accept(value);
        root.add(section, value);
        if (root.equals(before)) return;
        Path destination = file(scope);
        Path temporary = null;
        try {
            byte[] bytes =
                    (new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n")
                            .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES)
                throw new IOException("Server file would exceed size limit");
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), "plugin-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    /* A failed cleanup must not hide the write error. */
                }
            }
        }
    }
}
