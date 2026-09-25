package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.utils.rotation.aim.AimPointsH;
import com.blanoir.moons.client.utils.rotation.aim.BlockTarget;
import com.blanoir.moons.client.utils.rotation.aim.TargetSelectorD;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class GodBridgeSafetyVerification {
    public static void main(String[] args) {
        verifyPlacementRotations();
        require(GodBridgeSneak.exposed(.12, 0, 0),
                "Diagonal corner overlap must brake before the next unsupported step");
        require(GodBridgeSneak.exposed(0, .12, 0),
                "Momentum toward an edge must brake even with safe requested input");
        require(GodBridgeSneak.exposed(Double.NaN, 0, .2),
                "No support always brakes, regardless of configured overhang");
        require(!GodBridgeSneak.exposed(0, 0, 0), "Supported movement is not stopped");
        require(!GodBridgeSneak.exposed(.04, .03, .05), "Respect explicit overhang allowance");
        var sneak = new GodBridgeSneak();
        int[] samples = {0};
        java.util.function.IntSupplier duration = () -> {
            samples[0]++;
            return 75;
        };
        require(sneak.update(1, 0, true, duration), "Brake immediately at an edge");
        require(sneak.update(2, 50_000_000, false, duration), "Keep the sampled minimum hold");
        require(sneak.update(3, 75_000_000, true, duration), "An expired timer cannot release over air");
        require(sneak.update(4, 500_000_000, true, duration), "Remain protected if placement fails");
        require(!sneak.update(5, 501_000_000, false, duration), "Release once support returns");
        require(samples[0] == 1, "Sample once per hold, not every tick at the edge");

        sneak.reset();
        sneak.placed(true);
        require(!sneak.update(1, 0, false, duration), "The first diagonal block does not pulse");
        sneak.placed(true);
        require(sneak.update(2, 10_000_000, false, duration), "The second diagonal block pulses");
        require(sneak.update(3, 84_999_999, false, duration), "Hold uses milliseconds, not tick count");
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
                TargetSelectorD.cells(feet, new Vec3(.4, 0, .4), 0).getFirst()
                        .equals(new BlockPos(1, 0, 1)),
                "Prioritize the next diagonal footprint over the cell behind");
        require(
                TargetSelectorD.cells(feet.move(-1.8, 0, -1.8), new Vec3(-.4, 0, -.4), 0)
                        .getFirst().equals(new BlockPos(-2, 0, -2)),
                "Negative coordinates retain forward priority");
        BlockTarget target = new BlockTarget(BlockPos.ZERO, Direction.EAST);
        require(
                !AimPointsH.insideFace(
                        new BlockHitResult(new Vec3(1, .5, 1), Direction.EAST, BlockPos.ZERO, false),
                        target),
                "Reject ambiguous shared corners");
        require(
                AimPointsH.insideFace(
                        new BlockHitResult(new Vec3(1, .5, .5), Direction.EAST, BlockPos.ZERO, false),
                        target),
                "Accept the interior of a support face");
        System.out.println("GodBridge safety verification passed");
    }

    private static void verifyPlacementRotations() {
        var rotations = new ScaffoldManager.PlacementRotations();
        rotations.rotationSent(0);
        rotations.rotationSent(45);
        rotations.placementSent();
        require(rotations.wouldRepeatNext(90), "Consecutive equal placement turns are rejected");
        Float varied = rotations.variedYaw(
                90, .15F,
                yaw -> 45 + Math.round((yaw - 45) / .15F) * .15F,
                yaw -> yaw > 90 && yaw < 90.5F);
        require(varied != null && !rotations.wouldRepeatNext(varied),
                "Use a distinct quantized turn that still reaches the face");
        require(rotations.wouldRepeatNext(90),
                "Planning a varied angle does not validate an older frozen angle");
        require(rotations.variedYaw(90, .15F, yaw -> yaw, yaw -> yaw == 90) == null,
                "Wait if the repeated angle is the only reachable candidate");
        require(rotations.variedYaw(90, .000001F, yaw -> 90F, yaw -> true) == null,
                "Quantization collapsing the offsets must not reintroduce repetition");
        rotations.rotationSent(90);
        require(rotations.needsSettling(), "Already-sent repeated turns require a look-only tick");
        float settled = rotations.settlingYaw(.15F);
        rotations.rotationSent(settled);
        require(!rotations.needsSettling(), "A distinct sent look clears the pending repetition");
        rotations.placementSent();
        require(!rotations.wouldRepeatNext(settled), "Holding the same view needs no artificial jitter");

        rotations.reset();
        rotations.rotationSent(179);
        rotations.rotationSent(-179);
        rotations.placementSent();
        require(rotations.wouldRepeatNext(179), "Yaw wrap checks the actual packet delta");
        rotations.reset();
        require(!rotations.wouldRepeatNext(179) && !rotations.needsSettling(),
                "Reconnect/reset cannot retain a stale placement delta");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
