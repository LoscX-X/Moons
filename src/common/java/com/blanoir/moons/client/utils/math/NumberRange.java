package com.blanoir.moons.client.utils.math;

/** Finite single-value or min-max setting input, normalized before either endpoint is written. */
public record NumberRange(double min, double max) {
    public static NumberRange parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty range");
        String[] parts = raw.trim().split("[-,:]", 2);
        double first = Double.parseDouble(parts[0].trim());
        double second = parts.length == 1 ? first : Double.parseDouble(parts[1].trim());
        if (!Double.isFinite(first) || !Double.isFinite(second))
            throw new IllegalArgumentException("Range must be finite");
        return new NumberRange(Math.min(first, second), Math.max(first, second));
    }
}
