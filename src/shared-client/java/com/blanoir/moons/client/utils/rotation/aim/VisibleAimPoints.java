package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Searches for aim points on an entity hitbox that are visible from the local player's eyes. */
public final class VisibleAimPoints {
    private static final double[] FACE_SAMPLES = {0.1D, 0.3D, 0.5D, 0.7D, 0.9D};
    /** LB-style dense outline scan with extra edge probes for narrow cover gaps. */
    private static final double[] PRECISE_FACE_SAMPLES = {
            0.015D, 0.05D, 0.15D, 0.25D, 0.35D, 0.45D,
            0.55D, 0.65D, 0.75D, 0.85D, 0.95D, 0.985D
    };

    private VisibleAimPoints() {
    }


    public static Vec3 findVisibleAimPoint(
            Minecraft client,
            LivingEntity entity,
            Vec3 preferredAimPoint,
            double range,
            double hysteresis
    ) {
        Vec3 eyePos =
                client.player.getEyePosition();

        Vec3 look =
                client.player.getLookAngle();

        AABB box =
                entity.getBoundingBox();


        Optional<Vec3> onBody =
                box.clip(
                        eyePos,
                        eyePos.add(
                                look.scale(range)
                        )
                );

        if (onBody.isPresent()) {
            Vec3 point =
                    onBody.get();

            if (isUsableAimPoint(
                    client,
                    eyePos,
                    point,
                    range
            )) {
                return point;
            }
        }


        Vec3 steerPoint =
                AimPointUtils.closest(
                        box,
                        eyePos,
                        look,
                        range
                );

        if (isUsableAimPoint(
                client,
                eyePos,
                steerPoint,
                range
        )) {
            return steerPoint;
        }


        Vec3 bestPoint =
                bestVisibleSample(
                        client,
                        entity,
                        eyePos,
                        range
                );

        if (bestPoint == null) {
            return null;
        }


        if (preferredAimPoint != null
                && isUsableAimPoint(
                client,
                eyePos,
                preferredAimPoint,
                range
        )) {

            double preferredAngle =
                    RotationUtils.angleFromView(
                            client,
                            preferredAimPoint
                    );

            double bestAngle =
                    RotationUtils.angleFromView(
                            client,
                            bestPoint
                    );

            if (preferredAngle
                    <= bestAngle + hysteresis) {
                return preferredAimPoint;
            }
        }


        return bestPoint;
    }


    public static boolean hasVisiblePoint(
            Minecraft client,
            LivingEntity entity,
            double range
    ) {
        return findVisibleAimPoint(
                client,
                entity,
                null,
                range,
                0.0D
        ) != null;
    }

