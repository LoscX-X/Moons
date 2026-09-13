package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.utils.plugin.PluginServerStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Reads existing server files without allowing one damaged entry to discard other choices. */
public final class PluginXrayConfig {
    public record ManualTarget(String selector, int rgb, boolean enabled) {}

    public record Loaded(
            PluginBlockCatalog catalog,
            LinkedHashMap<String, ManualTarget> manual,
            List<String> problems) {
        public boolean writable() {
            return problems.isEmpty();
        }
    }

    private PluginXrayConfig() {}

    public static Loaded load(String scope) {
        var catalog = new PluginBlockCatalog();
        var manual = new LinkedHashMap<String, ManualTarget>();
        var problems = new ArrayList<String>();
        try {
            var root = PluginServerStore.read(scope);
            JsonObject section =
                    root.has("plugin_xray")
                            ? root.getAsJsonObject("plugin_xray")
                            : new JsonObject();
            for (var value : array(section, "blocks", problems)) {
                try {
                    JsonArray single = new JsonArray();
                    single.add(value);
                    for (var entry : PluginBlockCatalog.read(single).entries()) {
                        catalog.discover(entry.models());
                        catalog.edit(entry.id(), entry.enabled(), entry.rgb());
                    }
                } catch (RuntimeException invalid) {
                    problems.add("Invalid block choice");
                }
            }
            for (var value : array(section, "appearances", problems)) {
                try {
                    catalog.discover(
                            value.getAsJsonObject().getAsJsonArray("models").asList().stream()
                                    .map(JsonElement::getAsString)
                                    .toList());
                } catch (RuntimeException invalid) {
                    problems.add("Invalid model evidence");
                }
            }
            for (var value : array(section, "manual_targets", problems)) {
                try {
                    var entry = value.getAsJsonObject();
                    String selector = entry.get("state").getAsString();
                    if (selector.isBlank()) throw new IllegalArgumentException("Empty selector");
                    var target =
                            new ManualTarget(
                                    selector,
                                    entry.get("rgb").getAsInt() & 0xffffff,
                                    !entry.has("enabled") || entry.get("enabled").getAsBoolean());
                    manual.put(selector, target);
                } catch (RuntimeException invalid) {
                    problems.add("Invalid manual rule");
                }
            }
        } catch (RuntimeException invalid) {
            problems.add("Cannot read server file: " + invalid.getMessage());
        }
        return new Loaded(catalog, manual, List.copyOf(problems));
    }

    private static JsonArray array(JsonObject section, String key, List<String> problems) {
        if (!section.has(key)) return new JsonArray();
        if (section.get(key).isJsonArray()) return section.getAsJsonArray(key);
        problems.add("Invalid " + key + " section");
        return new JsonArray();
    }
}
