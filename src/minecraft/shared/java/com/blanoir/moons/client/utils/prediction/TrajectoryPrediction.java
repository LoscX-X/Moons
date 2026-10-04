package com.blanoir.moons.client.utils.prediction;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.compat.input.InputSnapshot;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.compat.world.LegacyCollision;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

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
        return position.add(VecMath.scale(velocity, ticks));
    }

    public static AxisAlignedBB linearBox(AxisAlignedBB box, Vec3 velocity, double ticks) {
        return VecMath.move(box, VecMath.scale(velocity, ticks));
    }

    public static TrajectoryStep advance(
            Minecraft client, EntityPlayer target, Vec3 position, Vec3 velocity, boolean grounded) {
        AxisAlignedBB box =
                VecMath.move(
                        target.getEntityBoundingBox(), position.subtract(VecMath.position(target)));
        return advance(
                position,
                velocity,
                grounded,
                requested -> LegacyCollision.resolve(target, requested, box, client.theWorld));
    }

    /** One physics step; collision resolution is supplied by the world adapter. */
    public static TrajectoryStep advance(
            Vec3 position, Vec3 velocity, boolean grounded, UnaryOperator<Vec3> collision) {
        // Resolve each step against real block shapes. The small downward
        // grounded step detects both floor support and walking off an edge.
        Vec3 requested =
                grounded && velocity.yCoord <= 0.0D
                        ? new Vec3(velocity.xCoord, -GRAVITY, velocity.zCoord)
                        : velocity;
        Vec3 movement = collision.apply(requested);
        boolean blockedX = Math.abs(movement.xCoord - requested.xCoord) > RAY_EPSILON;
        boolean blockedY = Math.abs(movement.yCoord - requested.yCoord) > RAY_EPSILON;
        boolean blockedZ = Math.abs(movement.zCoord - requested.zCoord) > RAY_EPSILON;
        Vec3 nextVelocity =
                new Vec3(
                        blockedX ? 0.0D : velocity.xCoord * HORIZONTAL_DRAG,
                        blockedY ? 0.0D : (requested.yCoord - GRAVITY) * VERTICAL_DRAG,
                        blockedZ ? 0.0D : velocity.zCoord * HORIZONTAL_DRAG);
        return new TrajectoryStep(
                position.add(movement), nextVelocity, blockedY && requested.yCoord < 0.0D);
    }

    public static AxisAlignedBB freeFlightBox(EntityPlayer target, int ticks) {
        Vec3 position =
                freeFlightPosition(
                        VecMath.position(target), VecMath.motion(target), target.onGround, ticks);
        return VecMath.move(
                target.getEntityBoundingBox(), position.subtract(VecMath.position(target)));
    }

    /** Collision-free path, retaining the original ground/air integration order. */
    public static Vec3 freeFlightPosition(
            Vec3 position, Vec3 velocity, boolean grounded, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            if (grounded) {
                position = position.addVector(velocity.xCoord, 0.0D, velocity.zCoord);
                velocity = new Vec3(velocity.xCoord * 0.91D, 0.0D, velocity.zCoord * 0.91D);
            } else {
                double nextY = (velocity.yCoord - 0.08D) * 0.98D;
                velocity = new Vec3(velocity.xCoord * 0.91D, nextY, velocity.zCoord * 0.91D);
                position = position.add(velocity);
            }
        }
        return position;
    }

    /** Immediate horizontal footprint, before applying drag or gravity. */
    public static Vec3 horizontalCollisionTravel(
            Minecraft client, EntityPlayer target, Vec3 velocity, double ticks) {
        return LegacyCollision.resolve(
                target,
                new Vec3(velocity.xCoord * ticks, 0.0D, velocity.zCoord * ticks),
                target.getEntityBoundingBox(),
                client.theWorld);
    }

    public static Vec3 observedVelocity(EntityPlayer target) {
        Vec3 observed =
                new Vec3(
                        target.posX - target.prevPosX,
                        target.posY - target.prevPosY,
                        target.posZ - target.prevPosZ);
        return Double.isFinite(VecMath.lengthSqr(observed)) && VecMath.lengthSqr(observed) <= 2.25D
                ? observed
                : VecMath.ZERO;
    }

    /**
     * Advances horizontal movement with vanilla drag and world collision. A
     * wall stops only the blocked axis, so lateral knockback continues sliding
     * along it instead of predicting through the wall or stopping completely.
     */
    public static Vec3 horizontalPosition(
            Minecraft client, EntityPlayer entity, Vec3 velocity, Vec3 acceleration, double ticks) {
        Vec3 position = VecMath.position(entity);
        AxisAlignedBB box = entity.getEntityBoundingBox();
        double remaining = Math.max(0.0D, ticks);
        double velocityX =
                velocity.xCoord
                        + Mth.clamp(acceleration.xCoord, -0.08D, 0.08D) * Math.min(1.0D, remaining);
        double velocityZ =
                velocity.zCoord
                        + Mth.clamp(acceleration.zCoord, -0.08D, 0.08D) * Math.min(1.0D, remaining);
        while (remaining > 1.0E-6D) {
            double fraction = Math.min(1.0D, remaining);
            Vec3 moved =
                    collideHorizontal(
                            client, entity, box, velocityX * fraction, velocityZ * fraction);
            position = position.addVector(moved.xCoord, 0.0D, moved.zCoord);
            box = box.offset(moved.xCoord, 0.0D, moved.zCoord);
            if (Math.abs(moved.xCoord - velocityX * fraction) > 1.0E-4D) {
                velocityX = 0.0D;
            }
            if (Math.abs(moved.zCoord - velocityZ * fraction) > 1.0E-4D) {
                velocityZ = 0.0D;
            }
            velocityX *= Math.pow(HORIZONTAL_DRAG, fraction);
            velocityZ *= Math.pow(HORIZONTAL_DRAG, fraction);
            remaining -= fraction;
        }
        return position;
    }

    private static Vec3 collideHorizontal(
            Minecraft client,
            EntityPlayer entity,
            AxisAlignedBB box,
            double requestedX,
            double requestedZ) {
        double movedX = clipAxis(client, entity, box, requestedX, true);
        AxisAlignedBB afterX = box.offset(movedX, 0.0D, 0.0D);
        double movedZ = clipAxis(client, entity, afterX, requestedZ, false);
        return new Vec3(movedX, 0.0D, movedZ);
    }

    private static double clipAxis(
            Minecraft client,
            EntityPlayer entity,
            AxisAlignedBB box,
            double requested,
            boolean xAxis) {
        if (Math.abs(requested) <= 1.0E-9D) {
            return 0.0D;
        }
        AxisAlignedBB full =
                xAxis ? box.offset(requested, 0.0D, 0.0D) : box.offset(0.0D, 0.0D, requested);
        if (client.theWorld.getCollidingBoundingBoxes(entity, full).isEmpty()) {
            return requested;
        }
        double low = 0.0D;
        double high = 1.0D;
        for (int step = 0; step < COLLISION_BINARY_STEPS; step++) {
            double middle = (low + high) * 0.5D;
            AxisAlignedBB candidate =
                    xAxis
                            ? box.offset(requested * middle, 0.0D, 0.0D)
                            : box.offset(0.0D, 0.0D, requested * middle);
            if (client.theWorld.getCollidingBoundingBoxes(entity, candidate).isEmpty()) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return requested * low;
    }

    public static AxisAlignedBB nextInputBox(Minecraft client) {
        return nextInputBox(client, GameAccess.inputSnapshot(client.thePlayer.movementInput));
    }

    /** Use the caller's raw input when prediction runs before movement correction. */
    public static AxisAlignedBB nextInputBox(Minecraft client, InputSnapshot input) {
        int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        int strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double moveX;
        double moveZ;
        if (forward == 0 && strafe == 0) {
            Vec3 velocity = VecMath.motion(client.thePlayer);
            moveX = velocity.xCoord;
            moveZ = velocity.zCoord;
        } else {
            double speed = client.thePlayer.isSprinting() ? 0.2873D : 0.221D;
            float yaw = adjustedYaw(client.thePlayer.rotationYaw, forward, strafe);
            moveX = -Math.sin(yaw * Mth.DEG_TO_RAD) * speed;
            moveZ = Math.cos(yaw * Mth.DEG_TO_RAD) * speed;
        }
        return client.thePlayer.getEntityBoundingBox().offset(moveX, 0.0D, moveZ);
    }

    private static float adjustedYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0F) yaw += 180.0F;
        if (strafe != 0.0F) {
            float multiplier = forward == 0.0F ? 1.0F : 0.5F * Math.signum(forward);
            yaw += -90.0F * multiplier * Math.signum(strafe);
        }
        return Mth.wrapDegrees(yaw);
    }

    public static AxisAlignedBB boundedLinearBox(
            AxisAlignedBB box, Vec3 smoothedVelocity, double ticksAhead) {
        double ticks = Mth.clamp(ticksAhead, 0.0D, 3.0D);
        return VecMath.move(box, VecMath.scale(smoothedVelocity, ticks));
    }
}
