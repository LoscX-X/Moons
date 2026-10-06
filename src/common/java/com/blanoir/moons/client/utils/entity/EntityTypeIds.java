package com.blanoir.moons.client.utils.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Stable settings IDs for the legacy registry's case-sensitive entity names. */
public final class EntityTypeIds {
    private EntityTypeIds() {}

    private static String canonical(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static ResourceLocation id(Entity entity) {
        if (entity instanceof EntityPlayer) return new ResourceLocation("minecraft", "player");
        String name = entity == null ? null : EntityList.getEntityString(entity);
        return name == null ? null : new ResourceLocation("minecraft", canonical(name));
    }

    public static Set<ResourceLocation> knownIds() {
        Set<ResourceLocation> ids = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        for (String name : EntityList.getEntityNameList())
            ids.add(new ResourceLocation("minecraft", canonical(name)));
        ids.add(new ResourceLocation("minecraft", "player"));
        return ids;
    }

    /** Accepts PigZombie, pigzombie, pig_zombie and minecraft:pig_zombie identically. */
    public static ResourceLocation parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        int separator = text.indexOf(':');
        String namespace =
                separator < 0 ? "minecraft" : text.substring(0, separator).toLowerCase(Locale.ROOT);
        String path = canonical(separator < 0 ? text : text.substring(separator + 1));
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+")) return null;
        if (namespace.equals("minecraft")) {
            String compact = path.replace("_", "");
            for (String name : EntityList.getEntityNameList()) {
                String normalized = canonical(name);
                if (normalized.replace("_", "").equals(compact)) {
                    path = normalized;
                    break;
                }
            }
        }
        return new ResourceLocation(namespace, path);
    }

    public static Set<ResourceLocation> parseKnown(String stored) {
        Set<ResourceLocation> result =
                new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        if (stored == null || stored.isBlank()) return result;
        Set<ResourceLocation> known = knownIds();
        for (String raw : stored.split(",")) {
            ResourceLocation id = parse(raw);
            if (id != null && known.contains(id)) result.add(id);
        }
        return result;
    }

    public static String serialize(Collection<ResourceLocation> ids) {
        return ids == null
                ? ""
                : ids.stream()
                        .map(ResourceLocation::toString)
                        .sorted()
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
    }
}
