package com.blanoir.moons.client.config;

import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Named presets must restore live values and must not partially apply invalid files. */
public final class ConfigProfilesVerification {
    public static void main(String[] args) throws Exception {
        Settings.configure(Files.createTempDirectory("moons-config-verify-"));
        boolean[] enabled = {true};
        String[] mode = {"a"};
        double[] amount = {25};
        boolean[] failOnce = {false};
        List<String> events = new ArrayList<>();
        var modeSetting =
                ModuleRegistry.customChoice(
                        "mode",
                        "Mode",
                        () -> mode[0],
                        List.of("a", "b"),
                        (client, value) -> {
                            mode[0] = value;
                            events.add("mode:" + value);
                            return 1;
                        });
        var hiddenSetting =
                new ModuleRegistry.Setting(
                                "amount",
                                "Amount",
                                "number",
                                () -> new JsonPrimitive(amount[0]),
                                0.0,
                                100.0,
                                1.0,
                                List.of(),
                                (client, value) -> {
                                    if (failOnce[0]) {
                                        failOnce[0] = false;
                                        throw new IllegalStateException("Simulated apply failure");
                                    }
                                    amount[0] = value.getAsDouble();
                                    events.add("amount");
                                })
                        .visibleWhen(() -> mode[0].equals("b"));
        var module =
                new ModuleRegistry.Module(
                        "sample",
                        "Sample",
                        "Misc",
                        () -> enabled[0],
                        (client, value) -> {
                            enabled[0] = value;
                            events.add("toggle:" + value);
                            return 1;
                        },
                        () -> "",
                        List.of(modeSetting, hiddenSetting));
        var gui =
                new ModuleRegistry.Module(
                        "clickgui",
                        "ClickGUI",
                        "Render",
                        () -> true,
                        (client, value) -> {
                            throw new AssertionError("Loading must not toggle the editor");
                        },
                        () -> "",
                        List.of());
        ModuleRegistry.installCatalog(
                () -> {
                    ModuleRegistry.add(module);
                    ModuleRegistry.add(gui);
                });
        Settings.setString("keybind.sample", "key.keyboard.r");
        ConfigProfiles.create("Duel");
        String original = Files.readString(ConfigProfiles.directory().resolve("Duel.json"));
        expectFailure(() -> ConfigProfiles.create("Duel"));
        check(
                original.equals(Files.readString(ConfigProfiles.directory().resolve("Duel.json"))),
                "Create must not overwrite an existing config");
        enabled[0] = false;
        mode[0] = "b";
        amount[0] = 80;
        Settings.remove("keybind.sample");
        Settings.setString("keybind.extra", "key.keyboard.q");
        events.clear();
        ConfigProfiles.load("Duel");
        check(
                enabled[0] && mode[0].equals("a") && amount[0] == 25,
                "Live and hidden values must restore");
        check(
                events.indexOf("mode:a") < events.indexOf("amount"),
                "Modes must apply before dependent values");
        check(events.getLast().equals("toggle:true"), "Enabling must follow applying values");
        check(
                Settings.getString("keybind.sample", "").equals("key.keyboard.r")
                        && Settings.getString("keybind.extra", "missing").equals("missing"),
                "Bindings must replace instead of merge");
        mode[0] = "b";
        amount[0] = 60;
        ConfigProfiles.save("Duel");
        ConfigProfiles.create("练习");
        amount[0] = 3;
        ConfigProfiles.load("Duel");
        check(
                amount[0] == 60 && ConfigProfiles.list().size() == 2,
                "Save and multiple Unicode names must work");
        check(ConfigProfiles.selected().equals("Duel"), "Last used config must persist");
        JsonObject malformed =
                JsonParser.parseString(
                                Files.readString(ConfigProfiles.directory().resolve("Duel.json")))
                        .getAsJsonObject();
        malformed
                .getAsJsonObject("modules")
                .getAsJsonObject("sample")
                .getAsJsonObject("settings")
                .addProperty("amount", 500);
        Files.writeString(ConfigProfiles.directory().resolve("invalid.json"), malformed.toString());
        events.clear();
        expectFailure(() -> ConfigProfiles.load("invalid"));
        check(
                events.isEmpty() && amount[0] == 60,
                "Invalid values must fail before any runtime mutation");
        Files.writeString(ConfigProfiles.directory().resolve("broken.json"), "{ invalid json");
        expectFailure(() -> ConfigProfiles.load("broken"));
        amount[0] = 45;
        mode[0] = "a";
        failOnce[0] = true;
        expectFailure(() -> ConfigProfiles.load("Duel"));
        check(
                amount[0] == 45 && mode[0].equals("a") && enabled[0],
                "Apply failure must restore the previous live state");
        for (String name : List.of("../outside", "E:\\outside", "CON", "", "a/b")) {
            expectFailure(() -> ConfigProfiles.create(name));
        }
        expectFailure(() -> ConfigProfiles.save("missing"));
        expectFailure(() -> ConfigProfiles.load("missing"));
        System.out.println(
                "CONFIG_PROFILES_VERIFIED create save load selection hidden-values rollback validation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Action action) throws Exception {
        try {
            action.run();
        } catch (java.io.IOException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected config operation to fail");
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
