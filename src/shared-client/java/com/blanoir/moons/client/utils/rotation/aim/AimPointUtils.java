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

    private AimPointUtils() {
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
            double firstDistance = EntityDistance.squaredToBox(
                    eye.add(direction.scale(first)), box);
            double secondDistance = EntityDistance.squaredToBox(
                    eye.add(direction.scale(second)), box);
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
        return mode == Mode.CLOSEST
                ? closest(box, eye, look, maxDistance)
                : center(box);
    }

    public static AABB predict(AABB box, Vec3 smoothedVelocity, double ticksAhead) {
        double ticks = Mth.clamp(ticksAhead, 0.0D, 3.0D);
        return box.move(smoothedVelocity.scale(ticks));
    }

    public static boolean rayIntersects(AABB box, Vec3 eye, Vec3 look, double maxDistance) {
        Vec3 end = eye.add(normalizedLook(look).scale(Math.max(0.0D, maxDistance)));
        return box.contains(eye) || box.clip(eye, end).isPresent();
    }

    /**
     * Continuous correction demand for a predicted box. Zero means the current
     * ray is still safely usable; one means it has moved well outside the box.
     */
    public static double correctionWeight(
            AABB predictedBox,
            Vec3 eye,
            Vec3 look,
            double maxDistance
    ) {
        if (rayIntersects(predictedBox, eye, look, maxDistance)) {
            return 0.0D;
        }
        Vec3 nearest = closest(predictedBox, eye, look, maxDistance);
        Vec3 direction = normalizedLook(look);
        double alongRay = Mth.clamp(nearest.subtract(eye).dot(direction), 0.0D, maxDistance);
        double missDistance = nearest.distanceTo(eye.add(direction.scale(alongRay)));
        double bodyScale = Math.max(0.12D, Math.min(
                predictedBox.maxY - predictedBox.minY,
                Math.max(predictedBox.maxX - predictedBox.minX,
                        predictedBox.maxZ - predictedBox.minZ)));
        return smoothstep(0.0D, bodyScale * 0.42D, missDistance);
    }

    public static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0D : 0.0D;
        }
        double x = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    private static Vec3 normalizedLook(Vec3 look) {
        return look != null && look.lengthSqr() > 1.0E-9D
                ? look.normalize()
                : new Vec3(0.0D, 0.0D, 1.0D);
    }
}
