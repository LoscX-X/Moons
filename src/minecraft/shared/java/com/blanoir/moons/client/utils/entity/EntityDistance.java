package com.blanoir.moons.client.utils.entity;

import com.blanoir.moons.client.utils.math.MathUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared interaction-distance calculations. Entity and block distances are
 * measured from the local player's eyes to the nearest point of the target's
 * bounding box, matching reach checks better than center-to-center distance.
 */
public final class EntityDistance {
    private EntityDistance() {}

    public static double squaredToEntity(Minecraft client, Entity target) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || target == null) {
            return Double.MAX_VALUE;
        }
        return squaredToBox(currentPlayer.getEyePosition(), target.getBoundingBox());
    }

    public static double squaredToBox(Vec3 point, AABB box) {
        if (point == null || box == null) {
            return Double.MAX_VALUE;
        }
        return MathUtils.squaredDistanceToBox(point, box);
    }

    public static Vec3 closestPoint(Vec3 point, AABB box) {
        return MathUtils.closestPoint(point, box);
    }
}
