package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.math.SmoothNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Perturbs an active aiming path while keeping its requested point inside the hitbox. */
public final class AimJitter {
    private AimJitter() {}

    public static Vec3 insideHitbox(
            Vec3 eye,
            Vec3 base,
            AABB box,
            double seconds,
            int targetId,
            double strength,
            double speed
    ) {
        double amount = Mth.clamp(strength, 0.0D, 1.0D);
        if (amount <= 0.0D || box == null) return base;

        double insetX = Math.min(box.getXsize() * 0.18D, 0.10D);
        double insetY = Math.min(box.getYsize() * 0.15D, 0.20D);
        double insetZ = Math.min(box.getZsize() * 0.18D, 0.10D);
        double minX = box.minX + insetX;
        double maxX = box.maxX - insetX;
        double minY = box.minY + insetY;
        double maxY = box.maxY - insetY;
        double minZ = box.minZ + insetZ;
        double maxZ = box.maxZ - insetZ;

        Vec3 safeBase = new Vec3(
                Mth.clamp(base.x, minX, maxX),
                Mth.clamp(base.y, minY, maxY),
                Mth.clamp(base.z, minZ, maxZ));
        double time = seconds * Mth.clamp(speed, 0.1D, 3.0D);
        long seed = Integer.toUnsignedLong(targetId) * 0x9E3779B97F4A7C15L;
        double horizontalSize = Math.min(box.getXsize(), box.getZsize());
        double lateralAmplitude = Math.min(horizontalSize * 0.105D * amount,
                Math.min(maxX - minX, maxZ - minZ) * 0.18D);
        // Keep pitch almost level. Vertical motion is intentionally tiny because
        // even a small world-space Y offset is prominent in sent head pitch.
        double amplitudeY = Math.min(box.getYsize() * 0.0035D * amount, (maxY - minY) * 0.008D);

        // Use a view-relative right vector. World-axis X/Z noise can turn into
        // mostly depth movement from some camera angles and become invisible.
        double viewX = safeBase.x - eye.x;
        double viewZ = safeBase.z - eye.z;
        double horizontalLength = Math.hypot(viewX, viewZ);
        double rightX = horizontalLength < 1.0E-6D ? 1.0D : -viewZ / horizontalLength;
        double rightZ = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardX = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardZ = horizontalLength < 1.0E-6D ? 1.0D : viewZ / horizontalLength;

        // Trace a continuously varying, flattened orbit around the closest
        // surface point. Most of the visible movement is yaw; the depth axis
        // prevents a mechanical left-right pendulum without adding pitch.
        double orbit = time * 1.9D
                + SmoothNoise.sample(time * 0.31D, seed ^ 0x94D049BB133111EBL) * 0.72D;
        double radius = lateralAmplitude
                * (0.84D + SmoothNoise.sample(time * 0.47D, seed) * 0.16D);
        double lateral = Math.cos(orbit) * radius;
        double depth = Math.sin(orbit) * radius * 0.34D;
        double vertical = SmoothNoise.sample(
                time * 0.58D, seed ^ 0xD1B54A32D192ED03L) * amplitudeY;

        return new Vec3(
                Mth.clamp(safeBase.x + rightX * lateral + forwardX * depth, minX, maxX),
                Mth.clamp(safeBase.y + vertical, minY, maxY),
                Mth.clamp(safeBase.z + rightZ * lateral + forwardZ * depth, minZ, maxZ));
    }
}
