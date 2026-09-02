package com.blanoir.moons.agent;

/** Branding available before the nested bootstrap API JAR has been installed. */
public final class AgentBranding {
    private static final String DEFAULT_NAME = "Moons";

    private AgentBranding() {
    }

    public static String name() {
        String property = normalize(System.getProperty("moons.name", ""));
        if (!property.isEmpty()) return property;
        String environment = normalize(System.getenv("MOONS_NAME"));
        return environment.isEmpty() ? DEFAULT_NAME : environment;
    }

    public static String prefix() {
        return "[" + name() + "]";
    }

    public static void setName(String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) System.setProperty("moons.name", normalized);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}
