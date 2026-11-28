package com.blanoir.tempos.client.module.impl.movement;

import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.impl.movement.strafe.Legit2Strafe;
import com.blanoir.tempos.client.module.impl.movement.strafe.Legit2Strafe.Phase;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Body;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Direction;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Frame;
import com.blanoir.tempos.client.module.impl.movement.strafe.MovementSimulation;
import com.blanoir.tempos.client.module.impl.movement.strafe.ReachPrediction;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Deterministic trade sequences and closed-loop motion; not a real server duel. */
public final class Legit2Verification {
    public static void verify() throws Exception {
        timing();
        trades();
        movement();
        shortStepPreference();
        settings();
        System.out.println("TEMPOS_LEGIT2_VERIFIED rules confirmations trade-order latency bounded-strokes small-strafe reduced-retreat settings");
    }

    private static void timing() {
        var controller = new Legit2Strafe();
        require(!controller.confirmedHit(0), "Damage without a local attempt is not our confirmed hit");
        controller.attacked(0, 0);
        require(!controller.confirmedHit(7), "Reject damage after the bounded confirmation window");
        controller.reset();
        controller.attacked(0, 300);
        require(controller.confirmedHit(10), "Higher latency extends the movement confirmation window");
        require(!controller.confirmedHit(11), "Duplicate confirmations cannot queue repeated movement");
        controller.reset();
        for (int tick = 0; tick <= 6; tick++) controller.attacked(tick, 0);
        require(!controller.confirmedHit(7), "A burst of attempts cannot keep an old confirmation window alive");
        controller.attacked(8, 0);
        require(controller.confirmedHit(9), "An expired attempt does not prevent the next real trade");
        controller.reset();
        require(!controller.confirmedHit(9), "Disable/target/context reset discards the previous confirmation");
    }

    private static void trades() {
        Frame follow = frame(body(0), moving(3.15, new Vec3(0, .25, .2)), 0, false);
        Frame recover = frame(moving(0, new Vec3(0, .3, -.3)), body(3.15), 0, false);
        for (boolean incomingFirst : new boolean[] {false, true}) {
            var controller = new Legit2Strafe();
            controller.attacked(0, 100);
            if (incomingFirst) controller.incomingHit(1);
            require(controller.confirmedHit(1), "Accept the outgoing side of a simultaneous trade");
            if (!incomingFirst) controller.incomingHit(1);
            require(controller.choose(recover, Legit2Verification::floor, 1).equals(Direction.STILL),
                    "A trade first releases the old direction");
            controller.attacked(1, 100);
            controller.choose(recover, Legit2Verification::floor, 2);
            require(controller.phase() == Phase.RECOVER,
                    "Same-tick incoming damage wins both packet orders, even after a counter attempt");
        }
        var controller = new Legit2Strafe();
        controller.attacked(0, 100);
        controller.confirmedHit(1);
        require(!controller.choose(follow, Legit2Verification::floor, 2).equals(Direction.STILL)
                        && controller.phase() == Phase.FOLLOW,
                "A confirmed knockback can request a short follow stroke");
        controller.incomingHit(3);
        require(controller.choose(recover, Legit2Verification::floor, 3).equals(Direction.STILL),
                "Losing the next trade interrupts the previously winning follow stroke");
        controller.choose(recover, Legit2Verification::floor, 4);
        require(controller.phase() == Phase.RECOVER, "Replan from our actual knockback instead of assuming a combo");
        controller.reset();
        controller.attacked(0, 100);
        controller.incomingHit(1);
        controller.confirmedHit(2);
        controller.choose(recover, Legit2Verification::floor, 2);
        require(controller.phase() == Phase.RECOVER, "A late confirmation of the earlier hit cannot erase recovery");

        controller.reset();
        controller.attacked(0, 0);
        controller.confirmedHit(1);
        Frame noKnockback = frame(body(0), body(3.15), 0, false);
        require(controller.choose(noKnockback, Legit2Verification::floor, 2).equals(Direction.STILL),
                "Damage without a spacing change does not force movement");
        controller.reset();
        controller.attacked(0, 0);
        controller.confirmedHit(1);
        Frame suppressed = frame(follow.player(), follow.target(), 0, true);
        require(controller.choose(suppressed, Legit2Verification::floor, 2).equals(Direction.STILL),
                "A pending stroke waits for SprintReset/Critical to release input");
        require(!controller.choose(follow, Legit2Verification::floor, 3).equals(Direction.STILL),
                "A fresh stroke may start when suppression ends");
        controller.reset();
        controller.attacked(0, 0);
        controller.confirmedHit(1);
        for (int tick = 2; tick <= 8; tick++) controller.choose(suppressed, Legit2Verification::floor, tick);
        require(controller.choose(follow, Legit2Verification::floor, 9).equals(Direction.STILL),
                "An expired hit must not move us after a long Critical reservation");
    }

