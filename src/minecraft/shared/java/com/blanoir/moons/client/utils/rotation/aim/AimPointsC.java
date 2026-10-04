package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** C: Existing visible-ray and hitbox-surface searches. Used by entity targeting and prediction.
 * Stateless, no random sampling. Returns null when no point passes the original visibility checks. */
public final class AimPointsC {
    private static final double[] FACE_SAMPLES = {0.1D, 0.3D, 0.5D, 0.7D, 0.9D};

    /** Dense outline scan with extra edge probes for narrow cover gaps. */
    private static final double[] PRECISE_FACE_SAMPLES = {
        0.015D, 0.05D, 0.15D, 0.25D, 0.35D, 0.45D,
        0.55D, 0.65D, 0.75D, 0.85D, 0.95D, 0.985D
    };

    private AimPointsC() {}

    public static Vec3 findVisibleAimPoint(
            Minecraft client,
            EntityLivingBase entity,
            Vec3 preferredAimPoint,
            double range,
            double hysteresis) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || client.theWorld == null || entity == null) {
            return null;
        }
        Vec3 eyePos = currentPlayer.getPositionEyes(1F);

        Vec3 look = currentPlayer.getLook(1F);

        AxisAlignedBB box = entity.getEntityBoundingBox();

        Optional<Vec3> onBody = VecMath.clip(box, eyePos, eyePos.add(VecMath.scale(look, range)));

        if (onBody.isPresent()) {
            Vec3 point = onBody.get();

            if (isUsableAimPoint(client, eyePos, point, range)) {
                return point;
            }
        }

        Vec3 steerPoint = AimPointsF.closest(box, eyePos, look, range);

        if (isUsableAimPoint(client, eyePos, steerPoint, range)) {
            return steerPoint;
        }

        Vec3 bestPoint = bestVisibleSample(client, entity, eyePos, range);

        if (bestPoint == null) {
            return null;
        }

        if (preferredAimPoint != null
                && isUsableAimPoint(client, eyePos, preferredAimPoint, range)) {

            double preferredAngle = AimSolverD.angleFromView(client, preferredAimPoint);

            double bestAngle = AimSolverD.angleFromView(client, bestPoint);

            if (preferredAngle <= bestAngle + hysteresis) {
                return preferredAimPoint;
            }
        }

        return bestPoint;
    }

    public static boolean hasVisiblePoint(Minecraft client, EntityLivingBase entity, double range) {
        return findVisibleAimPoint(client, entity, null, range, 0.0D) != null;
    }

    /**
     * Finds the visible hitbox surface point closest to the current view while
     * preferring the configured vertical body region.
     */
    public static Vec3 findBestVisibleSurfacePoint(
            Minecraft client,
            AxisAlignedBB box,
            Vec3 referenceLook,
            double range,
            double preferredHeight,
            boolean precise) {
        return findBestVisibleSurfacePoint(
                client, box, referenceLook, range, preferredHeight, precise, false);
    }

    public static Vec3 findBestVisibleSurfacePoint(
            Minecraft client,
            AxisAlignedBB box,
            Vec3 referenceLook,
            double range,
            double preferredHeight,
            boolean precise,
            boolean throughBlocks) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null
                || currentPlayer == null
                || client.theWorld == null
                || box == null
                || !Double.isFinite(range)
                || range <= 0.0D) {
            return null;
        }
        Vec3 eye = currentPlayer.getPositionEyes(1F);
        if (VecMath.contains(box, eye)) {
            return VecMath.center(box);
        }
        Vec3 look =
                referenceLook != null && VecMath.lengthSqr(referenceLook) > 1.0E-9D
                        ? referenceLook.normalize()
                        : currentPlayer.getLook(1.0F);
        return findBestSurfacePoint(
                box,
                eye,
                look,
                range,
                preferredHeight,
                precise,
                point -> RaytraceUtils.canRayTraceTo(client, eye, point, throughBlocks));
    }

    /** Evaluate in the original order, without retaining up to 867 temporary points in a list. */
    static Vec3 findBestSurfacePoint(
            AxisAlignedBB box,
            Vec3 eye,
            Vec3 look,
            double range,
            double preferredHeight,
            boolean precise,
            Predicate<Vec3> visible) {
        SurfaceSearch search = new SurfaceSearch(box, eye, look, range, preferredHeight, visible);
        VecMath.clip(box, eye, eye.add(VecMath.scale(look, range))).ifPresent(search::consider);
        Vec3 closest = EntityDistance.closestPoint(eye, box);
        surfacePoint(eye, closest, box).ifPresent(search::consider);
        search.consider(closest);
        double[] samples = precise ? PRECISE_FACE_SAMPLES : FACE_SAMPLES;
        for (double a : samples) {
            double x = Mth.lerp(a, box.minX, box.maxX);
            double y = Mth.lerp(a, box.minY, box.maxY);
            for (double b : samples) {
                double z = Mth.lerp(b, box.minZ, box.maxZ);
                double xb = Mth.lerp(b, box.minX, box.maxX);
                double yb = Mth.lerp(b, box.minY, box.maxY);
                search.consider(new Vec3(box.minX, y, z));
                search.consider(new Vec3(box.maxX, y, z));
                search.consider(new Vec3(x, box.minY, z));
                search.consider(new Vec3(x, box.maxY, z));
                search.consider(new Vec3(xb, yb, box.minZ));
                search.consider(new Vec3(xb, yb, box.maxZ));
            }
        }
        return search.best;
    }

    private static final class SurfaceSearch {
        private final Vec3 eye, look;
        private final double rangeSquared, preferredY, height;
        private final Predicate<Vec3> visible;
        private Vec3 best;
        private double bestScore = Double.POSITIVE_INFINITY;

        private SurfaceSearch(
                AxisAlignedBB box,
                Vec3 eye,
                Vec3 look,
                double range,
                double preferredHeight,
                Predicate<Vec3> visible) {
            this.eye = eye;
            this.look = look;
            this.rangeSquared = range * range;
            this.preferredY = Mth.lerp(Mth.clamp(preferredHeight, 0.0D, 1.0D), box.minY, box.maxY);
            this.height = Math.max((box.maxY - box.minY), 0.1D);
            this.visible = visible;
        }

        private void consider(Vec3 point) {
            double distanceSquared = eye.squareDistanceTo(point);
            if (distanceSquared > rangeSquared || !visible.test(point)) return;
            Vec3 direction = point.subtract(eye).normalize();
            double angularCost = 1.0D - Mth.clamp(look.dotProduct(direction), -1.0D, 1.0D);
            double lowAimPenalty = Math.max(0.0D, preferredY - point.yCoord) / height;
            double score = angularCost * 32.0D + distanceSquared * 0.002D + lowAimPenalty * 1.35D;
            if (score < bestScore) {
                bestScore = score;
                best = point;
            }
        }
    }

    private static Optional<Vec3> surfacePoint(Vec3 eye, Vec3 desired, AxisAlignedBB box) {
        Vec3 difference = desired.subtract(eye);
        return VecMath.lengthSqr(difference) < 1.0E-9D
                ? Optional.empty()
                : VecMath.clip(box, eye, eye.add(VecMath.scale(difference, 2.0D)));
    }

    private static Vec3 bestVisibleSample(
            Minecraft client, EntityLivingBase entity, Vec3 eyePos, double range) {
        AxisAlignedBB box = entity.getEntityBoundingBox();

        Vec3 bestPoint = null;
        double bestAngle = Double.MAX_VALUE;

        for (Vec3 point : aimPoints(box)) {

            if (!isUsableAimPoint(client, eyePos, point, range)) {
                continue;
            }

            double angle = AimSolverD.angleFromView(client, point);

            if (angle < bestAngle) {
                bestAngle = angle;
                bestPoint = point;
            }
        }

        return bestPoint;
    }

    private static boolean isUsableAimPoint(
            Minecraft client, Vec3 eyePos, Vec3 point, double range) {
        return eyePos.squareDistanceTo(point) <= range * range
                && RaytraceUtils.canRayTraceTo(client, eyePos, point);
    }

    public static List<Vec3> aimPoints(AxisAlignedBB box) {
        List<Vec3> points = new ArrayList<>(30);

        Vec3 center = VecMath.center(box);

        points.add(center);

        points.add(new Vec3(center.xCoord, Mth.lerp(0.75D, box.minY, box.maxY), center.zCoord));

        points.add(new Vec3(center.xCoord, Mth.lerp(0.35D, box.minY, box.maxY), center.zCoord));

        double insetX = Math.min((box.maxX - box.minX) * 0.15D, 0.1D);

        double insetY = Math.min((box.maxY - box.minY) * 0.15D, 0.1D);

        double insetZ = Math.min((box.maxZ - box.minZ) * 0.15D, 0.1D);

        double minX = box.minX + insetX;
        double maxX = box.maxX - insetX;

        double minY = box.minY + insetY;
        double maxY = box.maxY - insetY;

        double minZ = box.minZ + insetZ;
        double maxZ = box.maxZ - insetZ;

        for (int ix = 0; ix < 3; ix++) {
            double x = ix == 0 ? minX : ix == 1 ? center.xCoord : maxX;
            for (int iy = 0; iy < 3; iy++) {
                double y = iy == 0 ? minY : iy == 1 ? center.yCoord : maxY;
                for (int iz = 0; iz < 3; iz++) {
                    double z = iz == 0 ? minZ : iz == 1 ? center.zCoord : maxZ;
                    points.add(new Vec3(x, y, z));
                }
            }
        }

        return points;
    }
}
