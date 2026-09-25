package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.*;
import com.blanoir.moons.client.module.impl.combat.aim.AimAssist;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.critical.mode.Predict;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.utils.registry.RegistryLists;

/** Defines combat module descriptors; ordering is owned by ModuleCatalog. */
final class Combat {
    private Combat() {}

    static ModuleRegistry.Module hitSelect() {
        return module(
                "hitselect",
                "HitSelect",
                ModuleCategories.COMBAT,
                HitSelect::isEnabled,
                HitSelect::setEnabled,
                HitSelect::statusTag,
                integer(
                        "pause", "Repeat pause (ms)", "hitselect.pauseMs",
                        450, 0, 500, 10, HitSelect::setPause),
                integer(
                        "first", "Wait for first hit (ms)", "hitselect.firstMs",
                        150, 0, 500, 10, HitSelect::setFirst),
                integer(
                        "trade", "Wait in trades (ms)", "hitselect.tradeMs",
                        0, 0, 250, 10, HitSelect::setTrade),
                bool(
                        "servertime", "Use damage confirmation", "hitselect.serverTime",
                        false, HitSelect::setServerTime));
    }

    static ModuleRegistry.Module misplace() {
        return module(
                "misplace",
                "Misplace",
                ModuleCategories.COMBAT,
                Misplace::isEnabled,
                Misplace::setEnabled,
                Misplace::statusTag,
                bool(
                        "adaptive",
                        "Dynamic offset",
                        "misplace.adaptive",
                        true,
                        Misplace::setAdaptive),
                number(
                        "distance",
                        "Maximum offset",
                        "misplace.distance",
                        .4,
                        0,
                        1.5,
                        .05,
                        Misplace::setDistance),
                integer(
                        "prediction",
                        "Prediction limit (ms)",
                        "misplace.predictionMs",
                        400,
                        0,
                        1000,
                        10,
                        Misplace::setPrediction),
                integer("jitter", "Timing uncertainty (ms)", "misplace.jitterMs", 15,
                        0, 100, 5, Misplace::setJitter),
                bool(
                        "knockback",
                        "Track knockback timing",
                        "misplace.knockback",
                        true,
                        Misplace::setKnockback),
                integer(
                        "smoothing",
                        "Smoothing (ms)",
                        "misplace.smoothingMs",
                        60,
                        0,
                        200,
                        10,
                        Misplace::setSmoothing));
    }

    static ModuleRegistry.Module autoMace() {
        return module(
                "automace",
                "AutoMace",
                ModuleCategories.COMBAT,
                AutoMace::isEnabled,
                AutoMace::setEnabled,
                AutoMace::statusTag,
                bool(
                        "require_attack",
                        "Hold attack to start",
                        "automace.requireAttack",
                        true,
                        AutoMace::setRequireAttack),
                number(
                        "range",
                        "Target range",
                        "automace.range",
                        3.0,
                        1.0,
                        3.0,
                        .1,
                        AutoMace::setRange),
                integer(
                        "cooldown",
                        "Combo cooldown (ticks)",
                        "automace.cooldown",
                        40,
                        20,
                        200,
                        5,
                        AutoMace::setCooldown));
    }

    static ModuleRegistry.Module autoSpear() {
        return module(
                "autospear",
                "SpearAssist",
                ModuleCategories.COMBAT,
                AutoSpear::isEnabled,
                AutoSpear::setEnabled,
                () -> "",
                bool(
                        "ignore_cooldown",
                        "Ignore client cooldown",
                        "autospear.ignoreCooldown",
                        false,
                        AutoSpear::setIgnoreCooldown),
                bool(
                        "full_hold",
                        "Full hold",
                        "autospear.fullHold",
                        false,
                        AutoSpear::setFullHold),
                number(
                        "motion_multiply",
                        "Motion multiply",
                        "autospear.motionMultiply",
                        1.0,
                        1.0,
                        5.0,
                        .1,
                        AutoSpear::setMotionMultiply),
                bool(
                        "impact_burst",
                        "Impact burst (experimental)",
                        "autospear.impactBurst",
                        false,
                        AutoSpear::setImpactBurst),
                number(
                                "impact_multiply",
                                "Impact multiply",
                                "autospear.impactMultiply",
                                8.0,
                                1.0,
                                20.0,
                                .5,
                                AutoSpear::setImpactMultiply)
                        .visibleWhen(AutoSpear::impactBurstEnabled),
                bool("blink", "FakeLag", "autospear.blink", false, AutoSpear::setFakeLag),
                integer(
                                "blink_duration",
                                "FakeLag delay (ms)",
                                "autospear.blinkDurationMs",
                                150,
                                50,
                                500,
                                25,
                                AutoSpear::setFakeLagDelay)
                        .visibleWhen(AutoSpear::fakeLagEnabled),
                integer(
                                "fakelag_duration",
                                "FakeLag duration (ms)",
                                "autospear.fakeLagDurationMs",
                                500,
                                100,
                                2000,
                                50,
                                AutoSpear::setFakeLagDuration)
                        .visibleWhen(AutoSpear::fakeLagEnabled));
    }

