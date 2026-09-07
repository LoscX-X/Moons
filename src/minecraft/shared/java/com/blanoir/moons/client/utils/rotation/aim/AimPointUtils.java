package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Stateless target-box point selection and short-horizon motion prediction. */
public final class AimPointUtils {
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

    private AimPointUtils() {}

    /** Converts normalized box coordinates to a world point, clamping each axis. */
    public static Vec3 localPoint(AABB box, double x, double y, double z) {
        return new Vec3(
                Mth.lerp(Mth.clamp(x, 0.0D, 1.0D), box.minX, box.maxX),
                Mth.lerp(Mth.clamp(y, 0.0D, 1.0D), box.minY, box.maxY),
                Mth.lerp(Mth.clamp(z, 0.0D, 1.0D), box.minZ, box.maxZ));
    }

    /** Degenerate box axes use their midpoint instead of an unstable quotient. */
    public static double fraction(double value, double minimum, double maximum) {
        double size = maximum - minimum;
        return size <= 1.0E-6D ? 0.5D : Mth.clamp((value - minimum) / size, 0.0D, 1.0D);
    }

    public static Vec3 center(AABB box) {
        return center(box, 0.58D);
    }

    public static Vec3 center(AABB box, double verticalFactor) {
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                Mth.lerp(Mth.clamp(verticalFactor, 0.0D, 1.0D), box.minY, box.maxY),
                (box.minZ + box.maxZ) * 0.5D);
    }

    /** Point on the box nearest to a bounded view ray, not merely to the eye. */
    public static Vec3 closest(AABB box, Vec3 eye, Vec3 look, double maxDistance) {
        Vec3 direction = normalizedLook(look);
        double low = 0.0D;
        double high = Math.max(0.0D, maxDistance);
        for (int iteration = 0; iteration < 24; iteration++) {
            double first = (low * 2.0D + high) / 3.0D;
            double second = (low + high * 2.0D) / 3.0D;
            double firstDistance =
                    EntityDistance.squaredToBox(eye.add(direction.scale(first)), box);
            double secondDistance =
                    EntityDistance.squaredToBox(eye.add(direction.scale(second)), box);
            if (firstDistance <= secondDistance) {
                high = second;
            } else {
                low = first;
            }
        }
        Vec3 rayPoint = eye.add(direction.scale((low + high) * 0.5D));
        return EntityDistance.closestPoint(rayPoint, box);
    }

    public static Vec3 resolve(Mode mode, AABB box, Vec3 eye, Vec3 look, double maxDistance) {
        return mode == Mode.CLOSEST ? closest(box, eye, look, maxDistance) : center(box);
    }

    public static AABB predict(AABB box, Vec3 smoothedVelocity, double ticksAhead) {
        double ticks = Mth.clamp(ticksAhead, 0.0D, 3.0D);
        return box.move(smoothedVelocity.scale(ticks));
    }

    private static Vec3 normalizedLook(Vec3 look) {
        return look != null && look.lengthSqr() > 1.0E-9D
                ? look.normalize()
                : new Vec3(0.0D, 0.0D, 1.0D);
    }
}
