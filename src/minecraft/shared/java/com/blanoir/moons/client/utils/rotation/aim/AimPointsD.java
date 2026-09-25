package com.blanoir.moons.client.utils.rotation.aim;

import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatGeometry;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * D: SilentAura tracking-point composition, shared by Latest/Legacy Lock, Balance and FullLock.
 * FullLock uses the center corridor; Lock retains a valid ray; other paths use A/B anchors
 * with C visibility fallback. Candidate evaluation uses visibleAimPoint, which consumes no
 * random values. trackingAimPoint advances caller-owned anchors in the original branch order.
 * Re-resolve against the live box each frame; null means no usable visible fallback.
 */
public final class AimPointsD {
    private AimPointsD() {}

    private static final double UPPER_BODY_ANCHOR = 0.72D;

    public record Parameters(
            boolean lockMode,
            boolean fullLockMode,
            boolean closestAimPoint,
            double aimWander,
            int aimWanderTicks,
            boolean throughBlocks,
            double aimRange,
            double scanExtra) {}

    /** The mode owns both independent anchor histories; only tracking resolution can advance them. */
    public record Context(
            Parameters parameters, AimPointsA.State center, AimPointsB.State closest) {}

    public static Vec3 trackingAimPoint(
            Context context, Minecraft client, LivingEntity target, Vec3 look) {
        Vec3 eye = client.player.getEyePosition();
        AABB box = CombatGeometry.box(client, target);
        double trackingRange =
                inAttackRange(context, client, target)
                        ? attackRange(context, client)
                        : scanRange(context, client);

        if (context.parameters().fullLockMode()) {
            Vec3 preferred = fullLockAimPoint(context, eye, box);
            double attackRange = attackRange(context, client);
            boolean attackReach =
                    attackRange > 0.0D
                            && CombatGeometry.distanceSquared(client, target)
                                    <= attackRange * attackRange;
            double activeRange = attackReach ? attackRange : trackingRange;
            if (eye.distanceToSqr(preferred) <= activeRange * activeRange
                    && RaytraceUtils.canRayTraceTo(
                            client, eye, preferred, context.parameters().throughBlocks())) {
                return preferred;
            }
            // FULL-Lock uses the centre corridor until it is covered. Re-scan the
            // complete hitbox in the same frame and choose an actually visible
            // surface point; while in attack reach, reject scan-only points.
            return visibleAimPoint(context, client, target, look, activeRange);
        }

        // Lock prioritises ray uptime.  Center/Closest still owns the vertical
        // policy, but when the confirmed packet ray misses we steer toward the
        // visible hitbox point with the smallest angular correction.  When it
        // already hits, retain that ray slightly inside the box instead of
        // allowing wander to pull the next packet back out.  This is strictly
        // target-point selection: packet acceleration/GCD and the final HIT
        // gate remain unchanged.
        Vec3 lockPoint =
                context.parameters().lockMode()
                        ? lockRayRetentionPoint(
                                context, client, target, eye, box, look, trackingRange)
                        : null;
        if (lockPoint != null) return lockPoint;

        Vec3 preferred;
        if (context.parameters().closestAimPoint()) {
            preferred =
                    AimPointsB.resolve(
                            context.closest(),
                            client,
                            target,
                            eye,
                            box,
                            scanRange(context, client),
                            context.parameters().aimWanderTicks(),
                            context.parameters().throughBlocks());
        } else {
            preferred =
                    AimPointsA.resolve(
                            context.center(),
                            client,
                            target,
                            eye,
                            box,
                            trackingRange,
                            context.parameters().aimWander(),
                            context.parameters().aimWanderTicks(),
                            !context.parameters().lockMode(),
                            context.parameters().throughBlocks());
        }
        if (eye.distanceToSqr(preferred) <= trackingRange * trackingRange
                && RaytraceUtils.canRayTraceTo(
                        client, eye, preferred, context.parameters().throughBlocks())) {
            return preferred;
        }
        // The preferred upper-body point can be covered while a leg remains
        // exposed. Search the complete visible surface and merely prefer, not
        // require, the configured upper-body height.
        return visibleAimPoint(context, client, target, look, trackingRange);
    }

