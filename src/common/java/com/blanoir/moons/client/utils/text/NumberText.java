package com.blanoir.moons.client.utils.text;

import java.util.Locale;

/** Numeric display primitives; callers retain their integral-value conversion rules. */
public final class NumberText {
    private NumberText() {}

    public static String trimmedDecimal(double value, int fractionDigits) {
        String formatted = String.format(Locale.ROOT, "%." + fractionDigits + "f", value);
        if (fractionDigits == 0) return formatted;
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Preserves the original long-cast check used by native nametag labels. */
    public static String compactDouble(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }
}
