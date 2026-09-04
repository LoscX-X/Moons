package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.*;
import com.blanoir.moons.client.module.impl.combat.aim.AimAssist;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.critical.mode.Predict;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.config.feature.HitEstimateSettings;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines combat module descriptors; ordering is owned by ModuleCatalog. */
final class Combat {
    private Combat() {
    }

    static ModuleRegistry.Module autoClicker() {
        return module("autoclicker", "AutoClicker", ModuleCategories.COMBAT,
                        AutoClicker::isEnabled, AutoClicker::setEnabled, AutoClicker::hudTag,
                        number("min_cps", "Min CPS", "autoclicker.minCps", 13, 1, 20, .1, AutoClicker::setMinCps),
                        number("max_cps", "Max CPS", "autoclicker.maxCps", 15, 1, 20, .1, AutoClicker::setMaxCps),
                        bool("break_blocks", "Break blocks", "autoclicker.breakBlocks", true, AutoClicker::setBreakBlocks),
                        bool("sag", "Sag", "autoclicker.sag", false, AutoClicker::setSag),
                        integer("sag_block_ticks", "Sag blocking ticks", "autoclicker.sagBlockingTicks",
                                4, 0, 20, 1, AutoClicker::setSagBlockingTicks),
                        integer("sag_unblock_ticks", "Sag unblock ticks", "autoclicker.sagUnblockTicks",
                                0, 0, 20, 1, AutoClicker::setSagUnblockTicks));
    }

    static ModuleRegistry.Module reach() {
        return module("reach", "Reach", ModuleCategories.COMBAT,
                        Reach::isEnabled, Reach::setEnabled, Reach::statusTag,
                        choice("mode", "Mode", "reach.mode", "advanced",
                                Reach.modeOptions(), Reach::setMode),
                        number("range", "Target range", "reach.range", 3.1, 3, 6, .05,
                                Reach::setRange),
                        number("chance", "Chance %", "reach.normal.chance", 100, 0, 100, 1,
                                Reach::setChance).visibleWhen(() -> !Reach.advancedMode()),
                        integer("timeout", "Target lag timeout", "reach.advanced.timeout", 20, 1, 40, 1,
                                Reach::setTimeout).visibleWhen(Reach::advancedMode));
    }

    static ModuleRegistry.Module sprintReset() {
        return module("sprintreset", "SprintReset", ModuleCategories.COMBAT,
                        SprintReset::isEnabled, SprintReset::setEnabled, SprintReset::hudTag,
                        choice("mode", "Mode", "sprintreset.mode", "no_stop",
                                SprintReset.modeOptions(), SprintReset::setMode),
                        integer("interval", "Interval ms", "sprintreset.intervalMs",
                                400, 0, 2000, 1, SprintReset::setIntervalMs),
                        bool("require_damage", "Require target damage", "sprintreset.requireTargetDamage",
                                true, SprintReset::setRequireTargetDamage),
                        integer("duration", "Duration ms", "sprintreset.durationMs",
                                50, 0, 200, 1, SprintReset::setDurationMs));
    }

