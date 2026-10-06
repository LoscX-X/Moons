package com.blanoir.moons.client.config;

import com.google.gson.JsonObject;

import java.util.List;

/** Applied only when no configuration file exists, before any feature caches its settings. */
final class FirstRunDefaults {
    private FirstRunDefaults() {}

    static void apply(JsonObject values) {
        for (String key :
                List.of(
                        "movefix.enabled",
                        "featurehud.enabled",
                        "targetinfo.enabled",
                        "nametags.enabled",
                        "caver.enabled",
                        "chams.players.enabled",
                        "chams.players.highlighter.enabled",
                        "uhcfinder.enabled",
                        "offlineplayerdetect.enabled",
                        "lightningtracker.enabled",
                        "premiumcheck.enabled",
                        "clientchat.prefixEnabled",
                        "xray.display.enabled",
                        "xray.target.diamond.enabled",
                        "xray.target.gold.enabled",
                        "xray.target.lapis.enabled",
                        "xray.target.copper_block.enabled",
                        "xray.target.book_shelf.enabled",
                        "xray.target.end_portal_frame.enabled",
                        "xray.target.chest.enabled")) {
            values.addProperty(key, false);
        }
    }
}
