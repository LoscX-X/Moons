package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.BlockAim;
import com.blanoir.moons.client.utils.rotation.aim.BlockTarget;
import com.blanoir.moons.client.utils.rotation.aim.FaceAimJitter;
import com.blanoir.moons.client.utils.rotation.aim.GodBridgeFacePoints;
import com.blanoir.moons.client.utils.rotation.aim.GodBridgeTargetSelector;
import com.blanoir.moons.client.utils.rotation.quantize.MouseAngleQuantizer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class GodBridgeSafetyVerification {
    public static void main(String[] args) {
        verifyAimMotion();
        verifyPlacementRotations();
        verifyDirectionChanges();
        require(
                GodBridgeSneak.exposed(.12, 0, 0),
                "Diagonal corner overlap must brake before the next unsupported step");
        require(
                GodBridgeSneak.exposed(0, .12, 0),
                "Momentum toward an edge must brake even with safe requested input");
        require(
                GodBridgeSneak.exposed(Double.NaN, 0, .2),
                "No support always brakes, regardless of configured overhang");
        require(!GodBridgeSneak.exposed(0, 0, 0), "Supported movement is not stopped");
        require(!GodBridgeSneak.exposed(.04, .03, .05), "Respect explicit overhang allowance");
        var sneak = new GodBridgeSneak();
        int[] samples = {0};
        java.util.function.IntSupplier duration =
                () -> {
                    samples[0]++;
                    return 75;
                };
        require(sneak.update(1, 0, true, duration), "Brake immediately at an edge");
        require(sneak.update(2, 50_000_000, false, duration), "Keep the sampled minimum hold");
        require(
                sneak.update(3, 75_000_000, true, duration),
                "An expired timer cannot release over air");
        require(
                sneak.update(4, 500_000_000, true, duration),
                "Remain protected if placement fails");
        require(!sneak.update(5, 501_000_000, false, duration), "Release once support returns");
        require(samples[0] == 1, "Sample once per hold, not every tick at the edge");

        sneak.reset();
        sneak.placed(true);
        require(!sneak.update(1, 0, false, duration), "The first diagonal block does not pulse");
        sneak.placed(true);
        require(sneak.update(2, 10_000_000, false, duration), "The second diagonal block pulses");
        require(
                sneak.update(3, 84_999_999, false, duration),
                "Hold uses milliseconds, not tick count");
        require(!sneak.update(4, 85_000_000, false, duration), "Release exactly at the deadline");
        sneak.placed(true);
        sneak.placed(false);
        sneak.placed(true);
        require(!sneak.update(5, 100_000_000, false, duration), "Straight placement breaks a pair");
        sneak.placed(true);
        sneak.reset();
        require(!sneak.update(1, 0, false, duration), "Disable/reset clears pending pairs");
        require(sneak.update(2, 0, true, duration), "Edge protection restarts after reset");
        require(!sneak.update(0, 1, false, duration), "Tick rollback clears an old hold");

        require(GodBridgeSneak.diagonalMovement(45, 1, 0), "Forward with diagonal camera counts");
        require(GodBridgeSneak.diagonalMovement(0, 1, 1), "Diagonal keys count");
        require(!GodBridgeSneak.diagonalMovement(45, 1, 1), "Combined keys can move cardinally");
        require(!GodBridgeSneak.diagonalMovement(45, 0, 0), "Stopped input is not diagonal");

        AABB feet = new AABB(.6, 1, .6, 1.2, 2.8, 1.2);
        require(
                GodBridgeTargetSelector.cells(feet, new Vec3(.4, 0, .4), 0)
                        .getFirst()
                        .equals(new BlockPos(1, 0, 1)),
                "Prioritize the next diagonal footprint over the cell behind");
        require(
                GodBridgeTargetSelector.cells(feet.move(-1.8, 0, -1.8), new Vec3(-.4, 0, -.4), 0)
                        .getFirst()
                        .equals(new BlockPos(-2, 0, -2)),
                "Negative coordinates retain forward priority");
        BlockTarget target = new BlockTarget(BlockPos.ZERO, Direction.EAST);
        require(
                !GodBridgeFacePoints.insideFace(
                        new BlockHitResult(
                                new Vec3(1, .5, 1), Direction.EAST, BlockPos.ZERO, false),
                        target),
                "Reject ambiguous shared corners");
        require(
                GodBridgeFacePoints.insideFace(
                        new BlockHitResult(
                                new Vec3(1, .5, .5), Direction.EAST, BlockPos.ZERO, false),
                        target),
                "Accept the interior of a support face");
        System.out.println("GodBridge safety verification passed");
    }

    private static void verifyDirectionChanges() {
        AABB feet = new AABB(.2, 1, .2, .8, 2.8, .8);
        Vec3 diagonal = new Vec3(.156, 0, .156);
        require(
                !GodBridgeTargetSelector.cells(feet, diagonal, 0).contains(new BlockPos(1, 0, 0)),
                "Old diagonal inertia has not reached the straight destination yet");
        require(
                GodBridgeTargetSelector.cells(feet, diagonal, feet.move(.221, 0, 0), 0)
                        .contains(new BlockPos(1, 0, 0)),
                "Diagonal-to-straight input adds its destination before inertia catches up");
        AABB nearCorner = feet.move(0, 0, .15);
        Vec3 straight = new Vec3(.221, 0, 0);
        require(
                !GodBridgeTargetSelector.cells(nearCorner, straight, 0)
                        .contains(new BlockPos(1, 0, 1)),
                "Old straight inertia does not search the new diagonal corner");
        require(
                GodBridgeTargetSelector.cells(nearCorner, straight, nearCorner.move(diagonal), 0)
                        .contains(new BlockPos(1, 0, 1)),
                "Straight-to-diagonal input includes the corner and its connecting cells");

        // Both handed turns, all four faces, negative coordinates and coarse mouse steps.
        for (int turn = 0; turn < 4; turn++) {
            for (boolean opposite : new boolean[] {false, true}) {
                for (double step : new double[] {.0096, .15, .6144}) {
                    int quarter = turn;
                    Vec3 eye = rotateBridgePoint(new Vec3(1.08, 2.62, opposite ? 1.1 : -.1), turn);
                    Direction face =
                            new Direction[] {
                                        Direction.EAST,
                                        Direction.SOUTH,
                                        Direction.WEST,
                                        Direction.NORTH
                                    }
                                    [turn];
                    Rotation preferred = new Rotation((opposite ? 45 : 135) + turn * 90, 75.7F);
                    var quantizer =
                            new MouseAngleQuantizer.Adapter(
                                    (base, desired) ->
                                            MouseAngleQuantizer.quantizeYawWithStep(
                                                    base, desired, step),
                                    (base, desired) ->
                                            MouseAngleQuantizer.quantizePitchWithStep(
                                                    base, desired, step));
                    var target = new BlockTarget(new BlockPos(-3, 0, -3), face);
                    var box = new AABB(-3, 0, -3, -2, 1, -2);
                    java.util.function.Predicate<Rotation> reachable =
                            rotation -> {
                                Vec3 end =
                                        eye.add(
                                                Vec3.directionFromRotation(
                                                                rotation.pitch(), rotation.yaw())
                                                        .scale(4.5));
                                var hit = box.clip(eye, end);
                                if (hit.isEmpty()) return false;
                                Vec3 point = hit.get();
                                double plane =
                                        face.getAxis() == Direction.Axis.X
                                                ? (face.getStepX() > 0 ? box.maxX : box.minX)
                                                : (face.getStepZ() > 0 ? box.maxZ : box.minZ);
                                double at = face.getAxis() == Direction.Axis.X ? point.x : point.z;
                                return Math.abs(at - plane) < 1.0E-5
                                        && GodBridgeFacePoints.insideFace(
                                                new BlockHitResult(
                                                        point, face, target.support(), false),
                                                target);
                            };
                    for (float offset = -12; offset <= 12; offset += .5F) {
                        Vec3 direction = Vec3.directionFromRotation(0, preferred.yaw() + offset);
                        double distance =
                                .08
                                        / (face.getAxis() == Direction.Axis.X
                                                ? Math.abs(direction.x)
                                                : Math.abs(direction.z));
                        Vec3 atPlane = eye.add(direction.scale(distance));
                        double tangent = face.getAxis() == Direction.Axis.X ? atPlane.z : atPlane.x;
                        require(
                                tangent < -3 || tangent > -2,
                                "The old +/-12 degree horizontal rays miss this real side face");
                    }
                    Rotation recovered =
                            GodBridgeFacePoints.turnAim(
                                    eye,
                                    preferred,
                                    face,
                                    (side, height) ->
                                            rotateBridgePoint(new Vec3(1, height, side), quarter),
                                    quantizer,
                                    reachable);
                    require(
                            recovered != null && reachable.test(recovered),
                            "Turn fallback must hit the actual face after quantization");
                    require(
                            GodBridgeFacePoints.turnAim(
                                            eye,
                                            preferred,
                                            face,
                                            (side, height) ->
                                                    rotateBridgePoint(
                                                            new Vec3(1, height, side), quarter),
                                            quantizer,
                                            ignored -> false)
                                    == null,
                            "An occluded or unreachable face must never authorize placement");
                }
            }
        }
    }

    private static Vec3 rotateBridgePoint(Vec3 point, int quarters) {
        double x = point.x - .5, z = point.z - .5;
        for (int i = 0; i < quarters; i++) {
            double previousX = x;
            x = -z;
            z = previousX;
        }
        return new Vec3(x - 2.5, point.y, z - 2.5);
    }

    private static void verifyAimMotion() {
        AABB bounds = new AABB(-2.0, .25, -4.0, -1.0, .75, -3.0);
        Vec3 eye = new Vec3(.2, 2.62, -2.8);
        for (Direction face : Direction.values()) {
            Vec3 base =
                    new Vec3(
                            face == Direction.WEST
                                    ? bounds.minX
                                    : face == Direction.EAST ? bounds.maxX : -1.5,
                            face == Direction.DOWN
                                    ? bounds.minY
                                    : face == Direction.UP ? bounds.maxY : .5,
                            face == Direction.NORTH
                                    ? bounds.minZ
                                    : face == Direction.SOUTH ? bounds.maxZ : -3.5);
            Vec3 old = null;
            boolean moved = false;
            for (int tick = 0; tick < 100; tick++) {
                Vec3 point =
                        FaceAimJitter.sample(eye, base, bounds, face, tick * .05, 19L, .38, .85);
                require(
                        point.x >= bounds.minX
                                && point.x <= bounds.maxX
                                && point.y >= bounds.minY
                                && point.y <= bounds.maxY
                                && point.z >= bounds.minZ
                                && point.z <= bounds.maxZ,
                        "Face noise stays inside the actual partial-block bounds");
                require(
                        switch (face.getAxis()) {
                            case X -> point.x == base.x;
                            case Y -> point.y == base.y;
                            case Z -> point.z == base.z;
                        },
                        "Combat interior noise must preserve the clicked face plane");
                require(
                        point.equals(
                                FaceAimJitter.sample(
                                        eye, base, bounds, face, tick * .05, 19L, .38, .85)),
                        "Same-tick point reads are deterministic");
                if (old != null) {
                    require(
                            point.distanceTo(old) < .03,
                            "Point noise has no per-tick random jumps");
                    moved |= !point.equals(old);
                }
                old = point;
            }
            require(moved, "The face point must vary instead of remaining on one grid coordinate");
            require(
                    base.equals(FaceAimJitter.sample(eye, base, bounds, face, 0, 19L, 0, .85)),
                    "Zero strength preserves the exact point");
        }

        var motion = new ScaffoldAimMotion((min, max) -> (min + max) * .5);
        motion.rebase(new Rotation(0, 0));
        BlockTarget target = new BlockTarget(new BlockPos(-2, 0, -4), Direction.EAST);
        Vec3 facePoint = new Vec3(-1, .5, -3.5);
        BlockAim fallback =
                new BlockAim(
                        target,
                        new Rotation(90, 60),
                        new BlockHitResult(facePoint, Direction.EAST, target.support(), false));
        int[] probes = {0};
        BlockAim blocked =
                motion.varyPoint(
                        fallback,
                        eye,
                        bounds,
                        0,
                        point -> {
                            probes[0]++;
                            return null;
                        });
        require(
                blocked == fallback && probes[0] == 3,
                "Occluded or GCD-unreachable noise retains the validated scanned hit");
        BlockAim reached =
                motion.varyPoint(
                        fallback,
                        eye,
                        bounds,
                        10,
                        point -> {
                            BlockHitResult hit =
                                    AABB.clip(
                                            java.util.List.of(
                                                    bounds.move(
                                                            -target.support().getX(),
                                                            -target.support().getY(),
                                                            -target.support().getZ())),
                                            eye,
                                            eye.add(point.subtract(eye).scale(2)),
                                            target.support());
                            return hit != null && hit.getDirection() == target.face()
                                    ? new BlockAim(target, fallback.rotation(), hit)
                                    : null;
                        });
        require(
                reached != fallback && reached.hit().getDirection() == Direction.EAST,
                "A partial-block face must still be reached by the actual candidate ray");
        Rotation desired = new Rotation(135, 80);
        Rotation first = motion.sample(1, desired, .5, 85, 2);
        require(
                first.equals(motion.sample(1, new Rotation(-90, -30), .5, 45, 1)),
                "Candidate reads cannot replace a sampled movement look in the same tick");
        require(
                first.equals(motion.sample(2, desired, .5, 85, 2)),
                "A cancelled movement must not advance from the unsent candidate");
        require(
                !motion.accepts(2, new Rotation(first.yaw() + 10, first.pitch())),
                "Duplicate alternatives cannot escape the acceleration envelope");
        Rotation sent = new Rotation(0, 0);
        double quantum = MouseAngleQuantizer.mouseSensitivityGcd(.5);
        for (int tick = 3; tick <= 40; tick++) {
            Rotation next = motion.sample(tick, desired, .5, 85, tick < 6 ? 2 : 1);
            require(motion.accepts(tick, next), "Final GCD look remains inside the motion budget");
            double yaw = net.minecraft.util.Mth.wrapDegrees(next.yaw() - sent.yaw());
            double pitch = next.pitch() - sent.pitch();
            require(
                    Math.abs(yaw / quantum - Math.rint(yaw / quantum)) < .0002
                            && Math.abs(pitch / quantum - Math.rint(pitch / quantum)) < .0002,
                    "Both axes use actual-sent-relative mouse steps");
            motion.confirm(next);
            sent = next;
        }
        require(
                Math.abs(net.minecraft.util.Mth.wrapDegrees(desired.yaw() - sent.yaw())) < .3
                        && Math.abs(desired.pitch() - sent.pitch()) < .3,
                "A finite placement target converges instead of perpetually orbiting");

        motion.rebase(new Rotation(179, 0));
        Rotation wrapped = motion.sample(50, new Rotation(-179, 0), .5, 85, 1);
        require(Math.abs(wrapped.yaw() - 179) < 3, "Crossing +/-180 follows the short arc");
        require(motion.pending(49) == null, "Old ticks cannot revive pending motion");

        motion.rebase(new Rotation(0, 0));
        Rotation fast = motion.sample(60, new Rotation(170, 0), .5, 85, 1);
        motion.confirm(fast);
        Rotation faster = motion.sample(61, new Rotation(170, 0), .5, 85, 1);
        motion.confirm(faster);
        Rotation slowed = motion.sample(62, new Rotation(170, 0), .5, 5, 1);
        require(
                slowed.yaw() - faster.yaw() > 5,
                "A reduced speed cap is approached through braking instead of snapping");
        require(motion.accepts(62, slowed), "Braking retains the final packet interval");
        motion.rebase(new Rotation(20, -5));
        Rotation idle = motion.sample(70, new Rotation(20, -5), .5, 45, 2);
        require(
                idle.equals(new Rotation(20, -5)) && motion.settled(),
                "A fresh tenure cannot inherit velocity from another mode or owner");
    }

    private static void verifyPlacementRotations() {
        var rotations = new ScaffoldManager.PlacementRotations();
        rotations.rotationSent(0);
        rotations.rotationSent(45);
        rotations.placementSent();
        require(rotations.wouldRepeatNext(90), "Consecutive equal placement turns are rejected");
        Float varied =
                rotations.variedYaw(
                        90,
                        .15F,
                        yaw -> 45 + Math.round((yaw - 45) / .15F) * .15F,
                        yaw -> yaw > 90 && yaw < 90.5F);
        require(
                varied != null && !rotations.wouldRepeatNext(varied),
                "Use a distinct quantized turn that still reaches the face");
        require(
                rotations.wouldRepeatNext(90),
                "Planning a varied angle does not validate an older frozen angle");
        require(
                rotations.variedYaw(90, .15F, yaw -> yaw, yaw -> yaw == 90) == null,
                "Wait if the repeated angle is the only reachable candidate");
        require(
                rotations.variedYaw(90, .000001F, yaw -> 90F, yaw -> true) == null,
                "Quantization collapsing the offsets must not reintroduce repetition");
        rotations.rotationSent(90);
        require(rotations.needsSettling(), "Already-sent repeated turns require a look-only tick");
        float settled = rotations.settlingYaw(.15F);
        rotations.rotationSent(settled);
        require(!rotations.needsSettling(), "A distinct sent look clears the pending repetition");
        rotations.placementSent();
        require(
                !rotations.wouldRepeatNext(settled),
                "Holding the same view needs no artificial jitter");

        rotations.reset();
        rotations.rotationSent(179);
        rotations.rotationSent(-179);
        rotations.placementSent();
        require(rotations.wouldRepeatNext(179), "Yaw wrap checks the actual packet delta");
        rotations.reset();
        require(
                !rotations.wouldRepeatNext(179) && !rotations.needsSettling(),
                "Reconnect/reset cannot retain a stale placement delta");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
