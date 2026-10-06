package com.blanoir.moons.client.utils.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Field access with explicit required, nullable, and tolerant fallback contracts. */
public final class JsonFields {
    private JsonFields() {}

    public static JsonElement required(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return object.get(name);
    }

    /** Missing/null is nullable; malformed values still propagate their conversion failure. */
    public static String nullableString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    public static String stringOr(JsonObject object, String name, String fallback) {
        try {
            return object.has(name) ? object.get(name).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static int integerOr(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
