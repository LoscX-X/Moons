package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import com.blanoir.tempos.client.access.CushionAccess;
import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionPlacement.Plan;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Camera-directed search; all actual placement and mount checks remain native. */
final class CusionRunPlacement {
    record Course(Vec3 origin, Vec3 eye, Vec3 look) {
        double progress(Vec3 point) {
            double length = look.horizontalDistance();
            if (length < 1.0E-5) return Double.NEGATIVE_INFINITY;
            Vec3 offset = point.subtract(origin);
            double forward = (offset.x * look.x + offset.z * look.z) / length;
            double sideways = Math.abs(offset.x * look.z - offset.z * look.x) / length;
            // A block-wide corridor follows the camera even between grid centers.
            if (forward < .75 || sideways > .75 || point.subtract(eye).dot(look) <= 0)
                return Double.NEGATIVE_INFINITY;
            return forward;
        }
    }

    private CusionRunPlacement() {}

    static Course course(Minecraft client) {
        var camera = MinecraftClientAccess.camera(client);
        return new Course(
                client.player.position(),
                client.player.getEyePosition(),
                Vec3.directionFromRotation(camera.xRot(), camera.yRot()));
    }

    static Plan farthest(Course course, List<Plan> candidates, Predicate<Plan> valid) {
        return candidates.stream()
                .filter(plan -> Double.isFinite(course.progress(plan.position())))
                .sorted(
                        (first, second) -> {
                            int progress =
                                    Double.compare(
                                            course.progress(second.position()),
                                            course.progress(first.position()));
                            if (progress != 0) return progress;
                            double a =
                                    first.position()
                                            .subtract(course.eye())
                                            .normalize()
                                            .dot(course.look());
                            double b =
                                    second.position()
                                            .subtract(course.eye())
                                            .normalize()
                                            .dot(course.look());
                            return Double.compare(b, a);
                        })
                .filter(valid)
                .findFirst()
                .orElse(null);
    }

    static Plan find(Minecraft client) {
        return CushionPlacement.preferExisting(() -> existing(client), () -> place(client));
    }

    static Plan existing(Minecraft client) {
        Course course = course(client);
        List<Plan> candidates = new ArrayList<>();
        for (Entity seat :
                client.level.getEntities(
                        client.player,
                        client.player
                                .getBoundingBox()
                                .inflate(client.player.entityInteractionRange()),
                        CushionAccess::isCushion)) candidates.add(Plan.existing(seat));
        return farthest(
                course,
                candidates,
                plan -> reusable(client, client.level.getEntity(plan.entityId())));
    }

    static boolean reusable(Minecraft client, Entity seat) {
        return seat != null
                && CushionPlacement.seatUsable(client, seat)
                && Double.isFinite(course(client).progress(seat.position()))
                && CushionPlacement.safeSpace(client, seat.position(), seat.getBoundingBox());
    }

    private static Plan place(Minecraft client) {
        int slot = CushionPlacement.findSlot(client);
        if (slot < 0) return null;
        var stack = client.player.getInventory().getItem(slot);
        Course course = course(client);
        double reach =
                Math.min(
                        client.player.blockInteractionRange(),
                        client.player.entityInteractionRange());
        int radius = Mth.ceil(reach);
        BlockPos center = BlockPos.containing(course.eye());
        List<Plan> candidates = new ArrayList<>();
        for (int x = -radius; x <= radius; x++)
            for (int z = -radius; z <= radius; z++)
                for (int y = -radius; y <= radius; y++) {
                    BlockPos support = center.offset(x, y, z);
                    if (!client.level.hasChunkAt(support)
                            || !BlockPlacementUtils.solidWithoutMenu(client, support)) continue;
                    Vec3 point =
                            BlockPlacementUtils.facePoint(client, support, Direction.UP, .5, .5);
                    if (!Double.isFinite(course.progress(point))) continue;
                    var hit =
                            BlockPlacementUtils.visibleFaceHit(
                                    client, course.eye(), support, Direction.UP, point, 1.0E-4);
                    if (hit != null)
                        candidates.add(
                                new Plan(
                                        hit,
                                        CushionAccess.placementPosition(client, stack, hit),
                                        slot));
                }
        return farthest(
                course, candidates, plan -> CushionPlacement.validPlan(client, plan.hit(), stack));
    }
}
