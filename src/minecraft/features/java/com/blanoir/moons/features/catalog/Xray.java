package com.blanoir.moons.features.catalog;

import static com.blanoir.moons.client.module.framework.ModuleRegistry.*;

import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.module.framework.ModuleCategories;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.render.xray.*;
import com.blanoir.moons.client.utils.registry.RegistryLists;

import java.util.ArrayList;
import java.util.List;

/** Defines xray module descriptors; ordering is owned by ModuleCatalog. */
final class Xray {
    private Xray() {}

    static ModuleRegistry.Module display() {
        return module(
                        "xraydisplay",
                        "XrayDisplay",
                        ModuleCategories.RENDER,
                        OreHighlighter::isDisplayEnabled,
                        (client, value) -> {
                            OreHighlighter.setDisplayEnabled(client, value);
                            return 1;
                        },
                        OreHighlighter::displayStatusText,
                        color(
                                        "color",
                                        "Default color",
                                        OreHighlighter::getRgbString,
                                        (client, rgb) ->
                                                OreHighlighter.setColor(rgb[0], rgb[1], rgb[2]))
                                .withDefault("#00dcff"))
                .withDefaultEnabled(true);
    }

    static ModuleRegistry.Module xray() {
        return module(
                "xray",
                "Xray",
                ModuleCategories.RENDER,
                OreScanner::isAutoScanEnabled,
                (client, value) -> {
                    OreScanner.setAutoScanEnabled(client, value);
                    return 1;
                },
                () -> XrayDestroyPacketMode.isEnabled() ? "packet" : "scan",
                customBool(
                                "plugin_compatibility",
                                "Plugin compatibility",
                                PluginXrayTargets::isEnabled,
                                (client, value) -> {
                                    PluginXrayTargets.setEnabled(client, value);
                                    return 1;
                                })
                        .withDefault(false),
                customBool(
                                "packet",
                                "Packet scan",
                                XrayDestroyPacketMode::isPacketScanEnabled,
                                (client, value) -> {
                                    XrayDestroyPacketMode.setEnabled(client, value);
                                    return 1;
                                })
                        .withDefault(false),
                customBool(
                                "cover",
                                "XrayCover",
                                XrayCoverMode::isEnabled,
                                (client, value) -> {
                                    XrayCoverMode.setEnabled(client, value);
                                    return 1;
                                })
                        .withDefault(MoonsConfig.COVER_MODE_DEFAULT_ENABLED),
                customBool(
                                "static_scan",
                                "Static scan",
                                XrayDestroyPacketMode::isStaticScanEnabled,
                                XrayDestroyPacketMode::setStaticScanEnabled)
                        .withDefault(false),
                integer(
                        "interval",
                        "Static scan interval (ticks)",
                        "xray.destroyPacket.intervalTicks",
                        MoonsConfig.DESTROY_PACKET_INTERVAL_TICKS,
                        1,
                        10,
                        1,
                        XrayDestroyPacketMode::setPacketIntervalTicks));
    }

    static ModuleRegistry.Module targets() {
        List<Setting> targetSettings = new ArrayList<>();
        targetSettings.add(
                RegistryLists.setting(
                        "custom_targets",
                        "Custom blocks",
                        "block",
                        CustomXrayTargets::selectedTargets,
                        CustomXrayTargets::setTargets,
                        new com.google.gson.JsonArray()));
        for (XrayBlockTarget target : XrayBlockTarget.values()) {
            String id = target.commandName();
            targetSettings.add(
                    customBool(
                                    id + "_enabled",
                                    title(id),
                                    target::isEnabled,
                                    (client, value) -> {
                                        target.setEnabled(value);
                                        return 1;
                                    })
                            .withDefault(target.defaultEnabled()));
            targetSettings.add(
                    color(
                                    id + "_color",
                                    title(id) + " color",
                                    () ->
                                            "rgb("
                                                    + target.red()
                                                    + ", "
                                                    + target.green()
                                                    + ", "
                                                    + target.blue()
                                                    + ")",
                                    (client, rgb) -> target.setColor(rgb[0], rgb[1], rgb[2]))
                            .withDefault(target.defaultColor()));
        }
        return module(
                        "xraytargets",
                        "XrayTargets",
                        ModuleCategories.RENDER,
                        () ->
                                java.util.Arrays.stream(XrayBlockTarget.values())
                                                .anyMatch(XrayBlockTarget::isEnabled)
                                        || CustomXrayTargets.snapshot().stream()
                                                .anyMatch(
                                                        CustomXrayTargets.CustomTarget::isEnabled),
                        (client, value) -> {
                            XrayBlockTarget.setAllEnabled(value);
                            CustomXrayTargets.setAllEnabled(value);
                            return 1;
                        },
                        () -> "targets",
                        targetSettings.toArray(Setting[]::new))
                .withDefaultEnabled(true);
    }
}
