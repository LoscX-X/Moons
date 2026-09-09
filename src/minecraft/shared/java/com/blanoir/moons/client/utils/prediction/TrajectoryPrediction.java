package com.blanoir.moons.client.utils.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/** Motion projections with explicit collision and integration policies. Horizons are ticks. */
public final class TrajectoryPrediction {
    private static final double HORIZONTAL_DRAG = 0.91D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final int COLLISION_BINARY_STEPS = 8;

    private TrajectoryPrediction() {}

    public record TrajectoryStep(Vec3 position, Vec3 velocity, boolean grounded) {}

    /** Constant-velocity projection; the caller owns the horizon policy. */
    public static Vec3 linearPosition(Vec3 position, Vec3 velocity, double ticks) {
        return position.add(velocity.scale(ticks));
    }

    public static AABB linearBox(AABB box, Vec3 velocity, double ticks) {
        return box.move(velocity.scale(ticks));
    }

    public static TrajectoryStep advance(
            Minecraft client, Player target, Vec3 position, Vec3 velocity, boolean grounded) {
        AABB box = target.getBoundingBox().move(position.subtract(target.position()));
        return advance(
                position,
                velocity,
                grounded,
                requested ->
                        Entity.collideBoundingBox(
                                target, requested, box, client.level, java.util.List.of()));
    }

    /** One physics step; collision resolution is supplied by the world adapter. */
    public static TrajectoryStep advance(
            Vec3 position, Vec3 velocity, boolean grounded, UnaryOperator<Vec3> collision) {
        // Resolve each step against real block shapes. The small downward
        // grounded step detects both floor support and walking off an edge.
        Vec3 requested =
                grounded && velocity.y <= 0.0D
                        ? new Vec3(velocity.x, -GRAVITY, velocity.z)
                        : velocity;
        Vec3 movement = collision.apply(requested);
        boolean blockedX = Math.abs(movement.x - requested.x) > RAY_EPSILON;
        boolean blockedY = Math.abs(movement.y - requested.y) > RAY_EPSILON;
        boolean blockedZ = Math.abs(movement.z - requested.z) > RAY_EPSILON;
        Vec3 nextVelocity =
                new Vec3(
                        blockedX ? 0.0D : velocity.x * HORIZONTAL_DRAG,
                        blockedY ? 0.0D : (requested.y - GRAVITY) * VERTICAL_DRAG,
                        blockedZ ? 0.0D : velocity.z * HORIZONTAL_DRAG);
        return new TrajectoryStep(
                position.add(movement), nextVelocity, blockedY && requested.y < 0.0D);
    }

    public static AABB freeFlightBox(Player target, int ticks) {
        Vec3 position =
                freeFlightPosition(
                        target.position(), target.getDeltaMovement(), target.onGround(), ticks);
        return target.getDimensions(Pose.STANDING).makeBoundingBox(position);
    }