    public static Vec3 visibleAimPoint(
            Context context, Minecraft client, LivingEntity target, Vec3 look, double range) {
        if (!ClientReady.world(client) || target == null || range <= 0.0D) return null;
        AABB aimBox = CombatGeometry.box(client, target);
        return AimPointsC.findBestVisibleSurfacePoint(
                client,
                aimBox,
                look,
                range,
                UPPER_BODY_ANCHOR,
                context.parameters().lockMode(),
                context.parameters().throughBlocks());
    }

    public static Vec3 lockRayRetentionPoint(
            Context context,
            Minecraft client,
            LivingEntity target,
            Vec3 eye,
            AABB box,
            Vec3 look,
            double trackingRange) {
        if (look == null || look.lengthSqr() <= 1.0E-9D || box.contains(eye)) return null;
        double attackRange = attackRange(context, client);
        if (attackRange <= 0.0D
                || CombatGeometry.distanceSquared(client, target) > attackRange * attackRange) {
            return null;
        }

        Vec3 direction = look.normalize();
        RaytraceUtils.EntityRayState state =
                CombatGeometry.traceEntity(
                        client,
                        eye,
                        direction,
                        attackRange,
                        target,
                        context.parameters().throughBlocks());
        Vec3 policyPoint =
                context.parameters().closestAimPoint()
                        ? AimPointsB.resolve(
                                context.closest(),
                                client,
                                target,
                                eye,
                                box,
                                scanRange(context, client),
                                context.parameters().aimWanderTicks(),
                                context.parameters().throughBlocks())
                        : AimPointsA.centerTrackingPoint(eye, box);

        if (state == RaytraceUtils.EntityRayState.HIT) {
            var intersection = box.clip(eye, eye.add(direction.scale(attackRange)));
            if (intersection.isEmpty()) return null;
            Vec3 retained = intersection.get().lerp(policyPoint, 0.20D);
            return RaytraceUtils.canRayTraceTo(
                            client, eye, retained, context.parameters().throughBlocks())
                    ? retained
                    : intersection.get();
        }
        if (state != RaytraceUtils.EntityRayState.AIM) return null;

        Vec3 angularRecovery =
                visibleAimPoint(
                        context, client, target, direction, Math.min(trackingRange, attackRange));
        if (angularRecovery == null) return null;
        // Keep Center's eye-height Y (or Closest's configured Y) while taking
        // only the angularly-nearest surface X/Z from the recovery sampler.
        Vec3 policyRecovery = new Vec3(angularRecovery.x, policyPoint.y, angularRecovery.z);
        return eye.distanceToSqr(policyRecovery) <= attackRange * attackRange
                        && RaytraceUtils.canRayTraceTo(
                                client, eye, policyRecovery, context.parameters().throughBlocks())
                ? policyRecovery
                : angularRecovery;
    }

    public static Vec3 fullLockAimPoint(Context context, Vec3 eye, AABB box) {
        double height = box.maxY - box.minY;
        double minimumY = box.minY + height * 0.05D;
        double maximumY = box.minY + height * 0.75D;
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                Mth.clamp(eye.y, minimumY, maximumY),
                (box.minZ + box.maxZ) * 0.5D);
    }

    private static boolean inAttackRange(Context context, Minecraft client, LivingEntity target) {
        if (!ClientReady.world(client) || target == null) return false;
        double range = attackRange(context, client);
        return range > 0.0D && CombatGeometry.distanceSquared(client, target) <= range * range;
    }

    private static double attackRange(Context context, Minecraft client) {
        return CombatReach.entityInteractionRange(client, context.parameters().aimRange());
    }

    private static double scanRange(Context context, Minecraft client) {
        return CombatReach.scanRange(
                client, context.parameters().aimRange(), context.parameters().scanExtra());
    }
}
