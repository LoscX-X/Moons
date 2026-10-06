package com.blanoir.moons.client.config;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.config.settings.StringSetting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.init.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Exercises actual failed publication, dirty revision retention, retry and saved values. */
public final class SettingsPersistenceVerification {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child(args[0], Path.of(args[1]));
            return;
        }
        Path home = Path.of(System.getProperty("moons.home"));
        Files.createDirectories(home);
        Path directory = Files.createTempDirectory(home, "settings-persistence-");
        Settings.configure(directory);
        Settings.setString("fixture.text", "中文\nvalue=1");
        require(Settings.saveResult().saved(), "Initial mutation was not persisted");
        byte[] previous = Files.readAllBytes(Settings.file());
        Path backup = directory.resolve("previous.json");
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
        JsonObject persisted = ConfigStore.values(ConfigStore.read(Settings.file()));
        require(
                persisted.get("fixture.text").getAsString().equals("中文\nvalue=1")
                        && persisted.get("fixture.number").getAsInt() == 23
                        && persisted.get("fixture.enabled").getAsBoolean(),
                "JSON roundtrip changed values");
        try (var files = Files.list(Settings.file().getParent())) {
            require(
                    files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "Staging file leaked");
        }
        verifyProfiles(directory);
        fork("legacy", Files.createTempDirectory(home, "legacy-config-"));
        fork("malformed", Files.createTempDirectory(home, "invalid-config-"));
        fork("bad-legacy", Files.createTempDirectory(home, "invalid-legacy-"));
        System.out.println(
                "MOONS_SETTINGS_PERSISTENCE_VERIFIED JSON full-migration restart active-profile isolation copy overwrite rollback cache-refresh failed-publication retry batching unicode");
    }

    private static void verifyProfiles(Path directory) throws Exception {
        Bootstrap.register();
        var enabled = new BooleanSetting.Builder().name("test.enabled").defaultValue(false).build();
        boolean[] resourcesActive = {false};
        double[] number = {Settings.getDouble("test.number", 2)};
        ModuleRegistry.installCatalog(
                () ->
                        ModuleRegistry.add(
                                ModuleRegistry.module(
                                        "test",
                                        "Test",
                                        "test",
                                        enabled::get,
                                        (client, value) -> {
                                            if (enabled.get() == value) return 1;
                                            enabled.set(value);
                                            resourcesActive[0] = value;
                                            return 1;
                                        },
                                        () -> "",
                                        ModuleRegistry.numeric(
                                                        "number",
                                                        "Number",
                                                        "number",
                                                        () -> number[0],
                                                        0,
                                                        20,
                                                        1,
                                                        (client, value) -> {
                                                            if (value == 13)
                                                                throw new IllegalStateException(
                                                                        "fixture apply failure");
                                                            Settings.setDouble(
                                                                    "test.number", value);
                                                            number[0] = value;
                                                        })
                                                .withDefault(2))));
        ConfigProfiles.initialize();
        var text = new StringSetting.Builder().name("ui.text").defaultValue("initial").build();
        var flag = new BooleanSetting.Builder().name("ui.flag").defaultValue(false).build();
        var integer = new IntSetting.Builder().name("ui.int").defaultValue(0).build();
        var decimal = new DoubleSetting.Builder().name("ui.double").defaultValue(0).build();
        var mode =
                new ModeSetting.Builder<String>()
                        .name("ui.mode")
                        .defaultValue("first")
                        .option("first", "first")
                        .option("second", "second")
                        .build();
        text.set("default UI");
        flag.set(true);
        integer.set(7);
        decimal.set(1.5);
        mode.set("second");
        Settings.setString("keybind.test", "key.keyboard.r");
        Settings.setString("keybind.mode.test", "hold");
        require(
                ModuleRegistry.setEnabled("test", true).get("persisted").getAsBoolean(),
                "API toggle was not saved");
        require(
                ModuleRegistry.setValue("test", "number", new JsonPrimitive(5))
                        .get("persisted")
                        .getAsBoolean(),
                "API parameter was not saved");
        var stored =
                ConfigStore.read(Settings.file())
                        .getAsJsonObject("modules")
                        .getAsJsonObject("test");
        require(
                stored.get("enabled").getAsBoolean()
                        && stored.getAsJsonObject("settings").get("number").getAsDouble() == 5,
                "Snapshot ran before the module callback completed");
        byte[] defaultBytes = Files.readAllBytes(Settings.file());
        ConfigProfiles.create("arena");
        require(
                ConfigProfiles.selected().equals("default")
                        && enabled.get()
                        && resourcesActive[0]
                        && number[0] == 5
                        && text.get().equals("default UI"),
                "Create reset values or switched the active profile");
        expectFailure(() -> ConfigProfiles.create("arena"));
        ConfigProfiles.load("arena");
        require(
                enabled.get() && resourcesActive[0],
                "Raw enabled flag bypassed runtime activation callback");
        text.set("arena UI");
        flag.set(false);
        integer.set(8);
        decimal.set(2.5);
        mode.set("first");
        Settings.setString("arena.only", "exclusive");
        Settings.setString("keybind.test", "key.keyboard.t");
        ModuleRegistry.setEnabled("test", false);
        ModuleRegistry.setValue("test", "number", new JsonPrimitive(9));
        require(
                java.util.Arrays.equals(
                        defaultBytes,
                        Files.readAllBytes(directory.resolve("profiles/default.json"))),
                "Changes to arena overwrote default");
        ConfigProfiles.create("copy");
        ModuleRegistry.setValue("test", "number", new JsonPrimitive(10));
        ConfigProfiles.save("copy");
        require(
                ConfigProfiles.selected().equals("arena"),
                "Saving another target switched active profile");
        ConfigProfiles.load("default");
        require(
                enabled.get()
                        && resourcesActive[0]
                        && number[0] == 5
                        && text.get().equals("default UI")
                        && flag.get()
                        && integer.get() == 7
                        && decimal.get() == 1.5
                        && mode.get().equals("second")
                        && Settings.getString("keybind.test", "").equals("key.keyboard.r")
                        && Settings.getString("arena.only", "").isEmpty(),
                "Load did not restore complete settings or cached scalar values");
        ConfigProfiles.load("copy");
        require(
                !enabled.get()
                        && !resourcesActive[0]
                        && number[0] == 10
                        && text.get().equals("arena UI"),
                "Explicit overwrite missed current values");
        var invalid = ConfigStore.read(Settings.file());
        invalid.getAsJsonObject("modules")
                .getAsJsonObject("test")
                .getAsJsonObject("settings")
                .addProperty("number", 13);
        Path broken = directory.resolve("profiles/broken.json");
        ConfigStore.write(broken, invalid, false);
        byte[] brokenBytes = Files.readAllBytes(broken);
        expectFailure(() -> ConfigProfiles.load("broken"));
        require(
                ConfigProfiles.selected().equals("copy")
                        && number[0] == 10
                        && text.get().equals("arena UI")
                        && java.util.Arrays.equals(brokenBytes, Files.readAllBytes(broken)),
                "Failed application did not restore previous profile or changed target data");
        Files.writeString(directory.resolve("profiles/malformed.json"), "{ invalid");
        expectFailure(() -> ConfigProfiles.load("malformed"));
        require(
                ConfigProfiles.selected().equals("copy") && number[0] == 10,
                "Invalid JSON changed active settings");
        // Old module-only named files remain loadable and are normalized on successful load.
        JsonObject old = new JsonObject();
        old.addProperty("format", 1);
        old.add("modules", ConfigStore.read(Settings.file()).get("modules").deepCopy());
        old.add("bindings", new JsonObject());
        ConfigStore.write(directory.resolve("profiles/old.json"), old, false);
        ConfigProfiles.load("old");
        require(
                ConfigStore.format(ConfigStore.read(Settings.file())) == 2
                        && text.get().equals("arena UI"),
                "Legacy named config did not retain global settings or normalize to JSON format 2");
        fork("restart-default", directory);
    }

    private static void child(String mode, Path directory) throws Exception {
        Settings.configure(directory);
        Path target = Settings.file();
        Files.createDirectories(target.getParent());
        Path legacy = directory.resolve("moons.properties");
        if (mode.equals("legacy")) {
            Properties values = new Properties();
            values.setProperty("client.name", "测试 月");
            values.setProperty("unknown.custom", "001\\literal\nline");
            values.setProperty("config.profile", "arena");
            values.setProperty("fixture.int", "42");
            try (var output = Files.newOutputStream(legacy)) {
                values.store(output, "fixture");
            }
            byte[] legacyBytes = Files.readAllBytes(legacy);
            String previousDefault = "{\"format\":1,\"modules\":{},\"bindings\":{}}";
            Files.writeString(target, previousDefault);
            Settings.load();
            var json = ConfigStore.values(ConfigStore.read(target));
            for (String key : values.stringPropertyNames())
                require(
                        json.get(key).getAsString().equals(values.getProperty(key)),
                        "Migration lost " + key);
            require(
                    ConfigProfiles.selected().equals("default")
                            && java.util.Arrays.equals(legacyBytes, Files.readAllBytes(legacy))
                            && Files.readString(
                                            target.resolveSibling("default-before-migration.json"))
                                    .equals(previousDefault),
                    "Migration switched active config or destroyed legacy/default data");
            Settings.setInt("fixture.int", 43);
            Files.writeString(legacy, "fixture.int=999\nclient.name=old");
            fork("restart-migration", directory);
        } else if (mode.equals("restart-migration")) {
            Settings.load();
            require(
                    Settings.getInt("fixture.int", 0) == 43
                            && Settings.getString("client.name", "").equals("测试 月"),
                    "Restart used the retired properties instead of ordinary default.json");
        } else if (mode.equals("restart-default")) {
            Settings.load();
            require(
                    ConfigProfiles.selected().equals("default")
                            && Settings.getString("ui.text", "").equals("default UI"),
                    "Restart did not load ordinary default.json");
        } else if (mode.equals("malformed")) {
            String invalid = "{\"format\":2,\"values\":{\"partial\":true,\"invalid\":{}}}";
            Files.writeString(target, invalid);
            expectFailure(() -> Settings.setString("overwrite", "forbidden"));
            require(
                    Files.readString(target).equals(invalid),
                    "Failed read overwrote the existing file");
            JsonObject root = new JsonObject();
            root.addProperty("format", 2);
            root.add("values", new JsonObject());
            ConfigStore.write(target, root, true);
            Settings.load();
            require(
                    Settings.getString("partial", "absent").equals("absent"),
                    "Failed read leaked partial settings");
        } else if (mode.equals("bad-legacy")) {
            String invalid = "ok=1\nbad=\\uXXXX\n";
            Files.writeString(legacy, invalid);
            expectFailure(Settings::load);
            require(
                    Files.readString(legacy).equals(invalid) && !Files.exists(target),
                    "Invalid legacy input was published or changed");
        } else throw new IllegalArgumentException(mode);
    }

    private static void fork(String mode, Path directory) throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SettingsPersistenceVerification.class.getName(),
                                mode,
                                directory.toString())
                        .inheritIO()
                        .start();
        require(process.waitFor() == 0, "Child configuration check failed: " + mode);
    }

    private interface Operation {
        void run() throws Exception;
    }

    private static void expectFailure(Operation action) throws Exception {
        try {
            action.run();
        } catch (java.io.IOException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Expected a configuration failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
