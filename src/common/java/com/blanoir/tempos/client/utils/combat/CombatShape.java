package com.blanoir.tempos.client.utils.combat;

import com.blanoir.tempos.client.utils.raytrace.EntityRaycast;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** The visible box and its offset from the original position. */
public record CombatShape(AABB box, Vec3 offset) {
    public Vec3 original(Vec3 point) {
        return point.subtract(offset);
    }

    public boolean shifted() {
        return offset.lengthSqr() > 1.0E-9;
    }

    public static EntityRaycast.EntityRayState traceShape(
            CombatShape shape, Vec3 start, Vec3 look, double range, Predicate<Vec3> visible) {
        if (shape.box().distanceToSqr(start) > range * range)
            return EntityRaycast.EntityRayState.RANGE;
        Vec3 hit = contact(shape.box(), start, start.add(look.normalize().scale(range)));
        if (hit == null) return EntityRaycast.EntityRayState.AIM;
        return visible.test(hit)
                ? EntityRaycast.EntityRayState.HIT
                : EntityRaycast.EntityRayState.BLOCKED;
    }

    public static boolean visible(
            Minecraft client, CombatShape shape, Vec3 eye, Vec3 hit, boolean throughBlocks) {
        return LineOfSight.canRayTraceTo(client, eye, hit, throughBlocks)
                && (!shape.shifted()
                        || LineOfSight.canRayTraceTo(
                                client, eye, shape.original(hit), throughBlocks));
    }

    public static Vec3 contact(AABB box, Vec3 start, Vec3 end) {
        return box.contains(start) ? start : box.clip(start, end).orElse(null);
    }
}
