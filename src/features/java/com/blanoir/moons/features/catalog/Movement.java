package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.movement.*;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines movement module descriptors; ordering is owned by ModuleCatalog. */
final class Movement {
    private Movement() {
    }

    static ModuleRegistry.Module keepSprint() {
        return module("keepsprint", "KeepSprint", "Combat", KeepSprint::isEnabled,
                        KeepSprint::setEnabled, () -> "",
                        rangeDoubles("motion", "Motion %", "keepsprint.motion.min", "keepsprint.motion.max",
                                100, 100, 0, 100, 1, KeepSprint::setMotion),
                        rangeDoubles("hurt_motion", "Motion when hurt %", "keepsprint.hurtMotion.min", "keepsprint.hurtMotion.max",
                                100, 100, 0, 100, 1, KeepSprint::setHurtMotion),
                        rangeInts("hurt_time", "Hurt time", "keepsprint.hurtTime.min", "keepsprint.hurtTime.max",
                                1, 10, 1, 10, 1, KeepSprint::setHurtTime),
                        number("chance", "Chance %", "keepsprint.chance", 100, 0, 100, 1, KeepSprint::setChance));
    }

    static ModuleRegistry.Module sprint() {
        return module("sprint", "Sprint", "Movement", Sprint::isEnabled, Sprint::setEnabled,
                        Sprint::modeName,
                        choice("mode", "Mode", "sprint.mode", "legit", Sprint.modeOptions(), Sprint::setMode));
    }

    static ModuleRegistry.Module noSlow() {
        return module("noslow", "NoSlow", ModuleCategories.MOVEMENT,
                        NoSlow::isEnabled, NoSlow::setEnabled, NoSlow::hudTag,
                        bool("bow", "Bow", "noslow.bow", false, NoSlow::setBow),
                        bool("keep_sprinting", "Keep sprinting", "noslow.keepSprinting", true,
                                NoSlow::setKeepSprinting),
                        bool("crossbow", "Crossbow", "noslow.crossbow", false, NoSlow::setCrossbow),
                        bool("food", "Food", "noslow.food", true, NoSlow::setFood),
                        bool("potion", "Potion", "noslow.potion", true, NoSlow::setPotion),
                        bool("shield", "Shield", "noslow.shield", true, NoSlow::setShield));
    }

    static ModuleRegistry.Module noJumpDelay() {
        return module("nojumpdelay", "NoJumpDelay", "Movement", NoJumpDelay::isEnabled,
                        NoJumpDelay::setEnabled, () -> "");
    }
}
