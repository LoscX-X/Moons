package com.blanoir.tempos.client.manager.combat;

import com.blanoir.tempos.client.module.impl.combat.Misplace;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.combat.CombatShape;
import com.blanoir.tempos.client.utils.raytrace.EntityRaycast;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Shared aiming/attack geometry. Backtrack already updates the live entity by delayed replay. */
public final class CombatGeometry {
    private static int originalPositionsDepth;

    private CombatGeometry() {}

    /** Keeps movement planning on authoritative boxes without changing attack geometry. */
    public static void withOriginalPositions(Runnable attack) {
        originalPositionsDepth++;
        try {
            attack.run();
        } finally {
            originalPositionsDepth--;
        }
    }

    public static CombatShape shape(Minecraft client, Entity entity) {
        if (originalPositionsDepth > 0) return new CombatShape(entity.getBoundingBox(), Vec3.ZERO);
        return Misplace.INSTANCE.attackShape(client, entity);
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
        return originalPositionsDepth == 0 && Misplace.INSTANCE.isEnabled() ? 5.5 : 1;
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
            CombatShape shape = shape(client, entity);
            Vec3 hit = CombatShape.contact(shape.box(), start, end);
            if (hit == null
                    || start.distanceToSqr(hit) > nearest
                    || !CombatShape.visible(client, shape, start, hit, throughBlocks)) continue;
            nearest = start.distanceToSqr(hit);
            best = entity;
        }
        return best;
    }

    public static EntityRaycast.EntityRayState traceEntity(
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
                || range <= 0) return EntityRaycast.EntityRayState.RANGE;
        CombatShape shape = shape(client, target);
        return CombatShape.traceShape(
                shape,
                start,
                look,
                range,
                hit -> CombatShape.visible(client, shape, start, hit, throughBlocks));
    }

    public static EntityHitResult attackHit(
            Minecraft client, Entity target, Vec3 eye, Vec3 look, double range) {
        Vec3 contact =
                CombatShape.contact(
                        box(client, target), eye, eye.add(look.normalize().scale(range)));
        return contact == null ? null : new EntityHitResult(target, contact);
    }
}
