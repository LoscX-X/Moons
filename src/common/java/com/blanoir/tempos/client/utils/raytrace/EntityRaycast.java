package com.blanoir.tempos.client.utils.raytrace;

import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.entity.EntityDistance;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/** Entity intersections along a view ray. */
public final class EntityRaycast {
    private EntityRaycast() {}

    public enum EntityRayState {
        HIT,
        AIM,
        BLOCKED,
        RANGE
    }

    /** Direction-based entity query; filtering policy is supplied by the caller. */
    public static Entity findEntityOnRay(
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
                || look.lengthSqr() <= 1.0E-9D
                || !Double.isFinite(range)
                || range <= 0.0D) {
            return null;
        }
        Vec3 end = start.add(look.normalize().scale(range));
        EntityHitResult hit = findEntity(client, start, end, range, throughBlocks, predicate);
        return hit == null ? null : hit.getEntity();
    }

    public static Entity findEntityOnViewRay(
            Minecraft client, Predicate<Entity> predicate, boolean throughBlocks) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) {
            return null;
        }
        double range = CombatReach.vanillaEntityInteractionRange(currentPlayer);
        Vec3 start = currentPlayer.getEyePosition();
        return findEntityOnRay(
                client, start, currentPlayer.getViewVector(1.0F), range, predicate, throughBlocks);
    }

    public static EntityHitResult findEntity(
            Minecraft client,
            Vec3 start,
            Vec3 end,
            double maxDistance,
            boolean throughBlocks,
            Predicate<Entity> predicate) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null
                || client.level == null
                || currentPlayer == null
                || start == null
                || end == null
                || predicate == null
                || !Double.isFinite(maxDistance)
                || maxDistance <= 0.0D) {
            return null;
        }

        Vec3 rayEnd = end;
        double rayDistance = maxDistance;
        if (!throughBlocks) {
            HitResult blockHit = BlockRaycast.clipBlocks(client, start, end);
            if (blockHit.getType() != HitResult.Type.MISS) {
                rayEnd = blockHit.getLocation();
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
            Optional<Vec3> cobwebHit = BlockRaycast.firstCobwebHit(client, start, rayEnd);
            if (cobwebHit.isPresent()) {
                rayEnd = cobwebHit.get();
                rayDistance = Math.min(rayDistance, start.distanceTo(rayEnd));
            }
        }

        AABB searchBox = new AABB(start, start).expandTowards(rayEnd.subtract(start)).inflate(1.0D);
        return ProjectileUtil.getEntityHitResult(
                currentPlayer, start, rayEnd, searchBox, predicate, rayDistance * rayDistance);
    }

    public static EntityRayState traceEntity(
            Minecraft client, Vec3 start, Vec3 look, double reach, Entity target) {
        return traceEntity(client, start, look, reach, target, false);
    }

    public static EntityRayState traceEntity(
            Minecraft client,
            Vec3 start,
            Vec3 look,
            double reach,
            Entity target,
            boolean throughBlocks) {
        if (client == null
                || client.player == null
                || client.level == null
                || start == null
                || look == null
                || target == null
                || !Double.isFinite(reach)
                || reach <= 0.0D
                || EntityDistance.squaredToBox(start, target.getBoundingBox()) > reach * reach) {
            return EntityRayState.RANGE;
        }

        Vec3 end = start.add(look.scale(reach));
        AABB box = target.getBoundingBox();
        Optional<Vec3> entityHit = box.clip(start, end);
        if (!box.contains(start) && entityHit.isEmpty()) {
            return EntityRayState.AIM;
        }

        // Ignoring cover never bypasses the range and ray/hitbox intersection checks above.
        if (throughBlocks) return EntityRayState.HIT;

        Optional<Vec3> cobwebHit = BlockRaycast.firstCobwebHit(client, start, end);
        if (cobwebHit.isPresent()
                && entityHit.isPresent()
                && start.distanceToSqr(cobwebHit.get()) + 1.0E-7D
                        < start.distanceToSqr(entityHit.get())) {
            return EntityRayState.BLOCKED;
        }

        HitResult blockHit = BlockRaycast.clipBlocks(client, start, end);
        if (blockHit.getType() == HitResult.Type.MISS) {
            return EntityRayState.HIT;
        }
        boolean unobstructed =
                box.contains(start)
                        || entityHit.isPresent()
                                && start.distanceToSqr(blockHit.getLocation())
                                        >= start.distanceToSqr(entityHit.get());
        return unobstructed ? EntityRayState.HIT : EntityRayState.BLOCKED;
    }
}
