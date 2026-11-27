package com.blanoir.tempos.client.utils.math;

import com.blanoir.tempos.client.utils.rotation.Rotation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Yaw and pitch calculations in degrees. */
public final class AngleMath {
    private AngleMath() {}

    public static Rotation rotationTo(Vec3 from, Vec3 to) {
        if (from == null || to == null) {
            return new Rotation(0.0F, 0.0F);
        }
        double deltaX = to.x - from.x;
        double deltaY = to.y - from.y;
        double deltaZ = to.z - from.z;
        double horizontal = Math.hypot(deltaX, deltaZ);
        float yaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(deltaY, horizontal));
        return new Rotation(Mth.wrapDegrees(yaw), Math.clamp(pitch, -90.0F, 90.0F));
    }

    public static double angularDistance(float yaw, float pitch, Rotation target) {
        if (target == null) {
            return Double.MAX_VALUE;
        }
        float yawDifference = Math.abs(Mth.wrapDegrees(target.yaw() - yaw));
        float pitchDifference = Math.abs(target.pitch() - pitch);
        return Math.hypot(yawDifference, pitchDifference);
    }

    public static float wrappedAngleDifference(float current, float target) {
        return Mth.wrapDegrees(target - current);
    }

    /** Per-axis tolerance: wrapped yaw, linear pitch. Not a combined angular distance. */
    public static boolean withinRotationTolerance(
            float yawDifference, float pitchDifference, float tolerance) {
        return Math.abs(Mth.wrapDegrees(yawDifference)) <= tolerance
                && Math.abs(pitchDifference) <= tolerance;
    }

    public static float yawTo(Vec3 from, Vec3 to) {
        return rotationTo(from, to).yaw();
    }

    public static float smoothWrappedAngle(float current, float target, double response) {
        return current + wrappedAngleDifference(current, target) * (float) response;
    }
}
