package com.blanoir.moons.client.utils.render;

import java.util.Locale;

/** Hexadecimal color values; prefix handling and failure policy belong to callers. */
public final class ColorCodec {
    private ColorCodec() {}

    /** Parses a six/eight-character value using Java hex-number syntax; RGB gains opaque alpha. */
    public static int parseRgbOrArgb(String digits) {
        return switch (digits.length()) {
            case 6 -> (int) (0xFF000000L | Long.parseLong(digits, 16));
            case 8 -> (int) Long.parseLong(digits, 16);
            default -> throw new NumberFormatException("Color must have six or eight hex digits");
        };
    }

    public static int[] rgbChannels(int rgb) {
        return new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
    }

    /** Keeps the supplied channel values; callers own range validation. */
    public static String formatRgb(int red, int green, int blue) {
        return String.format("#%02x%02x%02x", red, green, blue);
    }

    public static String formatArgb(int argb) {
        return String.format(Locale.ROOT, "%08x", argb & 0xFFFFFFFFL);
    }
}
