package com.blanoir.moons.client.utils.rotation.aim;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** SilentAura's continuous spatial noise projected onto a block's clicked face. */
public final class FaceAimJitter {
    private FaceAimJitter() {}

    public static Vec3 sample(
            Vec3 eye,
            Vec3 base,
            AABB bounds,
            Direction face,
            double seconds,
            long seed,
            double strength,
            double speed) {
        Vec3 moving = HitboxJitter.insideHitbox(eye, base, bounds, seconds, seed, strength, speed);
        // A block interaction needs a surface point, whereas combat accepts an interior point.
        return switch (face.getAxis()) {
            case X -> new Vec3(base.x, moving.y, moving.z);
            case Y -> new Vec3(moving.x, base.y, moving.z);
            case Z -> new Vec3(moving.x, moving.y, base.z);
        };
    }
}
