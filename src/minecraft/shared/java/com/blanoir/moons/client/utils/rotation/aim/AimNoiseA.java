package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.compat.math.Mth;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/** A: Existing hitbox-constrained spatial aim jitter. Explicit time/seed inputs; no owned history. */
public final class AimNoiseA {
    private AimNoiseA() {}

    public static Vec3 insideHitbox(
            Vec3 eye,
            Vec3 base,
            AxisAlignedBB box,
            double seconds,
            int targetId,
            double strength,
            double speed) {
        return insideHitbox(
                eye,
                base,
                box,
                seconds,
                Integer.toUnsignedLong(targetId) * 0x9E3779B97F4A7C15L,
                strength,
                speed);
    }

    /** A caller-owned session seed prevents entity IDs from defining the motion profile. */
    public static Vec3 insideHitbox(
            Vec3 eye,
            Vec3 base,
            AxisAlignedBB box,
            double seconds,
            long seed,
            double strength,
            double speed) {
        double amount = Mth.clamp(strength, 0.0D, 1.0D);
        if (amount <= 0.0D || box == null) return base;

        // Inset must vanish with strength too. Otherwise, 0 -> epsilon snaps a
        // surface point inward by 0.1 blocks even though the noise is tiny.
        double insetX = Math.min((box.maxX - box.minX) * 0.18D, 0.10D) * amount;
        double insetY = Math.min((box.maxY - box.minY) * 0.15D, 0.20D) * amount;
        double insetZ = Math.min((box.maxZ - box.minZ) * 0.18D, 0.10D) * amount;
        double minX = box.minX + insetX;
        double maxX = box.maxX - insetX;
        double minY = box.minY + insetY;
        double maxY = box.maxY - insetY;
        double minZ = box.minZ + insetZ;
        double maxZ = box.maxZ - insetZ;

        Vec3 safeBase =
                new Vec3(
                        Mth.clamp(base.xCoord, minX, maxX),
                        Mth.clamp(base.yCoord, minY, maxY),
                        Mth.clamp(base.zCoord, minZ, maxZ));
        double time = seconds * Mth.clamp(speed, 0.1D, 3.0D);
        double horizontalSize = Math.min((box.maxX - box.minX), (box.maxZ - box.minZ));
        double lateralAmplitude =
                Math.min(
                        horizontalSize * 0.105D * amount,
                        Math.min(maxX - minX, maxZ - minZ) * 0.18D);
        // Keep pitch almost level. Vertical motion is intentionally tiny because
        // even a small world-space Y offset is prominent in sent head pitch.
        double amplitudeY =
                Math.min((box.maxY - box.minY) * 0.0035D * amount, (maxY - minY) * 0.008D);

        // Use a view-relative right vector. World-axis X/Z noise can turn into
        // mostly depth movement from some camera angles and become invisible.
        double viewX = safeBase.xCoord - eye.xCoord;
        double viewZ = safeBase.zCoord - eye.zCoord;
        double horizontalLength = Math.hypot(viewX, viewZ);
        double rightX = horizontalLength < 1.0E-6D ? 1.0D : -viewZ / horizontalLength;
        double rightZ = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardX = horizontalLength < 1.0E-6D ? 0.0D : viewX / horizontalLength;
        double forwardZ = horizontalLength < 1.0E-6D ? 1.0D : viewZ / horizontalLength;

        // Independent bounded channels avoid a shared sine/cosine orbit.
        double lateral = AimNoiseB.sample(time * 1.9D, seed) * lateralAmplitude;
        double depth =
                AimNoiseB.sample(time * 1.37D, seed ^ 0x94D049BB133111EBL)
                        * lateralAmplitude
                        * 0.34D;
        double vertical = AimNoiseB.sample(time * 0.58D, seed ^ 0xD1B54A32D192ED03L) * amplitudeY;

        return new Vec3(
                Mth.clamp(safeBase.xCoord + rightX * lateral + forwardX * depth, minX, maxX),
                Mth.clamp(safeBase.yCoord + vertical, minY, maxY),
                Mth.clamp(safeBase.zCoord + rightZ * lateral + forwardZ * depth, minZ, maxZ));
    }
}
