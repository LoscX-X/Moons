package com.blanoir.moons.client.utils.prediction;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.compat.world.LegacyCollision;
import com.blanoir.moons.client.utils.world.LegacyWorld;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Bounded fall-time and landing-cell estimates using the existing discrete physics policies. */
public final class LandingPrediction {
    private LandingPrediction() {}

    public record FallImpact(int ticks, double fallDistance, AxisAlignedBB box) {}

    /** Predict the first downward collision of the whole body, bounded by the reaction window. */
    public static FallImpact fallImpact(EntityPlayer player, int horizon) {
        return fallImpact(
                player.getEntityBoundingBox(),
                VecMath.motion(player),
                player.fallDistance,
                horizon,
                (box, velocity) -> LegacyCollision.resolve(player, velocity, box, player.worldObj),
                (box, movement) -> interruptsFall(player, box, movement));
    }

    static FallImpact fallImpact(
            AxisAlignedBB box,
            Vec3 velocity,
            double distance,
            int horizon,
            BiFunction<AxisAlignedBB, Vec3, Vec3> collision,
            BiPredicate<AxisAlignedBB, Vec3> interrupted) {
        for (int tick = 1; tick <= Math.clamp(horizon, 0, 20); tick++) {
            Vec3 movement = collision.apply(box, velocity);
            if (interrupted.test(box, movement)) return null;
            box = VecMath.move(box, movement);
            distance += Math.max(0, -(float) movement.yCoord);
            if (velocity.yCoord < 0 && movement.yCoord > velocity.yCoord + 1.0E-5)
                return new FallImpact(tick, distance, box);
            velocity =
                    new Vec3(
                            Math.abs(movement.xCoord - velocity.xCoord) > 1.0E-5
                                    ? 0
                                    : velocity.xCoord * .91,
                            (velocity.yCoord - .08) * .98,
                            Math.abs(movement.zCoord - velocity.zCoord) > 1.0E-5
                                    ? 0
                                    : velocity.zCoord * .91);
        }
        return null;
    }

    private static boolean interruptsFall(EntityPlayer player, AxisAlignedBB box, Vec3 movement) {
        // Sample the swept body, so fast falls cannot skip a one-block water layer.
        int steps = Math.clamp(Mth.ceil(movement.lengthVector() * 2), 1, 64);
        for (int step = 1; step <= steps; step++) {
            AxisAlignedBB sample =
                    VecMath.move(box, VecMath.scale(movement, (double) step / steps))
                            .expand(-1.0E-5, -1.0E-5, -1.0E-5);
            for (BlockPos pos :
                    BlockPos.getAllInBox(
                            new BlockPos(sample.minX, sample.minY, sample.minZ),
                            new BlockPos(sample.maxX, sample.maxY, sample.maxZ))) {
                var state = player.worldObj.getBlockState(pos);
                var block = state.getBlock();
                boolean water = block.getMaterial() == Material.water;
                double height =
                        water
                                ? 1
                                        - BlockLiquid.getLiquidHeightPercent(
                                                block.getMetaFromState(state))
                                : 0;
                if ((water && sample.minY < pos.getY() + height)
                        || block == Blocks.web
                        || block == Blocks.ladder
                        || block == Blocks.vine) return true;
            }
        }
        return false;
    }

    /** Resolve the actual support shape, including slabs, fences and edge landings. */
    public static BlockPos supportBlock(EntityPlayer player, AxisAlignedBB box) {
        BlockPos best = null;
        double overlap = 0;

        for (int x = Mth.floor(box.minX + 1.0E-5); x <= Mth.floor(box.maxX - 1.0E-5); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-5); z <= Mth.floor(box.maxZ - 1.0E-5); z++) {
                for (int y = Mth.floor(box.minY) - 2; y <= Mth.floor(box.minY); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    for (AxisAlignedBB local :
                            LegacyWorld.collision(
                                            player.worldObj.getBlockState(pos),
                                            player.worldObj,
                                            pos,
                                            player)
                                    .toAabbs()) {
                        AxisAlignedBB shape = local.offset(x, y, z);
                        if (Math.abs(shape.maxY - box.minY) > 1.0E-5) continue;
                        double area =
                                Math.max(
                                                0,
                                                Math.min(shape.maxX, box.maxX)
                                                        - Math.max(shape.minX, box.minX))
                                        * Math.max(
                                                0,
                                                Math.min(shape.maxZ, box.maxZ)
                                                        - Math.max(shape.minZ, box.minZ));
                        if (area > overlap) {
                            best = pos;
                            overlap = area;
                        }
                    }
                }
            }
        }
        return best;
    }

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

    public static BlockPos landingBlock(EntityPlayer player) {
        return landingBlock(VecMath.position(player), VecMath.motion(player));
    }

    /** Projects onto the current feet-cell plane; this is not a world collision scan. */
    public static BlockPos landingBlock(Vec3 position, Vec3 velocity) {
        double x = position.xCoord;
        double y = position.yCoord;
        double z = position.zCoord;
        double vx = velocity.xCoord;
        double vy = velocity.yCoord;
        double vz = velocity.zCoord;
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