    static ModuleRegistry.Module silentAura() {
        return module("silentaura", "SilentAura", "Combat", SilentAura::isEnabled,
                        SilentAura::setEnabled, SilentAura::hudTag,
                        number("range", "Attack range", "silentaura.range", 3.7, 1, 6, .05, SilentAura::setRange),
                        number("scan_extra", "Scan range increase", "silentaura.scanExtra", 2.5, 0, 7, .1,
                                SilentAura::setScanExtra),
                        number("fov", "FOV", "silentaura.fov", 180, 1, 360, 1, SilentAura::setFov),
                        choice("target_mode", "Target mode", "silentaura.targetMode", "switch",
                                SilentAuraConfig.targetModeOptions(),
                                SilentAura::setTargetMode),
                        integer("hurt_time", "Maximum hurt time", "silentaura.hurtTime", 10, 0, 10, 1,
                                SilentAura::setHurtTime),
                        customChoice("aim_mode", "Aim mode", SilentAuraConfig::aimMode,
                                SilentAuraConfig.aimModeOptions(), SilentAura::setAimMode),
                        number("smooth", "Smooth", "silentaura.smooth", .58, .05, 1, .01,
                                SilentAura::setSmooth).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        bool("return_rotation", "Return rotation", "silentaura.returnRotation", true,
                                SilentAura::setReturnRotation),
                        number("return_smooth", "Return smooth", "silentaura.returnSmooth", .45, .05, 1, .01,
                                SilentAura::setReturnSmooth).visibleWhen(SilentAuraConfig::returnRotation),
                        number("jitter", "Path jitter", "silentaura.jitter", .38, 0, 1, .01,
                                SilentAura::setJitter).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        number("jitter_speed", "Jitter speed", "silentaura.jitterSpeed", .85, .1, 3, .05,
                                SilentAura::setJitterSpeed).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        number("settled_jitter", "Settled sway", "silentaura.settledJitter", .55, 0, 1, .01,
                                SilentAura::setSettledJitter).visibleWhen(SilentAuraConfig::balanceMode),
                        number("aim_wander", "Aim wander", "silentaura.aimWander", .45, 0, 1, .01,
                                SilentAura::setAimWander).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        integer("aim_wander_ticks", "Wander interval", "silentaura.aimWanderTicks", 9, 2, 40, 1,
                                SilentAura::setAimWanderTicks).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        number("prediction_lead", "Velocity lead", "silentaura.predictionLead", .5, 0, 2, .05,
                                SilentAura::setPredictionLead).visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        integer("full_lock_angle_step", "Full-lock angle step",
                                "silentaura.fullLock.angleStep", 90, 30, 180, 1,
                                SilentAura::setFullLockAngleStep)
                                .visibleWhen(SilentAuraConfig::fullLockMode),
                        number("full_lock_smoothing", "Full-lock smoothing",
                                "silentaura.fullLock.smoothing", 0, 0, 1, .01,
                                SilentAura::setFullLockSmoothing)
                                .visibleWhen(SilentAuraConfig::fullLockMode),
                        number("full_lock_prediction", "Full-lock lead ticks",
                                "silentaura.fullLock.prediction", 1, 0, 3, .05,
                                SilentAura::setFullLockPrediction)
                                .visibleWhen(SilentAuraConfig::fullLockMode),
                        bool("matrix", "Matrix compatibility", "silentaura.matrix", false,
                                SilentAura::setMatrixCompatibility)
                                .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        bool("critical", "Critical", "silentaura.critical", true,
                                SilentAura::setCriticalIntegration),
                        customChoice("aim_point", "Aim point", SilentAuraConfig::aimPoint,
                                SilentAuraConfig.aimPointOptions(), SilentAura::setAimPoint)
                                .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        number("prediction", "Turn prediction", "silentaura.predictionStrength", 1, 0, 3, .05,
                                SilentAura::setPredictionStrength)
                                .visibleWhen(() -> !SilentAuraConfig.fullLockMode()),
                        range("charge", "Attack charge", "silentaura.minCharge", "silentaura.maxCharge",
                                .7, 1, .7, 1.3, .01, SilentAura::setCharge),
                        bool("block", "Block", "silentaura.block", true, SilentAura::setBlock),
                        bool("target_players", "Target players", "silentaura.target.player", true,
                                (client, value) -> SilentAura.setTargetCategory(client, "player", value)),
                        bool("target_mobs", "Target mobs", "silentaura.target.mob", false,
                                (client, value) -> SilentAura.setTargetCategory(client, "mob", value)),
                        bool("debugger", "Debugger", "silentaura.debugger", false,
                                SilentAura::setDebugger));
    }

    static ModuleRegistry.Module aimAssist() {
        return module(
                        "aimassist",
                        "AimAssist",
                        "Combat",
                        AimAssist::isEnabled,
                        AimAssist::setEnabled,
                        () -> "Assist",
        
                        number(
                                "range", "Range",
                                "aimassist.range",
                                4.2, 1, 8, .05,
                                AimAssist::setRange
                        ),
        
                        number(
                                "fov", "FOV",
                                "aimassist.fov",
                                45, 1, 360, 1,
                                AimAssist::setFov
                        ),
        
                        number(
                                "smooth", "Smooth",
                                "aimassist.smooth",
                                .35, .05, 1, .01,
                                AimAssist::setSmooth
                        )
                );
    }