    private static void movement() {
        for (double latency : new double[] {0, 1.2, 3}) {
            var quiet = new Legit2Strafe();
            for (int tick = 0; tick < 60; tick++) {
                Frame sample = frame(body(0), body(3.15 + .015 * Math.sin(tick)), latency, false);
                require(quiet.choose(sample, Legit2Verification::floor, tick).equals(Direction.STILL),
                        "Stay still in reach before either side wins the first hit");
            }
            for (double distance : new double[] {1.8, 4}) simulateSpacing(distance, latency);
        }
        var controller = new Legit2Strafe();
        Frame far = frame(body(0), body(4), 0, false);
        require(controller.choose(far, Legit2Verification::floor, 0).forward() > 0, "Approach before a trade");
        require(controller.choose(far, (local, box, movement) -> movement, 1).equals(Direction.STILL),
                "A running stroke stops before walking off a newly exposed ledge");
        controller.reset();
        require(controller.choose(far, (local, box, movement) -> local ? Vec3.ZERO : floor(local, box, movement), 0)
                .equals(Direction.STILL), "A wall does not justify holding a useless movement key");
    }

    private static void simulateSpacing(double distance, double latency) {
        var controller = new Legit2Strafe();
        Body player = body(0), target = body(distance);
        Direction previous = Direction.STILL;
        int held = 0, releasedAt = -100;
        double travel = 0;
        for (int tick = 0; tick < 80; tick++) {
            Frame sample = frame(player, target, latency, false);
            Direction input = controller.choose(sample, Legit2Verification::floor, tick);
            if (!input.equals(Direction.STILL)) {
                if (!previous.equals(Direction.STILL))
                    require(input.equals(previous), "Do not reverse direction inside a movement stroke");
                else require(tick - releasedAt >= 2, "Release between separate movement strokes");
                require(++held <= 4, "No uninterrupted movement longer than four ticks");
            } else {
                if (!previous.equals(Direction.STILL)) releasedAt = tick;
                held = 0;
            }
            if (tick >= 40) require(input.equals(Direction.STILL), "Settle without continuing front/back corrections");
            Body moved = MovementSimulation.step(player, input, sample.movementYaw(), false, sample.physics(),
                    Legit2Verification::floor);
            travel += moved.eye().subtract(player.eye()).horizontalDistance();
            player = moved;
            previous = input;
        }
        double outgoing = ReachPrediction.distances(player, target, Vec3.ZERO).outgoing();
        require(outgoing > 2.3 && outgoing <= 3, "Stop at usable range: " + outgoing);
        require(travel < 1.8, "Reach spacing with little excess travel: " + travel);
    }

    private record StepMetrics(int backwardTicks, double lateralTravel, double outgoing) {}

    private static void shortStepPreference() {
        for (double latency : new double[] {0, 1.2, 3}) {
            for (int side : new int[] {-1, 1}) {
                StepMetrics rush = shortStep(true, latency, side);
                require(rush.backwardTicks() <= 1,
                        "A mild sprint overshoot should not trigger a sustained retreat: " + rush);
                require(rush.lateralTravel() < 0.3 && rush.outgoing() > 2.3 && rush.outgoing() <= 3,
                        "A small correction stays near the opponent without a wide orbit: " + rush);
                if (latency == 1.2) require(rush.lateralTravel() > 0.05,
                        "Use a small side step when its spacing is comparable to straight retreat");
                StepMetrics approach = shortStep(false, latency, side);
                require(approach.backwardTicks() == 0 && approach.outgoing() > 2.3 && approach.outgoing() < 2.75,
                        "Follow through into usable spacing without a correcting retreat: " + approach);
            }
        }
    }

