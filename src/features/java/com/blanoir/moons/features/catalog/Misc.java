package com.blanoir.moons.features.catalog;

import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.misc.AntiNick;
import com.blanoir.moons.client.module.impl.misc.ArmorHide;
import com.blanoir.moons.client.module.impl.misc.ChatFilter;
import com.blanoir.moons.client.module.impl.misc.StaticFov;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;
import com.blanoir.moons.client.chat.ClientChat;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

/** Defines misc module descriptors; ordering is owned by ModuleCatalog. */
final class Misc {
    private Misc() {
    }

    static ModuleRegistry.Module chatPrefix() {
        return module("chatprefix", "ChatPrefix", ModuleCategories.EXPERIMENT,
                        ClientChat::isPrefixEnabled, ClientChat::setPrefixEnabled, ClientChat::prefix,
                        text("prefix", "Client name", ClientBranding::name, ClientChat::setPrefix));
    }

    static ModuleRegistry.Module chatFilter() {
        return module("chatfilter", "ChatFilter", ModuleCategories.MISC,
                        ChatFilter::isEnabled, ChatFilter::setEnabled, ChatFilter::hudTag,
                        choice("mode", "Mode", "chatfilter.mode", "smart",
                                ChatFilter.modeOptions(), ChatFilter::setMode));
    }

    static ModuleRegistry.Module premiumCheck() {
        return module("premiumcheck", "CheckPremium", ModuleCategories.EXPERIMENT, PremiumCheckCommand::isEnabled,
                        PremiumCheckCommand::setEnabled, PremiumCheckCommand::statusText,
                        integer("interval", "Query interval (s)", "premiumcheck.intervalSeconds",
                                60, 30, 600, 5, PremiumCheckCommand::setIntervalSeconds));
    }

    static ModuleRegistry.Module staticFov() {
        return module("staticfov", "StaticFov", ModuleCategories.MISC, StaticFov::isEnabled,
                        StaticFov::setEnabled, StaticFov::hudTag,
                        number("fov", "FOV", "staticfov.fov", 90, 30, 170, 1, StaticFov::setFov));
    }

    static ModuleRegistry.Module armorHide() {
        return module("armorhide", "ArmorHide", ModuleCategories.MISC, ArmorHide::isEnabled,
                        ArmorHide::setEnabled, () -> "Self");
    }

    static ModuleRegistry.Module antiNick() {
        return module("antinick", "AntiNick", ModuleCategories.MISC, AntiNick::isEnabled,
                        AntiNick::setEnabled, AntiNick::hudTag,
                        bool("mark_nick_uuid", "Mark nick UUID", "antinick.markNickUuid", true,
                                AntiNick::setMarkNickUuid),
                        bool("resolve_names", "Resolve original names", "antinick.resolveNames", true,
                                AntiNick::setResolveNames),
                        text("suffix", "Nick suffix", "antinick.suffix", "[Nick]", AntiNick::setSuffix),
                        integer("refresh", "Refresh ms", "antinick.refreshMs", 5000, 500, 15000, 100,
                                AntiNick::setRefreshMs));
    }

    static ModuleRegistry.Module antiBot() {
        return module("antibot", "AntiBot", ModuleCategories.MISC, AntiBot::isEnabled,
                        AntiBot::setEnabled, AntiBot::hudTag,
                        choice("mode", "Mode", "antibot.mode", "custom",
                                AntiBot.modeOptions(), AntiBot::setMode),
                        bool("literal_npc", "Literal NPC", "antibot.literalNpc", false, AntiBot::setLiteralNpc),
                        bool("not_in_tab", "Not in tab list", "antibot.notInTabList", false, AntiBot::setNotInTab),
                        bool("invalid_ground", "Invalid ground", "antibot.custom.invalidGround", true,
                                AntiBot::setInvalidGround).visibleWhen(AntiBot::customMode),
                        integer("invalid_ground_vl", "Invalid ground VL", "antibot.custom.invalidGroundVl",
                                10, 1, 50, 1, AntiBot::setInvalidGroundVl).visibleWhen(AntiBot::customMode).enabledWhen(AntiBot::invalidGroundEnabled),
                        bool("always_in_radius", "Always in radius", "antibot.custom.alwaysInRadius", false,
                                AntiBot::setAlwaysInRadius).visibleWhen(AntiBot::customMode),
                        number("radius", "Observation radius", "antibot.custom.radius", 20, 5, 30, .5,
                                AntiBot::setRadius).visibleWhen(AntiBot::customMode).enabledWhen(AntiBot::radiusEnabled),
                        bool("age", "Age check", "antibot.custom.age", false, AntiBot::setAgeCheck).visibleWhen(AntiBot::customMode),
                        integer("minimum_age", "Minimum age", "antibot.custom.minimumAge", 20, 0, 120, 1,
                                AntiBot::setMinimumAge).visibleWhen(AntiBot::customMode).enabledWhen(AntiBot::ageEnabled),
                        bool("name", "Name check", "antibot.custom.name", true, AntiBot::setNameCheck).visibleWhen(AntiBot::customMode),
                        integer("name_min", "Minimum name length", "antibot.custom.nameMin", 3, 1, 32, 1,
                                AntiBot::setNameMin).visibleWhen(AntiBot::customMode).enabledWhen(AntiBot::nameEnabled),
                        integer("name_max", "Maximum name length", "antibot.custom.nameMax", 16, 1, 32, 1,
                                AntiBot::setNameMax).visibleWhen(AntiBot::customMode).enabledWhen(AntiBot::nameEnabled),
                        bool("duplicate", "Duplicate profile", "antibot.custom.duplicate", false,
                                AntiBot::setDuplicate).visibleWhen(AntiBot::customMode),
                        bool("no_game_mode", "No game mode", "antibot.custom.noGameMode", true,
                                AntiBot::setNoGameMode).visibleWhen(AntiBot::customMode),
                        bool("illegal_pitch", "Illegal pitch", "antibot.custom.illegalPitch", true,
                                AntiBot::setIllegalPitch).visibleWhen(AntiBot::customMode),
                        bool("fake_entity_id", "Fake entity ID", "antibot.custom.fakeEntityId", true,
                                AntiBot::setFakeEntityId).visibleWhen(AntiBot::customMode),
                        bool("need_hit", "Require hit", "antibot.custom.needHit", false, AntiBot::setNeedHit).visibleWhen(AntiBot::customMode),
                        bool("illegal_health", "Illegal health", "antibot.custom.illegalHealth", false,
                                AntiBot::setIllegalHealth).visibleWhen(AntiBot::customMode),
                        bool("need_swing", "Require swing", "antibot.custom.needSwing", false,
                                AntiBot::setNeedSwing).visibleWhen(AntiBot::customMode),
                        bool("need_crit", "Require critical", "antibot.custom.needCrit", false,
                                AntiBot::setNeedCrit).visibleWhen(AntiBot::customMode),
                        bool("need_attributes", "Require attributes", "antibot.custom.needAttributes", false,
                                AntiBot::setNeedAttributes).visibleWhen(AntiBot::customMode),
                        bool("illegal_scale", "Illegal scale", "antibot.custom.illegalScale", false,
                                AntiBot::setIllegalScale).visibleWhen(AntiBot::customMode));
    }
}