    static ModuleRegistry.Module autoClicker() {
        return module(
                "autoclicker",
                "AutoClicker",
                ModuleCategories.COMBAT,
                AutoClicker::isEnabled,
                AutoClicker::setEnabled,
                AutoClicker::hudTag,
                number(
                        "min_cps",
                        "Min CPS",
                        "autoclicker.minCps",
                        13,
                        1,
                        20,
                        .1,
                        AutoClicker::setMinCps),
                number(
                        "max_cps",
                        "Max CPS",
                        "autoclicker.maxCps",
                        15,
                        1,
                        20,
                        .1,
                        AutoClicker::setMaxCps),
                bool(
                        "break_blocks",
                        "Break blocks",
                        "autoclicker.breakBlocks",
                        true,
                        AutoClicker::setBreakBlocks),
                bool("sag", "Sag", "autoclicker.sag", false, AutoClicker::setSag),
                integer(
                        "sag_block_ticks",
                        "Sag blocking ticks",
                        "autoclicker.sagBlockingTicks",
                        4,
                        0,
                        20,
                        1,
                        AutoClicker::setSagBlockingTicks),
                integer(
                        "sag_unblock_ticks",
                        "Sag unblock ticks",
                        "autoclicker.sagUnblockTicks",
                        0,
                        0,
                        20,
                        1,
                        AutoClicker::setSagUnblockTicks));
    }

    static ModuleRegistry.Module reach() {
        return module(
                "reach",
                "Reach",
                ModuleCategories.COMBAT,
                Reach::isEnabled,
                Reach::setEnabled,
                Reach::statusTag,
                number(
                        "range",
                        "Target range",
                        "reach.range",
                        3.1,
                        3,
                        6,
                        .05,
                        Reach::setRange),
                number(
                        "chance",
                        "Chance %",
                        "reach.normal.chance",
                        100,
                        0,
                        100,
                        1,
                        Reach::setChance));
    }

    static ModuleRegistry.Module sprintReset() {
        return module(
                "sprintreset",
                "SprintReset",
                ModuleCategories.COMBAT,
                SprintReset::isEnabled,
                SprintReset::setEnabled,
                SprintReset::hudTag,
                choice(
                        "mode",
                        "Mode",
                        "sprintreset.mode",
                        "no_stop",
                        SprintReset.modeOptions(),
                        SprintReset::setMode),
                integer(
                        "interval",
                        "Interval ms",
                        "sprintreset.intervalMs",
                        400,
                        0,
                        2000,
                        1,
                        SprintReset::setIntervalMs),
                bool(
                        "require_damage",
                        "Require target damage",
                        "sprintreset.requireTargetDamage",
                        true,
                        SprintReset::setRequireTargetDamage),
                integer(
                        "duration",
                        "Duration ms",
                        "sprintreset.durationMs",
                        50,
                        0,
                        200,
                        1,
                        SprintReset::setDurationMs));
    }

    static ModuleRegistry.Module autoBlock() {
        return module(
                        "autoblock",
                        "AutoBlock",
                        ModuleCategories.COMBAT,
                        AutoBlock::isEnabled,
                        AutoBlock::setEnabled,
                        AutoBlock::hudTag,
                        AutoBlock.settings())
                .withHudTag("Legacy");
    }

    static ModuleRegistry.Module silentAura() {
        return module(
                        "silentaura",
                        "SilentAura",
                        "Combat",
                        SilentAura::isEnabled,
                        SilentAura::setEnabled,
                        SilentAura::hudTag,
                        SilentAuraConfig.settings())
                .withHudTag(() -> SilentAuraConfig.legacyCombat() ? "Legacy" : "Latest");
    }

    static ModuleRegistry.Module aimAssist() {
        return module(
                "aimassist",
                "AimAssist",
                "Combat",
                AimAssist::isEnabled,
                AimAssist::setEnabled,
                AimAssist::mode,
                AimAssist.settings());
    }

