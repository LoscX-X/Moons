package com.blanoir.moons.client.config.settings;

/** Immutable storage metadata; consumers retain their existing read and mutation policies. */
public record SettingSpec<T>(String key, T defaultValue, T min, T max) {
    public static SettingSpec<Integer> integer(String key, int fallback, int min, int max) {
        return new SettingSpec<>(key, fallback, min, max);
    }

    public static SettingSpec<Double> number(String key, double fallback, double min, double max) {
        return new SettingSpec<>(key, fallback, min, max);
    }

    public static SettingSpec<Boolean> bool(String key, boolean fallback) {
        return new SettingSpec<>(key, fallback, false, true);
    }
}
