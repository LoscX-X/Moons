package com.blanoir.moons.client.compat.math;

/** Game-independent numeric operations used by retained module algorithms. */
public final class Mth {
    public static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    public static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

    private Mth() {}

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    public static float wrapDegrees(float angle) {
        return (float) wrapDegrees((double) angle);
    }

    public static double wrapDegrees(double angle) {
        double wrapped = angle % 360;
        return wrapped >= 180 ? wrapped - 360 : wrapped < -180 ? wrapped + 360 : wrapped;
    }

    public static float rotLerp(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    public static int floor(double value) {
        return (int) Math.floor(value);
    }

    public static int ceil(double value) {
        return (int) Math.ceil(value);
    }

    public static float sqrt(double value) {
        return (float) Math.sqrt(value);
    }

    public static float sin(float value) {
        return (float) Math.sin(value);
    }

    public static float cos(float value) {
        return (float) Math.cos(value);
    }

    public static double square(double value) {
        return value * value;
    }
}
