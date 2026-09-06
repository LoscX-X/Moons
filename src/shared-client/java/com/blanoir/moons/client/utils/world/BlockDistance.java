package com.blanoir.moons.client.utils.world;

import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Block interaction distances measured from the local player's eyes to the
 * nearest point of the full block box, reusing the same box semantics as
 * {@link EntityDistance}.
 */
public final class BlockDistance {
    private BlockDistance() {
    }

    public static double toBlock(Minecraft client, BlockPos pos) {
        return Math.sqrt(squaredToBlock(client, pos));
    }

    public static double squaredToBlock(Minecraft client, BlockPos pos) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return squaredToBlock(currentPlayer.getEyePosition(), pos);
    }

    public static double squaredToBlock(Vec3 point, BlockPos pos) {
        if (point == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return EntityDistance.squaredToBox(point, new AABB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D));
    }
}
