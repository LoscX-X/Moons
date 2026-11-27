package com.blanoir.tempos.client.utils.rotation.aim;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared box coordinates and serialized Center/Closest policy IDs.
 * Point algorithms live in CenterAimPoints through J; this class owns no history. */
public final class AimGeometry {
    public enum Mode {
        CENTER,
        CLOSEST;

        public static Mode parse(String value) {
            return "closest".equalsIgnoreCase(value) ? CLOSEST : CENTER;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private AimGeometry() {}

    /** Converts normalized box coordinates to a world point, clamping each axis. */
    public static Vec3 localPoint(AABB box, double x, double y, double z) {
        return new Vec3(
                Mth.lerp(Math.clamp(x, 0.0D, 1.0D), box.minX, box.maxX),
                Mth.lerp(Math.clamp(y, 0.0D, 1.0D), box.minY, box.maxY),
                Mth.lerp(Math.clamp(z, 0.0D, 1.0D), box.minZ, box.maxZ));
    }

    /** Degenerate box axes use their midpoint instead of an unstable quotient. */
    public static double fraction(double value, double minimum, double maximum) {
        double size = maximum - minimum;
        return size <= 1.0E-6D ? 0.5D : Math.clamp((value - minimum) / size, 0.0D, 1.0D);
    }

    public static Vec3 resolve(Mode mode, AABB box, Vec3 eye, Vec3 look, double maxDistance) {
        return mode == Mode.CLOSEST
                ? RayNearestPoint.closest(box, eye, look, maxDistance)
                : CenterAimPoints.center(box);
    }
}
