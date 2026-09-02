package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.module.impl.network.FakeLag;
import com.blanoir.moons.client.module.impl.network.LowHealthFakeLag;
import com.blanoir.moons.client.module.impl.network.RandomFakeLag;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines network module descriptors; ordering is owned by ModuleCatalog. */
final class Network {
    private Network() {
    }

    static ModuleRegistry.Module backtrack() {
        return module("backtrack", "Backtrack", ModuleCategories.NETWORK, Backtrack::isEnabled,
                        Backtrack::setEnabled, Backtrack::hudStats,
                        range("range", "Range", "backtrack.range.min", "backtrack.range.max", 1, 3, 0, 10, .1,
                                Backtrack::setRange),
                        range("delay", "Delay (ms)", "backtrack.delay.min", "backtrack.delay.max", 100, 150, 0, 1000, 1,
                                Backtrack::setDelay),
                        range("next_delay", "Next delay (ms)", "backtrack.nextBacktrackDelay.min", "backtrack.nextBacktrackDelay.max",
                                0, 10, 0, 2000, 1, Backtrack::setNextBacktrackDelay),
                        integerText("tracking_buffer", "Tracking buffer", "backtrack.trackingBuffer", 500, 0, 2000, 1,
                                Backtrack::setTrackingBuffer),
                        numberText("chance", "Chance %", "backtrack.chance", 50, 0, 100, 1, Backtrack::setChance),
                        choice("target_mode", "Target mode", "backtrack.targetMode", "attack",
                                Backtrack.targetModeOptions(), Backtrack::setTargetMode),
                        bool("pause_hurt", "Pause on hurt", "backtrack.pauseOnHurtTime.enabled", false,
                                Backtrack::setHurtEnabled),
                        integerText("hurt_time", "Hurt time", "backtrack.pauseOnHurtTime.hurtTime", 3, 0, 10, 1,
                                Backtrack::setHurtTime),
                        integerText("last_attack", "Last attack time", "backtrack.lastAttackTimeToWork", 1000, 0, 5000, 10,
                                Backtrack::setLastAttackTime),
                        integerText("arm_ticks", "Arm window ticks", "backtrack.armWindowTicks", 2, 1, 5, 1,
                                Backtrack::setArmWindowTicks),
                        integerText("max_packets", "Max packets/tick", "backtrack.maxPacketsPerTick", 2, 1, 10, 1,
                                Backtrack::setMaxPacketsPerTick),
                        integerText("max_queue", "Queue safety limit", "backtrack.maxQueueSize", 256, 32, 1024, 16,
                                Backtrack::setMaxQueueSize),
                        integerText("jitter", "Jitter (ms)", "backtrack.jitter", 15, 0, 50, 1, Backtrack::setJitter),
                        numberText("speed_factor", "Speed factor", "backtrack.speedFactor", 8, 0, 30, .1,
                                Backtrack::setSpeedFactor),
                        numberText("ping_ratio", "Ping ratio", "backtrack.pingRatio", 0, 0, 3, .05,
                                Backtrack::setPingRatio),
                        bool("actionbar", "Action bar", "backtrack.actionbar", false, Backtrack::setActionBarEnabled),
                        choice("esp", "Real location ESP", "backtrack.esp", "wireframe", Backtrack.espModeOptions(),
                                Backtrack::setEsp),
                        text("color", "ESP color", Backtrack::espColor,
                                (client, value) -> Backtrack.setColor(client, value, false))
                                .visibleWhen(Backtrack::espEnabled).enabledWhen(() -> !Backtrack.modelEspSelected()),
                        text("outline_color", "Outline color", Backtrack::espOutlineColor,
                                (client, value) -> Backtrack.setColor(client, value, true)).visibleWhen(Backtrack::espEnabled),
                        integerText("light", "Model light %", "backtrack.esp.model.lightPercent", 60, 0, 100, 1,
                                Backtrack::setLightPercent).visibleWhen(Backtrack::modelEspSelected));
    }

    static ModuleRegistry.Module fakeLag() {
        return module("fakelag", "FakeLag", ModuleCategories.NETWORK, FakeLag::isEnabled,
                        FakeLag::setEnabled, FakeLag::modeName,
                        choice("mode", "Mode", "fakelag.mode", "constant",
                                FakeLag.modeOptions(), FakeLag::setMode),
                        rangeInts("delay", "Delay (ms)", "constantfakelag.delay.min", "constantfakelag.delay.max",
                                300, 600, 0, 5000, 10, FakeLag::setDelay).visibleWhen(FakeLag::constantMode),
                        integer("recoil", "Recoil (ms)", "constantfakelag.recoil", 250, 0, 2000, 10,
                                FakeLag::setRecoil).visibleWhen(FakeLag::constantMode),
                        rangeInts("low_health_cycles", "Low health cycles", "lowhealthfakelag.cycles.min", "lowhealthfakelag.cycles.max",
                                1, 5, 1, 20, 1, LowHealthFakeLag::setCycles).visibleWhen(FakeLag::lowHealthMode),
                        rangeInts("low_health_delay", "Low health delay (ms)", "lowhealthfakelag.delay.min", "lowhealthfakelag.delay.max",
                                300, 600, 0, 5000, 10, LowHealthFakeLag::setDelay).visibleWhen(FakeLag::lowHealthMode),
                        rangeDoubles("low_health_range", "Low health enemy range", "lowhealthfakelag.range.min", "lowhealthfakelag.range.max",
                                2, 5, 0, 16, .1, LowHealthFakeLag::setRange).visibleWhen(FakeLag::lowHealthMode),
                        numeric("low_health_cooldown", "Low health cooldown (s)", "number",
                                () -> Settings.getInt("lowhealthfakelag.cooldown.ms", 10000) / 1000.0,
                                0, 60, .5, LowHealthFakeLag::setCooldownSeconds).visibleWhen(FakeLag::lowHealthMode),
                        number("random_chance", "Random chance", "randomfakelag.chance", .35, 0, 1, .01, RandomFakeLag::setChance).visibleWhen(FakeLag::randomMode),
                        rangeInts("random_delay", "Random delay (ms)", "randomfakelag.delay.min", "randomfakelag.delay.max",
                                180, 420, 0, 5000, 10, RandomFakeLag::setDelay).visibleWhen(FakeLag::randomMode),
                        rangeDoubles("random_range", "Random range", "randomfakelag.range.min", "randomfakelag.range.max",
                                2, 5, 0, 16, .1, RandomFakeLag::setRange).visibleWhen(FakeLag::randomMode),
                        numeric("random_cooldown", "Random cooldown (s)", "number",
                                () -> Settings.getInt("randomfakelag.cooldown.ms", 5000) / 1000.0,
                                0, 60, .5, RandomFakeLag::setCooldownSeconds).visibleWhen(FakeLag::randomMode));
    }
}
