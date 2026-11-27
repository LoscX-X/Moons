package com.blanoir.tempos.client.ui.theme;

import com.blanoir.tempos.client.utils.render.ArgbColors;

/** Checks both animated phase and the contrast of the actual eight-bit output. */
public final class AccentClockVerification {
    public static void main(String[] args) {
        AccentClock clock = new AccentClock();
        for (double speed : new double[]{0.1, 1, 10}) {
            clock.configure(speed, .78, 1, .001);
            for (int fps : new int[]{30, 60, 237}) {
                double distance = 0;
                clock.sample(0);
                float previous = clock.base();
                for (int frame = 1; frame <= fps * 10; frame++) {
                    clock.sample((double) frame / fps);
                    double delta = clock.base() - previous;
                    distance += delta < 0 ? delta + 1 : delta;
                    previous = clock.base();
                    clock.argb(.2, 1);
                    if (clock.base() != previous) throw new AssertionError("Color lookup advanced the clock");
                }
                if (Math.abs(distance - speed * 1.8) > .00001) throw new AssertionError("Clock drift at " + fps);
            }
        }
        double panel = luminance(Theme.PANEL);
        for (double saturation : new double[]{0, .3, .78, 1}) {
            for (double value : new double[]{0, .1, .42, .8, 1}) {
                clock.configure(1, saturation, value, .001);
                clock.sample(0);
                for (int n = 0; n < 1000; n++) {
                    double hue = n / 1000.0;
                    int original = ArgbColors.hsvToOpaqueRgb(hue, saturation, value);
                    int actual = clock.argb(hue, 1);
                    if ((luminance(actual) + .05) / (panel + .05) < 4)
                        throw new AssertionError("Insufficient contrast at " + hue + " " + Integer.toHexString(actual));
                    if ((luminance(original) + .05) / (panel + .05) >= 4 && original != actual)
                        throw new AssertionError("Changed an already readable color at " + hue);
                }
            }
        }
        clock.configure(1, .78, 1, .001);
        double seconds = 3_000_000.125;
        clock.sample(seconds);
        double expected = seconds * .18 - Math.floor(seconds * .18);
        if (Math.abs(clock.base() - expected) > 1e-7) throw new AssertionError("Lost elapsed time precision");
        System.out.println("Accent clock verified at 30/60/237 FPS and 20,000 contrast samples");
    }

    private static double luminance(int color) {
        return .2126 * linear(color >> 16 & 255) + .7152 * linear(color >> 8 & 255) + .0722 * linear(color & 255);
    }
    private static double linear(int channel) {
        double s = channel / 255.0;
        return s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
    }
}
