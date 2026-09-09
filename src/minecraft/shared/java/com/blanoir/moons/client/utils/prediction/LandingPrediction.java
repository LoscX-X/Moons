package com.blanoir.moons.client.utils.prediction;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Bounded fall-time and landing-cell estimates using the existing discrete physics policies. */
public final class LandingPrediction {
    private LandingPrediction() {}

    /** First falling tick reaching the measured ground distance, or MAX_VALUE. */
    public static int ticksUntilGround(double velocityY, double distance) {
        if (velocityY >= 0.0D || distance == Double.POSITIVE_INFINITY) return Integer.MAX_VALUE;
        double simulatedDrop = 0.0D;
        double simulatedVelocity = velocityY;
        for (int tick = 1; tick <= 20; tick++) {
            simulatedDrop += simulatedVelocity;
            simulatedVelocity = (simulatedVelocity - 0.08D) * 0.98D;
            if (Math.abs(simulatedDrop) >= distance) return tick;
        }
        return Integer.MAX_VALUE;
    }

    public static BlockPos landingBlock(Player player) {
        return landingBlock(player.position(), player.getDeltaMovement());
    }

    /** Projects onto the current feet-cell plane; this is not a world collision scan. */
    public static BlockPos landingBlock(Vec3 position, Vec3 velocity) {
        double x = position.x;
        double y = position.y;
        double z = position.z;
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;
        double landY = Math.floor(y - 0.01D) + 1.0D;

        for (int tick = 0; tick < 20; tick++) {
            vy = (vy - 0.08D) * 0.98D;
            y += vy;
            vx *= 0.91D;
            vz *= 0.91D;
            x += vx;
            z += vz;

            if (y <= landY) {
                return new BlockPos(Mth.floor(x), Mth.floor(landY - 0.01D), Mth.floor(z));
            }
        }

        return null;
    }
}
