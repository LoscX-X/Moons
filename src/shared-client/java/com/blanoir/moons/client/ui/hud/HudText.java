package com.blanoir.moons.client.ui.hud;

import com.blanoir.moons.client.ui.theme.UiThemes;

/** Color sampling shared with the independent Skia TextGUI renderer. */
final class HudText {
    private HudText() {
    }

    static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    static int hudColor() {
        if (HudConfig.USE_THEME_COLOR.get()) return UiThemes.COFFEE.palette().primary();
        Integer custom = HudConfig.parseColor(HudConfig.COLOR.get());
        return custom == null ? UiThemes.COFFEE.palette().primary() : custom;
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
                double spatialPhase = switch (HudConfig.GRADIENT_DIRECTION.get()) {
                    case HORIZONTAL -> row * HudConfig.COLOR_SPREAD.get()
                            + pixelOffset * HudConfig.CHARACTER_COLOR_SPREAD.get();
                    case TOP_TO_BOTTOM -> row * HudConfig.COLOR_SPREAD.get();
                    case BOTTOM_TO_TOP -> -row * HudConfig.COLOR_SPREAD.get();
                };
                double phase = seconds * HudConfig.COLOR_SPEED.get() + spatialPhase;
                double blend = (Math.sin(phase * Math.PI * 2.0D) + 1.0D) * 0.5D;
                yield blendRgb(primary, secondary, blend);
            }
            case RAINBOW -> {
                double hue = seconds * HudConfig.COLOR_SPEED.get() * 0.18D
                        + row * HudConfig.COLOR_SPREAD.get()
                        + pixelOffset * HudConfig.CHARACTER_COLOR_SPREAD.get();
                yield hsvToRgb(hue - Math.floor(hue), 0.78D, 1.0D);
            }
        };
    }

    static int parameterColor() {
        Integer parsed = HudConfig.parseColor(HudConfig.PARAMETER_COLOR.get());
        return parsed == null ? 0xFFFFFFFF : parsed;
    }

    static int hudBackgroundColor() {
        if (HudConfig.USE_THEME_BACKGROUND.get()) return UiThemes.COFFEE.palette().panel();
        Integer custom = HudConfig.parseColor(HudConfig.BACKGROUND_COLOR.get());
        return custom == null ? UiThemes.COFFEE.palette().panel() : custom;
    }

    private static int blendRgb(int first, int second, double amount) {
        double t = Math.max(0.0D, Math.min(1.0D, amount));
        int red = (int) Math.round(((first >> 16) & 0xFF) * (1.0D - t)
                + ((second >> 16) & 0xFF) * t);
        int green = (int) Math.round(((first >> 8) & 0xFF) * (1.0D - t)
                + ((second >> 8) & 0xFF) * t);
        int blue = (int) Math.round((first & 0xFF) * (1.0D - t)
                + (second & 0xFF) * t);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int hsvToRgb(double hue, double saturation, double value) {
        double scaled = (hue - Math.floor(hue)) * 6.0D;
        int section = (int) Math.floor(scaled);
        double fraction = scaled - section;
        double p = value * (1.0D - saturation);
        double q = value * (1.0D - fraction * saturation);
        double t = value * (1.0D - (1.0D - fraction) * saturation);
        double red;
        double green;
        double blue;
        switch (section % 6) {
            case 0 -> { red = value; green = t; blue = p; }
            case 1 -> { red = q; green = value; blue = p; }
            case 2 -> { red = p; green = value; blue = t; }
            case 3 -> { red = p; green = q; blue = value; }
            case 4 -> { red = t; green = p; blue = value; }
            default -> { red = value; green = p; blue = q; }
        }
        return 0xFF000000
                | (int) Math.round(red * 255.0D) << 16
                | (int) Math.round(green * 255.0D) << 8
                | (int) Math.round(blue * 255.0D);
    }
}
