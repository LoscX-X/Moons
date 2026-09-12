package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.world.*;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.utils.registry.RegistryLists;

/** Defines world module descriptors; ordering is owned by ModuleCatalog. */
final class World {
    private World() {}

    static ModuleRegistry.Module fastPlace() {
        return module(
                "fastplace",
                "FastPlace",
                ModuleCategories.PLAYER,
                FastPlace::isEnabled,
                FastPlace::setEnabled,
                FastPlace::statusText,
                number("delay", "Delay", "fastplace.delay", 1, 1, 3, .05, FastPlace::setDelay),
                bool(
                        "blocks_only",
                        "Blocks only",
                        "fastplace.blocksOnly",
                        true,
                        FastPlace::setBlocksOnly),
                bool("place_fix", "Place fix", "fastplace.placeFix", true, FastPlace::setPlaceFix),
                bool(
                        "skip_obsidian",
                        "Skip obsidian",
                        "fastplace.skipObsidian",
                        true,
                        FastPlace::setSkipObsidian),
                bool(
                        "skip_interactable",
                        "Skip interactable",
                        "fastplace.skipInteractable",
                        true,
                        FastPlace::setSkipInteractable));
    }

    static ModuleRegistry.Module autoTool() {
        return module(
                "autotool",
                "AutoTool",
                "World",
                cfgBool("autotool.enabled", false),
                AutoTool::setEnabled,
                AutoTool::statusText,
                choice(
                        "mode",
                        "Mode",
                        "autotool.mode",
                        "dynamic",
                        AutoTool.modeOptions(),
                        AutoTool::setMode),
                integer("slot", "Static slot", "autotool.slot", 0, 0, 8, 1, AutoTool::setStaticSlot)
                        .visibleWhen(AutoTool::staticMode),
                bool(
                        "ignore_durability",
                        "Ignore durability",
                        "autotool.ignoredurability",
                        false,
                        AutoTool::setIgnoreDurability),
                bool(
                        "silk_touch",
                        "Silk touch",
                        "autotool.silktouch",
                        false,
                        AutoTool::setSilkTouch),
                bool(
                        "sneaking",
                        "Require sneaking",
                        "autotool.sneaking",
                        false,
                        AutoTool::setRequireSneaking),
                bool(
                        "avoid_combat",
                        "Avoid combat",
                        "autotool.combat",
                        false,
                        AutoTool::setNotDuringCombat),
                integer(
                        "combat_grace",
                        "Combat grace",
                        "autotool.combatgrace",
                        30,
                        0,
                        100,
                        1,
                        AutoTool::setCombatGraceTicks));
    }

