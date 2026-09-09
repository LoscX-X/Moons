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
                numeric(
                        "delay",
                        "Track time (ms)",
                        "integer",
                        Backtrack::delayMillis,
                        0,
                        1000,
                        1,
                        (client, value) ->
                                Backtrack.setDelay(client, Integer.toString((int) value))),
                numeric(
                        "range",
                        "Max track range",
                        "number",
                        Backtrack::maxRange,
                        0,
                        10,
                        .1,
                        (client, value) -> Backtrack.setRange(client, Double.toString(value))),
                choice(
                        "esp",
                        "Real location ESP",
                        "backtrack.esp",
                        "box",
                        Backtrack.espModeOptions(),
                        Backtrack::setEsp));
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
                        .visibleWhen(FakeLag::randomMode));
    }
}
