package com.blanoir.moons.client.ui.animation;

import com.blanoir.moons.client.ui.theme.UiTheme;

import java.util.HashMap;
import java.util.Map;

/** Retained hover/active state used by lightweight native controls. */
public final class UiMotion {
    private UiMotion() {
    }

    public static double approach(double current, double target, double seconds, double rate) {
        return Animation.approach(current, target, seconds, rate);
    }

    public static int mixColor(int first, int second, double amount) {
        double t = Math.max(0.0D, Math.min(1.0D, amount));
        int a = mixChannel(first >>> 24, second >>> 24, t);
        int r = mixChannel((first >>> 16) & 0xFF, (second >>> 16) & 0xFF, t);
        int g = mixChannel((first >>> 8) & 0xFF, (second >>> 8) & 0xFF, t);
        int b = mixChannel(first & 0xFF, second & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    public static final class Controls {
        private final Map<String, State> states = new HashMap<>();
        private final double hoverResponse;
        private final double activeResponse;

        public Controls(UiTheme theme) {
            hoverResponse = theme.metrics().hoverResponse();
            activeResponse = theme.metrics().activeResponse();
        }

        public Visual update(String id, boolean hovered, boolean selected, double seconds) {
            State state = states.computeIfAbsent(id, ignored -> new State());
            state.hover = approach(state.hover, hovered ? 1.0D : 0.0D, seconds, hoverResponse);
            state.selected = approach(state.selected, selected ? 1.0D : 0.0D, seconds, activeResponse);
            return new Visual(state.hover, state.selected);
        }

        public void clear() {
            states.clear();
        }

        private static final class State {
            private double hover;
            private double selected;
        }
    }

    public record Visual(double hover, double selected) {
    }

    private static int mixChannel(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }
}
