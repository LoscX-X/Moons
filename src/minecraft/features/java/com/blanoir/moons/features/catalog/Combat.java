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

/** Defines combat module descriptors; ordering is owned by ModuleCatalog. */
final class Combat {
    private Combat() {}

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
                choice(
                        "mode",
                        "Mode",
                        "reach.mode",
                        "advanced",
                        Reach.modeOptions(),
                        Reach::setMode),
                number("range", "Target range", "reach.range", 3.1, 3, 6, .05, Reach::setRange),
                number(
                                "chance",
                                "Chance %",
                                "reach.normal.chance",
                                100,
                                0,
                                100,
                                1,
                                Reach::setChance)
                        .visibleWhen(() -> !Reach.advancedMode()),
                integer(
                                "timeout",
                                "Target lag timeout",
                                "reach.advanced.timeout",
                                20,
                                1,
                                40,
                                1,
                                Reach::setTimeout)
                        .visibleWhen(Reach::advancedMode));
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
                AutoBlock.settings());
    }

    static ModuleRegistry.Module silentAura() {
        return module(
                "silentaura",
                "SilentAura",
                "Combat",
                SilentAura::isEnabled,
                SilentAura::setEnabled,
                SilentAura::hudTag,
                SilentAuraConfig.settings());
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
                bool(
                        "target_mobs",
                        "Target mobs",
                        "triggerbot.target.mob",
                        false,
                        (client, value) -> TriggerBot.setTargetCategory(client, "mob", value)));
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
