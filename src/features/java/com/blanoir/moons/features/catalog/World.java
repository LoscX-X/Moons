package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.world.*;
import com.blanoir.moons.client.module.world.*;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines world module descriptors; ordering is owned by ModuleCatalog. */
final class World {
    private World() {
    }

    static ModuleRegistry.Module fastPlace() {
        return module("fastplace", "FastPlace", ModuleCategories.PLAYER,
                        FastPlace::isEnabled, FastPlace::setEnabled, FastPlace::statusText,
                        number("delay", "Delay", "fastplace.delay", 1, 1, 3, .05, FastPlace::setDelay),
                        bool("blocks_only", "Blocks only", "fastplace.blocksOnly", true, FastPlace::setBlocksOnly),
                        bool("place_fix", "Place fix", "fastplace.placeFix", true, FastPlace::setPlaceFix),
                        bool("skip_obsidian", "Skip obsidian", "fastplace.skipObsidian", true, FastPlace::setSkipObsidian),
                        bool("skip_interactable", "Skip interactable", "fastplace.skipInteractable", true,
                                FastPlace::setSkipInteractable));
    }

    static ModuleRegistry.Module autoTool() {
        return module("autotool", "AutoTool", "World", cfgBool("autotool.enabled", false), AutoTool::setEnabled, AutoTool::statusText,
                        choice("mode", "Mode", "autotool.mode", "dynamic", AutoTool.modeOptions(), AutoTool::setMode),
                        integer("slot", "Static slot", "autotool.slot", 0, 0, 8, 1, AutoTool::setStaticSlot).visibleWhen(AutoTool::staticMode),
                        bool("ignore_durability", "Ignore durability", "autotool.ignoredurability", false, AutoTool::setIgnoreDurability),
                        bool("silk_touch", "Silk touch", "autotool.silktouch", false, AutoTool::setSilkTouch),
                        bool("sneaking", "Require sneaking", "autotool.sneaking", false, AutoTool::setRequireSneaking),
                        bool("avoid_combat", "Avoid combat", "autotool.combat", false, AutoTool::setNotDuringCombat),
                        integer("combat_grace", "Combat grace", "autotool.combatgrace", 30, 0, 100, 1, AutoTool::setCombatGraceTicks));
    }

    static ModuleRegistry.Module scaffold() {
        return module("scaffold", "Scaffold", "World", Scaffold::isEnabled, Scaffold::setEnabled,
                        Scaffold::statusTag,
                        choice("mode", "Mode", "scaffold.mode", "legit",
                                Scaffold.modeOptions(), Scaffold::setMode),
                        rangeInts("legit_delay", "Sneak delay (ticks)",
                                "scaffold.legitDelayMin", "scaffold.legitDelayMax",
                                2, 3, 0, 10, 1, Scaffold::setLegitDelay)
                                .visibleWhen(Scaffold::legitSelected),
                        choice("telly_rotation", "Telly rotation", "scaffold.tellyRotation", "instant",
                                Scaffold.tellyRotationOptions(), Scaffold::setTellyRotation)
                                .visibleWhen(Scaffold::tellySelected),
                        choice("face_sampling", "Face sampling", "scaffold.faceSampling", "standard",
                                Scaffold.faceSamplingOptions(), Scaffold::setFaceSampling)
                                .visibleWhen(Scaffold::tellySelected),
                        number("telly_start_speed", "Start turn limit", "scaffold.tellyStartSpeed",
                                85, 1, 180, .5, Scaffold::setTellyStartSpeed)
                                .visibleWhen(Scaffold::smoothTellySelected),
                        number("telly_track_speed", "Track turn limit", "scaffold.tellyTrackSpeed",
                                45, 1, 180, .5, Scaffold::setTellyTrackSpeed)
                                .visibleWhen(Scaffold::smoothTellySelected),
                        number("telly_place_angle", "Telly place angle", "scaffold.tellyPlaceAngle",
                                6, .5, 20, .5, Scaffold::setTellyPlaceAngle)
                                .visibleWhen(Scaffold::tellySelected),
                        choice("telly_delay_mode", "Air delay mode", "scaffold.tellyDelayMode", "fixed",
                                Scaffold.tellyDelayModeOptions(), Scaffold::setTellyDelayMode)
                                .visibleWhen(Scaffold::tellySelected),
                        integer("telly_place_delay", "Air delay (ticks)", "scaffold.tellyPlaceDelay",
                                4, 0, 8, 1, Scaffold::setTellyPlaceDelay)
                                .visibleWhen(Scaffold::tellySelected),
                        bool("telly_bps_limit", "Limit forward BPS", "scaffold.tellyBlocksPerSecondEnabled",
                                true, Scaffold::setTellyBlocksPerSecondEnabled)
                                .visibleWhen(Scaffold::tellySelected),
                        rangeInts("telly_blocks_per_second", "Blocks per second",
                                "scaffold.tellyBlocksPerSecondMin", "scaffold.tellyBlocksPerSecondMax",
                                3, 4, 1, 20, 1, Scaffold::setTellyBlocksPerSecond)
                                .visibleWhen(() -> Scaffold.tellySelected() && Scaffold.tellyBpsLimitSelected()),
                        bool("telly_flat", "Flat", "scaffold.tellyFlat",
                                false, Scaffold::setTellyFlat)
                                .visibleWhen(Scaffold::tellySelected),
                        bool("telly_fall_rescue", "Fall rescue", "scaffold.tellyFallRescue",
                                false, Scaffold::setTellyFallRescue)
                                .visibleWhen(Scaffold::tellySelected),
                        choice("strafe", "Strafe", "scaffold.moveFix", "silent",
                                Scaffold.moveFixOptions(), Scaffold::setMoveFix)
                                .visibleWhen(Scaffold::tellySelected),
                        choice("sprint", "Sprint", "scaffold.sprintMode", "vanilla",
                                Scaffold.sprintModeOptions(), Scaffold::setSprintMode)
                                .visibleWhen(Scaffold::tellySelected),
                        choice("tower", "Tower", "scaffold.tower", "vanilla",
                                Scaffold.towerOptions(), Scaffold::setTower)
                                .visibleWhen(Scaffold::tellySelected),
                        bool("block_counter", "Block count", "scaffold.blockCounter", true, Scaffold::setBlockCounter),
                        bool("debugger", "Debugger", "scaffold.debugger", false, Scaffold::setDebugger)
                                .visibleWhen(Scaffold::tellySelected));
    }

    static ModuleRegistry.Module fastBreak() {
        return module("fastbreak", "FastBreak", "World", cfgBool("fastbreak.enabled", false), FastBreak::setEnabled, FastBreak::statusText,
                        bool("only_tool", "Only tool", "fastbreak.onlyTool", false, FastBreak::setOnlyTool),
                        choice("mode", "Mode", "fastbreak.mode", "abort_another", FastBreak.modeOptions(), FastBreak::setMode));
    }

    static ModuleRegistry.Module chestStealer() {
        return module("cheststealer", "ChestStealer", ModuleCategories.PLAYER, cfgBool("cheststealer.enabled", false), ChestStealer::setEnabled,
                        ChestStealer::statusText,
                        range("miss", "Miss delay (ms)", "cheststealer.miss.min", "cheststealer.miss.max", 0, 0, 0, 5000, 1,
                                ChestStealer::setMiss));
    }

    static ModuleRegistry.Module lightningTracker() {
        return module("lightningtracker", "LightningTracker", ModuleCategories.EXPERIMENT, cfgBool("lightningtracker.enabled", true),
                        LightningTracker::setEnabled, LightningTracker::statusText);
    }
}
