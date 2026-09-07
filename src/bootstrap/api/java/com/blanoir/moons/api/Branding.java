package com.blanoir.moons.api;

/** User-visible process branding; internal moons identifiers remain stable. */
public final class Branding {
    public static final String DEFAULT_NAME = "Moons";
    private static final int MAX_NAME_LENGTH = 32;

    private Branding() {}

    public static String name() {
        String explicit = explicitName();
        return explicit.isEmpty() ? DEFAULT_NAME : explicit;
    }

    public static String explicitName() {
        String property = normalize(System.getProperty("moons.name", ""));
        if (!property.isEmpty()) return property;
        return normalize(System.getenv("MOONS_NAME"));
    }

    public static boolean setName(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        System.setProperty("moons.name", normalized);
        return true;
    }

    public static String prefix() {
        return "[" + name() + "]";
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.length() <= MAX_NAME_LENGTH
                ? normalized
                : normalized.substring(0, MAX_NAME_LENGTH);
    }
}
