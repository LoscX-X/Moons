package com.blanoir.moons.client.utils.prediction;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/** Bounded fall-time and landing-cell estimates using the existing discrete physics policies. */
public final class LandingPrediction {
    private LandingPrediction() {}

    public record FallImpact(int ticks, double fallDistance, AABB box) {}

    /** Predict the first downward collision of the whole body, bounded by the reaction window. */
    public static FallImpact fallImpact(Player player, int horizon) {
        return fallImpact(
                player.getBoundingBox(),
                player.getDeltaMovement(),
                player.fallDistance,
                horizon,
                (box, velocity) ->
                        Entity.collideBoundingBox(player, velocity, box, player.level(), List.of()),
                (box, movement) -> interruptsFall(player, box, movement));
    }

    static FallImpact fallImpact(
            AABB box,
            Vec3 velocity,
            double distance,
            int horizon,
            BiFunction<AABB, Vec3, Vec3> collision,
            BiPredicate<AABB, Vec3> interrupted) {
        for (int tick = 1; tick <= Math.clamp(horizon, 0, 20); tick++) {
            Vec3 movement = collision.apply(box, velocity);
            if (interrupted.test(box, movement)) return null;
            box = box.move(movement);
            distance += Math.max(0, -(float) movement.y);
            if (velocity.y < 0 && movement.y > velocity.y + 1.0E-5)
                return new FallImpact(tick, distance, box);
            velocity =
                    new Vec3(
                            Math.abs(movement.x - velocity.x) > 1.0E-5 ? 0 : velocity.x * .91,
                            (velocity.y - .08) * .98,
                            Math.abs(movement.z - velocity.z) > 1.0E-5 ? 0 : velocity.z * .91);
        }
        return null;
    }

    private static boolean interruptsFall(Player player, AABB box, Vec3 movement) {
        // Sample the swept body, so fast falls cannot skip a one-block water layer.
        int steps = Math.clamp(Mth.ceil(movement.length() * 2), 1, 64);
        for (int step = 1; step <= steps; step++) {
            AABB sample = box.move(movement.scale((double) step / steps)).deflate(1.0E-5);
            for (BlockPos pos :
                    BlockPos.betweenClosed(
                            Mth.floor(sample.minX),
                            Mth.floor(sample.minY),
                            Mth.floor(sample.minZ),
                            Mth.floor(sample.maxX),
                            Mth.floor(sample.maxY),
                            Mth.floor(sample.maxZ))) {
                var state = player.level().getBlockState(pos);
                var fluid = state.getFluidState();
                if ((fluid.is(FluidTags.WATER)
                                && sample.minY < pos.getY() + fluid.getHeight(player.level(), pos))
                        || state.is(Blocks.COBWEB)
                        || state.is(Blocks.POWDER_SNOW)
                        || state.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return true;
            }
        }
        return false;
    }

    /** Resolve the actual support shape, including slabs, fences and edge landings. */
    public static BlockPos supportBlock(Player player, AABB box) {
        BlockPos best = null;
        double overlap = 0;
        var context = CollisionContext.of(player);
        for (int x = Mth.floor(box.minX + 1.0E-5); x <= Mth.floor(box.maxX - 1.0E-5); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-5); z <= Mth.floor(box.maxZ - 1.0E-5); z++) {
                for (int y = Mth.floor(box.minY) - 2; y <= Mth.floor(box.minY); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    for (AABB local :
                            player.level()
                                    .getBlockState(pos)
                                    .getCollisionShape(player.level(), pos, context)
                                    .toAabbs()) {
                        AABB shape = local.move(x, y, z);
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
