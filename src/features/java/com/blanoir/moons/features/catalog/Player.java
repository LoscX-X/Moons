package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.player.*;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines player module descriptors; ordering is owned by ModuleCatalog. */
final class Player {
    private Player() {
    }

    static void initialize() {
        AutoBed.init();
        AutoObsidian.init();
    }

    static ModuleRegistry.Module autoWeb() {
        return module("autoweb", "AutoWeb", ModuleCategories.WORLD, cfgBool("autoweb.enabled", false), AutoWeb::setEnabled, AutoWeb::hudTag,
                        number("range", "Range", "autoweb.range", 4.5, 2, 6, .1, AutoWeb::setRange),
                        integer("delay", "Delay ticks", "autoweb.delay", 2, 0, 100, 1, AutoWeb::setDelay),
                        integer("hold", "Hold ticks", "autoweb.holdTicks", 0, 0, 20, 1,
                                AutoWeb::setHoldTicks),
                        number("cooldown", "Cooldown", "autoweb.cooldown", .5, 0, 30, .05, AutoWeb::setCooldown),
                        integer("prediction", "Prediction", "autoweb.prediction", 4, 0, 12, 1, AutoWeb::setPrediction),
                        number("chance", "Chance", "autoweb.chance", .65, 0, 1, .01, AutoWeb::setChance),
                        bool("wall", "Wall webs", "autoweb.wall", false, AutoWeb::setWallEnabled),
                        bool("ground", "Ground webs", "autoweb.ground", true, AutoWeb::setGroundEnabled),
                        bool("wait_confirm_rotation", "Wait confirm rotation",
                                "autoweb.waitConfirmRotation", false,
                                AutoWeb::setWaitConfirmRotation));
    }

    static ModuleRegistry.Module autoLava() {
        return module("autolava", "AutoLava", ModuleCategories.WORLD, AutoLava::isEnabled,
                        AutoLava::setEnabled, AutoLava::hudTag,
                        number("chance", "Critical chance", "autolava.chance", .5, 0, 1, .01,
                                AutoLava::setChance),
                        number("range", "Range", "autolava.range", 4.5, 2, 6, .1,
                                AutoLava::setRange),
                        number("fov", "FOV", "autolava.fov", 90, 1, 360, 1,
                                AutoLava::setFov),
                        bool("wall", "Wall", "autolava.wall", true,
                                AutoLava::setWallEnabled),
                        bool("ground", "Ground", "autolava.ground", true,
                                AutoLava::setGroundEnabled),
                        integer("trigger_delay", "Trigger delay ms", "autolava.triggerDelayMs",
                                100, 0, 1000, 5, AutoLava::setTriggerDelayMs),
                        integer("switch_delay", "Switch delay ms", "autolava.switchDelayMs",
                                50, 0, 500, 5, AutoLava::setSwitchDelayMs),
                        number("prediction", "Prediction", "autolava.prediction", 1, 0, 1.5, .05,
                                AutoLava::setPrediction),
                        integer("prediction_delay", "Prediction delay ms", "autolava.predictionDelayMs",
                                20, 0, 250, 5, AutoLava::setPredictionDelayMs),
                        integer("pickup_delay", "Pickup delay ms", "autolava.pickupDelayMs", 15, 0, 1000, 1,
                                AutoLava::setPickupDelayMs),
                        integer("return_switch_delay", "Return switch delay ms", "autolava.returnSwitchDelayMs",
                                0, 0, 500, 1, AutoLava::setReturnSwitchDelayMs),
                        integer("smooth", "Smooth turn", "autolava.smoothTicks", 3, 1, 10, 1,
                                AutoLava::setSmoothTicks),
                        number("cooldown", "Cooldown", "autolava.cooldown", 1, 0, 30, .05,
                                AutoLava::setCooldown));
    }

