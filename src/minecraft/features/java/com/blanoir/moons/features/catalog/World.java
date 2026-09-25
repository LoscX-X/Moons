package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.world.*;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.module.impl.world.structure.StructureLocate;
import com.blanoir.moons.client.utils.registry.RegistryLists;

/** Defines world module descriptors; ordering is owned by ModuleCatalog. */
final class World {
    private World() {}

    static ModuleRegistry.Module structureLocate() {
        return module(
                        "structurelocate",
                        "StructureLocate",
                        ModuleCategories.WORLD,
                        StructureLocate::isEnabled,
                        StructureLocate::setEnabled,
                        StructureLocate::statusText,
                        StructureLocate.RANGE.describe(
                                "range",
                                "Range",
                                16,
                                (c, v) -> {
                                    StructureLocate.RANGE.set(v);
                                    StructureLocate.rescan();
                                    return 1;
                                }),
                        StructureLocate.ANCIENT_CITY.describe(
                                "ancient_city",
                                "Ancient City",
                                (c, v) ->
                                        StructureLocate.changeTarget(
                                                StructureLocate.ANCIENT_CITY, v)),
                        StructureLocate.STRONGHOLD.describe(
                                "stronghold",
                                "Stronghold",
                                (c, v) ->
                                        StructureLocate.changeTarget(
                                                StructureLocate.STRONGHOLD, v)),
                        StructureLocate.TRIAL_CHAMBER.describe(
                                "trial_chamber",
                                "Trial Chamber",
                                (c, v) ->
                                        StructureLocate.changeTarget(
                                                StructureLocate.TRIAL_CHAMBER, v)),
                        StructureLocate.NETHER_PORTAL.describe(
                                "nether_portal",
                                "Nether Portal",
                                (c, v) ->
                                        StructureLocate.changeTarget(
                                                StructureLocate.NETHER_PORTAL, v)),
                        StructureLocate.SPAWNER.describe(
                                "spawner",
                                "Spawner",
                                (c, v) -> StructureLocate.changeTarget(StructureLocate.SPAWNER, v)),
                        StructureLocate.DUNGEON.describe(
                                "dungeon",
                                "Possible dungeon",
                                (c, v) -> StructureLocate.changeTarget(StructureLocate.DUNGEON, v)),
                        StructureLocate.AMETHYST_GEODE.describe(
                                "amethyst_geode",
                                "Amethyst geode",
                                (c, v) ->
                                        StructureLocate.changeTarget(
                                                StructureLocate.AMETHYST_GEODE, v)),
                        StructureLocate.GEODE_SHAPE
                                .describe(
                                        "geode_shape",
                                        "Geode shape fallback",
                                        (c, v) ->
                                                StructureLocate.changeTarget(
                                                        StructureLocate.GEODE_SHAPE, v))
                                .visibleWhen(StructureLocate.AMETHYST_GEODE::get),
                        StructureLocate.BOX.describe(
                                "box",
                                "Box",
                                (c, v) -> {
                                    StructureLocate.BOX.set(v);
                                    return 1;
                                }),
                        StructureLocate.NAMETAG.describe(
                                "nametag",
                                "Name tag",
                                (c, v) -> {
                                    StructureLocate.NAMETAG.set(v);
                                    return 1;
                                }),
                        StructureLocate.HIDE_VISITED.describe(
                                "hide_visited",
                                "Hide visited",
                                (c, v) -> {
                                    StructureLocate.HIDE_VISITED.set(v);
                                    return 1;
                                }),
                        StructureLocate.DELAY.describe(
                                "scan_delay",
                                "Scan delay (s)",
                                1,
                                (c, v) -> {
                                    StructureLocate.DELAY.set(v);
                                    return 1;
                                }),
                        StructureLocate.TEXT_SCALE.describe(
                                "text_scale",
                                "Text scale",
                                .1,
                                (c, v) -> {
                                    StructureLocate.TEXT_SCALE.set(v);
                                    return 1;
                                }),
                        StructureLocate.TEXT_ALPHA.describe(
                                "text_alpha",
                                "Text background alpha",
                                1,
                                (c, v) -> {
                                    StructureLocate.TEXT_ALPHA.set(v);
                                    return 1;
                                }))
                .withHudTag(StructureLocate::hudTag, "888 found");
    }

