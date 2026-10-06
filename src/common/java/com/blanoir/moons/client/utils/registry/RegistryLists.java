package com.blanoir.moons.client.utils.registry;

import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.utils.entity.EntityTypeIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

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

    public static JsonArray entityIds(java.util.Collection<ResourceLocation> ids) {
        JsonArray result = new JsonArray();
        ids.forEach(id -> result.add(entry(id.toString(), null)));
        return result;
    }

    public static java.util.Set<ResourceLocation> readEntityIds(JsonElement value) {
        if (!valid("mob_list", value)) throw new IllegalArgumentException("Invalid entity list");
        var result = new java.util.LinkedHashSet<ResourceLocation>();
        value.getAsJsonArray()
                .forEach(
                        entry ->
                                result.add(
                                        EntityTypeIds.parse(
                                                entry.getAsJsonObject().get("id").getAsString())));
        return result;
    }

    public static java.util.Set<ResourceLocation> allMobIds() {
        var result = new java.util.LinkedHashSet<ResourceLocation>();
        result.addAll(EntityTypeIds.knownIds());
        result.remove(new ResourceLocation("minecraft:player"));
        return result;
    }

    public static boolean valid(String type, JsonElement value) {
        if (value == null || !value.isJsonArray()) return false;
        var ids = new java.util.HashSet<ResourceLocation>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) return false;
            JsonObject entry = element.getAsJsonObject();
            JsonElement id = entry.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString())
                return false;
            ResourceLocation key = EntityTypeIds.parse(id.getAsString());
            if (key == null || !ids.add(key)) return false;
            boolean exists =
                    switch (type) {
                        case "item_list" -> Item.itemRegistry.containsKey(key);
                        case "block_list" -> Block.blockRegistry.containsKey(key);
                        case "entity_list", "mob_list" -> EntityTypeIds.knownIds().contains(key);
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