    static ModuleRegistry.Module triggerBot() {
        return module("triggerbot", "TriggerBot", "Combat", TriggerBot::isEnabled,
                        TriggerBot::setEnabled, () -> rangeText("triggerbot.minCharge", "triggerbot.maxCharge", .7, 1.3),
                        range("charge", "Attack charge", "triggerbot.minCharge", "triggerbot.maxCharge",
                                .7, 1.3, .7, 1.3, .01, TriggerBot::setChargeRange),
                        range("miss_delay", "Miss delay", "triggerbot.minMissDelaySeconds", "triggerbot.maxMissDelaySeconds",
                                0, 0, 0, 2, .01, TriggerBot::setMissDelayRange),
                        bool("through_block", "Through block", "triggerbot.throughBlock.enabled", false,
                                TriggerBot::setThroughBlockEnabled),
                        bool("target_players", "Target players", "triggerbot.target.player", true,
                                (client, value) -> TriggerBot.setTargetCategory(client, "player", value)),
                        bool("target_mobs", "Target mobs", "triggerbot.target.mob", false,
                                (client, value) -> TriggerBot.setTargetCategory(client, "mob", value)));
    }

    static ModuleRegistry.Module critical() {
        return module("critical", "Critical", "Combat", Critical::isEnabled,
                        Critical::setEnabled, Critical::modeName,
                        choice("mode", "Mode", "critical.mode", "predict", Critical.modeIds(),
                                Critical::setMode),
                        number("window", "Prediction window", "predictcritical.window", .3, .05, .6, .01,
                                Predict::setWindow).visibleWhen(Critical::predictMode),
                        bool("stop_sprint", "Stop sprint", "predictcritical.stopSprint", true,
                                Predict::setStopSprint).visibleWhen(Critical::predictMode),
                        bool("sync", "Sync", "predictcritical.sync", true, Predict::setSyncEnabled)
                                .visibleWhen(Critical::predictMode),
                        integer("overcharge", "Overcharge ticks", "predictcritical.overcharge", 2, 0, 6, 1,
                                Predict::setMaxOverchargeTicks).visibleWhen(Critical::predictMode),
                        integer("cycles", "Sync cycles", "predictcritical.cycles", 2, 1, 3, 1,
                                Predict::setSyncCycles).visibleWhen(Critical::predictMode));
    }

    static ModuleRegistry.Module jumpReset() {
        return module("jumpreset", "Velocity", ModuleCategories.COMBAT, Velocity::isEnabled,
                        Velocity::setEnabled, Velocity::statusTag,
                        choice("mode", "Mode", "velocity.mode", "jumpreset",
                                Velocity.modeOptions(), Velocity::setMode),
                        number("chance", "Chance %", "velocity.chance", 100, 0, 100, 1,
                                Velocity::setChance).visibleWhen(Velocity::vanillaMode),
                        number("horizontal", "Horizontal %", "velocity.horizontal", 0, 0, 100, 1,
                                Velocity::setHorizontal).visibleWhen(Velocity::vanillaMode),
                        number("vertical", "Vertical %", "velocity.vertical", 100, 0, 100, 1,
                                Velocity::setVertical).visibleWhen(Velocity::vanillaMode),
                        number("explosion_horizontal", "Explosion horizontal %", "velocity.explosionHorizontal",
                                100, 0, 100, 1, Velocity::setExplosionHorizontal)
                                .visibleWhen(Velocity::vanillaMode),
                        number("explosion_vertical", "Explosion vertical %", "velocity.explosionVertical",
                                100, 0, 100, 1, Velocity::setExplosionVertical)
                                .visibleWhen(Velocity::vanillaMode),
                        bool("fake_check", "Fake check", "velocity.fakeCheck", true,
                                Velocity::setFakeCheck).visibleWhen(Velocity::vanillaMode),
                        bool("other_attacks", "Other attacks", "velocity.otherAttacks", false,
                                Velocity::setOtherAttacks).visibleWhen(Velocity::vanillaMode)
                                .enabledWhen(Velocity::fakeCheckEnabled),
                        bool("jump_rotate", "Rotate", "velocity.jump.rotate", false,
                                Velocity::setRotate).visibleWhen(Velocity::jumpMode),
                        bool("jump_follow_direction", "Follow direction", "velocity.jump.followDirection", false,
                                Velocity::setFollowDirection).visibleWhen(Velocity::jumpMode),
                        integer("jump_rotate_ticks", "Rotate ticks", "velocity.jump.rotateTicks", 12, 3, 20, 1,
                                Velocity::setRotateTicks).visibleWhen(Velocity::jumpMode)
                                .enabledWhen(Velocity::jumpRotationEnabled),
                        number("jumpreset_chance", "JumpReset chance", "jumpreset.chance",
                                .5, 0, 1, .01, JumpReset::setChance).visibleWhen(Velocity::jumpResetMode));
    }
}
