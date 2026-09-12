package com.blanoir.moons.client.utils.registry;

import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Supplier;

/** Registry-backed list settings share one small JSON shape. */
public final class RegistryLists {
    private RegistryLists() {}

    public static ModuleRegistry.Setting setting(
            String id,
            String name,
            String registry,
            Supplier<JsonElement> getter,
            ModuleRegistry.SettingApply setter,
            JsonArray defaults) {
        return new ModuleRegistry.Setting(
                        id, name, registry + "_list", getter, null, null, null, List.of(), setter)
                .withDefault(defaults);
    }

    public static JsonObject entry(String id, String color) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        if (color != null) {
            entry.addProperty("color", color);
            entry.addProperty("enabled", true);
        }
        return entry;
    }

    public static JsonArray entityIds(java.util.Collection<Identifier> ids) {
        JsonArray result = new JsonArray();
        ids.forEach(id -> result.add(entry(id.toString(), null)));
        return result;
    }

    public static java.util.Set<Identifier> readEntityIds(JsonElement value) {
        if (!valid("mob_list", value)) throw new IllegalArgumentException("Invalid entity list");
        var result = new java.util.LinkedHashSet<Identifier>();
        value.getAsJsonArray()
                .forEach(
                        entry ->
                                result.add(
                                        Identifier.parse(
                                                entry.getAsJsonObject().get("id").getAsString())));
        return result;
    }

    public static java.util.Set<Identifier> allMobIds() {
        var result = new java.util.LinkedHashSet<Identifier>();
        BuiltInRegistries.ENTITY_TYPE.forEach(
                type -> {
                    if (type.getCategory() != net.minecraft.world.entity.MobCategory.MISC)
                        result.add(BuiltInRegistries.ENTITY_TYPE.getKey(type));
                });
        return result;
    }

    public static boolean valid(String type, JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        var ids = new java.util.HashSet<Identifier>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) return false;
            JsonObject entry = element.getAsJsonObject();
            JsonElement id = entry.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString())
                return false;
            Identifier key = Identifier.tryParse(id.getAsString());
            if (key == null || !ids.add(key)) return false;
            boolean exists =
                    switch (type) {
                        case "item_list" -> BuiltInRegistries.ITEM.getOptional(key).isPresent();
                        case "block_list" -> BuiltInRegistries.BLOCK.getOptional(key).isPresent();
                        case "entity_list", "mob_list" ->
                                BuiltInRegistries.ENTITY_TYPE.getOptional(key).isPresent();
                        default -> false;
                    };
            if (!exists) return false;
            if (type.equals("mob_list") && key.toString().equals("minecraft:player")) return false;
            if (!type.equals("item_list") && !type.equals("mob_list")) {
                JsonElement color = entry.get("color");
                JsonElement enabled = entry.get("enabled");
                if (color == null
                        || !color.isJsonPrimitive()
                        || !color.getAsJsonPrimitive().isString()
                        || !color.getAsString().matches("#[0-9a-fA-F]{6}")
                        || enabled == null
                        || !enabled.isJsonPrimitive()
                        || !enabled.getAsJsonPrimitive().isBoolean()) return false;
            }
        }
        return true;
    }
}
