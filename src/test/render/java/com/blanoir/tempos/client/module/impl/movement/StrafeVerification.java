package com.blanoir.tempos.client.module.impl.movement;

import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Body;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Frame;
import com.blanoir.tempos.client.module.impl.movement.strafe.MovementSimulation;
import com.blanoir.tempos.client.module.impl.movement.strafe.ReachPrediction;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Controller geometry and input contract checks. Actual combat is verified in the game. */
public final class StrafeVerification {
    public static void main(String[] args) throws Exception {
        var controller = new LegitStrafe();
        var approach = controller.choose(frame(4, 0, 0, false), StrafeVerification::floor);
        require(approach.forward() > 0, "Approach an out-of-reach target");
        controller.reset();
        var retreat = controller.choose(frame(1.8, 0, 0, false), StrafeVerification::floor);
        require(retreat.forward() < 0, "Make room when the target is too close");
        controller.reset();
        var blocked = controller.choose(frame(4, 0, 0, true), StrafeVerification::floor);
        require(blocked.forward() <= 0, "Respect SprintReset/Critical forward suppression");
        controller.reset();
        var silent = controller.choose(frame(4, 90, 0, false), StrafeVerification::floor);
        require(silent.world(90).z > 0.9, "Use the captured silent movement yaw exactly once");

        var original = new Input(true, false, false, false, true, true, true);
        var applied = retreat.apply(original, true);
        require(applied.jump() && applied.shift() && !applied.sprint(),
                "Keep jump/sneak ownership and sprint suppression");
        require(applied.backward() && !applied.forward(), "Apply the selected keyboard direction");
        require(retreat.world(0).lengthSqr() <= 1.000001, "Normalize keyboard movement");

        Frame lowPing = frame(3.2, 0, 0, false);
        Frame highPing = frame(3.2, 0, 3, false);
        require(LegitStrafe.uncertainty(highPing) > LegitStrafe.uncertainty(lowPing),
                "Increase the spacing allowance with RTT uncertainty");
        controller.reset();
        var ledge = controller.choose(frame(4, 0, 3, false), (local, box, movement) -> {
            if (local && box.getCenter().z + movement.z > 0.08) return movement;
            return floor(local, box, movement);
        });
        require(ledge.forward() <= 0, "Reject a ground direction that loses floor support");

        Body low = body(0, 0);
        Body high = body(3.25, 1);
        require(Math.sqrt(high.box().distanceToSqr(low.eye())) < 3,
                "Lower player can reach the elevated hitbox in the illustrative geometry");
        require(Math.sqrt(low.box().distanceToSqr(high.eye())) > 3,
                "Elevated eyes can be outside the reciprocal range");
        var bounds = ReachPrediction.distances(low, body(3.2, 0), new Vec3(0, 0, 0.4));
        require(bounds.outgoing() > 3.2 && bounds.incoming() < 3,
                "Interpolation bounds must not grant outgoing reach through a larger hitbox");
        Body moving = new Body(low.box(), low.eye(), new Vec3(0.0025, 0, 0.0025), true);
        Body predicted = MovementSimulation.step(moving, LegitStrafe.Direction.STILL, 0, false,
                frame(3.2, 0, 0, false).physics(), StrafeVerification::floor);
        require(predicted.box().getCenter().x > 0.002,
                "Modern horizontal deadband keeps a diagonal whose total speed exceeds 0.003");
        var autoSprint = new MovementSimulation.Parameters(0.1, 0.02, 0.546, 0.91, 0.98,
                0.08, 0.42, 1, -1, false, true);
        var forward = new LegitStrafe.Direction(1, 0);
        Body startingSprint = MovementSimulation.step(low, forward, 0, false, autoSprint, StrafeVerification::floor);
        require(Math.abs(startingSprint.box().getCenter().z - 0.1274) < 1.0E-6,
                "Include the automatic sprint start observed after input selection in the game");
        Body knocked = new Body(low.box(), low.eye(), new Vec3(0, 0, -0.3), true);
        Body counterJump = MovementSimulation.step(knocked, forward, 0, true, autoSprint, StrafeVerification::floor);
        require(counterJump.box().getCenter().z > 0 && counterJump.box().minY > 0.4,
                "Simulate a grounded sprint jump against existing horizontal knockback");
        verifyMinimalMovement();
        Legit2Verification.verify();
        System.out.println("TEMPOS_STRAFE_VERIFIED spacing yaw input-ownership latency ledges height interpolation physics minimal-movement");
    }

