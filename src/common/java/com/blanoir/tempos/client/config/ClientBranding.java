package com.blanoir.tempos.client.config;

import com.blanoir.tempos.api.Branding;

/** Resolves the display name from a process override or the active player's profile. */
public final class ClientBranding {
    public static final String DEFAULT_NAME = Branding.DEFAULT_NAME;
    public static final String NAME_KEY = "client.name";

    private ClientBranding() {}

    public static String name() {
        String processOverride = Branding.explicitName();
        if (!processOverride.isEmpty()) return processOverride;

        String configured = normalize(Settings.getString(NAME_KEY, ""));
        return configured.isEmpty() ? DEFAULT_NAME : configured;
    }

    public static boolean setName(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return false;
        Settings.setString(NAME_KEY, normalized);
        return Branding.setName(normalized);
    }

    public static String initial() {
        String value = name();
        return value.substring(0, value.offsetByCodePoints(0, 1))
                .toUpperCase(java.util.Locale.ROOT);
    }

    public static String normalize(String value) {
        return Branding.normalize(value);
    }
}
