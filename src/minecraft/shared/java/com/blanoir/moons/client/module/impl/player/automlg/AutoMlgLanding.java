package com.blanoir.moons.client.module.impl.player.automlg;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Finds the first collision of the whole player, including a sliver over a neighbouring ledge. */
public final class AutoMlgLanding {
    private static final PlacementRaycast RAYS = new PlacementRaycast("nofall");
    private static final double EPSILON = 1.0E-5;

    private AutoMlgLanding() {}

    public static MovingObjectPosition find(Minecraft client, int horizon, boolean solidCheck) {
        return find(client, horizon, solidCheck, true);
    }

    /** Planning can look beyond interaction range; the use path still requires an in-range ray. */
    public static MovingObjectPosition find(
            Minecraft client, int horizon, boolean solidCheck, boolean requireReach) {
        var player = client.thePlayer;
        Vec3 velocity = VecMath.motion(player);
        if (velocity.yCoord >= 0 || player.onGround) return null;
        AxisAlignedBB box = player.getEntityBoundingBox();
        for (int tick = 0; tick < Math.clamp(horizon, 1, 20); tick++) {
            Vec3 moved = LegacyWorld.collide(player, velocity, box, client.theWorld);
            AxisAlignedBB next = LegacyWorld.move(box, moved);
            if (moved.yCoord > velocity.yCoord + EPSILON) {
                // Horizontal movement is resolved after the downward collision. Use the final
                // footprint to ensure the source will still overlap the player when it lands.
                return supportHit(client, next, solidCheck, requireReach);
            }
            box = next;
            velocity =
                    new Vec3(
                            Math.abs(moved.xCoord - velocity.xCoord) > EPSILON
                                    ? 0
                                    : velocity.xCoord * .91,
                            (velocity.yCoord - .08) * .98,
                            Math.abs(moved.zCoord - velocity.zCoord) > EPSILON
                                    ? 0
                                    : velocity.zCoord * .91);
        }
        return null;
    }

    private static MovingObjectPosition supportHit(
            Minecraft client, AxisAlignedBB landing, boolean solidCheck, boolean requireReach) {
        MovingObjectPosition best = null;
        double bestOverlap = -1;
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        var context = client.thePlayer;
        // Include tall shapes (fences/walls), partial blocks and both sides of block boundaries.
        for (int x = Mth.floor(landing.minX + EPSILON);
                x <= Mth.floor(landing.maxX - EPSILON);
                x++) {
            for (int z = Mth.floor(landing.minZ + EPSILON);
                    z <= Mth.floor(landing.maxZ - EPSILON);
                    z++) {
                for (int y = Mth.floor(landing.minY) - 2; y <= Mth.floor(landing.minY); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = client.theWorld.getBlockState(pos);
                    if (solidCheck && !BlockPlacementUtils.solidWithoutMenu(client, pos)) continue;
                    for (AxisAlignedBB local :
                            LegacyWorld.collision(state, client.theWorld, pos, context).toAabbs()) {
                        AxisAlignedBB surface = LegacyWorld.move(local, x, y, z);
                        if (!supports(landing, surface)) continue;
                        double minX = Math.max(surface.minX, landing.minX);
                        double maxX = Math.min(surface.maxX, landing.maxX);
                        double minZ = Math.max(surface.minZ, landing.minZ);
                        double maxZ = Math.min(surface.maxZ, landing.maxZ);
                        double overlap = overlapArea(landing, surface);
                        if (overlap <= 0 || overlap <= bestOverlap) continue;
                        Vec3 point = new Vec3((minX + maxX) * .5, surface.maxY, (minZ + maxZ) * .5);
                        if (requireReach
                                && !BlockPlacementUtils.withinReach(
                                        eye,
                                        point,
                                        Minecraft.getMinecraft()
                                                .playerController
                                                .getBlockReachDistance())) continue;
                        MovingObjectPosition hit =
                                RAYS.visibleFaceHit(
                                        client, eye, pos, EnumFacing.UP, point, EPSILON);
                        if (hit == null) continue;
                        best = hit;
                        bestOverlap = overlap;
                    }
                }
            }
        }
        return best;
    }

    /** Strict overlap, rather than a centre-cell test; exact edge contact does not support a body. */
    public static double overlapArea(AxisAlignedBB body, AxisAlignedBB surface) {
        return Math.max(0, Math.min(body.maxX, surface.maxX) - Math.max(body.minX, surface.minX))
                * Math.max(
                        0, Math.min(body.maxZ, surface.maxZ) - Math.max(body.minZ, surface.minZ));
    }

    public static boolean supports(AxisAlignedBB body, AxisAlignedBB surface) {
        return Math.abs(surface.maxY - body.minY) <= EPSILON && overlapArea(body, surface) > 0;
    }
}
