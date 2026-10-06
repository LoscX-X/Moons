package com.blanoir.moons.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Values of the currently loaded JSON profile, including non-module settings. */
public final class Settings {
    private static final Map<String, JsonPrimitive> VALUES = new ConcurrentHashMap<>();
    private static Path configDirectory;
    private static Path activeFile;
    private static JsonObject startupSnapshot;
    private static Supplier<JsonObject> profileSnapshot;
    private static volatile boolean loaded;
    private static volatile long revision;
    private static volatile long valuesEpoch;
    private static int deferredSaveDepth;
    private static volatile boolean savePending;
    private static long retryAfterNanos;
    private static long persistedRevision;
    private static String saveFailure = "";

    public record SaveResult(long appliedRevision, long persistedRevision, String error) {
        public boolean saved() {
            return error.isEmpty() && appliedRevision == persistedRevision;
        }
    }

    private Settings() {}

    public static synchronized Path file() {
        if (activeFile == null) activeFile = directory().resolve("profiles/default.json");
        return activeFile;
    }

    public static synchronized Path directory() {
        if (configDirectory == null) {
            String configuredHome = System.getProperty("moons.home", "").trim();
            Path home = configuredHome.isEmpty() ? defaultHome() : Path.of(configuredHome);
            configDirectory = home.resolve("config").toAbsolutePath().normalize();
        }
        return configDirectory;
    }

    public static synchronized void configure(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (loaded && !normalized.equals(configDirectory))
            throw new IllegalStateException("Settings were already loaded from " + configDirectory);
        configDirectory = normalized;
        if (!loaded) activeFile = null;
    }

    public static synchronized void load() {
        if (loaded) return;
        Path target = file();
        Path legacy = directory().resolve("moons.properties");
        try {
            JsonObject existing = Files.exists(target) ? ConfigStore.read(target) : null;
            boolean migrate =
                    Files.exists(legacy) && (existing == null || ConfigStore.format(existing) == 1);
            JsonObject values;
            if (migrate) {
                Properties properties = new Properties();
                try (InputStream input = Files.newInputStream(legacy)) {
                    properties.load(input);
                }
                values = new JsonObject();
                properties.stringPropertyNames().stream()
                        .sorted()
                        .forEach(key -> values.addProperty(key, properties.getProperty(key)));
                if (existing != null) {
                    int suffix = 0;
                    Path backup;
                    do {
                        backup =
                                target.resolveSibling(
                                        "default-before-migration"
                                                + (suffix == 0 ? "" : "-" + suffix)
                                                + ".json");
                        suffix++;
                    } while (Files.exists(backup));
                    Files.copy(target, backup);
                }
                startupSnapshot = null;
            } else if (existing != null) {
                values = ConfigStore.values(existing);
                startupSnapshot = existing;
                if (ConfigStore.format(existing) == 1) FirstRunDefaults.apply(values);
            } else {
                values = new JsonObject();
                FirstRunDefaults.apply(values);
            }
            values.entrySet()
                    .forEach(
                            entry ->
                                    VALUES.put(
                                            entry.getKey(), entry.getValue().getAsJsonPrimitive()));
            loaded = true;
            if (migrate || existing == null) {
                revision++;
                savePending = true;
                save();
            }
        } catch (IOException | RuntimeException failure) {
            // A failed read must never become an empty snapshot that overwrites its source.
            VALUES.clear();
            loaded = false;
            throw new IllegalStateException(
                    "Could not read config " + target + ": " + failure.getMessage(), failure);
        }
    }

    static synchronized JsonObject startupSnapshot() {
        load();
        return startupSnapshot == null ? null : startupSnapshot.deepCopy();
    }

    static synchronized void attachProfileSnapshot(Supplier<JsonObject> snapshot) {
        load();
        profileSnapshot = snapshot;
    }

    static synchronized JsonObject snapshotValues() {
        load();
        JsonObject result = new JsonObject();
        new TreeMap<>(VALUES).forEach((key, value) -> result.add(key, value.deepCopy()));
        return result;
    }

    static synchronized void replaceValues(JsonObject values) {
        ConfigStore.validateValues(values);
        VALUES.clear();
        values.entrySet()
                .forEach(
                        entry -> VALUES.put(entry.getKey(), entry.getValue().getAsJsonPrimitive()));
        valuesEpoch++;
        saveAfterMutation();
    }

    static synchronized void activate(Path path) {
        activeFile = path.toAbsolutePath().normalize();
        revision++;
        savePending = true;
    }

