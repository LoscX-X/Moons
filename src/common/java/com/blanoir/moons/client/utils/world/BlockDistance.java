package com.blanoir.moons.client.utils.world;

import com.blanoir.moons.client.utils.entity.EntityDistance;

import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * Block interaction distances measured from the local player's eyes to the
 * nearest point of the full block box, reusing the same box semantics as
 * {@link EntityDistance}.
 */
public final class BlockDistance {
    private BlockDistance() {}

    /** Vertical collider ray from the player's feet; fluids do not count as ground. */
    public static double toGround(Minecraft client, double maxDistance) {
        Vec3 start =
                new Vec3(
                        client.thePlayer.posX,
                        client.thePlayer.getEntityBoundingBox().minY,
                        client.thePlayer.posZ);
        MovingObjectPosition hit =
                LegacyWorld.clip(
                        client.theWorld,
                        new LegacyRay(
                                start,
                                start.addVector(0.0D, -maxDistance, 0.0D),
                                LegacyRay.Block.COLLIDER,
                                LegacyRay.Fluid.NONE,
                                client.thePlayer));
        return hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS
                ? Double.POSITIVE_INFINITY
                : start.yCoord - hit.hitVec.yCoord;
    }

    public static double toBlock(Minecraft client, BlockPos pos) {
        return Math.sqrt(squaredToBlock(client, pos));
    }

    public static double squaredToBlock(Minecraft client, BlockPos pos) {
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null || currentPlayer == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return squaredToBlock(currentPlayer.getPositionEyes(1.0F), pos);
    }

    public static double squaredToBlock(Vec3 point, BlockPos pos) {
        if (point == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return EntityDistance.squaredToBox(
                point,
                LegacyWorld.box(
                        pos.getX(),
                        pos.getY(),
                        pos.getZ(),
                        pos.getX() + 1.0D,
                        pos.getY() + 1.0D,
                        pos.getZ() + 1.0D));
    }
}
