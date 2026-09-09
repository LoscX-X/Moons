package com.blanoir.moons.client.utils.render;

import net.minecraft.util.ARGB;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Accesses optional Sodium quad colors without linking against Sodium classes. */
public final class SodiumQuadAlpha {
    private static final ClassValue<ColorMethods> COLOR_METHODS =
            new ClassValue<>() {
                @Override
                protected ColorMethods computeValue(Class<?> quadType) {
                    try {
                        return new ColorMethods(
                                MethodHandles.publicLookup()
                                        .unreflect(quadType.getMethod("getColor", int.class))
                                        .asType(
                                                MethodType.methodType(
                                                        int.class, Object.class, int.class)),
                                MethodHandles.publicLookup()
                                        .unreflect(
                                                quadType.getMethod(
                                                        "setColor", int.class, int.class))
                                        .asType(
                                                MethodType.methodType(
                                                        void.class,
                                                        Object.class,
                                                        int.class,
                                                        int.class)));
                    } catch (ReflectiveOperationException failure) {
                        throw unableToApply(failure);
                    }
                }
            };

    private SodiumQuadAlpha() {}

    public static void multiply(Object quad, float alpha) {
        if (alpha == 1.0F) return;
        ColorMethods methods = COLOR_METHODS.get(quad.getClass());
        try {
            for (int vertex = 0; vertex < 4; vertex++) {
                int color = (int) methods.getColor().invokeExact(quad, vertex);
                methods.setColor().invokeExact(quad, vertex, ARGB.multiplyAlpha(color, alpha));
            }
        } catch (Throwable failure) {
            throw unableToApply(failure);
        }
    }

    private static IllegalStateException unableToApply(Throwable failure) {
        return new IllegalStateException("Unable to apply Sodium Xray alpha", failure);
    }

    private record ColorMethods(MethodHandle getColor, MethodHandle setColor) {}
}