    static synchronized Map<String, String> snapshotPrefix(String prefix) {
        load();
        Map<String, String> snapshot = new TreeMap<>();
        VALUES.forEach(
                (key, value) -> {
                    if (key.startsWith(prefix)) snapshot.put(key, value.getAsString());
                });
        return snapshot;
    }

    static synchronized void replacePrefix(String prefix, Map<String, String> values) {
        load();
        if (values.keySet().stream().anyMatch(key -> !key.startsWith(prefix)))
            throw new IllegalArgumentException("Unexpected settings prefix");
        if (snapshotPrefix(prefix).equals(values)) return;
        VALUES.keySet().removeIf(key -> key.startsWith(prefix));
        values.forEach((key, value) -> VALUES.put(key, new JsonPrimitive(value)));
        saveAfterMutation();
    }

    public static synchronized void save() {
        load();
        try {
            JsonObject snapshot;
            if (profileSnapshot == null) {
                snapshot = startupSnapshot == null ? new JsonObject() : startupSnapshot.deepCopy();
                snapshot.addProperty("format", 2);
                snapshot.add("values", snapshotValues());
            } else snapshot = profileSnapshot.get();
            ConfigStore.write(file(), snapshot, true);
            persistedRevision = revision;
            saveFailure = "";
            savePending = false;
            retryAfterNanos = 0;
        } catch (IOException | RuntimeException failure) {
            saveFailure = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            savePending = true;
            retryAfterNanos = System.nanoTime() + 1_000_000_000L;
            System.err.println("[client] Failed to save config: " + saveFailure);
        }
    }

    public static synchronized SaveResult saveResult() {
        load();
        return new SaveResult(revision, persistedRevision, saveFailure);
    }

    /** Flushes after a complete setter callback, so cached module values have settled. */
    public static synchronized SaveResult flush() {
        load();
        if (savePending && deferredSaveDepth == 0) save();
        return saveResult();
    }

    /** Frame/tick retries are bounded while an explicit flush always retries immediately. */
    public static void flushPending() {
        if (!savePending) return;
        synchronized (Settings.class) {
            if (savePending && deferredSaveDepth == 0 && System.nanoTime() >= retryAfterNanos)
                save();
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key, "");
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return defaultValue;
    }

    public static void setBoolean(String key, boolean value) {
        setProperty(key, new JsonPrimitive(value));
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(getString(key, ""));
        } catch (NumberFormatException failure) {
            return defaultValue;
        }
    }

    public static void setInt(String key, int value) {
        setProperty(key, new JsonPrimitive(value));
    }

    public static double getDouble(String key, double defaultValue) {
        try {
            double value = Double.parseDouble(getString(key, ""));
            return Double.isFinite(value) ? value : defaultValue;
        } catch (NumberFormatException failure) {
            return defaultValue;
        }
    }

    public static void setDouble(String key, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Setting must be finite");
        setProperty(key, new JsonPrimitive(value));
    }

    public static String getString(String key, String defaultValue) {
        ensureLoaded();
        JsonPrimitive value = VALUES.get(key);
        return value == null ? defaultValue : value.getAsString();
    }

    public static void setString(String key, String value) {
        setProperty(key, new JsonPrimitive(value));
    }

    public static long revision() {
        ensureLoaded();
        return revision;
    }

    /** Changes only when a complete profile replaces cached scalar settings. */
    public static long valuesEpoch() {
        return valuesEpoch;
    }

    private static void ensureLoaded() {
        if (!loaded) load();
    }

    private static synchronized void setProperty(String key, JsonPrimitive value) {
        ensureLoaded();
        if (!value.equals(VALUES.put(key, value))) saveAfterMutation();
    }

    public static synchronized void remove(String key) {
        load();
        if (VALUES.remove(key) != null) saveAfterMutation();
    }

    public static synchronized void beginBatch() {
        load();
        deferredSaveDepth++;
    }

    public static synchronized void endBatch() {
        if (deferredSaveDepth <= 0) return;
        deferredSaveDepth--;
        if (deferredSaveDepth == 0 && savePending) save();
    }

    private static void saveAfterMutation() {
        revision++;
        savePending = true;
        // Runtime snapshots are flushed at the callback/batch or frame boundary.
        if (deferredSaveDepth == 0 && profileSnapshot == null && startupSnapshot == null) save();
    }

    private static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        return appData != null && !appData.isBlank()
                ? Path.of(appData).resolve(".moons").toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home", "."))
                        .resolve(".moons")
                        .toAbsolutePath()
                        .normalize();
    }
}
