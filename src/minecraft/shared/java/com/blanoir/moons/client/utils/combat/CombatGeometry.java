package com.blanoir.moons.client.utils.combat;

import com.blanoir.moons.client.module.impl.combat.Misplace;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Shared aiming/attack geometry. Backtrack already updates the live entity by delayed replay. */
public final class CombatGeometry {
    public record Shape(AABB box, Vec3 offset) {
        public Vec3 original(Vec3 point) {
            return point.subtract(offset);
        }

        public boolean shifted() {
            return offset.lengthSqr() > 1.0E-9;
        }
    }

    private CombatGeometry() {}

    public static Shape shape(Minecraft client, Entity entity) {
        return Misplace.attackShape(client, entity);
    }

    public static AABB box(Minecraft client, Entity entity) {
        return shape(client, entity).box();
    }

    public static double distanceSquared(Minecraft client, Entity entity) {
        return client == null || client.player == null || entity == null
                ? Double.MAX_VALUE
                : box(client, entity).distanceToSqr(client.player.getEyePosition());
    }

    public static boolean outsideVanillaRange(Minecraft client, Entity entity) {
        if (client == null || client.player == null || entity == null) return true;
        double range = CombatReach.vanillaEntityInteractionRange(client.player);
        return distanceSquared(client, entity) > range * range;
    }

    // Offset <= 1.5 plus up to 3 blocks of non-teleport interpolation movement.
    public static double searchPadding() {
        return Misplace.isEnabled() ? 5.5 : 1;
    }

    public static Entity findTargetOnRay(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double range,
            Predicate<Entity> predicate,
            boolean throughBlocks) {
        if (client == null
                || client.player == null
                || client.level == null
                || start == null
                || look == null
                || look.lengthSqr() < 1.0E-9
                || !Double.isFinite(range)
                || range <= 0) return null;
        Vec3 end = start.add(look.normalize().scale(range));
        Entity best = null;
        double nearest = range * range;
        for (Entity entity :
                client.level.getEntities(
                        client.player, new AABB(start, end).inflate(searchPadding()), predicate)) {
            if (entity.getRootVehicle() == client.player.getRootVehicle()) continue;
            Shape shape = shape(client, entity);
            Vec3 hit = contact(shape.box(), start, end);
            if (hit == null
                    || start.distanceToSqr(hit) > nearest
                    || !visible(client, shape, start, hit, throughBlocks)) continue;
            nearest = start.distanceToSqr(hit);
            best = entity;
        }
        return best;
    }

    public static RaytraceUtils.EntityRayState traceEntity(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double range,
            Entity target,
            boolean throughBlocks) {
        if (client == null
                || client.player == null
                || client.level == null
                || target == null
                || start == null
                || look == null
                || look.lengthSqr() < 1.0E-9
                || !Double.isFinite(range)
                || range <= 0) return RaytraceUtils.EntityRayState.RANGE;
        Shape shape = shape(client, target);
        return traceShape(
                shape,
                start,
                look,
                range,
                hit -> visible(client, shape, start, hit, throughBlocks));
    }

    public static RaytraceUtils.EntityRayState traceShape(
            Shape shape, Vec3 start, Vec3 look, double range, Predicate<Vec3> visible) {
        if (shape.box().distanceToSqr(start) > range * range)
            return RaytraceUtils.EntityRayState.RANGE;
        Vec3 hit = contact(shape.box(), start, start.add(look.normalize().scale(range)));
        if (hit == null) return RaytraceUtils.EntityRayState.AIM;
        return visible.test(hit)
                ? RaytraceUtils.EntityRayState.HIT
                : RaytraceUtils.EntityRayState.BLOCKED;
    }

    public static boolean visible(
            Minecraft client, Shape shape, Vec3 eye, Vec3 hit, boolean throughBlocks) {
        return RaytraceUtils.canRayTraceTo(client, eye, hit, throughBlocks)
                && (!shape.shifted()
                        || RaytraceUtils.canRayTraceTo(
                                client, eye, shape.original(hit), throughBlocks));
    }

    public static Vec3 contact(AABB box, Vec3 start, Vec3 end) {
        return box.contains(start) ? start : box.clip(start, end).orElse(null);
    }

    public static EntityHitResult attackHit(
            Minecraft client, Entity target, Vec3 eye, Vec3 look, double range) {
        Vec3 contact = contact(box(client, target), eye, eye.add(look.normalize().scale(range)));
        return contact == null ? null : new EntityHitResult(target, contact);
    }
}