    /**
     * Finds the visible hitbox surface point closest to the current view while
     * preferring the configured vertical body region.
     */
    public static Vec3 findBestVisibleSurfacePoint(
            Minecraft client,
            AABB box,
            Vec3 referenceLook,
            double range,
            double preferredHeight,
            boolean precise
    ) {
        if (client == null || client.player == null || client.level == null
                || box == null || !Double.isFinite(range) || range <= 0.0D) {
            return null;
        }
        Vec3 eye = client.player.getEyePosition();
        if (box.contains(eye)) {
            return box.getCenter();
        }
        Vec3 look = referenceLook != null && referenceLook.lengthSqr() > 1.0E-9D
                ? referenceLook.normalize() : client.player.getViewVector(1.0F);
        List<Vec3> points = new ArrayList<>(156);
        box.clip(eye, eye.add(look.scale(range))).ifPresent(points::add);

        Vec3 closest = EntityDistance.closestPoint(eye, box);
        surfacePoint(eye, closest, box).ifPresent(points::add);
        points.add(closest);
        addFacePoints(points, box, precise ? PRECISE_FACE_SAMPLES : FACE_SAMPLES);

        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double rangeSquared = range * range;
        double preferredY = Mth.lerp(
                Mth.clamp(preferredHeight, 0.0D, 1.0D), box.minY, box.maxY);
        for (Vec3 point : points) {
            if (eye.distanceToSqr(point) > rangeSquared
                    || !RaytraceUtils.canRayTraceTo(client, eye, point)) {
                continue;
            }
            Vec3 direction = point.subtract(eye).normalize();
            double angularCost = 1.0D - Mth.clamp(look.dot(direction), -1.0D, 1.0D);
            double lowAimPenalty = Math.max(0.0D, preferredY - point.y)
                    / Math.max(box.getYsize(), 0.1D);
            double score = angularCost * 32.0D + eye.distanceToSqr(point) * 0.002D
                    + lowAimPenalty * 1.35D;
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
        return best;
    }

    private static void addFacePoints(List<Vec3> points, AABB box, double[] samples) {
        for (double a : samples) for (double b : samples) {
            double x = Mth.lerp(a, box.minX, box.maxX);
            double y = Mth.lerp(a, box.minY, box.maxY);
            double z = Mth.lerp(b, box.minZ, box.maxZ);
            double xb = Mth.lerp(b, box.minX, box.maxX);
            double yb = Mth.lerp(b, box.minY, box.maxY);
            points.add(new Vec3(box.minX, y, z));
            points.add(new Vec3(box.maxX, y, z));
            points.add(new Vec3(x, box.minY, z));
            points.add(new Vec3(x, box.maxY, z));
            points.add(new Vec3(xb, yb, box.minZ));
            points.add(new Vec3(xb, yb, box.maxZ));
        }
    }

    private static Optional<Vec3> surfacePoint(Vec3 eye, Vec3 desired, AABB box) {
        Vec3 difference = desired.subtract(eye);
        return difference.lengthSqr() < 1.0E-9D
                ? Optional.empty()
                : box.clip(eye, eye.add(difference.scale(2.0D)));
    }


    private static Vec3 bestVisibleSample(
            Minecraft client,
            LivingEntity entity,
            Vec3 eyePos,
            double range
    ) {
        AABB box =
                entity.getBoundingBox();

        Vec3 bestPoint = null;
        double bestAngle = Double.MAX_VALUE;

        for (Vec3 point : aimPoints(box)) {

            if (!isUsableAimPoint(
                    client,
                    eyePos,
                    point,
                    range
            )) {
                continue;
            }

            double angle =
                    RotationUtils.angleFromView(
                            client,
                            point
                    );

            if (angle < bestAngle) {
                bestAngle = angle;
                bestPoint = point;
            }
        }

        return bestPoint;
    }


    private static boolean isUsableAimPoint(
            Minecraft client,
            Vec3 eyePos,
            Vec3 point,
            double range
    ) {
        return eyePos.distanceToSqr(point)
                <= range * range
                && RaytraceUtils.canRayTraceTo(
                client,
                eyePos,
                point
        );
    }


    public static List<Vec3> aimPoints(
            AABB box
    ) {
        List<Vec3> points =
                new ArrayList<>();

        Vec3 center =
                box.getCenter();

        points.add(center);

        points.add(
                new Vec3(
                        center.x,
                        Mth.lerp(
                                0.75D,
                                box.minY,
                                box.maxY
                        ),
                        center.z
                )
        );

        points.add(
                new Vec3(
                        center.x,
                        Mth.lerp(
                                0.35D,
                                box.minY,
                                box.maxY
                        ),
                        center.z
                )
        );


        double insetX =
                Math.min(
                        (box.maxX - box.minX) * 0.15D,
                        0.1D
                );

        double insetY =
                Math.min(
                        (box.maxY - box.minY) * 0.15D,
                        0.1D
                );

        double insetZ =
                Math.min(
                        (box.maxZ - box.minZ) * 0.15D,
                        0.1D
                );


        double minX = box.minX + insetX;
        double maxX = box.maxX - insetX;

        double minY = box.minY + insetY;
        double maxY = box.maxY - insetY;

        double minZ = box.minZ + insetZ;
        double maxZ = box.maxZ - insetZ;


        for (double x :
                new double[]{
                        minX,
                        center.x,
                        maxX
                }) {

            for (double y :
                    new double[]{
                            minY,
                            center.y,
                            maxY
                    }) {

                for (double z :
                        new double[]{
                                minZ,
                                center.z,
                                maxZ
                        }) {

                    points.add(
                            new Vec3(
                                    x,
                                    y,
                                    z
                            )
                    );
                }
            }
        }

        return points;
    }
}
