package com.blanoir.moons.client.module.catalog;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Exercises the actual Scaffold GUI descriptors, setters and saved JSON without a game. */
public final class ScaffoldSettingsVerification {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path home = Path.of(System.getProperty("moons.home"));
        Files.createDirectories(home);
        Settings.configure(Files.createTempDirectory(home, "scaffold-settings-"));
        var module = World.scaffold();
        setting(module, "mode").apply().apply(null, new JsonPrimitive("telly"));
        var planner = setting(module, "packet_planner");
        var pitch = setting(module, "pitch_acceleration");
        require(
                planner.isVisible() && pitch.isVisible(),
                "Planner controls must be visible in Smooth Telly");
        planner.apply().apply(null, new JsonPrimitive(false));
        require(
                !pitch.isVisible() && setting(module, "telly_start_speed").isVisible(),
                "Disabling the planner hides its controls while keeping phase speed limits");
        planner.apply().apply(null, new JsonPrimitive(true));
        Map<String, Double> values =
                Map.of(
                        "rotation_plan_ticks", 4.0,
                        "yaw_acceleration", 80.0,
                        "pitch_acceleration", 60.0,
                        "acceleration_variation", 30.0,
                        "acceleration_response", 1.0,
                        "point_jitter_strength", .2,
                        "point_jitter_speed", 2.0,
                        "telly_return_speed", 150.0);
        for (var entry : values.entrySet()) {
            var option = setting(module, entry.getKey());
            require(option.isVisible(), "Setting is missing from Smooth Telly: " + entry.getKey());
            option.apply().apply(null, new JsonPrimitive(entry.getValue()));
            require(
                    Math.abs(option.value().get().getAsDouble() - entry.getValue()) < .000001,
                    "GUI setter and reader disagree: " + entry.getKey());
        }
        Settings.flush();
        var saved =
                JsonParser.parseString(Files.readString(Settings.file()))
                        .getAsJsonObject()
                        .getAsJsonObject("values");
        require(
                saved.get("scaffold.pitchAcceleration").getAsDouble() == 60
                        && saved.get("scaffold.rotationPlanTicks").getAsInt() == 4
                        && saved.get("scaffold.pointJitterStrength").getAsDouble() == .2
                        && saved.get("scaffold.tellyReturnSpeed").getAsDouble() == 150,
                "New rotation controls must persist to the active JSON profile");
        setting(module, "telly_rotation").apply().apply(null, new JsonPrimitive("instant"));
        require(
                !planner.isVisible()
                        && !pitch.isVisible()
                        && setting(module, "point_jitter_strength").isVisible(),
                "Instant keeps point controls and hides planner controls");
        setting(module, "mode").apply().apply(null, new JsonPrimitive("godbridge"));
        require(
                !planner.isVisible() && setting(module, "point_jitter_strength").isVisible(),
                "GodBridge keeps its direct rotation and exposes point variation");
        setting(module, "mode").apply().apply(null, new JsonPrimitive("legit"));
        require(
                !planner.isVisible() && !setting(module, "point_jitter_strength").isVisible(),
                "Legit must not advertise unused controls");
        System.out.println("MOONS_SCAFFOLD_SETTINGS_VERIFIED visibility setters JSON phase-speed");
    }

    private static ModuleRegistry.Setting setting(ModuleRegistry.Module module, String id) {
        return module.settings().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
