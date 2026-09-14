package com.blanoir.moons.ysm.internal.runtime;

/** Scalar operations used by the upstream engine, independent of Minecraft. */
public final class ScalarMath {
    public static final float PI = (float) Math.PI;
    public static final float DEG_TO_RAD = PI / 180f;
    public static final float RAD_TO_DEG = 180f / PI;

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    public static float sin(float v) {
        return (float) Math.sin(v);
    }

    public static float cos(float v) {
        return (float) Math.cos(v);
    }

    public static int floor(float v) {
        return (int) Math.floor(v);
    }

    public static int floor(double v) {
        return (int) Math.floor(v);
    }

    public static int ceil(float v) {
        return (int) Math.ceil(v);
    }

    public static float smoothstep(float v) {
        return v * v * v * (v * (v * 6 - 15) + 10);
    }
}
