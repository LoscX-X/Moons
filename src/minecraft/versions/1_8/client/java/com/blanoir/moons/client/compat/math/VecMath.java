package com.blanoir.moons.client.compat.math;

import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

/** Pure vector operations absent from 1.8.9's immutable Vec3 API. */
public final class VecMath {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    private VecMath() {}

    public static Vec3 scale(Vec3 v, double n) {
        return new Vec3(v.xCoord * n, v.yCoord * n, v.zCoord * n);
    }

    public static Vec3 multiply(Vec3 v, double x, double y, double z) {
        return new Vec3(v.xCoord * x, v.yCoord * y, v.zCoord * z);
    }

    public static Vec3 lerp(Vec3 start, Vec3 end, double delta) {
        return new Vec3(
                Mth.lerp(delta, start.xCoord, end.xCoord),
                Mth.lerp(delta, start.yCoord, end.yCoord),
                Mth.lerp(delta, start.zCoord, end.zCoord));
    }

    public static double lengthSqr(Vec3 v) {
        return v.xCoord * v.xCoord + v.yCoord * v.yCoord + v.zCoord * v.zCoord;
    }

    public static double horizontalDistanceSqr(Vec3 v) {
        return v.xCoord * v.xCoord + v.zCoord * v.zCoord;
    }

    public static double horizontalDistance(Vec3 v) {
        return Math.sqrt(horizontalDistanceSqr(v));
    }

    public static Vec3 directionFromRotation(float pitch, float yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    public static Vec3 center(AxisAlignedBB b) {
        return new Vec3((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2);
    }

    public static Vec3 atCenterOf(BlockPos pos) {
        return new Vec3(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
    }

    public static Vec3 atBottomCenterOf(BlockPos pos) {
        return new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    }

    public static Vec3 position(Entity entity) {
        return new Vec3(entity.posX, entity.posY, entity.posZ);
    }

    public static Vec3 position(Entity entity, float partial) {
        return new Vec3(
                Mth.lerp(partial, entity.prevPosX, entity.posX),
                Mth.lerp(partial, entity.prevPosY, entity.posY),
                Mth.lerp(partial, entity.prevPosZ, entity.posZ));
    }

    public static Vec3 motion(Entity entity) {
        return new Vec3(entity.motionX, entity.motionY, entity.motionZ);
    }

    public static void motion(Entity entity, Vec3 motion) {
        entity.motionX = motion.xCoord;
        entity.motionY = motion.yCoord;
        entity.motionZ = motion.zCoord;
    }

    public static AxisAlignedBB move(AxisAlignedBB box, Vec3 v) {
        return box.offset(v.xCoord, v.yCoord, v.zCoord);
    }

    public static AxisAlignedBB move(AxisAlignedBB box, double x, double y, double z) {
        return box.offset(x, y, z);
    }

    public static AxisAlignedBB inflate(AxisAlignedBB box, double amount) {
        return box.expand(amount, amount, amount);
    }

    public static AxisAlignedBB inflate(AxisAlignedBB box, double x, double y, double z) {
        return box.expand(x, y, z);
    }

    public static java.util.Optional<Vec3> clip(AxisAlignedBB box, Vec3 start, Vec3 end) {
        var hit = box.calculateIntercept(start, end);
        return hit == null ? java.util.Optional.empty() : java.util.Optional.of(hit.hitVec);
    }

    public static boolean contains(AxisAlignedBB box, Vec3 v) {
        return v.xCoord >= box.minX
                && v.xCoord < box.maxX
                && v.yCoord >= box.minY
                && v.yCoord < box.maxY
                && v.zCoord >= box.minZ
                && v.zCoord < box.maxZ;
    }

    public static double distanceToSqr(Vec3 v, double x, double y, double z) {
        double dx = v.xCoord - x, dy = v.yCoord - y, dz = v.zCoord - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
