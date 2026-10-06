package com.blanoir.moons.client.module.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.SettingSpec;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.Disabler;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.module.impl.network.LowHealthFakeLag;
import com.blanoir.moons.client.module.impl.network.RandomFakeLag;
import com.blanoir.moons.client.module.impl.network.backtrack.BacktrackSettings;

/** Defines network module descriptors; ordering is owned by ModuleCatalog. */
final class Network {
    private Network() {}

    static ModuleRegistry.Module disabler() {
        return module(
                        "disabler",
                        "Disabler",
                        ModuleCategories.NETWORK,
                        Disabler::isEnabled,
                        Disabler::setEnabled,
                        Disabler::statusTag)
                .withHudTag(Disabler::hudTag, "Waiting", "Held");
    }

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
                        .withDefault(BacktrackSettings.TARGET_MODE.defaultId()),
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
                                BacktrackSettings.DELAY_MAX.min().doubleValue(),
                                BacktrackSettings.DELAY_MAX.max().doubleValue(),
                                1.0,
                                java.util.List.of(),
                                (client, value) ->
                                        Backtrack.setDelay(
                                                client,
                                                value.getAsJsonArray().get(0).getAsInt()
                                                        + "-"
                                                        + value.getAsJsonArray().get(1).getAsInt()))
                        .withDefault(
                                BacktrackSettings.DELAY_MIN.defaultValue(),
                                BacktrackSettings.DELAY_MAX.defaultValue()),
                numeric(
                                "range",
                                "Max range",
                                "number",
                                Backtrack::maxRange,
                                BacktrackSettings.RANGE_MAX.min(),
                                BacktrackSettings.RANGE_MAX.max(),
                                .1,
                                (client, value) ->
                                        Backtrack.setRange(client, Double.toString(value)))
                        .withDefault(BacktrackSettings.RANGE_MAX.defaultValue()),
                choice(
                        "esp",
                        "ESP",
                        BacktrackSettings.ESP.key(),
                        BacktrackSettings.ESP.defaultId(),
                        Backtrack.espModeOptions(),
                        Backtrack::setEsp),
                internalNumber("min_range", "Min range", BacktrackSettings.RANGE_MIN),
                internalInt("next_min", "Next delay min", BacktrackSettings.NEXT_MIN),
                internalInt("next_max", "Next delay max", BacktrackSettings.NEXT_MAX),
                internalInt(
                        "tracking_buffer", "Tracking buffer", BacktrackSettings.TRACKING_BUFFER),
                internalNumber("chance", "Chance", BacktrackSettings.CHANCE),
                internalBool("pause_hurt", "Pause on hurt", BacktrackSettings.PAUSE_HURT),
                internalInt("hurt_time", "Hurt time", BacktrackSettings.HURT_TIME),
                internalInt("last_attack", "Last attack", BacktrackSettings.LAST_ATTACK),
                internalInt("max_queue", "Queue limit", BacktrackSettings.QUEUE_LIMIT),
                internalNumber("ping_ratio", "Ping ratio", BacktrackSettings.PING_RATIO),
                internalBool("actionbar", "Action bar", BacktrackSettings.ACTION_BAR));
    }

    private static Setting internalInt(String id, String label, SettingSpec<Integer> spec) {
        return numeric(
                        id,
                        label,
                        "integer",
                        () ->
                                Math.clamp(
                                        Settings.getInt(spec.key(), spec.defaultValue()),
                                        spec.min(),
                                        spec.max()),
                        spec.min(),
                        spec.max(),
                        1,
                        (client, value) -> {
                            Settings.setInt(spec.key(), (int) Math.round(value));
                        })
                .withDefault(spec.defaultValue())
                .visibleWhen(() -> false);
    }

    private static Setting internalNumber(String id, String label, SettingSpec<Double> spec) {
        return numeric(
                        id,
                        label,
                        "number",
                        () -> {
                            double value = Settings.getDouble(spec.key(), spec.defaultValue());
                            return Double.isFinite(value)
                                    ? Math.clamp(value, spec.min(), spec.max())
                                    : spec.defaultValue();
                        },
                        spec.min(),
                        spec.max(),
                        .1,
                        (client, value) -> {
                            Settings.setDouble(spec.key(), value);
                        })
                .withDefault(spec.defaultValue())
                .visibleWhen(() -> false);
    }

    private static Setting internalBool(String id, String label, SettingSpec<Boolean> spec) {
        return bool(
                        id,
                        label,
                        spec.key(),
                        spec.defaultValue(),
                        (client, value) -> {
                            Settings.setBoolean(spec.key(), value);
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
