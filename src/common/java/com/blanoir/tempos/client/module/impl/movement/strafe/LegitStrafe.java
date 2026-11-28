package com.blanoir.tempos.client.module.impl.movement.strafe;

import com.blanoir.tempos.client.utils.prediction.TrajectoryPrediction;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Short-horizon selection among the nine ordinary keyboard movement directions. */
public final class LegitStrafe {
    public record Direction(int forward, int sideways) {
        public static final Direction STILL = new Direction(0, 0);

        public Input apply(Input input, boolean suppressSprint) {
            return new Input(
                    forward > 0, forward < 0, sideways > 0, sideways < 0,
                    input.jump(), input.shift(), input.sprint() && !suppressSprint);
        }

        public Vec3 world(float yaw) {
            double angle = Math.toRadians(yaw);
            return new Vec3(
                            sideways * Math.cos(angle) - forward * Math.sin(angle),
                            0,
                            forward * Math.cos(angle) + sideways * Math.sin(angle))
                    .normalize();
        }
    }

    public record Body(AABB box, Vec3 eye, Vec3 velocity, boolean grounded) {}

    public record Frame(
            Body player, Body target, Vec3 interpolationDelta, Vec3 look, float movementYaw,
            double reach, double targetReach, MovementSimulation.Parameters physics, boolean jump,
            double latencyTicks, double margin, int attackInTicks,
            boolean automaticAim, boolean forwardSuppressed, int preferredSide) {}

    @FunctionalInterface
    public interface Collision {
        Vec3 resolve(boolean localPlayer, AABB box, Vec3 movement);
    }

    private static final Direction[] DIRECTIONS = {
        Direction.STILL,
        new Direction(1, 0), new Direction(1, 1), new Direction(0, 1),
        new Direction(-1, 1), new Direction(-1, 0), new Direction(-1, -1),
        new Direction(0, -1), new Direction(1, -1)
    };

    private Direction previous = Direction.STILL;

    public Direction choose(Frame frame, Collision collision) {
        // Even at low RTT, look far enough ahead to see friction after releasing a key.
        int horizon = Math.max(4, 2 + (int) Math.ceil(Math.clamp(frame.latencyTicks(), 0, 3)));
        Body[] targets = forecastTarget(frame.target(), horizon, collision);
        double uncertainty = uncertainty(frame);
        Direction best = Direction.STILL;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Direction direction : DIRECTIONS) {
            if (frame.forwardSuppressed() && direction.forward() > 0) continue;
            double score = score(frame, targets, direction, collision, uncertainty, 1, false);
            if (direction.equals(previous)) score += 0.002;
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        previous = best;
        return best;
    }

    record Step(Direction direction, int ticks) {}

    /** One short stroke followed by release, used by the event-driven Legit2 controller. */
    Step planStep(Frame frame, Collision collision) {
        Body[] targets = forecastTarget(frame.target(), 8, collision);
        double uncertainty = uncertainty(frame);
        Step best = new Step(Direction.STILL, 0);
        double bestScore = score(frame, targets, Direction.STILL, collision, uncertainty, 0, true);
        for (int ticks = 1; ticks <= 4; ticks++) {
            for (Direction direction : DIRECTIONS) {
                if (direction.equals(Direction.STILL)
                        || frame.forwardSuppressed() && direction.forward() > 0) continue;
                double candidate = score(frame, targets, direction, collision, uncertainty, ticks, true);
                if (candidate > bestScore) {
                    bestScore = candidate;
                    best = new Step(direction, ticks);
                }
            }
        }
        return best;
    }

    /** A running stroke may end early, but is never steered into another stroke mid-trade. */
    boolean shouldRelease(Frame frame, Collision collision, Direction direction) {
        if (frame.forwardSuppressed() && direction.forward() > 0) return true;
        Body[] targets = forecastTarget(frame.target(), 4, collision);
        double uncertainty = uncertainty(frame);
        double moving = score(frame, targets, direction, collision, uncertainty, 1, true);
        return !Double.isFinite(moving)
                || score(frame, targets, Direction.STILL, collision, uncertainty, 0, true) >= moving;
    }

    /** RTT bounds uncertainty and the horizon; it is not an exact entity snapshot age. */
    public static double uncertainty(Frame frame) {
        double relativeSpeed = frame.target().velocity().subtract(frame.player().velocity()).length();
        return Math.clamp(frame.latencyTicks() * (0.035 + relativeSpeed * 0.12), 0, 0.35);
    }

