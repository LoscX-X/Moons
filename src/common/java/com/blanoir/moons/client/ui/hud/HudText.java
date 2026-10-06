package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.utils.render.ArgbColors;

/** Color sampling shared with the independent Skia TextGUI renderer. */
final class HudText {
    private static final int THEME_PRIMARY = 0xFFC49A6C;
    private static final int THEME_PANEL = 0xD02D2723;
    private static final CachedColor PRIMARY = new CachedColor();
    private static final CachedColor TITLE = new CachedColor();
    private static final CachedColor GRADIENT = new CachedColor();
    private static final CachedColor PARAMETER = new CachedColor();
    private static final CachedColor BACKGROUND = new CachedColor();

    private HudText() {}

    static int withAlpha(int rgb, int alpha) {
        return ArgbColors.withAlpha(rgb, alpha);
    }

    static int hudColor() {
        if (HudConfig.USE_THEME_COLOR.get()) return THEME_PRIMARY;
        return PRIMARY.resolve(HudConfig.COLOR.get(), THEME_PRIMARY);
    }

    static int titleColor() {
        return TITLE.resolve(HudConfig.TITLE_COLOR.get(), 0xFFFFFFFF);
    }

    static int hudNameColor(int row, double seconds) {
        return hudNameColor(row, 0.0D, seconds);
    }

    static int hudNameColor(int row, double pixelOffset, double seconds) {
        int primary = hudColor();
        return switch (HudConfig.NAME_COLOR_MODE.get()) {
            case FIXED -> primary;
            case GRADIENT -> {
                int secondary = GRADIENT.resolve(HudConfig.GRADIENT_COLOR.get(), 0xFF765CFF);
                double spatialPhase =
                        switch (HudConfig.GRADIENT_DIRECTION.get()) {
                            case HORIZONTAL ->
                                    row * HudConfig.COLOR_SPREAD.get()
                                            + pixelOffset * HudConfig.CHARACTER_COLOR_SPREAD.get();
                            case TOP_TO_BOTTOM -> row * HudConfig.COLOR_SPREAD.get();
                            case BOTTOM_TO_TOP -> -row * HudConfig.COLOR_SPREAD.get();
                        };
                double phase = seconds * HudConfig.COLOR_SPEED.get() + spatialPhase;
                double blend = (Math.sin(phase * Math.PI * 2.0D) + 1.0D) * 0.5D;
                yield ArgbColors.blendOpaqueRgb(primary, secondary, blend);
            }
            case RAINBOW -> {
                double hue =
                        seconds * HudConfig.COLOR_SPEED.get() * 0.18D
                                + row * HudConfig.COLOR_SPREAD.get()
                                + pixelOffset * HudConfig.CHARACTER_COLOR_SPREAD.get();
                yield ArgbColors.hsvToOpaqueRgb(hue - Math.floor(hue), 0.78D, 1.0D);
            }
        };
    }

    static int parameterColor() {
        return PARAMETER.resolve(HudConfig.PARAMETER_COLOR.get(), 0xFFFFFFFF);
    }

    static int hudBackgroundColor() {
        if (HudConfig.USE_THEME_BACKGROUND.get()) return THEME_PANEL;
        return BACKGROUND.resolve(HudConfig.BACKGROUND_COLOR.get(), THEME_PANEL);
    }

    /** Gradient sampling asks for the same configured colors many times per frame. */
    private static final class CachedColor {
        private String raw;
        private int color;
        private boolean initialized;

        private int resolve(String next, int fallback) {
            if (!initialized || !java.util.Objects.equals(raw, next)) {
                raw = next;
                Integer parsed = HudConfig.parseColor(next);
                color = parsed == null ? fallback : parsed;
                initialized = true;
            }
            return color;
        }
    }
}
