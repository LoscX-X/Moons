package com.blanoir.tempos.client.config.feature;

import com.blanoir.tempos.client.config.settings.BooleanSetting;

/** Shared visibility switch for ore and structure highlights. */
public final class HighlightDisplay {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("xray.display.enabled").defaultValue(true).build();

    private HighlightDisplay() {}

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean value) {
        ENABLED.set(value);
    }
}