    static ModuleRegistry.Module autoBed() {
        return module("autobed", "AutoBed", ModuleCategories.EXPERIMENT, AutoBed::isEnabled,
                        AutoBed::setEnabled, AutoBed::hudTag,
                        choice("mode", "Mode", "autobed.mode", "balance",
                                AutoBed.modeOptions(), AutoBed::setMode),
                        number("range", "Scan range", "autobed.range", 4.5, 1, 10, .1,
                                AutoBed::setRange),
                        number("fov", "FOV", "autobed.fov", 90, 1, 360, 1,
                                AutoBed::setFov),
                        number("min_damage", "Min target damage", "autobed.minDamage",
                                6, 0, 36, .5, AutoBed::setMinDamage),
                        number("max_self_damage", "Max self damage", "autobed.maxSelfDamage",
                                8, 0, 36, .5, AutoBed::setMaxSelfDamage),
                        bool("anti_suicide", "Anti suicide", "autobed.antiSuicide",
                                true, AutoBed::setAntiSuicide),
                        bool("target_range_recheck", "Target move check",
                                "autobed.targetRangeRecheck", true,
                                AutoBed::setTargetRangeRecheck).visibleWhen(AutoBed::blatantMode),
                        integer("smooth", "Balance smooth turn", "autobed.smoothTicks", 3, 1, 20, 1,
                                AutoBed::setSmoothTicks).visibleWhen(() -> !AutoBed.blatantMode()),
                        integer("blatant_smooth", "Blatant smooth turn",
                                "autobed.blatantSmoothTicks", 1, 1, 20, 1,
                                AutoBed::setBlatantSmoothTicks).visibleWhen(AutoBed::blatantMode),
                        integer("switch_delay", "Switch delay ms", "autobed.switchDelayMs",
                                50, 0, 500, 5, AutoBed::setSwitchDelayMs),
                        integer("click_delay", "Bed click delay ms", "autobed.clickDelayMs",
                                50, 0, 500, 5, AutoBed::setClickDelayMs));
    }

    static ModuleRegistry.Module autoObsidian() {
        return module("autoobsidian", "AutoObsidian", ModuleCategories.EXPERIMENT,
                        AutoObsidian::isEnabled, AutoObsidian::setEnabled, AutoObsidian::hudTag,
                        number("range", "Scan range", "autoobsidian.range", 4.5, 1, 8, .1,
                                AutoObsidian::setRange),
                        number("fov", "FOV", "autoobsidian.fov", 100, 1, 360, 1,
                                AutoObsidian::setFov),
                        integer("min_walls", "Minimum walls", "autoobsidian.minWalls", 2, 2, 3, 1,
                                AutoObsidian::setMinWalls),
                        integer("smooth", "Smooth turn", "autoobsidian.smoothTicks", 2, 1, 20, 1,
                                AutoObsidian::setSmoothTicks),
                        integer("switch_delay", "Switch delay ms", "autoobsidian.switchDelayMs",
                                50, 0, 500, 5, AutoObsidian::setSwitchDelayMs),
                        bool("collect_water", "Collect water", "autoobsidian.collectWater",
                                true, AutoObsidian::setCollectWater));
    }

    static ModuleRegistry.Module antiLava() {
        return module("antilava", "AntiLava", ModuleCategories.MOVEMENT, cfgBool("antilava.enabled", false), AntiLava::setEnabled, () -> "Predict",
                        rangeInts("delay", "Delay ticks", "antilava.delay.min", "antilava.delay.max", 1, 3, 1, 20, 1,
                                AntiLava::setDelay),
                        number("range", "Range", "antilava.range", 4.5, 1, 6, .1, AntiLava::setRange),
                        number("fov", "FOV", "antilava.fov", 90, 1, 360, 1, AntiLava::setFov));
    }

