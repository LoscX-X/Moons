package com.blanoir.tempos.client.module.impl.world.scaffold;

import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Body;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Direction;
import com.blanoir.tempos.client.module.impl.movement.strafe.MovementSimulation;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.BlockAim;
import com.blanoir.tempos.client.utils.rotation.aim.BlockTarget;
import com.blanoir.tempos.client.utils.rotation.quantize.MouseAngleQuantizer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.HashSet;
import java.util.Set;

public final class GodBridgePathVerification {
    public static void verify() {
        // Feet still overlap the old corner while edge sneak can keep this pose across ticks.
        AABB feet = new AABB(.73, 1, .73, 1.33, 2.8, 1.33);
        Vec3 velocity = new Vec3(.15, 0, 0), eye = new Vec3(1.03, 2.62, 1.03);
        Set<BlockPos> blocks = new HashSet<>(Set.of(BlockPos.ZERO));
        var path = new GodBridgePath();
        var cells = path.cells(feet, velocity, feet.move(.221, 0, 0), 0, blocks::contains);
        BlockAim first =
                GodBridgeTargetSelector.select(
                        cells, blocks::contains, (cell, recovery) -> aim(blocks, cell, eye));
        require(first != null, "The corner pose has a real reachable first support face");
        blocks.add(first.target().placePos());
        path.placed(first.target().placePos(), feet.getCenter());
        cells = path.cells(feet, velocity, feet.move(.221, 0, 0), 0, blocks::contains);
        BlockAim second =
                GodBridgeTargetSelector.select(
                        cells, blocks::contains, (cell, recovery) -> aim(blocks, cell, eye));
        System.out.println(
                "GODBRIDGE_CORNER first="
                        + first.target().placePos()
                        + " second="
                        + (second == null ? "none" : second.target().placePos()));
        require(
                second == null,
                "Do not fill another footprint cell while the player stays in place");
        for (int update = 0; update < 200; update++)
            require(
                    path.cells(feet, velocity, feet.move(.221, 0, 0), 0, blocks::contains)
                            .isEmpty(),
                    "More frames or ticks at the same standing position do not unlock a second block");
        AABB moved = feet.move(.02, 0, 0);
        require(
                !path.cells(moved, velocity, moved.move(.221, 0, 0), 0, blocks::contains).isEmpty(),
                "Actual movement permits the next support even when still outside the first block center");
        path.placed(first.target().placePos(), feet.getCenter());
        blocks.remove(first.target().placePos());
        require(
                !path.cells(feet, velocity, feet.move(.221, 0, 0), 0, blocks::contains).isEmpty(),
                "A rejected or removed block does not prevent retrying placement");
        for (int quarter = 0; quarter < 4; quarter++) {
            bridge(quarter, false);
            bridge(quarter, true);
        }
    }

    private static void bridge(int quarter, boolean diagonal) {
        var path = new GodBridgePath();
        Set<BlockPos> blocks = new HashSet<>(Set.of(BlockPos.ZERO));
        Vec3 start = rotate(new Vec3(1.03, 1, 1.03), quarter);
        float yaw = (diagonal ? -45 : -90) + quarter * 90;
        Body player =
                new Body(
                        new AABB(start.x - .3, 1, start.z - .3, start.x + .3, 2.8, start.z + .3),
                        start.add(0, 1.62, 0),
                        Vec3.ZERO,
                        true);
        var physics =
                new MovementSimulation.Parameters(
                        .1, .02, .546, .91, .98, .08, .42, 1, -1, false, false);
        Input requested = new Input(true, false, false, false, false, false, false);
        Vec3 advance = new Direction(1, 0).world(yaw).scale(.221);
        Vec3 lastPlacement = null;
        for (int tick = 0; tick < 180; tick++) {
            // GodBridge only chooses support cells; the player's requested direction owns motion.
            Input corrected = requested;
            var candidates =
                    path.cells(
                            player.box(),
                            player.velocity(),
                            player.box().move(advance),
                            0,
                            blocks::contains);
            Vec3 eye = player.eye();
            BlockAim chosen =
                    GodBridgeTargetSelector.select(
                            candidates,
                            blocks::contains,
                            (cell, recovery) -> aim(blocks, cell, eye));
            if (chosen != null) {
                if (lastPlacement != null)
                    require(
                            eye.distanceTo(lastPlacement) > .01,
                            "Each new block follows real movement, not footprint filling");
                blocks.add(chosen.target().placePos());
                path.placed(chosen.target().placePos(), player.box().getCenter());
                lastPlacement = eye;
            }
            Direction input =
                    new Direction(
                            (corrected.forward() ? 1 : 0) - (corrected.backward() ? 1 : 0),
                            (corrected.left() ? 1 : 0) - (corrected.right() ? 1 : 0));
            player =
                    MovementSimulation.step(
                            player,
                            input,
                            yaw,
                            false,
                            physics,
                            (local, box, movement) -> collide(blocks, box, movement));
            require(
                    player.grounded() && player.box().minY >= .9999,
                    "Unmodified movement retains real block support: turn="
                            + quarter
                            + " diagonal="
                            + diagonal
                            + " tick="
                            + tick);
        }
        double progress = player.eye().subtract(start.add(0, 1.62, 0)).dot(advance.normalize());
        System.out.println(
                "GODBRIDGE_PATH turn="
                        + quarter
                        + " diagonal="
                        + diagonal
                        + " blocks="
                        + blocks.size()
                        + " progress="
                        + progress);
        require(progress > 10, "Cell selection must not wait for a forced landing correction");
        Vec3 travel = player.eye().subtract(start.add(0, 1.62, 0));
        require(
                Math.abs(travel.x * advance.z - travel.z * advance.x) < 1.0E-6,
                "The bridge follows requested motion without sideways recentering");
        if (!diagonal)
            for (BlockPos block : blocks) {
                Vec3 local = rotate(Vec3.atCenterOf(block), 4 - quarter);
                if (local.x > 2)
                    require(
                            Math.floor(local.z) == 1,
                            "After its initial corner connector, the straight bridge stays one block wide along the player route");
            }
    }

