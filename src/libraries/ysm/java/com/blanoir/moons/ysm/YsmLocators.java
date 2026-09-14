package com.blanoir.moons.ysm;

import java.util.*;

/** Upstream GeometryBaker name normalization and attachment aliases, independent of rendering. */
public final class YsmLocators {
    private YsmLocators() {}

    public static String normalize(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    public static String find(Collection<String> names, String... candidates) {
        for (String target : candidates) {
            if (names.contains(target)) return target;
            String normalized = normalize(target);
            for (String name : names) if (normalize(name).equals(normalized)) return name;
        }
        return null;
    }

    public static String sword(Collection<String> names, String side) {
        String exact = find(names, side + "Sword");
        if (exact != null) return exact;
        String prefix = normalize(side + "Sword");
        for (String name : names) {
            String n = normalize(name);
            if (n.startsWith(prefix)
                    && n.substring(prefix.length()).chars().allMatch(Character::isDigit))
                return name;
        }
        return null;
    }

    public static Map<String, String> aliases(Collection<String> names) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String side : List.of("Left", "Right")) {
            String hand =
                    find(
                            names,
                            side + "HandLocator",
                            side + "Item",
                            side + "Hand",
                            side + "Palm",
                            side + "Wrist",
                            side + "ForeArm",
                            side + "LowerArm",
                            side + "Arm");
            if (hand != null) result.put(side + "HandLocator", hand);
            String sword = sword(names, side);
            if (sword != null) result.put(side + "Sword", sword);
            for (int i = 2; i <= 8; i++) alias(result, names, side + "HandLocator" + i);
            alias(result, names, side + "ShoulderLocator");
        }
        for (String name : List.of("Head", "ElytraLocator", "CameraViewLocator"))
            alias(result, names, name);
        return result;
    }

    private static void alias(Map<String, String> result, Collection<String> names, String key) {
        String name = find(names, key);
        if (name != null) result.put(key, name);
    }
}
