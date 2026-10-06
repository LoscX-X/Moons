package com.blanoir.moons.client.utils.render;

/** Pure color arithmetic with explicit opaque-ARGB and RGB return formats. */
public final class ArgbColors {
    private ArgbColors() {}

    public static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    public static int rainbowRgb(double phase) {
        double hue = phase - Math.floor(phase);
        double scaled = hue * 6.0D;
        int sector = (int) Math.floor(scaled);
        double fraction = scaled - sector;
        int rising = (int) Math.round(fraction * 255.0D);
        int falling = 255 - rising;
        return switch (sector % 6) {
            case 0 -> 0xFF0000 | rising << 8;
            case 1 -> falling << 16 | 0x00FF00;
            case 2 -> 0x00FF00 | rising;
            case 3 -> falling << 8 | 0x0000FF;
            case 4 -> rising << 16 | 0x0000FF;
            default -> 0xFF0000 | falling;
        };
    }

    /** Samples a palette containing at least two RGB colors, preserving channel rounding. */
    public static int interpolateRgb(int[] colors, double position) {
        double scaled = Math.max(0.0D, Math.min(1.0D, position)) * (colors.length - 1);
        int leftIndex = Math.min(colors.length - 2, (int) Math.floor(scaled));
        int rightIndex = leftIndex + 1;
        double amount = scaled - leftIndex;
        int left = colors[leftIndex];
        int right = colors[rightIndex];
        int red = lerp(left >> 16 & 0xFF, right >> 16 & 0xFF, amount);
        int green = lerp(left >> 8 & 0xFF, right >> 8 & 0xFF, amount);
        int blue = lerp(left & 0xFF, right & 0xFF, amount);
        return red << 16 | green << 8 | blue;
    }

    private static int lerp(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * amount);
    }

    public static int blendOpaqueRgb(int first, int second, double amount) {
        double t = Math.max(0.0D, Math.min(1.0D, amount));
        int red =
                (int) Math.round(((first >> 16) & 0xFF) * (1.0D - t) + ((second >> 16) & 0xFF) * t);
        int green =
                (int) Math.round(((first >> 8) & 0xFF) * (1.0D - t) + ((second >> 8) & 0xFF) * t);
        int blue = (int) Math.round((first & 0xFF) * (1.0D - t) + (second & 0xFF) * t);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static int hsvToOpaqueRgb(double hue, double saturation, double value) {
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
            case 0 -> {
                red = value;
                green = t;
                blue = p;
            }
            case 1 -> {
                red = q;
                green = value;
                blue = p;
            }
            case 2 -> {
                red = p;
                green = value;
                blue = t;
            }
            case 3 -> {
                red = p;
                green = q;
                blue = value;
            }
            case 4 -> {
                red = t;
                green = p;
                blue = value;
            }
            default -> {
                red = value;
                green = p;
                blue = q;
            }
        }
        return 0xFF000000
                | (int) Math.round(red * 255.0D) << 16
                | (int) Math.round(green * 255.0D) << 8
                | (int) Math.round(blue * 255.0D);
    }
}
