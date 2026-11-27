package com.blanoir.tempos.api;

/** Resolves the configured client name, including settings from earlier installations. */
public final class Branding {
    public static final String DEFAULT_NAME = "Tempos";
    private static final int MAX_NAME_LENGTH = 32;

    private Branding() {}

    public static String name() {
        String explicit = explicitName();
        return explicit.isEmpty() ? DEFAULT_NAME : explicit;
    }

    public static String explicitName() {
        String value = normalize(System.getProperty("tempos.name", ""));
        if (value.isEmpty()) value = normalize(System.getenv("TEMPOS_NAME"));
        if (value.isEmpty()) value = normalize(System.getProperty("moons.name", ""));
        if (value.isEmpty()) value = normalize(System.getenv("MOONS_NAME"));
        return value.equalsIgnoreCase("Moons") ? DEFAULT_NAME : value;
    }

    public static boolean setName(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        System.setProperty("tempos.name", normalized);
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
