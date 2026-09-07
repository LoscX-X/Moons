package com.blanoir.moons.client.module.world.scaffold;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Rotation deltas belonging to placements, observed only after packet sends. */
final class PlacementRotationHistory {
    private boolean yawKnown;
    private boolean rotatedSincePlacement;
    private float yaw;
    private float deltaYaw;
    private float lastPlacementDeltaYaw;

    void reset() {
        yawKnown = rotatedSincePlacement = false;
        yaw = deltaYaw = lastPlacementDeltaYaw = 0.0F;
    }

    void rotationSent(float sentYaw) {
        if (yawKnown) {
            // Match the raw packet delta, including crossing +/-180 degrees.
            deltaYaw = Math.abs(sentYaw - yaw);
            rotatedSincePlacement = true;
        }
        yaw = sentYaw;
        yawKnown = true;
    }

    void placementSent() {
        if (!rotatedSincePlacement) return;
        lastPlacementDeltaYaw = deltaYaw;
        rotatedSincePlacement = false;
    }

    boolean needsSettling() {
        return rotatedSincePlacement && repeatsPlacementDelta(deltaYaw);
    }

    private boolean repeatsPlacementDelta(float delta) {
        return delta > 2.0F && Math.abs(delta - lastPlacementDeltaYaw) < 0.0001F;
    }

    boolean wouldRepeatNext(float nextYaw) {
        if (!yawKnown) return false;
        float delta = Math.abs(nextYaw - yaw);
        // A UseOn may consume the pending delta before this upcoming look.
        // Check both outcomes, including local rejection and no placement.
        return repeatsPlacementDelta(delta)
                || rotatedSincePlacement && delta > 2.0F && Math.abs(delta - deltaYaw) < 0.0001F;
    }

    /** Prefer the selected angle, then nearby mouse steps in both directions. */
    Float variedYaw(
            float desiredYaw,
            float mouseStep,
            UnaryOperator<Float> quantize,
            Predicate<Float> reachable) {
        for (int i = 0; i <= 8; i++) {
            int offset = i == 0 ? 0 : ((i + 1) / 2) * (i % 2 == 1 ? 1 : -1);
            float candidate =
                    offset == 0
                            ? desiredYaw
                            : quantize.apply(desiredYaw + offset * Math.abs(mouseStep));
            if (Float.isFinite(candidate)
                    && !wouldRepeatNext(candidate)
                    && reachable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** A small look on a tick without a placement; pitch can stay at 90 for Tower. */
    float settlingYaw(float mouseStep) {
        float step = Math.max(Math.abs(mouseStep), Math.ulp(yaw));
        if (!Float.isFinite(step) || step == 0.0F) step = 0.02F;
        // Move toward zero to avoid overflow for large, unwrapped yaw values.
        float direction = yaw > 0.0F ? -1.0F : 1.0F;
        float candidate = yaw + direction * step;
        if (repeatsPlacementDelta(Math.abs(candidate - yaw))) {
            candidate = yaw + direction * (2.0F * step);
        }
        return candidate;
    }
}
