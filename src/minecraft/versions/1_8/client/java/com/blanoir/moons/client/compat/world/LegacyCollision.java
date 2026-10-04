package com.blanoir.moons.client.compat.world;

import net.minecraft.entity.Entity;
import net.minecraft.util.*;
import net.minecraft.world.World;

/** Vanilla 1.8 axis clipping (Y, X, Z), without mutating the entity. */
public final class LegacyCollision {
    private LegacyCollision() {}

    public static Vec3 resolve(Entity entity, Vec3 delta, AxisAlignedBB box, World world) {
        var collisions =
                world.getCollidingBoundingBoxes(
                        entity, box.addCoord(delta.xCoord, delta.yCoord, delta.zCoord));
        double x = delta.xCoord, y = delta.yCoord, z = delta.zCoord;
        for (AxisAlignedBB obstacle : collisions) y = obstacle.calculateYOffset(box, y);
        box = box.offset(0, y, 0);
        for (AxisAlignedBB obstacle : collisions) x = obstacle.calculateXOffset(box, x);
        box = box.offset(x, 0, 0);
        for (AxisAlignedBB obstacle : collisions) z = obstacle.calculateZOffset(box, z);
        return new Vec3(x, y, z);
    }
}
