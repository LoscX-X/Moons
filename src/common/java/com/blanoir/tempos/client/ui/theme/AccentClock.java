package com.blanoir.tempos.client.ui.theme;

import com.blanoir.tempos.client.utils.render.ArgbColors;

/** A frame sets the hue once. All geometry and native text then sample the same phase. */
public final class AccentClock {
    private static final double[] LINEAR = new double[256];
    public static final double PANEL_LUMINANCE;
    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double s = i / 255.0;
            LINEAR[i] = s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
        }
        PANEL_LUMINANCE = luminance(Theme.PANEL);
    }
    private double speed = 1, saturation = .78, brightness = 1, spread = .001;
    private float base;
    private long layoutRevision;

    public void configure(double speed, double saturation, double brightness, double spread) {
        this.speed = limit(speed, .1, 10, 1);
        this.saturation = limit(saturation, 0, 1, .78);
        this.brightness = limit(brightness, 0, 1, 1);
        double nextSpread = limit(spread, 0, .005, .001);
        if (this.spread != nextSpread) layoutRevision++;
        this.spread = nextSpread;
    }
    private static double limit(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
    public void sample(double seconds) {
        double turns = seconds * speed * .18;
        base = (float)(turns - Math.floor(turns));
        if (base == 1) base = 0;
    }
    void freeze(float phase) { base = phase; }
    public float base() { return base; }
    public float saturation() { return (float)saturation; }
    public float brightness() { return (float)brightness; }
    long layoutRevision() { return layoutRevision; }

    /** R:G hold the phase, B holds the value multiplier, A keeps the requested opacity. */
    public int pack(float y, int opacity, float multiplier) {
        double phase = y * spread;
        int word = (int)Math.round((phase - Math.floor(phase)) * 65535);
        return Math.clamp(opacity, 0, 255) << 24 | word << 8 | Math.round(Math.clamp(multiplier, 0, 1) * 255);
    }
    public int unpackArgb(int packed) {
        int rgb = argb(((packed >>> 8) & 65535) / 65535.0, (packed & 255) / 255.0);
        return packed & 0xff000000 | rgb & 0xffffff;
    }
    public int argb(double phase, double multiplier) {
        int color = ArgbColors.hsvToOpaqueRgb(base + phase, saturation, brightness * Math.clamp(multiplier, 0, 1));
        double light = luminance(color);
        double target = 4 * (PANEL_LUMINANCE + .05) - .05;
        if (light >= target) return color;
        double mix = (target - light) / (1 - light);
        return 0xff000000 | lifted(color >>> 16 & 255, mix) << 16
                | lifted(color >>> 8 & 255, mix) << 8 | lifted(color & 255, mix);
    }
    private static double luminance(int color) {
        return .2126 * LINEAR[color >>> 16 & 255] + .7152 * LINEAR[color >>> 8 & 255] + .0722 * LINEAR[color & 255];
    }
    private static int lifted(int channel, double mix) {
        double linear = LINEAR[channel] + (1 - LINEAR[channel]) * mix;
        double srgb = linear <= .0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        // Rounding upward keeps an eight-bit readable color above the contrast threshold.
        return Math.min(255, (int)Math.ceil(srgb * 255));
    }
}