    /** Collision-free path, retaining the original ground/air integration order. */
    public static Vec3 freeFlightPosition(
            Vec3 position, Vec3 velocity, boolean grounded, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            if (grounded) {
                position = position.add(velocity.x, 0.0D, velocity.z);
                velocity = new Vec3(velocity.x * 0.91D, 0.0D, velocity.z * 0.91D);
            } else {
                double nextY = (velocity.y - 0.08D) * 0.98D;
                velocity = new Vec3(velocity.x * 0.91D, nextY, velocity.z * 0.91D);
                position = position.add(velocity);
            }
        }
        return position;
    }

    /** Immediate horizontal footprint, before applying drag or gravity. */
    public static Vec3 horizontalCollisionTravel(
            Minecraft client, Player target, Vec3 velocity, double ticks) {
        return Entity.collideBoundingBox(
                target,
                new Vec3(velocity.x * ticks, 0.0D, velocity.z * ticks),
                target.getBoundingBox(),
                client.level,
                java.util.List.of());
    }

    public static Vec3 observedVelocity(Player target) {
        Vec3 observed =
                new Vec3(
                        target.getX() - target.xo,
                        target.getY() - target.yo,
                        target.getZ() - target.zo);
        return Double.isFinite(observed.lengthSqr()) && observed.lengthSqr() <= 2.25D
                ? observed
                : Vec3.ZERO;
    }

    /**
     * Advances horizontal movement with vanilla drag and world collision. A
     * wall stops only the blocked axis, so lateral knockback continues sliding
     * along it instead of predicting through the wall or stopping completely.
     */
    public static Vec3 horizontalPosition(
            Minecraft client, Player entity, Vec3 velocity, Vec3 acceleration, double ticks) {
        Vec3 position = entity.position();
        AABB box = entity.getBoundingBox();
        double remaining = Math.max(0.0D, ticks);
        double velocityX =
                velocity.x + Mth.clamp(acceleration.x, -0.08D, 0.08D) * Math.min(1.0D, remaining);
        double velocityZ =
                velocity.z + Mth.clamp(acceleration.z, -0.08D, 0.08D) * Math.min(1.0D, remaining);
        while (remaining > 1.0E-6D) {
            double fraction = Math.min(1.0D, remaining);
            Vec3 moved =
                    collideHorizontal(
                            client, entity, box, velocityX * fraction, velocityZ * fraction);
            position = position.add(moved.x, 0.0D, moved.z);
            box = box.move(moved.x, 0.0D, moved.z);
            if (Math.abs(moved.x - velocityX * fraction) > 1.0E-4D) {
                velocityX = 0.0D;
            }
            if (Math.abs(moved.z - velocityZ * fraction) > 1.0E-4D) {
                velocityZ = 0.0D;
            }
            velocityX *= Math.pow(HORIZONTAL_DRAG, fraction);
            velocityZ *= Math.pow(HORIZONTAL_DRAG, fraction);
            remaining -= fraction;
        }
        return position;
    }

    private static Vec3 collideHorizontal(
            Minecraft client, Player entity, AABB box, double requestedX, double requestedZ) {
        double movedX = clipAxis(client, entity, box, requestedX, true);
        AABB afterX = box.move(movedX, 0.0D, 0.0D);
        double movedZ = clipAxis(client, entity, afterX, requestedZ, false);
        return new Vec3(movedX, 0.0D, movedZ);
    }

    private static double clipAxis(
            Minecraft client, Player entity, AABB box, double requested, boolean xAxis) {
        if (Math.abs(requested) <= 1.0E-9D) {
            return 0.0D;
        }
        AABB full = xAxis ? box.move(requested, 0.0D, 0.0D) : box.move(0.0D, 0.0D, requested);
        if (client.level.noCollision(entity, full)) {
            return requested;
        }
        double low = 0.0D;
        double high = 1.0D;
        for (int step = 0; step < COLLISION_BINARY_STEPS; step++) {
            double middle = (low + high) * 0.5D;
            AABB candidate =
                    xAxis
                            ? box.move(requested * middle, 0.0D, 0.0D)
                            : box.move(0.0D, 0.0D, requested * middle);
            if (client.level.noCollision(entity, candidate)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return requested * low;
    }

    public static AABB nextInputBox(Minecraft client) {
        Input input = client.player.input.keyPresses;
        int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        int strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double moveX;
        double moveZ;
        if (forward == 0 && strafe == 0) {
            Vec3 velocity = client.player.getDeltaMovement();
            moveX = velocity.x;
            moveZ = velocity.z;
        } else {
            double speed = client.player.isSprinting() ? 0.2873D : 0.221D;
            float yaw = adjustedYaw(client.player.getYRot(), forward, strafe);
            moveX = -Math.sin(yaw * Mth.DEG_TO_RAD) * speed;
            moveZ = Math.cos(yaw * Mth.DEG_TO_RAD) * speed;
        }
        return client.player.getBoundingBox().move(moveX, 0.0D, moveZ);
    }

    private static float adjustedYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0F) yaw += 180.0F;
        if (strafe != 0.0F) {
            float multiplier = forward == 0.0F ? 1.0F : 0.5F * Math.signum(forward);
            yaw += -90.0F * multiplier * Math.signum(strafe);
        }
        return Mth.wrapDegrees(yaw);
    }

    public static AABB boundedLinearBox(AABB box, Vec3 smoothedVelocity, double ticksAhead) {
        double ticks = Mth.clamp(ticksAhead, 0.0D, 3.0D);
        return box.move(smoothedVelocity.scale(ticks));
    }
}
