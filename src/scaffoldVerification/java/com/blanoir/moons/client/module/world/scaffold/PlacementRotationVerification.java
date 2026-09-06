package com.blanoir.moons.client.module.world.scaffold;

public final class PlacementRotationVerification {
    private static int assertions;

    public static void main(String[] args) {
        PlacementRotationHistory history = new PlacementRotationHistory();
        history.rotationSent(0);
        history.rotationSent(45);
        require(!history.needsSettling(), "first placement has no repeated delta");
        history.placementSent();
        history.rotationSent(90);
        require(history.needsSettling(), "two placement-related 45 degree turns repeat");

        // Planning a rotation (or cancelling its send) must not acknowledge it.
        float settling = history.settlingYaw(0.15F);
        require(settling != 90, "settling forces a look even when position is unchanged");
        require(history.needsSettling(), "planning alone cannot clear pending repetition");
        require(history.needsSettling(), "a position-only packet leaves rotation history intact");
        history.rotationSent(settling);
        require(!history.needsSettling(), "actual settling look makes placement eligible");
        history.placementSent();
        history.placementSent();
        require(!history.needsSettling(), "multiple placements without a look have no new delta");

        history.reset();
        history.rotationSent(0);
        history.rotationSent(25);
        history.placementSent(); // Also done when useItemOn's local result is FAIL.
        history.rotationSent(50.000015F);
        require(history.needsSettling(), "screenshot-sized floating point difference repeats");
        history.rotationSent(50.001F);
        require(!history.needsSettling(), "latest emitted look supersedes earlier look");

        history.reset();
        history.rotationSent(0);
        history.rotationSent(45);
        history.placementSent();
        // UseOn consumes the preceding look; the closing flying starts the next interval.
        history.rotationSent(90);
        require(history.needsSettling(), "post-flying look belongs to next placement");
        history.placementSent();
        require(!history.needsSettling(), "sent placement consumes the pending look once");

        history.reset();
        history.rotationSent(179);
        history.rotationSent(-179);
        history.placementSent();
        history.rotationSent(179);
        require(history.needsSettling(), "raw 358 degree deltas are not wrapped to 2");

        history.reset();
        history.rotationSent(0);
        history.rotationSent(2);
        history.placementSent();
        history.rotationSent(4);
        require(!history.needsSettling(), "small repeated deltas do not require settling");

        // At large yaw values, float precision may exceed the mouse step.
        for (float origin : new float[] {0, 180, -180, 100_000_000, -100_000_000}) {
            history.reset();
            history.rotationSent(origin);
            history.rotationSent(origin + 8);
            history.placementSent();
            history.rotationSent(origin + 16);
            require(history.needsSettling(), "large-yaw fixture repeats");
            float nextYaw = history.settlingYaw(0.15F);
            require(Float.isFinite(nextYaw) && nextYaw != origin + 16, "settling changes finite yaw");
            history.rotationSent(nextYaw);
            require(!history.needsSettling(), "settling survives float precision loss");
        }
        history.reset();
        history.rotationSent(90);
        require(!history.needsSettling(), "new connection has no inherited placement history");

        history.reset();
        history.rotationSent(0);
        history.rotationSent(45);
        Float varied = history.variedYaw(90, .15F, yaw -> yaw, yaw -> yaw < 90);
        require(varied != null && varied < 90, "try opposite direction when first variant misses face");
        require(history.variedYaw(90, .15F, yaw -> yaw, yaw -> false) == null,
                "never select an unreachable alternative");
        require(history.variedYaw(90, .15F, yaw -> 90F, yaw -> true) == null,
                "quantization collapsing all alternatives cannot bypass repetition guard");
        require(history.wouldRepeatNext(90), "pending placement delta checked before placement is sent");
        history.placementSent();
        history.rotationSent(varied);
        require(!history.needsSettling(), "preemptive variation allows next placement without waiting");

        history.reset();
        history.rotationSent(0);
        float emittedYaw = 20;
        history.rotationSent(emittedYaw);
        for (int i = 0; i < 2_000; i++) {
            float base = emittedYaw;
            float desired = base + 20;
            Float selected = history.variedYaw(desired, .15F,
                    yaw -> base + Math.round((yaw - base) / .15F) * .15F,
                    yaw -> Math.abs(yaw - desired) <= .61F);
            require(selected != null, "bounded valid face has an alternative");
            require(!history.needsSettling(), "continuous placement does not need a settling tick");
            history.placementSent();
            history.rotationSent(selected);
            emittedYaw = selected;
        }
        System.out.println("SCAFFOLD_ROTATIONS_VERIFIED assertions=" + assertions);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
