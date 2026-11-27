package com.blanoir.tempos.client.module.catalog;

import static com.blanoir.tempos.client.module.framework.ModuleModel.module;
import static com.blanoir.tempos.client.module.framework.SettingFactory.customBool;
import static com.blanoir.tempos.client.module.framework.SettingFactory.integer;

import com.blanoir.tempos.client.config.TemposConfig;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.impl.render.xray.*;

/** Defines xray module descriptors; ordering is owned by ModuleCatalog. */
final class Xray {
    private Xray() {}

    static ModuleModel.Module xray() {
        return module(
                "xray",
                "Xray",
                ModuleCategory.RENDER,
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
                        .withDefault(TemposConfig.COVER_MODE_DEFAULT_ENABLED),
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
                        TemposConfig.DESTROY_PACKET_INTERVAL_TICKS,
                        1,
                        10,
                        1,
                        XrayDestroyPacketMode::setPacketIntervalTicks));
    }

}
