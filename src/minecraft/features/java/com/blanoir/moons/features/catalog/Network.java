package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.module.impl.network.LowHealthFakeLag;
import com.blanoir.moons.client.module.impl.network.RandomFakeLag;

/** Defines network module descriptors; ordering is owned by ModuleCatalog. */
final class Network {
    private Network() {}

    static ModuleRegistry.Module backtrack() {
        return module(
                "backtrack",
                "Backtrack",
                ModuleCategories.NETWORK,
                Backtrack::isEnabled,
                Backtrack::setEnabled,
                Backtrack::hudStats,
                customChoice(
                                "target_mode",
                                "Mode",
                                Backtrack::targetModeName,
                                Backtrack.targetModeOptions(),
                                Backtrack::setTargetMode)
                        .withDefault("attack"),
                new Setting(
                                "delay",
                                "Time (ms)",
                                "range",
                                () -> {
                                    var value = new com.google.gson.JsonArray();
                                    value.add(Backtrack.minDelayMillis());
                                    value.add(Backtrack.delayMillis());
                                    return value;
                                },
                                0.0,
                                1000.0,
                                1.0,
                                java.util.List.of(),
                                (client, value) ->
                                        Backtrack.setDelay(
                                                client,
                                                value.getAsJsonArray().get(0).getAsInt()
                                                        + "-"
                                                        + value.getAsJsonArray().get(1).getAsInt()))
                        .withDefault(100, 150),
                numeric(
                                "range",
                                "Max range",
                                "number",
                                Backtrack::maxRange,
                                0,
                                10,
                                .1,
                                (client, value) ->
                                        Backtrack.setRange(client, Double.toString(value)))
                        .withDefault(6.0),
                choice(
                        "esp",
                        "ESP",
                        "backtrack.esp",
                        "box",
                        Backtrack.espModeOptions(),
                        Backtrack::setEsp),
                internalNumber("min_range", "Min range", "backtrack.range.min", 1, 0, 10),
                internalInt(
                        "next_min",
                        "Next delay min",
                        "backtrack.nextBacktrackDelay.min",
                        0,
                        0,
                        2000),
                internalInt(
                        "next_max",
                        "Next delay max",
                        "backtrack.nextBacktrackDelay.max",
                        10,
                        0,
                        2000),
                internalInt(
                        "tracking_buffer",
                        "Tracking buffer",
                        "backtrack.trackingBuffer",
                        500,
                        0,
                        2000),
                internalNumber("chance", "Chance", "backtrack.chance", 100, 0, 100),
                internalBool(
                        "pause_hurt", "Pause on hurt", "backtrack.pauseOnHurtTime.enabled", false),
                internalInt(
                        "hurt_time", "Hurt time", "backtrack.pauseOnHurtTime.hurtTime", 3, 0, 10),
                internalInt(
                        "last_attack",
                        "Last attack",
                        "backtrack.lastAttackTimeToWork",
                        1000,
                        0,
                        5000),
                internalInt("max_queue", "Queue limit", "backtrack.maxQueueSize", 256, 32, 1024),
                internalNumber("speed_factor", "Speed factor", "backtrack.speedFactor", 8, 0, 30),
                internalNumber("ping_ratio", "Ping ratio", "backtrack.pingRatio", 0, 0, 3),
                internalBool("actionbar", "Action bar", "backtrack.actionbar", false));
    }

    private static Setting internalInt(
            String id, String label, String key, int fallback, int min, int max) {
        return numeric(
                        id,
                        label,
                        "integer",
                        () -> Math.clamp(Settings.getInt(key, fallback), min, max),
                        min,
                        max,
                        1,
                        (client, value) -> {
                            Settings.setInt(key, (int) Math.round(value));
                        })
                .withDefault(fallback)
                .visibleWhen(() -> false);
    }

    private static Setting internalNumber(
            String id, String label, String key, double fallback, double min, double max) {
        return numeric(
                        id,
                        label,
                        "number",
                        () -> {
                            double value = Settings.getDouble(key, fallback);
                            return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
                        },
                        min,
                        max,
                        .1,
                        (client, value) -> {
                            Settings.setDouble(key, value);
                        })
                .withDefault(fallback)
                .visibleWhen(() -> false);
    }

