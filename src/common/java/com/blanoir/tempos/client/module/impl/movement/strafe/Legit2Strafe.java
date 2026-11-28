package com.blanoir.tempos.client.module.impl.movement.strafe;

import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Collision;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Direction;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe.Frame;

/** Observes real trades to select bounded movement; never gates or dispatches an attack. */
public final class Legit2Strafe {
    public enum Phase { OPENING, HOLD, FOLLOW, RECOVER, PAUSE }

    private static final int NONE = Integer.MIN_VALUE;
    private final LegitStrafe movement = new LegitStrafe();
    private Phase phase = Phase.OPENING;
    private Phase pending;
    private Direction direction = Direction.STILL;
    private boolean awaitingCounter;
    private int attackTick = NONE, confirmationUntil = NONE, incomingTick = NONE;
    private int readyAt = NONE, expiresAt = NONE, stepUntil = NONE, pauseUntil = NONE;

    public Phase phase() { return phase; }

    public void attacked(int tick, int rttMs) {
        // Keep the first outstanding attempt: later CPS packets cannot identify which hit was acknowledged.
        if (confirmationUntil == NONE || tick > confirmationUntil) {
            attackTick = tick;
            confirmationUntil = tick + 6 + (int) Math.ceil(Math.clamp(rttMs, 0, 1000) / 50.0);
        }
        awaitingCounter = false;
        // Packet attempts are not hits. Extra clicks must not erase a confirmed recovery cue.
        if (phase == Phase.OPENING && stepUntil != NONE) stop(tick);
    }

    /** Caller verifies victim, direct attacker and current target identity from the server packet. */
    public boolean confirmedHit(int tick) {
        if (attackTick == NONE || tick < attackTick || tick > confirmationUntil) return false;
        confirmationUntil = NONE;
        // Packet callbacks in the same tick cannot establish a safe first/second-hit ordering.
        if (!awaitingCounter && incomingTick != tick) queue(Phase.FOLLOW, tick);
        return true;
    }

    public void incomingHit(int tick) {
        incomingTick = tick;
        awaitingCounter = true;
        queue(Phase.RECOVER, tick);
    }

    public Direction choose(Frame frame, Collision collision, int tick) {
        if (pending != null && tick > expiresAt) pending = null;
        if (stepUntil != NONE) {
            if (tick >= stepUntil || movement.shouldRelease(frame, collision, direction)) {
                stop(tick);
                return Direction.STILL;
            }
            return direction;
        }
        if (tick < pauseUntil) return Direction.STILL;
        if (pending != null) {
            if (tick < readyAt || frame.forwardSuppressed()) return Direction.STILL;
            Phase cue = pending;
            pending = null;
            return start(frame, collision, tick, cue);
        }
        double distance = ReachPrediction.distances(frame.player(), frame.target(), frame.interpolationDelta()).outgoing();
        // Before either side lands a hit, or after an escape, re-enter reachable spacing.
        // Ordinary in-range trades stay still until a real hit changes the situation.
        if (distance > frame.reach()
                || distance < frame.reach() - frame.margin() - LegitStrafe.uncertainty(frame) - 0.4) {
            if (frame.forwardSuppressed()) return Direction.STILL;
            return start(frame, collision, tick, Phase.OPENING);
        }
        phase = Phase.HOLD;
        return Direction.STILL;
    }

    private Direction start(Frame frame, Collision collision, int tick, Phase reason) {
        var step = movement.planStep(frame, collision);
        if (step.ticks() == 0) {
            stop(tick);
            return Direction.STILL;
        }
        phase = reason;
        direction = step.direction();
        stepUntil = tick + step.ticks();
        return direction;
    }

    private void queue(Phase reason, int tick) {
        pending = reason;
        readyAt = tick + 1;
        expiresAt = tick + 6;
        // Allow the applied damage/velocity and W-tap to settle before planning from current motion.
        stop(tick);
        pauseUntil = tick + 1;
    }

    private void stop(int tick) {
        direction = Direction.STILL;
        stepUntil = NONE;
        pauseUntil = tick + 2;
        phase = Phase.PAUSE;
    }

    public void reset() {
        movement.reset();
        phase = Phase.OPENING;
        pending = null;
        direction = Direction.STILL;
        awaitingCounter = false;
        attackTick = confirmationUntil = incomingTick = readyAt = expiresAt = stepUntil = pauseUntil = NONE;
    }
}
