package com.blanoir.moons.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Exercises actual failed publication, dirty revision retention, retry and saved values. */
public final class SettingsPersistenceVerification {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("moons.home"));
        Files.createDirectories(home);
        Path directory = Files.createTempDirectory(home, "settings-persistence-");
        Settings.configure(directory);
        Settings.setString("fixture.text", "中文\nvalue=1");
        require(Settings.saveResult().saved(), "Initial mutation was not persisted");
        byte[] previous = Files.readAllBytes(Settings.file());
        Path backup = directory.resolve("previous.properties");
        Files.move(Settings.file(), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectory(Settings.file());
        Path marker = Settings.file().resolve("keep.txt");
        Files.writeString(marker, "retain on failure");
        long saved = Settings.saveResult().persistedRevision();
        Settings.beginBatch();
        Settings.setInt("fixture.number", 23);
        Settings.setBoolean("fixture.enabled", true);
        require(
                !Settings.saveResult().saved()
                        && Settings.saveResult().persistedRevision() == saved,
                "Batch falsely claimed persistence");
        Settings.endBatch();
        var failed = Settings.saveResult();
        require(
                !failed.saved() && !failed.error().isEmpty() && failed.persistedRevision() == saved,
                "Failed publication cleared dirty revision");
        require(
                Settings.getInt("fixture.number", 0) == 23,
                "Failed save reverted applied settings");
        require(
                java.util.Arrays.equals(previous, Files.readAllBytes(backup))
                        && Files.readString(marker).equals("retain on failure"),
                "Write failure destroyed prior data");
        Files.delete(marker);
        Files.delete(Settings.file());
        Files.move(backup, Settings.file());
        var retried = Settings.flush();
        require(
                retried.saved() && retried.appliedRevision() == failed.appliedRevision(),
                "Explicit retry did not persist the existing dirty revision");
        Properties persisted = new Properties();
        try (var input = Files.newInputStream(Settings.file())) {
            persisted.load(input);
        }
        require(
                persisted.getProperty("fixture.text").equals("中文\nvalue=1")
                        && persisted.getProperty("fixture.number").equals("23")
                        && persisted.getProperty("fixture.enabled").equals("true"),
                "Properties roundtrip changed values");
        try (var files = Files.list(directory)) {
            require(
                    files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "Staging file leaked");
        }
        System.out.println(
                "MOONS_SETTINGS_PERSISTENCE_VERIFIED dirty-revision failed-publication retry batching unicode no-staging-leak");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
