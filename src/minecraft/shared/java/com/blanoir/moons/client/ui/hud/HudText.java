package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.utils.render.ArgbColors;

/** Color sampling shared with the independent Skia TextGUI renderer. */
final class HudText {
    private static final int THEME_PRIMARY = 0xFFC49A6C;
    private static final int THEME_PANEL = 0xD02D2723;

    private HudText() {}

    static int withAlpha(int rgb, int alpha) {
        return ArgbColors.withAlpha(rgb, alpha);
    }

    static int hudColor() {
        if (HudConfig.USE_THEME_COLOR.get()) return THEME_PRIMARY;
        Integer custom = HudConfig.parseColor(HudConfig.COLOR.get());
        return custom == null ? THEME_PRIMARY : custom;
    }

    static int titleColor() {
        Integer configured = HudConfig.parseColor(HudConfig.TITLE_COLOR.get());
        return configured == null ? 0xFFFFFFFF : configured;
    }

    static int hudNameColor(int row, double seconds) {
        return hudNameColor(row, 0.0D, seconds);
    }

    static int hudNameColor(int row, double pixelOffset, double seconds) {
        int primary = hudColor();
        return switch (HudConfig.NAME_COLOR_MODE.get()) {
            case FIXED -> primary;
            case GRADIENT -> {
                Integer parsed = HudConfig.parseColor(HudConfig.GRADIENT_COLOR.get());
                int secondary = parsed == null ? 0xFF765CFF : parsed;
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
        Integer parsed = HudConfig.parseColor(HudConfig.PARAMETER_COLOR.get());
        return parsed == null ? 0xFFFFFFFF : parsed;
    }

    static int hudBackgroundColor() {
        if (HudConfig.USE_THEME_BACKGROUND.get()) return THEME_PANEL;
        Integer custom = HudConfig.parseColor(HudConfig.BACKGROUND_COLOR.get());
        return custom == null ? THEME_PANEL : custom;
    }
}