    private static void verifyMinimalMovement() {
        for (double latency : new double[] {0, 1.2, 3}) {
            for (double margin : new double[] {0.05, 0.15}) {
                var quiet = simulateSpacing(3.15, latency, margin, 0, true);
                require(quiet.lateInputs() == 0 && quiet.travel() < 0.8,
                        "Settle through small target jitter and repeated attack cooldowns: " + latency + "/" + margin);
                for (double targetSpeed : new double[] {-0.08, 0.08}) {
                    var moving = simulateSpacing(3.15, latency, margin, targetSpeed, false);
                    require(moving.lateInputs() == 0 && moving.travel() < 3.5 && moving.maximumRange() < 3.02,
                            "Follow an approaching/receding target and stop after it stops: " + latency + "/" + margin);
                }
            }
        }
        var approach = simulateSpacing(4, 0, 0.15, 0, false);
        var retreat = simulateSpacing(1.8, 0, 0.15, 0, false);
        require(approach.settledAt() <= 12 && approach.travel() < 1.2,
                "Approach and brake promptly without orbiting or repeated range corrections");
        require(retreat.settledAt() <= 16 && retreat.travel() < 1.8,
                "Make room promptly and settle with little excess travel");

        var controller = new LegitStrafe();
        for (int tick = 0; tick < 20; tick++) controller.choose(frame(3.15, 0, 0, false), StrafeVerification::floor);
        require(controller.choose(frame(1.8, 0, 0, false), StrafeVerification::floor).forward() < 0,
                "A stable spacing must not delay an urgent retreat");
        controller.choose(frame(4, 0, 0, false), StrafeVerification::floor);
        require(controller.choose(frame(4, 0, 0, true), StrafeVerification::floor).forward() <= 0,
                "Forward suppression takes effect even during an ongoing approach");
    }

    private record SpacingRun(double travel, double maximumRange, int lateInputs, int settledAt) {}

    /** Closed-loop controller regression on ordinary flat-ground physics, not a gameplay test. */
    private static SpacingRun simulateSpacing(double initialDistance, double latency, double margin,
            double targetSpeed, boolean jitterAndCooldown) {
        var controller = new LegitStrafe();
        Body player = body(0, 0);
        boolean sprinting = false;
        double travel = 0, maximumRange = 0;
        int lateInputs = 0, lastUnsettled = -1;
        for (int tick = 0; tick < 200; tick++) {
            double targetZ = initialDistance + targetSpeed * Math.clamp(tick - 40, 0, 25);
            double velocity = tick >= 40 && tick < 65 ? targetSpeed : 0;
            if (jitterAndCooldown) {
                targetZ += 0.015 * Math.sin(tick * 1.7);
                velocity = 0.015 * (Math.sin(tick * 1.7) - Math.sin((tick - 1) * 1.7));
            }
            Body stationary = body(targetZ, 0);
            Body target = new Body(stationary.box(), stationary.eye(), new Vec3(0, 0, velocity), true);
            Vec3 look = target.eye().subtract(player.eye()).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            var physics = new MovementSimulation.Parameters(sprinting ? 0.13 : 0.1, sprinting ? 0.026 : 0.02,
                    0.546, 0.91, 0.98, 0.08, 0.42, 1, -1, sprinting, true);
            var sample = new Frame(player, target, Vec3.ZERO, look, yaw, 3, 3, physics, false,
                    latency, margin, jitterAndCooldown ? 11 - tick % 12 : 0, true, false, 1);
            var direction = controller.choose(sample, StrafeVerification::floor);
            Body moved = MovementSimulation.step(player, direction, yaw, false, physics, StrafeVerification::floor);
            travel += moved.eye().subtract(player.eye()).horizontalDistance();
            double range = ReachPrediction.distances(moved, target, Vec3.ZERO).outgoing();
            if (tick >= 30) maximumRange = Math.max(maximumRange, range);
            if (range < 2.6 || range > 3 || moved.velocity().horizontalDistance() > 0.006) lastUnsettled = tick;
            if (tick >= 100 && !direction.equals(LegitStrafe.Direction.STILL)) lateInputs++;
            player = moved;
            sprinting = direction.forward() > 0;
        }
        return new SpacingRun(travel, maximumRange, lateInputs, lastUnsettled + 1);
    }

    private static Frame frame(double distance, float yaw, double latency, boolean suppressForward) {
        return new Frame(body(0, 0), body(distance, 0), Vec3.ZERO, new Vec3(0, 0, 1), yaw,
                3, 3, new MovementSimulation.Parameters(0.1, 0.02, 0.546, 0.91, 0.98,
                        0.08, 0.42, 1, -1, false, false), false, latency, 0.15, 0, true, suppressForward, 1);
    }

    private static Body body(double z, double y) {
        return new Body(new AABB(-0.3, y, z - 0.3, 0.3, y + 1.8, z + 0.3),
                new Vec3(0, y + 1.62, z), Vec3.ZERO, y == 0);
    }

    private static Vec3 floor(boolean local, AABB box, Vec3 movement) {
        return new Vec3(movement.x, Math.max(movement.y, -box.minY), movement.z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
