package com.blanoir.moons.ysm.internal.runtime;

import java.util.*;
import java.util.function.Function;

/** Upstream ctrl.hold/swing/use/armor/ride predicates, evaluated against a local snapshot. */
final class LocalControlQueries {
    static boolean supports(String name) {
        return List.of("hold", "swing", "use", "armor", "ride").contains(name);
    }

    static Object query(String name, List<Object> args, Function<String, Object> values) {
        if (args.size() < 2 || args.size() > 3) return false;
        String slot = String.valueOf(args.get(0)).toLowerCase(Locale.ROOT);
        String spec = String.valueOf(args.get(1));
        if (spec.isBlank()) return false;
        if (name.equals("ride")) {
            if (!slot.equals("vehicle") && !slot.equals("passenger")) return false;
            String id = text(values, slot + "_id");
            if (id.isEmpty()) return false;
            return spec.equals("$" + id) || tag(values.apply(slot + "_tags"), spec);
        }
        boolean armor = List.of("head", "chest", "legs", "feet").contains(slot);
        if (name.equals("armor") ? !armor : !List.of("mainhand", "offhand").contains(slot))
            return false;
        if (name.equals("swing")
                && (!flag(values.apply("is_swinging"))
                        || !slot.equals(text(values, "swinging_hand")))) return false;
        if (name.equals("use")
                && (!flag(values.apply("is_using_item"))
                        || !slot.equals(text(values, "using_hand")))) return false;
        String id = text(values, slot + "_item");
        boolean empty = id.isEmpty() || id.equals("minecraft:air");
        if (spec.equals("empty")) return empty;
        if (spec.startsWith("$")) return spec.equals("$" + id);
        if (spec.startsWith("#")) return tag(values.apply(slot + "_tags"), spec);
        if (armor || !spec.startsWith(":")) return false;
        String expected = spec.substring(1), category = text(values, slot + "_category");
        // Weapon predicates deliberately bypass ItemUseAnimation, matching upstream WeaponKind.
        return switch (category) {
            case "trident" -> expected.equals("trident") || expected.equals("spear");
            case "lance", "spear" -> expected.equals("lance");
            case "mace" -> expected.equals("mace");
            default ->
                    expected.equals(category)
                            || (category.equals("shovel") && expected.equals("spade"))
                            || expected.equals(text(values, slot + "_use"));
        };
    }

    private static String text(Function<String, Object> values, String key) {
        return Objects.toString(values.apply(key), "");
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.doubleValue() != 0;
    }

    private static boolean tag(Object tags, String spec) {
        return spec.startsWith("#")
                && tags instanceof Collection<?> names
                && names.contains(spec.substring(1));
    }
}