    private static Vec3 rotate(Vec3 point, int quarters) {
        double x = point.x - .5, z = point.z - .5;
        for (int i = 0; i < quarters; i++) {
            double previous = x;
            x = -z;
            z = previous;
        }
        return new Vec3(x + .5, point.y, z + .5);
    }

    private static Vec3 collide(Set<BlockPos> blocks, AABB box, Vec3 movement) {
        var shapes = blocks.stream().map(block -> Shapes.create(new AABB(block))).toList();
        double y = Shapes.collide(net.minecraft.core.Direction.Axis.Y, box, shapes, movement.y);
        box = box.move(0, y, 0);
        double x = Shapes.collide(net.minecraft.core.Direction.Axis.X, box, shapes, movement.x);
        box = box.move(x, 0, 0);
        double z = Shapes.collide(net.minecraft.core.Direction.Axis.Z, box, shapes, movement.z);
        return new Vec3(x, y, z);
    }

    private static BlockAim aim(Set<BlockPos> blocks, BlockPos cell, Vec3 eye) {
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos support = cell.relative(face.getOpposite());
            if (!blocks.contains(support)) continue;
            BlockTarget target = new BlockTarget(support, face);
            var quantizer =
                    new MouseAngleQuantizer.Adapter((base, value) -> value, (base, value) -> value);
            Rotation rotation =
                    GodBridgeFacePoints.turnAim(
                            eye,
                            new Rotation(135, 75.7F),
                            face,
                            (side, height) -> facePoint(support, face, side, height),
                            quantizer,
                            value -> hit(blocks, target, eye, value) != null);
            if (rotation != null)
                return new BlockAim(target, rotation, hit(blocks, target, eye, rotation));
        }
        return null;
    }

    private static BlockHitResult hit(
            Set<BlockPos> blocks, BlockTarget target, Vec3 eye, Rotation rotation) {
        Vec3 end = eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(4.5));
        BlockPos closest = null;
        Vec3 point = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos block : blocks) {
            var clipped = new AABB(block).clip(eye, end);
            if (clipped.isPresent() && clipped.get().distanceToSqr(eye) < distance) {
                closest = block;
                point = clipped.get();
                distance = point.distanceToSqr(eye);
            }
        }
        if (!target.support().equals(closest)) return null;
        net.minecraft.core.Direction face = target.face();
        double plane =
                face.getAxis() == net.minecraft.core.Direction.Axis.X
                        ? closest.getX() + (face.getStepX() > 0 ? 1 : 0)
                        : closest.getZ() + (face.getStepZ() > 0 ? 1 : 0);
        double coordinate =
                face.getAxis() == net.minecraft.core.Direction.Axis.X ? point.x : point.z;
        if (Math.abs(coordinate - plane) > 1.0E-5) return null;
        var hit = new BlockHitResult(point, face, closest, false);
        return GodBridgeFacePoints.insideFace(hit, target) ? hit : null;
    }

    private static Vec3 facePoint(
            BlockPos block, net.minecraft.core.Direction face, double side, double height) {
        return new Vec3(
                block.getX()
                        + (face.getAxis() == net.minecraft.core.Direction.Axis.X
                                ? (face.getStepX() > 0 ? 1 : 0)
                                : side),
                block.getY() + height,
                block.getZ()
                        + (face.getAxis() == net.minecraft.core.Direction.Axis.Z
                                ? (face.getStepZ() > 0 ? 1 : 0)
                                : side));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
