package com.blanoir.tempos.client.config;

import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleRegistry;
import com.blanoir.tempos.client.module.framework.SettingFactory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Offline checks against real profile IO and callbacks; this does not launch a game client. */
public final class ParameterArchiveVerification {
    private static final String MODULE = "archive_fixture";
    private static final String PARAMETER =
            "data/ysm/parameters/123e4567-e89b-32d3-a456-426614174000.json";

    private ParameterArchiveVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 2) {
            child(args[0], Path.of(args[1]));
            return;
        }
        Path base =
                Path.of(System.getProperty("tempos.home", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(base);
        Path root = Files.createTempDirectory(base, "parameter-archive-");
        List<String> failed = new ArrayList<>();
        for (String mode :
                List.of(
                        "roundtrip",
                        "raw",
                        "format",
                        "range",
                        "raw-range",
                        "type",
                        "traversal",
                        "profile-traversal",
                        "case-names",
                        "attachment-json",
                        "publish",
                        "apply",
                        "selected-publish",
                        "raw-selected-publish",
                        "dirty-retry")) {
            if (!fork(mode, root.resolve(mode))) failed.add(mode);
        }
        require(
                failed.isEmpty(),
                "Parameter archive checks failed: " + failed + "; fixtures: " + root);
        System.out.println(
                "TEMPOS_PARAMETER_ARCHIVE_VERIFIED named-profiles selected-restart hidden-values keybinds window-values ysm-sidecars raw-import rejected-input publication-rollback apply-rollback dirty-retry offline-files");
    }

    private static final class Fixture {
        final BooleanSetting enabled =
                new BooleanSetting.Builder().name("archive.enabled").defaultValue(false).build();
        boolean resourcesActive;
        boolean failApply;
        double hidden = Settings.getDouble("archive.hidden", 2);
        double primary = Settings.getDouble("archive.primary", 3);
        final ModuleModel.Setting hiddenSetting =
                SettingFactory.numeric(
                                "hidden",
                                "Hidden option",
                                "number",
                                () -> hidden,
                                0,
                                100,
                                0.25,
                                (client, value) -> {
                                    Settings.setDouble("archive.hidden", value);
                                    hidden = value;
                                })
                        .withDefault(2)
                        .visibleWhen(() -> false);
        final ModuleModel.Setting primarySetting =
                SettingFactory.numeric(
                                "primary",
                                "Primary option",
                                "number",
                                () -> primary,
                                0,
                                20,
                                1,
                                (client, value) -> {
                                    if (failApply && value == 13)
                                        throw new IllegalStateException(
                                                "archive fixture callback failure");
                                    Settings.setDouble("archive.primary", value);
                                    primary = value;
                                })
                        .withDefault(3);

        Fixture() {
            ModuleRegistry.installCatalog(
                    () ->
                            ModuleRegistry.add(
                                    ModuleModel.module(
                                            MODULE,
                                            "Archive fixture",
                                            ModuleCategory.MISC,
                                            enabled::get,
                                            (client, value) -> {
                                                enabled.set(value);
                                                resourcesActive = value;
                                                return 1;
                                            },
                                            () -> "",
                                            hiddenSetting,
                                            primarySetting)));
            ConfigProfiles.initialize();
        }

        void values(int index) {
            try (var batch = Settings.batch()) {
                hiddenSetting.apply().apply(null, new com.google.gson.JsonPrimitive(20.25 + index));
                primarySetting.apply().apply(null, new com.google.gson.JsonPrimitive(4 + index));
                ModuleRegistry.setEnabled(MODULE, index % 2 == 0);
                Settings.setBoolean("module." + MODULE + ".hide", true);
                Settings.setString("keybind." + MODULE, "key.keyboard." + (char) ('a' + index));
                Settings.setString("keybind.mode." + MODULE, index % 2 == 0 ? "hold" : "toggle");
                Settings.setDouble("clickgui.window.misc.x", 117.5 + index);
                Settings.setDouble("clickgui.window.misc.y", -6.25 - index);
                Settings.setInt("clickgui.window.misc.width", 300 + index);
                Settings.setString("archive.unicode", "测试 参数 " + index);
            }
            require(Settings.saveResult().saved(), "Fixture values were not saved");
        }

        void matches(JsonObject expected) {
            require(
                    ConfigProfiles.capture().equals(expected),
                    "Current complete profile differs from the archived document");
            JsonObject module = expected.getAsJsonObject("modules").getAsJsonObject(MODULE);
            require(
                    hidden == module.getAsJsonObject("settings").get("hidden").getAsDouble(),
                    "Hidden cached parameter was not applied");
            require(
                    primary == module.getAsJsonObject("settings").get("primary").getAsDouble(),
                    "Cached numeric parameter was not applied");
            require(
                    enabled.get() == module.get("enabled").getAsBoolean()
                            && resourcesActive == enabled.get(),
                    "Module lifecycle did not follow the restored enabled flag");
            require(!hiddenSetting.isVisible(), "Fixture must exercise a genuinely hidden setting");
        }
    }

    private static void child(String mode, Path home) throws Exception {
        Files.createDirectories(home);
        Settings.configure(home.resolve("config"));
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Fixture fixture = new Fixture();
        if (mode.equals("restart")) {
            JsonObject archive = json(home.resolve("roundtrip.json"));
            String selected = archive.get("selected").getAsString();
            require(
                    ConfigProfiles.selected().equals(selected),
                    "Fresh JVM did not honor selected-profile.info");
            fixture.matches(archive.getAsJsonObject("profiles").getAsJsonObject(selected));
            require(Settings.saveResult().saved(), "Restarted selected profile is not saved");
        } else if (mode.equals("roundtrip")) {
            roundtrip(home, fixture);
        } else {
            fixture.values(1);
            switch (mode) {
                case "raw" -> raw(home, fixture);
                case "publish" -> publishFailure(home, fixture);
                case "apply" -> applyFailure(home, fixture);
                case "selected-publish", "raw-selected-publish" ->
                        selectedPublicationFailure(home, fixture, mode.startsWith("raw-"));
                case "dirty-retry" -> dirtyRetry(home, fixture);
                default -> rejectedInput(home, fixture, mode);
            }
        }
        System.out.println("TEMPOS_PARAMETER_ARCHIVE_CASE_VERIFIED " + mode);
    }

    private static void roundtrip(Path home, Fixture fixture) throws Exception {
        fixture.values(1);
        ConfigProfiles.create("arena");
        ConfigProfiles.load("arena");
        fixture.values(2);
        ConfigProfiles.create("旅行");
        ConfigProfiles.load("旅行");
        fixture.values(3);
        ConfigProfiles.load("arena");
        var expected = new LinkedHashMap<String, JsonObject>();
        for (String name : ConfigProfiles.list())
            expected.put(name, ConfigStore.read(ConfigProfiles.path(name)));
        var extras = new LinkedHashMap<String, String>();
        extras.put(
                "data/ysm/ysm.properties",
                "# verbatim properties\r\nmodel=C\\:\\\\models\\\\\\u732b.ysm\r\nscale=1.125\r\n");
        extras.put(PARAMETER, "{ \"scale\": 1.12345678901234567890, \"name\": \"参数\" }\n");
        extras.put(PARAMETER + ".pose.json", "{\"head\": {\"x\": 2.5, \"y\": -1}}\r\n");
        extras.put(PARAMETER + ".textures.json", "{ \"selected\": \"skin.png\" }\n");
        extras.put(
                PARAMETER + ".before-body-size-0123456789abcdef0123456789abcdef.bak",
                "{\"scale\":1}\n");
        extras.put(
                "config/server/server-example.org-0123456789abcdef.json",
                "{\"scope\":\"server:example.org\",\"name\":\"参数\"}\n");
        extras.put(
                "config/profiles/server/server-example.org-0123456789abcdef.json",
                "{\"scope\":\"server:example.org\"}\n");
        extras.put(
                "config/legacy/conflicts/" + "a".repeat(64) + ".json",
                "{\"path\":\"旧参数\",\"text\":\"中文原文\"}\n");
        for (var entry : extras.entrySet()) write(home.resolve(entry.getKey()), entry.getValue());
        String legacyKey = "config/legacy/moons-moons.properties";
        byte[] legacyBytes =
                "# unchanged Latin-1 \u00e9\r\nunknown.custom=001\\literal\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
        Files.createDirectories(home.resolve(legacyKey).getParent());
        Files.write(home.resolve(legacyKey), legacyBytes);
        Path archive = home.resolve("roundtrip.json");
        ConfigProfiles.exportParameters(archive);
        byte[] exported = Files.readAllBytes(archive);
        JsonObject document = json(archive);
        require(
                document.get("selected").getAsString().equals("arena"),
                "Export lost the selected profile");
        require(
                document.getAsJsonObject("profiles").keySet().equals(expected.keySet()),
                "Export lost a named profile");
        for (var entry : extras.entrySet())
            require(
                    document.getAsJsonObject("extras").has(entry.getKey())
                            && document.getAsJsonObject("extras")
                                    .get(entry.getKey())
                                    .getAsString()
                                    .equals(entry.getValue()),
                    "Export lost or changed the YSM attachment " + entry.getKey());
        require(
                document.getAsJsonObject("extras").has(legacyKey)
                        && Arrays.equals(
                                document.getAsJsonObject("extras")
                                        .get(legacyKey)
                                        .getAsString()
                                        .getBytes(StandardCharsets.ISO_8859_1),
                                legacyBytes),
                "Export did not preserve the original Latin-1 properties bytes");

        ConfigProfiles.load("default");
        fixture.values(4);
        ConfigProfiles.load("arena");
        fixture.values(5);
        Files.delete(ConfigProfiles.path("旅行"));
        for (var entry : extras.entrySet())
            Files.writeString(home.resolve(entry.getKey()), "overwritten fixture");
        Files.writeString(home.resolve(legacyKey), "overwritten legacy attachment");
        ConfigProfiles.importParameters(archive);
        require(
                ConfigProfiles.selected().equals("arena"),
                "Import did not restore the archive's selected profile");
        require(
                Arrays.equals(exported, Files.readAllBytes(archive)),
                "Import rewrote its source archive");
        for (var entry : extras.entrySet())
            require(
                    Files.readString(home.resolve(entry.getKey())).equals(entry.getValue()),
                    "Attachment did not roundtrip verbatim: " + entry.getKey());
        require(
                Arrays.equals(legacyBytes, Files.readAllBytes(home.resolve(legacyKey))),
                "Import changed the Latin-1 properties attachment bytes");
        for (var entry : expected.entrySet()) {
            require(
                    ConfigStore.read(ConfigProfiles.path(entry.getKey())).equals(entry.getValue()),
                    "Import did not restore named profile " + entry.getKey());
            ConfigProfiles.load(entry.getKey());
            fixture.matches(entry.getValue());
        }
        ConfigProfiles.load("arena");
        require(
                Files.readString(Settings.directory().resolve("selected-profile.info"))
                        .trim()
                        .equals("arena"),
                "Selected profile pointer was not persisted");
        require(fork("restart", home), "Fresh JVM selected-profile restoration failed");
    }

    private static void raw(Path home, Fixture fixture) throws Exception {
        JsonObject raw = changedProfile(7, 42);
        Path source = home.resolve("raw-original.json");
        write(source, new GsonBuilder().setPrettyPrinting().create().toJson(raw) + "\n");
        byte[] original = Files.readAllBytes(source);
        String selected = ConfigProfiles.selected();
        ConfigProfiles.importParameters(source);
        fixture.matches(raw);
        require(
                ConfigProfiles.selected().equals(selected),
                "Raw profile import unexpectedly changed the active profile name");
        require(
                Arrays.equals(original, Files.readAllBytes(source)),
                "Raw format-2 source was rewritten");
        require(Settings.saveResult().saved(), "Raw profile was not persisted");
    }

    private static void rejectedInput(Path home, Fixture fixture, String mode) throws Exception {
        JsonObject root = bundle("incoming", changedProfile(7, 42));
        JsonObject incoming = root.getAsJsonObject("profiles").getAsJsonObject("incoming");
        switch (mode) {
            case "format" -> root.addProperty("format", 42);
            case "range", "raw-range" ->
                    incoming.getAsJsonObject("modules")
                            .getAsJsonObject(MODULE)
                            .getAsJsonObject("settings")
                            .addProperty("hidden", 999);
            case "type" ->
                    incoming.getAsJsonObject("modules")
                            .getAsJsonObject(MODULE)
                            .getAsJsonObject("settings")
                            .addProperty("hidden", "wrong type");
            case "traversal" ->
                    root.getAsJsonObject("extras")
                            .addProperty("data/ysm/parameters/../../escape.json", "{}");
            case "profile-traversal" ->
                    root.getAsJsonObject("profiles").add("../escape", incoming.deepCopy());
            case "case-names" -> {
                root.getAsJsonObject("profiles").add("Arena", incoming.deepCopy());
                root.getAsJsonObject("profiles").add("arena", changedProfile(8, 43));
            }
            case "attachment-json" ->
                    root.getAsJsonObject("extras").addProperty(PARAMETER, "{ broken");
            default -> throw new AssertionError("Unknown case " + mode);
        }
        Path source = home.resolve("rejected.json");
        write(source, mode.equals("raw-range") ? incoming.toString() : root.toString());
        byte[] sourceBefore = Files.readAllBytes(source);
        JsonObject runtime = ConfigProfiles.capture();
        String selected = ConfigProfiles.selected();
        var saved = Settings.saveResult();
        Map<String, byte[]> disk = disk(home);
        expectIOException(() -> ConfigProfiles.importParameters(source));
        fixture.matches(runtime);
        require(
                ConfigProfiles.selected().equals(selected),
                "Rejected input switched the selected profile");
        sameDisk(disk, disk(home));
        require(
                Arrays.equals(sourceBefore, Files.readAllBytes(source)),
                "Rejected import changed its input file");
        var after = Settings.saveResult();
        require(
                after.saved()
                        && after.appliedRevision() == saved.appliedRevision()
                        && after.persistedRevision() == saved.persistedRevision(),
                "Validation failure mutated or dirtied current parameters");
        require(
                !Files.exists(home.resolve("escape.json")),
                "Rejected archive published a path outside its parameter destination");
    }

    private static void publishFailure(Path home, Fixture fixture) throws Exception {
        JsonObject root = bundle("incoming", changedProfile(7, 42));
        root.getAsJsonObject("extras").addProperty(PARAMETER, "{\"fixture\":1}");
        // Earlier profile writes succeed; the later attachment cannot create its parent directory.
        write(
                home.resolve("data/ysm/parameters"),
                "a regular file prevents attachment publication");
        Path source = home.resolve("publish.json");
        write(source, root.toString());
        JsonObject runtime = ConfigProfiles.capture();
        String selected = ConfigProfiles.selected();
        Map<String, byte[]> before = disk(home);
        expectIOException(() -> ConfigProfiles.importParameters(source));
        fixture.matches(runtime);
        require(
                ConfigProfiles.selected().equals(selected),
                "Mid-publication failure changed selection");
        sameDisk(before, disk(home));
        require(
                Settings.saveResult().saved(),
                "Failure before runtime application dirtied current parameters");
        require(
                !Files.exists(ConfigProfiles.path("incoming")),
                "Failed publication leaked a newly imported profile");
    }

    private static void applyFailure(Path home, Fixture fixture) throws Exception {
        JsonObject root = bundle("incoming", changedProfile(13, 42));
        Path source = home.resolve("callback-failure.json");
        write(source, root.toString());
        JsonObject runtime = ConfigProfiles.capture();
        String selected = ConfigProfiles.selected();
        Map<String, byte[]> before = disk(home);
        fixture.failApply = true;
        expectIOException(() -> ConfigProfiles.importParameters(source));
        fixture.matches(runtime);
        require(
                ConfigProfiles.selected().equals(selected),
                "Failed callback switched profile selection");
        sameDisk(before, disk(home));
        require(
                Settings.saveResult().saved(),
                "Successfully restored callbacks left current parameters dirty");
    }

    private static void selectedPublicationFailure(Path home, Fixture fixture, boolean raw)
            throws Exception {
        Path source = home.resolve("selected-publication.json");
        write(
                source,
                (raw ? changedProfile(7, 42) : bundle("incoming", changedProfile(7, 42)))
                        .toString());
        byte[] originalSource = Files.readAllBytes(source);
        require(
                Settings.flush().saved(),
                "Initial state was dirty before blocking selection publication");
        Path pointer = Settings.directory().resolve("selected-profile.info");
        byte[] previousPointer = Files.readAllBytes(pointer);
        Files.delete(pointer);
        Files.createDirectory(pointer);
        Path marker = pointer.resolve("retain.txt");
        Files.writeString(marker, "do not remove on save failure");
        JsonObject runtime = ConfigProfiles.capture();
        String selected = ConfigProfiles.selected();
        Map<String, byte[]> before = disk(home);
        expectIOException(() -> ConfigProfiles.importParameters(source));
        fixture.matches(runtime);
        require(
                ConfigProfiles.selected().equals(selected),
                "Selection-marker publication failure did not restore the active profile");
        sameDisk(before, disk(home));
        require(
                Arrays.equals(originalSource, Files.readAllBytes(source)),
                "Failed publication rewrote the source archive");
        var failed = Settings.saveResult();
        require(
                !failed.saved() && !failed.error().isEmpty(),
                "A blocked selection marker was falsely reported saved");
        Files.delete(marker);
        Files.delete(pointer);
        Files.write(pointer, previousPointer);
        var retried = Settings.flush();
        require(
                retried.saved() && retried.appliedRevision() == failed.appliedRevision(),
                "Retry did not persist the restored dirty revision");
        fixture.matches(runtime);
        require(
                ConfigStore.read(Settings.file()).equals(runtime),
                "Retry published the rejected import instead of the restored parameters");
    }

    private static void dirtyRetry(Path home, Fixture fixture) throws Exception {
        Path target = Settings.file();
        byte[] previous = Files.readAllBytes(target);
        Path backup = target.resolveSibling("saved-before-blocking.json");
        Files.move(target, backup);
        Files.createDirectory(target);
        Path marker = target.resolve("retain.txt");
        Files.writeString(marker, "retain");
        long persisted = Settings.saveResult().persistedRevision();
        try (var batch = Settings.batch()) {
            fixture.hiddenSetting.apply().apply(null, new com.google.gson.JsonPrimitive(55.5));
        }
        var failed = Settings.saveResult();
        require(
                !failed.saved()
                        && !failed.error().isEmpty()
                        && failed.persistedRevision() == persisted,
                "Failed config publication cleared the dirty revision");
        require(
                fixture.hidden == 55.5 && Settings.getDouble("archive.hidden", -1) == 55.5,
                "Failed publication lost the applied runtime parameter");
        Path export = home.resolve("must-not-exist.json");
        expectIOException(() -> ConfigProfiles.exportParameters(export));
        require(
                !Files.exists(export)
                        && Arrays.equals(previous, Files.readAllBytes(backup))
                        && Files.readString(marker).equals("retain"),
                "Export bypassed failed persistence or damaged prior data");
        Files.delete(marker);
        Files.delete(target);
        Files.move(backup, target);
        var retried = Settings.flush();
        require(
                retried.saved() && retried.appliedRevision() == failed.appliedRevision(),
                "Flush did not retry the existing dirty revision");
        require(
                ConfigStore.read(target)
                                .getAsJsonObject("modules")
                                .getAsJsonObject(MODULE)
                                .getAsJsonObject("settings")
                                .get("hidden")
                                .getAsDouble()
                        == 55.5,
                "Retry saved a stale hidden-value snapshot");
        ConfigProfiles.exportParameters(export);
        require(Files.isRegularFile(export), "Successful retry did not unblock export");
    }

    private static JsonObject changedProfile(double primary, double hidden) {
        JsonObject result = ConfigProfiles.capture();
        JsonObject values = result.getAsJsonObject("values");
        values.addProperty("archive.primary", primary);
        values.addProperty("archive.hidden", hidden);
        JsonObject module = result.getAsJsonObject("modules").getAsJsonObject(MODULE);
        module.getAsJsonObject("settings").addProperty("primary", primary);
        module.getAsJsonObject("settings").addProperty("hidden", hidden);
        return result;
    }

    private static JsonObject bundle(String selected, JsonObject profile) {
        JsonObject result = new JsonObject(), profiles = new JsonObject();
        result.addProperty("format", 1);
        result.addProperty("selected", selected);
        profiles.add("default", ConfigProfiles.capture());
        profiles.add(selected, profile);
        result.add("profiles", profiles);
        result.add("extras", new JsonObject());
        return result;
    }

    private static Map<String, byte[]> disk(Path home) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Path tree : List.of(home.resolve("config"), home.resolve("data/ysm"))) {
            if (!Files.exists(tree)) continue;
            try (var paths = Files.walk(tree)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList())
                    result.put(home.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return result;
    }

    private static void sameDisk(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        require(
                expected.keySet().equals(actual.keySet()),
                "Failed import changed the parameter file set: before="
                        + expected.keySet()
                        + " after="
                        + actual.keySet());
        for (String name : expected.keySet())
            require(
                    Arrays.equals(expected.get(name), actual.get(name)),
                    "Failed import changed persisted bytes: " + name);
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static boolean fork(String mode, Path home) throws Exception {
        String executable =
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        Process process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-Dfile.encoding=UTF-8",
                                "-Djava.awt.headless=true",
                                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ParameterArchiveVerification.class.getName(),
                                mode,
                                home.toString())
                        .inheritIO()
                        .start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Child archive check timed out: " + mode);
        }
        return process.exitValue() == 0;
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }

    private static void expectIOException(Operation action) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected parameter IO to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