    static ModuleRegistry.Module triggerBot() {
        return module(
                "triggerbot",
                "TriggerBot",
                "Combat",
                TriggerBot::isEnabled,
                TriggerBot::setEnabled,
                () -> rangeText("triggerbot.minCharge", "triggerbot.maxCharge", .7, 1.3),
                range(
                        "charge",
                        "Attack charge",
                        "triggerbot.minCharge",
                        "triggerbot.maxCharge",
                        .7,
                        1.3,
                        .7,
                        1.3,
                        .01,
                        TriggerBot::setChargeRange),
                range(
                        "miss_delay",
                        "Miss delay",
                        "triggerbot.minMissDelaySeconds",
                        "triggerbot.maxMissDelaySeconds",
                        0,
                        0,
                        0,
                        2,
                        .01,
                        TriggerBot::setMissDelayRange),
                bool(
                        "through_block",
                        "Through block",
                        "triggerbot.throughBlock.enabled",
                        false,
                        TriggerBot::setThroughBlockEnabled),
                bool(
                        "target_players",
                        "Target players",
                        "triggerbot.target.player",
                        true,
                        (client, value) -> TriggerBot.setTargetCategory(client, "player", value)),
                RegistryLists.setting(
                        "target_entities",
                        "Other entities",
                        "mob",
                        TriggerBot::selectedEntities,
                        TriggerBot::setSelectedEntities,
                        new com.google.gson.JsonArray()));
    }

    static ModuleRegistry.Module critical() {
        return module(
                "critical",
                "Critical",
                "Combat",
                Critical::isEnabled,
                Critical::setEnabled,
                Critical::modeName,
                choice(
                        "mode",
                        "Mode",
                        "critical.mode",
                        "predict",
                        Critical.modeIds(),
                        Critical::setMode),
                number(
                                "window",
                                "Prediction window",
                                "predictcritical.window",
                                .3,
                                .05,
                                .6,
                                .01,
                                Predict::setWindow)
                        .visibleWhen(Critical::predictMode),
                bool(
                                "stop_sprint",
                                "Stop sprint",
                                "predictcritical.stopSprint",
                                true,
                                Predict::setStopSprint)
                        .visibleWhen(Critical::predictMode),
                bool("sync", "Sync", "predictcritical.sync", true, Predict::setSyncEnabled)
                        .visibleWhen(Critical::predictMode),
                integer(
                                "overcharge",
                                "Overcharge ticks",
                                "predictcritical.overcharge",
                                2,
                                0,
                                6,
                                1,
                                Predict::setMaxOverchargeTicks)
                        .visibleWhen(Critical::predictMode),
                integer(
                                "cycles",
                                "Sync cycles",
                                "predictcritical.cycles",
                                2,
                                1,
                                3,
                                1,
                                Predict::setSyncCycles)
                        .visibleWhen(Critical::predictMode));
    }

    static ModuleRegistry.Module jumpReset() {
        return module(
                "jumpreset",
                "Velocity",
                ModuleCategories.COMBAT,
                Velocity::isEnabled,
                Velocity::setEnabled,
                Velocity::statusTag,
                choice(
                        "mode",
                        "Mode",
                        "velocity.mode",
                        "jumpreset",
                        Velocity.modeOptions(),
                        Velocity::setMode),
                number("chance", "Chance %", "velocity.chance", 100, 0, 100, 1, Velocity::setChance)
                        .visibleWhen(Velocity::normalMode),
                number(
                                "horizontal",
                                "Horizontal %",
                                "velocity.horizontal",
                                0,
                                0,
                                100,
                                1,
                                Velocity::setHorizontal)
                        .visibleWhen(Velocity::normalMode),
                number(
                                "vertical",
                                "Vertical %",
                                "velocity.vertical",
                                100,
                                0,
                                100,
                                1,
                                Velocity::setVertical)
                        .visibleWhen(Velocity::normalMode),
                number(
                                "explosion_horizontal",
                                "Explosion horizontal %",
                                "velocity.explosionHorizontal",
                                100,
                                0,
                                100,
                                1,
                                Velocity::setExplosionHorizontal)
                        .visibleWhen(Velocity::normalMode),
                number(
                                "explosion_vertical",
                                "Explosion vertical %",
                                "velocity.explosionVertical",
                                100,
                                0,
                                100,
                                1,
                                Velocity::setExplosionVertical)
                        .visibleWhen(Velocity::normalMode),
                bool("fake_check", "Fake check", "velocity.fakeCheck", true, Velocity::setFakeCheck)
                        .visibleWhen(Velocity::normalMode),
                bool(
                                "other_attacks",
                                "Other attacks",
                                "velocity.otherAttacks",
                                false,
                                Velocity::setOtherAttacks)
                        .visibleWhen(Velocity::normalMode)
                        .enabledWhen(Velocity::fakeCheckEnabled),
                number(
                                "jumpreset_chance",
                                "JumpReset chance",
                                "jumpreset.chance",
                                .5,
                                0,
                                1,
                                .01,
                                JumpReset::setChance)
                        .visibleWhen(Velocity::jumpResetMode));
    }
}
