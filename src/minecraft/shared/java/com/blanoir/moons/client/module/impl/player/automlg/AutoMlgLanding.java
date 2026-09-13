package com.blanoir.moons.client.module.impl.player.automlg;

import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;

/** Finds the first collision of the whole player, including a sliver over a neighbouring ledge. */
public final class AutoMlgLanding {
    private static final double EPSILON = 1.0E-5;

    private AutoMlgLanding() {}

    public static BlockHitResult find(Minecraft client, int horizon, boolean solidCheck) {
        return find(client, horizon, solidCheck, true);
    }

    /** Planning can look beyond interaction range; the use path still requires an in-range ray. */
    public static BlockHitResult find(
            Minecraft client, int horizon, boolean solidCheck, boolean requireReach) {
        var player = client.player;
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.y >= 0 || player.onGround()) return null;
        AABB box = player.getBoundingBox();
        for (int tick = 0; tick < Math.clamp(horizon, 1, 20); tick++) {
            Vec3 moved = Entity.collideBoundingBox(player, velocity, box, client.level, List.of());
            AABB next = box.move(moved);
            if (moved.y > velocity.y + EPSILON) {
                // Horizontal movement is resolved after the downward collision. Use the final
                // footprint to ensure the source will still overlap the player when it lands.
                return supportHit(client, next, solidCheck, requireReach);
            }
            box = next;
            velocity =
                    new Vec3(
                            Math.abs(moved.x - velocity.x) > EPSILON ? 0 : velocity.x * .91,
                            (velocity.y - .08) * .98,
                            Math.abs(moved.z - velocity.z) > EPSILON ? 0 : velocity.z * .91);
        }
        return null;
    }

    private static BlockHitResult supportHit(
            Minecraft client, AABB landing, boolean solidCheck, boolean requireReach) {
        BlockHitResult best = null;
        double bestOverlap = -1;
        Vec3 eye = client.player.getEyePosition();
        var context = CollisionContext.of(client.player);
        // Include tall shapes (fences/walls), partial blocks and both sides of block boundaries.
        for (int x = Mth.floor(landing.minX + EPSILON);
                x <= Mth.floor(landing.maxX - EPSILON);
                x++) {
            for (int z = Mth.floor(landing.minZ + EPSILON);
                    z <= Mth.floor(landing.maxZ - EPSILON);
                    z++) {
                for (int y = Mth.floor(landing.minY) - 2; y <= Mth.floor(landing.minY); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = client.level.getBlockState(pos);
                    if (solidCheck && !BlockPlacementUtils.solidWithoutMenu(client, pos)) continue;
                    for (AABB local :
                            state.getCollisionShape(client.level, pos, context).toAabbs()) {
                        AABB surface = local.move(x, y, z);
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
                                        eye, point, client.player.blockInteractionRange()))
                            continue;
                        BlockHitResult hit =
                                BlockPlacementUtils.visibleFaceHit(
                                        client, eye, pos, Direction.UP, point, EPSILON);
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
    public static double overlapArea(AABB body, AABB surface) {
        return Math.max(0, Math.min(body.maxX, surface.maxX) - Math.max(body.minX, surface.minX))
                * Math.max(
                        0, Math.min(body.maxZ, surface.maxZ) - Math.max(body.minZ, surface.minZ));
    }

    public static boolean supports(AABB body, AABB surface) {
        return Math.abs(surface.maxY - body.minY) <= EPSILON && overlapArea(body, surface) > 0;
    }
}