    static ModuleRegistry.Module antiWeb() {
        return module("antiweb", "AntiWeb", ModuleCategories.MOVEMENT, cfgBool("antiweb.enabled", false), AntiWeb::setEnabled,
                        () -> "Predict",
                        integer("hold_ticks", "Water hold ticks", "antiweb.holdTicks", 3, 1, 20, 1,
                                AntiWeb::setHoldTicks),
                        integer("delay_ticks", "Delay ticks", "antiweb.delayTicks", 0, 0, 20, 1,
                                AntiWeb::setDelayTicks),
                        integer("act_ticks", "Act ticks", "antiweb.actTicks", 4, 1, 20, 1,
                                AntiWeb::setActTicks),
                        bool("anti_antiweb", "Anti-antiweb", "antiweb.antiAntiWeb.enabled", false,
                                AntiWeb::setAntiAntiWebEnabled),
                        number("anti_antiweb_range", "Anti-antiweb range", "antiweb.antiAntiWeb.range", 4.5, 1, 6, .1,
                                AntiWeb::setAntiAntiWebRange),
                        number("anti_antiweb_fov", "Anti-antiweb FOV", "antiweb.antiAntiWeb.fov", 90, 1, 360, 1,
                                AntiWeb::setAntiAntiWebFov),
                        integer("anti_antiweb_smooth", "Anti-antiweb smooth ticks",
                                "antiweb.antiAntiWeb.smoothTicks", 2, 1, 20, 1,
                                AntiWeb::setAntiAntiWebSmoothTicks));
    }

    static ModuleRegistry.Module autoSword() {
        return module("autosword", "AutoSword", "Combat", cfgBool("autosword.enabled", false), AutoSword::setEnabled,
                        AutoSword::statusText,
                        integer("slot", "Sword slot", "autosword.slot", 0, 0, 8, 1, AutoSword::setSlot),
                        bool("switch_back", "Switch back", "autosword.switchback", false, AutoSword::setSwitchBack),
                        integer("switch_delay", "Switch-back delay", "autosword.switchbackdelay", 20, 1, 100, 1,
                                AutoSword::setSwitchBackDelay));
    }

    static ModuleRegistry.Module autoTotem() {
        return module("autototem", "AutoTotem", "Player", cfgBool("autototem.enabled", false), AutoTotem::setEnabled,
                        AutoTotem::statusText,
                        integer("threshold", "Health threshold", "autototem.threshold", 14, 0, 20, 1, AutoTotem::setThreshold),
                        integer("safe_threshold", "Safe threshold", "autototem.safethreshold", 10, 0, 20, 1, AutoTotem::setSafeThreshold),
                        bool("missing_armor", "Missing armor", "autototem.missingarmor", true, AutoTotem::setMissingArmor),
                        bool("safety", "Safety", "autototem.safety", true, AutoTotem::setSafety),
                        bool("subtract_damage", "Subtract damage", "autototem.subtractdamage", false, AutoTotem::setSubtractDamage),
                        bool("explosion_entities", "Explosion entities", "autototem.explosionentities", true, AutoTotem::setExplosionEntities),
                        bool("explosion_blocks", "Explosion blocks", "autototem.explosionblocks", false, AutoTotem::setExplosionBlocks),
                        bool("fall_damage", "Fall damage", "autototem.falldamage", true, AutoTotem::setFallDamage),
                        bool("ignore_elytra", "Ignore elytra", "autototem.fallignoreelytra", false, AutoTotem::setIgnoreElytra),
                        integer("switch_delay", "Switch delay", "autototem.switchdelay", 0, 0, 500, 5, AutoTotem::setSwitchDelay));
    }

    static ModuleRegistry.Module noFall() {
        return module("nofall", "AutoMLG", "Player", cfgBool("nofall.enabled", false), NoFall::setEnabled,
                        NoFall::statusTag,
                        number("threshold", "Fall distance", "nofall.threshold", 3, 1, 10, .1, NoFall::setThreshold),
                        integer("predict_ticks", "Predict ticks", "nofall.predictTicks", 2, 1, 5, 1,
                                NoFall::setPredictTicks),
                        bool("solid_check", "Solid check", "nofall.solidCheck", true,
                                NoFall::setSolidCheck),
                        bool("recovery", "Recovery", "nofall.recovery", true,
                                NoFall::setRecovery));
    }
}
