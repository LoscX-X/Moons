package com.blanoir.moons.client.module.impl.render.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Appearance kinds and user choices, never world positions or trusted server plugin IDs. */
public final class PluginBlockCatalog {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public record Entry(String id, List<String> models, boolean enabled, int rgb) {
        public String name() {
            return models.getFirst() + (models.size() > 1 ? " (+" + (models.size() - 1) + ")" : "");
        }
    }

    public static PluginBlockCatalog read(JsonArray values) {
        var catalog = new PluginBlockCatalog();
        for (var value : values) {
            JsonObject object = value.getAsJsonObject();
            List<String> models =
                    object.getAsJsonArray("models").asList().stream()
                            .map(element -> element.getAsString())
                            .distinct()
                            .sorted()
                            .toList();
            String id = key(models);
            catalog.entries.put(
                    id,
                    new Entry(
                            id,
                            models,
                            object.get("enabled").getAsBoolean(),
                            object.get("rgb").getAsInt() & 0xffffff));
        }
        return catalog;
    }

    public Entry discover(List<String> models) {
        List<String> ordered = models.stream().distinct().sorted().toList();
        String id = key(ordered);
        return entries.computeIfAbsent(
                id, ignored -> new Entry(id, ordered, false, defaultColor(id)));
    }

    public Entry get(String id) {
        return entries.get(id);
    }

    public List<Entry> entries() {
        return entries.values().stream()
                .sorted(java.util.Comparator.comparing(Entry::name))
                .toList();
    }

    public void edit(String id, boolean enabled, int rgb) {
        Entry previous = entries.get(id);
        if (previous == null) throw new IllegalArgumentException("Unknown plugin block");
        entries.put(id, new Entry(id, previous.models(), enabled, rgb & 0xffffff));
    }

    public JsonArray toJson() {
        JsonArray values = new JsonArray();
        for (Entry entry : entries()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.id());
            JsonArray models = new JsonArray();
            entry.models().forEach(models::add);
            object.add("models", models);
            object.addProperty("enabled", entry.enabled());
            object.addProperty("rgb", entry.rgb());
            values.add(object);
        }
        return values;
    }

    public static String key(List<String> models) {
        if (models.isEmpty()) throw new IllegalArgumentException("A model reference is required");
        try {
            String signature = String.join("\n", models.stream().distinct().sorted().toList());
            return "model:"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(signature.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int defaultColor(String key) {
        int[] palette = {0x57c7ff, 0xffbc57, 0xa78bfa, 0x5ee0a0, 0xff7ca8, 0xe4d85c, 0x72d9d2};
        return palette[Math.floorMod(key.hashCode(), palette.length)];
    }
}
