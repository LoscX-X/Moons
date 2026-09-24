package com.blanoir.moons.client.ui.hud;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Reserves the widest declared status, including proportional-font digit widths. */
public final class HudTagWidth {
    private HudTagWidth() {}

    public static double reserve(List<String> samples, ToDoubleFunction<String> measure) {
        double width = 0;
        for (String sample : samples) {
            width = Math.max(width, measure.applyAsDouble(sample));
            // A numeric sample declares the maximum digit count, not the current value.
            for (char digit = '0'; digit <= '9'; digit++) {
                StringBuilder variant = new StringBuilder(sample.length());
                for (int i = 0; i < sample.length(); i++) {
                    char c = sample.charAt(i);
                    variant.append(c >= '0' && c <= '9' ? digit : c);
                }
                width = Math.max(width, measure.applyAsDouble(variant.toString()));
            }
        }
        return width;
    }
}