    private static Setting internalBool(String id, String label, String key, boolean fallback) {
        return bool(
                        id,
                        label,
                        key,
                        fallback,
                        (client, value) -> {
                            Settings.setBoolean(key, value);
                            return 1;
                        })
                .visibleWhen(() -> false);
    }

    static ModuleRegistry.Module fakeLag() {
        return module(
                "fakelag",
                "FakeLag",
                ModuleCategories.NETWORK,
                FakeLag::isEnabled,
                FakeLag::setEnabled,
                FakeLag::modeName,
                choice(
                        "mode",
                        "Mode",
                        "fakelag.mode",
                        "constant",
                        FakeLag.modeOptions(),
                        FakeLag::setMode),
                rangeInts(
                                "delay",
                                "Delay (ms)",
                                "constantfakelag.delay.min",
                                "constantfakelag.delay.max",
                                300,
                                600,
                                0,
                                5000,
                                10,
                                FakeLag::setDelay)
                        .visibleWhen(FakeLag::constantMode),
                integer(
                                "recoil",
                                "Recoil (ms)",
                                "constantfakelag.recoil",
                                250,
                                0,
                                2000,
                                10,
                                FakeLag::setRecoil)
                        .visibleWhen(FakeLag::constantMode),
                rangeInts(
                                "low_health_cycles",
                                "Low health cycles",
                                "lowhealthfakelag.cycles.min",
                                "lowhealthfakelag.cycles.max",
                                1,
                                5,
                                1,
                                20,
                                1,
                                LowHealthFakeLag::setCycles)
                        .visibleWhen(FakeLag::lowHealthMode),
                rangeInts(
                                "low_health_delay",
                                "Low health delay (ms)",
                                "lowhealthfakelag.delay.min",
                                "lowhealthfakelag.delay.max",
                                300,
                                600,
                                0,
                                5000,
                                10,
                                LowHealthFakeLag::setDelay)
                        .visibleWhen(FakeLag::lowHealthMode),
                rangeDoubles(
                                "low_health_range",
                                "Low health enemy range",
                                "lowhealthfakelag.range.min",
                                "lowhealthfakelag.range.max",
                                2,
                                5,
                                0,
                                16,
                                .1,
                                LowHealthFakeLag::setRange)
                        .visibleWhen(FakeLag::lowHealthMode),
                numeric(
                                "low_health_cooldown",
                                "Low health cooldown (s)",
                                "number",
                                () ->
                                        Settings.getInt("lowhealthfakelag.cooldown.ms", 10000)
                                                / 1000.0,
                                0,
                                60,
                                .5,
                                LowHealthFakeLag::setCooldownSeconds)
                        .withDefault(10.0)
                        .visibleWhen(FakeLag::lowHealthMode),
                number(
                                "random_chance",
                                "Random chance",
                                "randomfakelag.chance",
                                .35,
                                0,
                                1,
                                .01,
                                RandomFakeLag::setChance)
                        .visibleWhen(FakeLag::randomMode),
                rangeInts(
                                "random_delay",
                                "Random delay (ms)",
                                "randomfakelag.delay.min",
                                "randomfakelag.delay.max",
                                180,
                                420,
                                0,
                                5000,
                                10,
                                RandomFakeLag::setDelay)
                        .visibleWhen(FakeLag::randomMode),
                rangeDoubles(
                                "random_range",
                                "Random range",
                                "randomfakelag.range.min",
                                "randomfakelag.range.max",
                                2,
                                5,
                                0,
                                16,
                                .1,
                                RandomFakeLag::setRange)
                        .visibleWhen(FakeLag::randomMode),
                numeric(
                                "random_cooldown",
                                "Random cooldown (s)",
                                "number",
                                () -> Settings.getInt("randomfakelag.cooldown.ms", 5000) / 1000.0,
                                0,
                                60,
                                .5,
                                RandomFakeLag::setCooldownSeconds)
                        .withDefault(5.0)
                        .visibleWhen(FakeLag::randomMode));
    }
}
