package com.blanoir.moons.client.module.impl.misc.antibot;

/** One evidence model per observed player; independent of server brands and custom settings. */
final class AdvancedBotDetector {
    private static final int OBSERVATION_TICKS = 20;
    private static final int CONFIRMATION_TICKS = 3;
    private static final int SCORE_THRESHOLD = 3;
    private static final int MISSING_PROFILE_TICKS = 40;

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
        if (observedTicks < OBSERVATION_TICKS || facts.age() < OBSERVATION_TICKS) {
            suspiciousTicks = 0;
            bot = false;
            return;
        }
        suspiciousTicks =
                score(facts) >= SCORE_THRESHOLD
                        ? Math.min(CONFIRMATION_TICKS, suspiciousTicks + 1)
                        : 0;
        bot = suspiciousTicks >= CONFIRMATION_TICKS;
    }

    boolean isBot() {
        return bot;
    }

    private int score(Facts facts) {
        // Broken entity data or a persistently absent player profile is strong evidence.
        // Entity IDs are opaque server identifiers, not evidence of authenticity.
        int score = !Float.isFinite(facts.pitch()) || Math.abs(facts.pitch()) > 90.0F ? 3 : 0;
        if (missingProfileTicks >= MISSING_PROFILE_TICKS) score += 3;
        else if (facts.duplicateName()) score += 2;

        // Movement and synthetic-looking profiles need corroboration. A skinless player,
        // low ping, duplicate name or on-ground movement alone must not reject a target.
        // Stairs and slabs can move a grounded player vertically. Combined with
        // missing skins/low ping this still must not be enough to reject them.
        if (facts.invalidGroundVl() >= 10) score++;
        if (facts.emptyProperties() && facts.latency() >= 0 && facts.latency() < 2) score++;
        return score;
    }

    record Facts(
            int age,
            int entityId,
            float pitch,
            int invalidGroundVl,
            boolean missingProfile,
            boolean duplicateName,
            boolean emptyProperties,
            int latency) {}
}
