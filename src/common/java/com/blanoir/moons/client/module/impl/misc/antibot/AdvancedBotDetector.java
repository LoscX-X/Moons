package com.blanoir.moons.client.module.impl.misc.antibot;

/** One evidence model per observed player; independent of server brands and custom settings. */
final class AdvancedBotDetector {
    private static final int OBSERVATION_TICKS = 20;
    private static final int CONFIRMATION_TICKS = 3;
    private static final int MISSING_PROFILE_TICKS = 40;
    private static final float PITCH_TOLERANCE = 360.0F / 256.0F;

    private int observedTicks;
    private int suspiciousTicks;
    private int missingProfileTicks;
    private boolean bot;

    void observe(Facts facts) {
        missingProfileTicks =
                facts.missingProfile()
                        ? Math.min(MISSING_PROFILE_TICKS, missingProfileTicks + 1)
                        : 0;
        observedTicks = Math.min(OBSERVATION_TICKS, observedTicks + 1);
        // Short-lived conflicting identities still need consecutive observations, but
        // must not wait for the ordinary entity-age grace period.
        boolean fastEvidence =
                !Float.isFinite(facts.pitch()) || (facts.missingProfile() && facts.duplicateName());
        if (!fastEvidence
                && (observedTicks < OBSERVATION_TICKS || facts.age() < OBSERVATION_TICKS)) {
            suspiciousTicks = 0;
            bot = false;
            return;
        }
        suspiciousTicks =
                (fastEvidence || hasCorroboratedEvidence(facts))
                        ? Math.min(CONFIRMATION_TICKS, suspiciousTicks + 1)
                        : 0;
        bot = suspiciousTicks >= CONFIRMATION_TICKS;
    }

    boolean isBot() {
        return bot;
    }

    private boolean hasCorroboratedEvidence(Facts facts) {
        // Equivalent angles and one packed-rotation step must not reject a player.
        float pitch = facts.pitch() % 360.0F;
        if (pitch >= 180.0F) pitch -= 360.0F;
        if (pitch < -180.0F) pitch += 360.0F;
        boolean illegalPitch = Math.abs(pitch) > 90.0F + PITCH_TOLERANCE;
        boolean missingProfile = missingProfileTicks >= MISSING_PROFILE_TICKS;

        // Missing player info alone is ambiguous. Rotation needs an independent signal;
        // ordinary skin, latency and stair movement must not add up to a rejected target.
        return illegalPitch && (missingProfile || facts.invalidGroundVl() >= 10);
    }

    record Facts(
            int age,
            float pitch,
            int invalidGroundVl,
            boolean missingProfile,
            boolean duplicateName) {}
}