    static ModuleRegistry.Module scaffold() {
        return module(
                "scaffold",
                "Scaffold",
                "World",
                Scaffold::isEnabled,
                Scaffold::setEnabled,
                Scaffold::statusTag,
                choice(
                        "mode",
                        "Mode",
                        "scaffold.mode",
                        "legit",
                        Scaffold.modeOptions(),
                        Scaffold::setMode),
                rangeInts(
                                "legit_delay_ms",
                                "Sneak delay (ms)",
                                "scaffold.legitDelayMinMs",
                                "scaffold.legitDelayMaxMs",
                                100,
                                150,
                                0,
                                500,
                                5,
                                Scaffold::setLegitDelay)
                        .visibleWhen(Scaffold::legitSelected),
                bool(
                                "legit_sneak_check",
                                "Sneak check",
                                "scaffold.legitSneakCheck",
                                false,
                                Scaffold::setLegitSneakCheck)
                        .visibleWhen(Scaffold::legitSelected),
                number(
                                "legit_edge_offset",
                                "Edge offset (blocks)",
                                "scaffold.legitEdgeOffset",
                                0,
                                0,
                                .3,
                                .01,
                                Scaffold::setLegitEdgeOffset)
                        .visibleWhen(Scaffold::legitSelected),
                choice(
                                "face_sampling",
                                "Face sampling",
                                "scaffold.faceSampling",
                                "standard",
                                Scaffold.faceSamplingOptions(),
                                Scaffold::setFaceSampling)
                        .visibleWhen(Scaffold::tellySelected),
                number(
                                "telly_start_speed",
                                "Start turn limit",
                                "scaffold.tellyStartSpeed",
                                85,
                                1,
                                180,
                                .5,
                                Scaffold::setTellyStartSpeed)
                        .visibleWhen(Scaffold::smoothTellySelected),
                number(
                                "telly_track_speed",
                                "Track turn limit",
                                "scaffold.tellyTrackSpeed",
                                45,
                                1,
                                180,
                                .5,
                                Scaffold::setTellyTrackSpeed)
                        .visibleWhen(Scaffold::smoothTellySelected),
                number(
                                "telly_place_angle",
                                "Telly place angle",
                                "scaffold.tellyPlaceAngle",
                                6,
                                .5,
                                20,
                                .5,
                                Scaffold::setTellyPlaceAngle)
                        .visibleWhen(Scaffold::tellySelected),
                number(
                                "telly_return_speed",
                                "Return turn limit",
                                "scaffold.tellyReturnSpeed",
                                45,
                                1,
                                90,
                                .5,
                                Scaffold::setTellyReturnSpeed)
                        .visibleWhen(Scaffold::returningTellySelected),
                choice(
                                "telly_delay_mode",
                                "Air delay mode",
                                "scaffold.tellyDelayMode",
                                "fixed",
                                Scaffold.tellyDelayModeOptions(),
                                Scaffold::setTellyDelayMode)
                        .visibleWhen(Scaffold::returningTellySelected),
                integer(
                                "telly_place_delay",
                                "Air delay (ticks)",
                                "scaffold.tellyPlaceDelay",
                                4,
                                0,
                                8,
                                1,
                                Scaffold::setTellyPlaceDelay)
                        .visibleWhen(Scaffold::returningTellySelected),
                bool(
                                "telly_bps_limit",
                                "Limit forward BPS",
                                "scaffold.tellyBlocksPerSecondEnabled",
                                true,
                                Scaffold::setTellyBlocksPerSecondEnabled)
                        .visibleWhen(Scaffold::tellySelected),
                rangeInts(
                                "telly_blocks_per_second",
                                "Blocks per second",
                                "scaffold.tellyBlocksPerSecondMin",
                                "scaffold.tellyBlocksPerSecondMax",
                                3,
                                4,
                                1,
                                20,
                                1,
                                Scaffold::setTellyBlocksPerSecond)
                        .visibleWhen(
                                () -> Scaffold.tellySelected() && Scaffold.tellyBpsLimitSelected()),
                bool("telly_flat", "Flat", "scaffold.tellyFlat", false, Scaffold::setTellyFlat)
                        .visibleWhen(Scaffold::tellySelected),
                bool(
                                "telly_fall_rescue",
                                "Fall rescue",
                                "scaffold.tellyFallRescue",
                                false,
                                Scaffold::setTellyFallRescue)
                        .visibleWhen(Scaffold::tellySelected),
                choice(
                                "strafe",
                                "Strafe",
                                "scaffold.moveFix",
                                "silent",
                                Scaffold.moveFixOptions(),
                                Scaffold::setMoveFix)
                        .visibleWhen(Scaffold::tellySelected),
                choice(
                                "sprint",
                                "Sprint",
                                "scaffold.sprintMode",
                                "vanilla",
                                Scaffold.sprintModeOptions(),
                                Scaffold::setSprintMode)
                        .visibleWhen(Scaffold::tellySelected),
                choice(
                                "tower",
                                "Tower",
                                "scaffold.tower",
                                "vanilla",
                                Scaffold.towerOptions(),
                                Scaffold::setTower)
                        .visibleWhen(Scaffold::tellySelected),
                bool(
                        "block_counter",
                        "Block count",
                        "scaffold.blockCounter",
                        true,
                        Scaffold::setBlockCounter),
                bool("debugger", "Debugger", "scaffold.debugger", false, Scaffold::setDebugger)
                        .visibleWhen(Scaffold::tellySelected));
    }

    static ModuleRegistry.Module fastBreak() {
        return module(
                "fastbreak",
                "FastBreak",
                "World",
                cfgBool("fastbreak.enabled", false),
                FastBreak::setEnabled,
                FastBreak::statusText,
                bool("only_tool", "Only tool", "fastbreak.onlyTool", false, FastBreak::setOnlyTool),
                choice(
                        "mode",
                        "Mode",
                        "fastbreak.mode",
                        "abort_another",
                        FastBreak.modeOptions(),
                        FastBreak::setMode));
    }

    static ModuleRegistry.Module chestStealer() {
        return module(
                "cheststealer",
                "ChestStealer",
                ModuleCategories.PLAYER,
                cfgBool("cheststealer.enabled", false),
                ChestStealer::setEnabled,
                ChestStealer::modeName,
                customChoice(
                                "mode",
                                "Mode",
                                ChestStealer::modeName,
                                java.util.List.of("legit", "blatant"),
                                ChestStealer::setMode)
                        .withDefault("legit"),
                bool("all", "All", "cheststealer.all", true, ChestStealer::setAll),
                bool(
                        "auto_close",
                        "Auto close",
                        "cheststealer.autoClose",
                        true,
                        ChestStealer::setAutoClose),
                rangeValue(
                                "delay_ms",
                                "Delay (ms)",
                                ChestStealer::delayMinMs,
                                ChestStealer::delayMaxMs,
                                0,
                                1000,
                                1,
                                (client, low, high) ->
                                        ChestStealer.setDelayRange(client, (int) low, (int) high))
                        .withDefault(100, 150)
                        .visibleWhen(ChestStealer::legitMode),
                RegistryLists.setting(
                                "items",
                                "Items",
                                "item",
                                ChestStealer::selectedItems,
                                ChestStealer::setSelectedItems,
                                new com.google.gson.JsonArray())
                        .visibleWhen(() -> !ChestStealer.allItems()));
    }

    static ModuleRegistry.Module lightningTracker() {
        return module(
                "lightningtracker",
                "LightningTracker",
                ModuleCategories.EXPERIMENT,
                cfgBool("lightningtracker.enabled", true),
                LightningTracker::setEnabled,
                LightningTracker::statusText);
    }
}