    static ModuleRegistry.Module fastPlace() {
        return PlacementOptions.module(
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
        return PlacementOptions.module(
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
                        bool(
                                        "godbridge_sneak",
                                        "Edge sneak",
                                        "scaffold.godBridgeSneak",
                                        true,
                                        Scaffold::setGodBridgeSneak)
                                .visibleWhen(Scaffold::godBridgeSelected),
                        rangeInts(
                                        "godbridge_sneak_ms",
                                        "Sneak hold (ms)",
                                        "scaffold.godBridgeSneakMinMs",
                                        "scaffold.godBridgeSneakMaxMs",
                                        50,
                                        100,
                                        0,
                                        1000,
                                        5,
                                        Scaffold::setGodBridgeSneakTime)
                                .visibleWhen(Scaffold::godBridgeSneakSelected),
                        number(
                                        "godbridge_edge_offset",
                                        "Allowed edge overhang",
                                        "scaffold.godBridgeEdgeOffset",
                                        0.0,
                                        0.0,
                                        .2,
                                        .01,
                                        Scaffold::setGodBridgeEdgeOffset)
                                .visibleWhen(Scaffold::godBridgeSneakSelected),
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
                                .visibleWhen(Scaffold::standardBridgeSelected),
                        choice(
                                        "telly_rotation",
                                        "Telly rotation",
                                        "scaffold.tellyRotation",
                                        "smooth",
                                        Scaffold.tellyRotationOptions(),
                                        Scaffold::setTellyRotation)
                                .visibleWhen(Scaffold::standardBridgeSelected),
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
                                .visibleWhen(Scaffold::standardBridgeSelected),
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
                                        "telly_early_rotation",
                                        "Early rotation",
                                        "scaffold.tellyEarlyRotation",
                                        true,
                                        Scaffold::setTellyEarlyRotation)
                                .visibleWhen(Scaffold::returningTellySelected),
                        numeric(
                                        "telly_rotation_delay",
                                        "Pre-turn wait (ticks)",
                                        "integer",
                                        Scaffold::tellyRotationDelay,
                                        0,
                                        8,
                                        1,
                                        (client, value) ->
                                                Scaffold.setTellyRotationDelay(
                                                        client, (int) Math.round(value)))
                                .withDefault(0)
                                .visibleWhen(Scaffold::earlyTellyRotationSelected),
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
                                        () ->
                                                Scaffold.tellySelected()
                                                        && Scaffold.tellyBpsLimitSelected()),
                        bool(
                                        "telly_flat",
                                        "Flat",
                                        "scaffold.tellyFlat",
                                        false,
                                        Scaffold::setTellyFlat)
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
                        bool(
                                        "debugger",
                                        "Debugger",
                                        "scaffold.debugger",
                                        false,
                                        Scaffold::setDebugger)
                                .visibleWhen(Scaffold::tellySelected))
                .withHudTag(Scaffold::modeName);
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
                ChestStealer.failureSetting(),
                bool(
                        "auto_close",
                        "Auto close",
                        "cheststealer.autoClose",
                        true,
                        ChestStealer::setAutoClose),
                rangeValue(
                                "close_delay_ms",
                                "Close delay (ms)",
                                ChestStealer::closeDelayMinMs,
                                ChestStealer::closeDelayMaxMs,
                                0,
                                5000,
                                10,
                                (client, low, high) ->
                                        ChestStealer.setCloseDelayRange(
                                                client, (int) low, (int) high))
                        .withDefault(0, 0)
                        .visibleWhen(ChestStealer::autoClose),
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
