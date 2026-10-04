package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.quantize.QuantizerA;

import net.minecraft.util.Vec3;

/**
 * E: Existing Scaffold point-to-angle arithmetic and relative-yaw quantization.
 * Quantization is injected so the original sensitivity reads and yaw-before-pitch calls stay
 * with the caller's quantizer. No rotation leases, packet writes or owned state.
 */
public final class AimSolverE {
    private AimSolverE() {}

    /** Null rejects the original near-zero-length probe before any quantizer call. */
    public static Rotation solve(
            Vec3 eye, Vec3 point, Rotation base, QuantizerA.Adapter quantizer) {
        Vec3 delta = point.subtract(eye);
        double horizontal = Math.hypot(delta.xCoord, delta.zCoord);
        if (VecMath.lengthSqr(delta) < 1.0E-8D) return null;
        float rawYaw = (float) Math.toDegrees(Math.atan2(delta.zCoord, delta.xCoord)) - 90.0F;
        float rawPitch = (float) -Math.toDegrees(Math.atan2(delta.yCoord, horizontal));
        return quantizer.relative(base, new Rotation(rawYaw, rawPitch));
    }

    public static double distance(Rotation rotation, float baseYaw, float basePitch) {
        return Math.abs(Mth.wrapDegrees(rotation.yaw() - baseYaw))
                + Math.abs(rotation.pitch() - basePitch);
    }
}