    private double score(
            Frame frame, Body[] targets, Direction direction,
            Collision collision, double uncertainty, int inputTicks, boolean shortStep) {
        Body player = frame.player();
        Vec3 movement = direction.world(frame.movementYaw());
        Vec3 toward = frame.target().eye().subtract(player.eye()).multiply(1, 0, 1).normalize();
        Vec3 tangent = new Vec3(toward.z, 0, -toward.x);
        double lateral = movement.dot(tangent) * frame.preferredSide();
        // Side preference only breaks equivalent corrections; it must not cause an orbit.
        double score = direction.equals(Direction.STILL) ? 0 : -0.06 * inputTicks + 1.0E-5 * lateral;
        if (shortStep) {
            // A small sideways preference, once per stroke, cannot pay for extra movement on its own.
            // Penalize travel away from the opponent rather than the camera-relative S key.
            score += 0.04 * Math.abs(lateral)
                    - 0.12 * inputTicks * Math.max(0, -movement.dot(toward));
        }
        for (int tick = 1; tick < targets.length; tick++) {
            // Apply only the proposed input duration, then predict release and natural braking.
            Direction input = tick <= inputTicks ? direction : Direction.STILL;
            Body moved = MovementSimulation.step(player, input, frame.movementYaw(),
                    frame.jump() && tick == 1, frame.physics(), collision);
            // A ground strafe must not walk off a ledge. Airborne control still remains available.
            if (player.grounded() && !moved.grounded() && player.velocity().y <= 0 && !frame.jump())
                return Double.NEGATIVE_INFINITY;
            score -= 0.05 * moved.eye().subtract(player.eye()).horizontalDistance();
            player = moved;
            Body target = targets[tick];
            var distances = ReachPrediction.distances(player, target, frame.interpolationDelta());
            double outgoing = distances.outgoing();
            double incoming = distances.incoming();
            double desired = Math.max(0.5, frame.reach() - frame.margin() - uncertainty - (shortStep ? 0.03 : 0));
            double usableReach = frame.reach() - uncertainty - 0.02;
            double reciprocalBoundary = frame.targetReach() + uncertainty + 0.02 - (incoming - outgoing);
            double upper = Math.min(usableReach, desired + (frame.attackInTicks() > tick ? 0.1 : 0.08));
            double lower = Math.min(desired - 0.08, upper - 0.16);
            // Legit2 tolerates a little more inward travel after a rush instead of immediately backing out.
            if (shortStep) lower -= 0.16;
            if (reciprocalBoundary < usableReach) {
                lower = Math.max(lower, reciprocalBoundary);
                upper = usableReach;
            }
            // Hold any useful spacing, including a genuine one-way reach window.
            // Cooldown may widen that interval, but never forces a retreat on its own.
            double distanceError = Math.max(0, lower - outgoing) + Math.max(0, outgoing - upper);
            double unreachable = Math.max(0, outgoing - usableReach);
            score -= 12 * distanceError * distanceError + 80 * unreachable * unreachable;
            if (tick == 1) {
                // Do not step out of today's hitbox merely because the opponent may approach.
                double visibleReach = ReachPrediction.distances(player, frame.target(), frame.interpolationDelta()).outgoing();
                double visibleMiss = Math.max(0, visibleReach - usableReach);
                score -= 250 * visibleMiss * visibleMiss;
            }

            // Fixed camera attacks need the player to keep tracking during lateral movement.
            Vec3 end = player.eye().add(frame.look().scale(frame.reach() + 1));
            if (!target.box().contains(player.eye()) && target.box().clip(player.eye(), end).isEmpty())
                score -= frame.automaticAim() ? 0.08 : 1.2;
        }
        return score;
    }

    private static Body[] forecastTarget(Body target, int horizon, Collision collision) {
        Body[] forecast = new Body[horizon + 1];
        forecast[0] = target;
        // Tiny ground deltas need fresh observations before assuming sustained movement.
        double groundRetention = Math.clamp(target.velocity().horizontalDistance() / 0.08, 0, 1);
        for (int tick = 1; tick <= horizon; tick++) {
            Body next = advance(target, target.velocity(), false, collision);
            Vec3 velocity = next.velocity();
            if (next.grounded()) velocity = velocity.multiply(groundRetention / 0.91, 1, groundRetention / 0.91);
            target = new Body(next.box(), next.eye(), velocity, next.grounded());
            forecast[tick] = target;
        }
        return forecast;
    }

    private static Body advance(Body body, Vec3 velocity, boolean local, Collision collision) {
        var step = TrajectoryPrediction.advance(
                Vec3.ZERO, velocity, body.grounded(),
                requested -> collision.resolve(local, body.box(), requested));
        Vec3 travel = step.position();
        return new Body(body.box().move(travel), body.eye().add(travel), step.velocity(), step.grounded());
    }

    public void reset() {
        previous = Direction.STILL;
    }
}
