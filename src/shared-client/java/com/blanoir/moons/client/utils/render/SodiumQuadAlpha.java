package com.blanoir.moons.client.utils.render;

import net.minecraft.util.ARGB;

import java.lang.reflect.Method;

/** Accesses optional Sodium quad colors without linking against Sodium classes. */
public final class SodiumQuadAlpha {
    private static final ClassValue<ColorMethods> COLOR_METHODS = new ClassValue<>() {
        @Override
        protected ColorMethods computeValue(Class<?> quadType) {
            try {
                return new ColorMethods(
                        quadType.getMethod("getColor", int.class),
                        quadType.getMethod("setColor", int.class, int.class));
            } catch (ReflectiveOperationException failure) {
                throw unableToApply(failure);
            }
        }
    };

    private SodiumQuadAlpha() { }

    public static void multiply(Object quad, float alpha) {
        ColorMethods methods = COLOR_METHODS.get(quad.getClass());
        try {
            for (int vertex = 0; vertex < 4; vertex++) {
                int color = (int) methods.getColor().invoke(quad, vertex);
                methods.setColor().invoke(quad, vertex, ARGB.multiplyAlpha(color, alpha));
            }
        } catch (ReflectiveOperationException failure) {
            throw unableToApply(failure);
        }
    }

    private static IllegalStateException unableToApply(ReflectiveOperationException failure) {
        return new IllegalStateException("Unable to apply Sodium Xray alpha", failure);
    }

    private record ColorMethods(Method getColor, Method setColor) { }
}
