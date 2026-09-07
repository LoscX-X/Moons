package com.blanoir.moons.client.utils.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Entity-type identifiers in settings; parsing itself does not access the registry. */
public final class EntityTypeIds {
    private EntityTypeIds() {}

    public static Identifier parse(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        return Identifier.tryParse(
                normalized.contains(":") ? normalized : "minecraft:" + normalized);
    }

    /** Resolves the stored list against the current Minecraft entity-type registry. */
    public static Set<Identifier> parseKnown(String stored) {
        Set<Identifier> result = new TreeSet<>();
        if (stored == null || stored.isBlank()) {
            return result;
        }
        for (String rawId : stored.split(",")) {
            Identifier id = parse(rawId);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.getOptional(id).isPresent()) {
                result.add(id);
            }
        }
        return result;
    }

    public static String serialize(Collection<Identifier> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return "";
        }
        return entityTypes.stream()
                .map(Identifier::toString)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
