package com.blanoir.moons.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Settings {
    private static final String FILE_NAME = MoonsConfig.MOD_ID + ".properties";
    private static final Properties PROPERTIES = new Properties();
    private static Path configDirectory;
    private static volatile boolean loaded = false;
    private static volatile long revision;
    private static int deferredSaveDepth;
    private static boolean savePending;

    private Settings() {}

    /** Configures the host-owned directory before the first settings access. */
    public static synchronized void configure(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (loaded && !normalized.equals(configDirectory)) {
            throw new IllegalStateException("Settings were already loaded from " + configDirectory);
        }
        configDirectory = normalized;
    }

    public static synchronized void load() {
        if (loaded) return;

        Path configPath = configPath();
        if (Files.exists(configPath)) {
            try (InputStream input = Files.newInputStream(configPath)) {
                PROPERTIES.load(input);
            } catch (IOException exception) {
                System.err.println("[client] Failed to load config: " + exception.getMessage());
            }
        }
        loaded = true;
    }

    public static synchronized void save() {
        load();
        Path configPath = configPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream output = Files.newOutputStream(configPath)) {
                PROPERTIES.store(output, "client settings");
            }
        } catch (IOException exception) {
            System.err.println("[client] Failed to save config: " + exception.getMessage());
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        ensureLoaded();
        String value = PROPERTIES.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public static void setBoolean(String key, boolean value) {
        setProperty(key, Boolean.toString(value));
    }

    public static int getInt(String key, int defaultValue) {
        ensureLoaded();
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    public static void setInt(String key, int value) {
        setProperty(key, Integer.toString(value));
    }

    public static double getDouble(String key, double defaultValue) {
        ensureLoaded();
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    public static void setDouble(String key, double value) {
        setProperty(key, Double.toString(value));
    }

    public static String getString(String key, String defaultValue) {
        ensureLoaded();
        return PROPERTIES.getProperty(key, defaultValue);
    }

    /** Changes only when an in-memory setting changes, including during a save batch. */
    public static long revision() {
        ensureLoaded();
        return revision;
    }

    public static void setString(String key, String value) {
        setProperty(key, value);
    }

    private static void ensureLoaded() {
        // Publish initialization once. Hot reads must not wait on the Settings
        // monitor while a settings mutation writes the properties file.
        if (!loaded) load();
    }

    private static void setProperty(String key, String value) {
        ensureLoaded();
        if (!value.equals(PROPERTIES.setProperty(key, value))) saveAfterMutation();
    }

    public static synchronized void remove(String key) {
        load();
        if (PROPERTIES.remove(key) != null) saveAfterMutation();
    }

    /** Defers disk writes while a burst of related setting changes is in progress. */
    public static synchronized void beginBatch() {
        load();
        deferredSaveDepth++;
    }

    public static synchronized void endBatch() {
        if (deferredSaveDepth <= 0) return;
        deferredSaveDepth--;
        if (deferredSaveDepth == 0 && savePending) {
            savePending = false;
            save();
        }
    }

    private static synchronized void saveAfterMutation() {
        revision++;
        if (deferredSaveDepth > 0) savePending = true;
        else save();
    }

    private static Path configPath() {
        Path directory = configDirectory;
        if (directory == null) {
            String configuredHome = System.getProperty("moons.home", "").trim();
            Path home =
                    configuredHome.isEmpty()
                            ? defaultHome()
                            : Path.of(configuredHome).toAbsolutePath().normalize();
            directory = home.resolve("config");
            configDirectory = directory.toAbsolutePath().normalize();
        }
        return directory.resolve(FILE_NAME);
    }

    private static Path defaultHome() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve(".moons").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."))
                .resolve(".moons")
                .toAbsolutePath()
                .normalize();
    }
}