    private static StepMetrics shortStep(boolean rush, double latency, int side) {
        var controller = new Legit2Strafe();
        Body player = body(0), target = body(rush ? 3.15 : 4);
        boolean sprint = rush;
        if (rush) player = new Body(player.box(), player.eye(), new Vec3(0, 0, .2), true);
        int backwardTicks = 0;
        double lateralTravel = 0;
        for (int tick = 0; tick < 80; tick++) {
            if (rush && tick % 12 == 0) controller.attacked(tick, 100);
            if (rush && tick % 12 == 1) controller.confirmedHit(tick);
            Vec3 look = target.eye().subtract(player.eye()).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            var physics = new MovementSimulation.Parameters(sprint ? .13 : .1,
                    sprint ? .026 : .02, .546, .91, .98, .08, .42, 1, -1, sprint, true);
            var frame = new Frame(player, target, Vec3.ZERO, look, yaw, 3, 3, physics,
                    false, latency, .13, 0, true, false, side);
            Direction direction = controller.choose(frame, Legit2Verification::floor, tick);
            if (direction.forward() < 0) backwardTicks++;
            Body moved = MovementSimulation.step(player, direction, yaw, false, physics,
                    Legit2Verification::floor);
            lateralTravel += Math.abs(moved.eye().subtract(player.eye()).dot(new Vec3(look.z, 0, -look.x)));
            player = moved;
            sprint = direction.forward() > 0;
        }
        return new StepMetrics(backwardTicks, lateralTravel,
                ReachPrediction.distances(player, target, Vec3.ZERO).outgoing());
    }

    private static void settings() throws Exception {
        Path home = Path.of(System.getProperty("tempos.home"));
        Files.createDirectories(home);
        Settings.configure(Files.createTempDirectory(home, "legit2-"));
        var module = Strafe.INSTANCE.toDescriptor();
        var mode = module.settings().stream().filter(s -> s.id().equals("mode")).findFirst().orElseThrow();
        var rules = module.settings().stream().filter(s -> s.id().equals("combat_rules")).findFirst().orElseThrow();
        require(module.category() == ModuleCategory.MOVEMENT && !module.enabled().getAsBoolean(),
                "Strafe remains an opt-in Movement module");
        require(mode.options().equals(List.of("legit", "legit2"))
                && mode.value().get().getAsString().equals("legit") && !rules.isVisible(),
                "Retain Legit as the default, without showing unused rules");
        mode.apply().apply(null, new JsonPrimitive("legit2"));
        require(rules.isVisible() && rules.value().get().getAsString().equals("auto"), "Legit2 defaults to Auto rules");
        rules.apply().apply(null, new JsonPrimitive("legacy"));
        Settings.flush();
        var values = JsonParser.parseString(Files.readString(Settings.file())).getAsJsonObject().getAsJsonObject("values");
        require(values.get("strafe.mode").getAsString().equals("legit2")
                && values.get("strafe.combatRules").getAsString().equals("legacy"), "Persist mode and rules through real GUI setters");
        mode.apply().apply(null, new JsonPrimitive("legit"));
    }

    private static Frame frame(Body player, Body target, double latency, boolean suppressed) {
        Vec3 look = target.eye().subtract(player.eye()).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        return new Frame(player, target, Vec3.ZERO, look, yaw, 3, 3,
                new MovementSimulation.Parameters(.1, .02, .546, .91, .98, .08, .42, 1, -1, false, false),
                false, latency, .15, 0, true, suppressed, 1);
    }

    private static Body body(double z) {
        return new Body(new AABB(-.3, 0, z - .3, .3, 1.8, z + .3), new Vec3(0, 1.62, z), Vec3.ZERO, true);
    }

    private static Body moving(double z, Vec3 velocity) {
        Body body = body(z);
        return new Body(body.box(), body.eye(), velocity, false);
    }

    private static Vec3 floor(boolean local, AABB box, Vec3 movement) {
        return new Vec3(movement.x, Math.max(movement.y, -box.minY), movement.z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
