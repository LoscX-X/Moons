package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.command.PremiumCheckCommand;
import com.blanoir.moons.client.config.ClientBranding;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.misc.AntiNick;
import com.blanoir.moons.client.module.impl.misc.ArmorHide;
import com.blanoir.moons.client.module.impl.misc.ChatFilter;
import com.blanoir.moons.client.module.impl.misc.StaticFov;
import com.blanoir.moons.client.module.impl.misc.antibot.AntiBot;

/** Defines misc module descriptors; ordering is owned by ModuleCatalog. */
final class Misc {
    private Misc() {}

    static ModuleRegistry.Module chatPrefix() {
        return module(
                "chatprefix",
                "ChatPrefix",
                ModuleCategories.EXPERIMENT,
                ClientChat::isPrefixEnabled,
                ClientChat::setPrefixEnabled,
                ClientChat::prefix,
                text("prefix", "Client name", ClientBranding::name, ClientChat::setPrefix));
    }

    static ModuleRegistry.Module chatFilter() {
        return module(
                "chatfilter",
                "ChatFilter",
                ModuleCategories.MISC,
                ChatFilter::isEnabled,
                ChatFilter::setEnabled,
                ChatFilter::hudTag,
                choice(
                        "mode",
                        "Mode",
                        "chatfilter.mode",
                        "smart",
                        ChatFilter.modeOptions(),
                        ChatFilter::setMode));
    }

    static ModuleRegistry.Module premiumCheck() {
        return module(
                "premiumcheck",
                "CheckPremium",
                ModuleCategories.EXPERIMENT,
                PremiumCheckCommand::isEnabled,
                PremiumCheckCommand::setEnabled,
                PremiumCheckCommand::statusText,
                integer(
                        "interval",
                        "Query interval (s)",
                        "premiumcheck.intervalSeconds",
                        60,
                        30,
                        600,
                        5,
                        PremiumCheckCommand::setIntervalSeconds));
    }

    static ModuleRegistry.Module staticFov() {
        return module(
                "staticfov",
                "StaticFov",
                ModuleCategories.MISC,
                StaticFov::isEnabled,
                StaticFov::setEnabled,
                StaticFov::hudTag,
                number("fov", "FOV", "staticfov.fov", 90, 30, 170, 1, StaticFov::setFov));
    }

    static ModuleRegistry.Module armorHide() {
        return module(
                "armorhide",
                "ArmorHide",
                ModuleCategories.MISC,
                ArmorHide::isEnabled,
                ArmorHide::setEnabled,
                () -> "Self");
    }

    static ModuleRegistry.Module antiNick() {
        return module(
                "antinick",
                "AntiNick",
                ModuleCategories.MISC,
                AntiNick::isEnabled,
                AntiNick::setEnabled,
                AntiNick::hudTag,
                bool(
                        "mark_nick_uuid",
                        "Mark nick UUID",
                        "antinick.markNickUuid",
                        true,
                        AntiNick::setMarkNickUuid),
                bool(
                        "resolve_names",
                        "Resolve original names",
                        "antinick.resolveNames",
                        true,
                        AntiNick::setResolveNames),
                text("suffix", "Nick suffix", "antinick.suffix", "[Nick]", AntiNick::setSuffix),
                integer(
                        "refresh",
                        "Refresh ms",
                        "antinick.refreshMs",
                        5000,
                        500,
                        15000,
                        100,
                        AntiNick::setRefreshMs));
    }

    static ModuleRegistry.Module antiBot() {
        return module(
                "antibot",
                "AntiBot",
                ModuleCategories.MISC,
                AntiBot::isEnabled,
                AntiBot::setEnabled,
                AntiBot::hudTag,
                customChoice(
                        "mode", "Mode", AntiBot::hudTag, AntiBot.modeOptions(), AntiBot::setMode));
    }
}
