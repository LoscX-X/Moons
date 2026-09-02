package com.blanoir.moons.client.ui.animation;

/** Frame-rate independent target/render value pair. */
public final class Animation {
    private double value;
    private double target;
    private final double response;

    public Animation(double initialValue, double response) {
        this.value = initialValue;
        this.target = initialValue;
        this.response = Math.max(0.0D, response);
    }

    public Animation target(double target) {
        this.target = target;
        return this;
    }

    public double update(double deltaSeconds) {
        value = approach(value, target, deltaSeconds, response);
        return value;
    }

    public double value() {
        return value;
    }

    public double eased(Easing easing) {
        return easing.apply(value);
    }

    public void snap(double value) {
        this.value = value;
        this.target = value;
    }

    public static double approach(double current, double target, double deltaSeconds, double response) {
        double safeDelta = Math.max(0.0D, Math.min(0.1D, deltaSeconds));
        double amount = 1.0D - Math.exp(-Math.max(0.0D, response) * safeDelta);
        double next = current + (target - current) * amount;
        return Math.abs(target - next) < 0.0005D ? target : next;
    }
}
