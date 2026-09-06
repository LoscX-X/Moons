package com.blanoir.moons.client.ui.animation;

/** Frame-rate independent smoothing shared by the HUD overlays. */
public final class Animation {
    private Animation() { }

    public static double approach(double current, double target, double deltaSeconds, double response) {
        double safeDelta = Math.max(0.0D, Math.min(0.1D, deltaSeconds));
        double amount = 1.0D - Math.exp(-Math.max(0.0D, response) * safeDelta);
        double next = current + (target - current) * amount;
        return Math.abs(target - next) < 0.0005D ? target : next;
    }
}
