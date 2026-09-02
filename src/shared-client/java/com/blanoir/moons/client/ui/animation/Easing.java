package com.blanoir.moons.client.ui.animation;

/** Small easing set used by the current UI; avoids unused animation variants. */
public enum Easing {
    LINEAR {
        @Override public double apply(double value) { return clamp(value); }
    },
    EASE_OUT_CUBIC {
        @Override public double apply(double value) {
            double inverse = 1.0D - clamp(value);
            return 1.0D - inverse * inverse * inverse;
        }
    },
    EASE_IN_OUT {
        @Override public double apply(double value) {
            double t = clamp(value);
            return t < 0.5D ? 2.0D * t * t : 1.0D - Math.pow(-2.0D * t + 2.0D, 2.0D) / 2.0D;
        }
    };

    public abstract double apply(double value);

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
