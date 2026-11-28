package com.blanoir.tempos.client.manager.input.click;

import java.util.SplittableRandom;

/** Extra+ drift, short bursts, fatigue, repeated intervals and occasional pauses from OpenVape. */
final class ClickDrift {
    private final SplittableRandom random;
    private final long salt;
    private boolean initialized;
    private double currentCps, targetCps, targetOffset, fatigue, pauseOffset;
    private double repeatChance = .06;
    private long previousDelay, lastClick, nextDrift, nextTarget;
    private int variationClicks, burstClicks;

    ClickDrift(long seed) {
        long mixed = mix(seed ^ 0x9E3779B97F4A7C15L);
        random = new SplittableRandom(mixed);
        salt = mix(mixed - 3335678366873096957L);
    }

    private static long mix(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    private double normal() {
        double sum = 0;
        for (int i = 0; i < 6; i++) sum += random.nextDouble();
        return (sum - 3) * 1.22474487139;
    }

    private long duration(long now, int min, int max) {
        long time = (now ^ salt) & 0xFFFFL;
        int span = max - min + 1;
        return min + (Math.abs((int) time) + random.nextInt(span)) % span;
    }

    private double noise() {
        double sample = normal(), roll = random.nextDouble();
        if (roll < .12) return (random.nextDouble() - .5) * 3;
        if (roll < .17) return sample * (1.4 + random.nextDouble() * .6);
        return sample;
    }

    long nextDelayMillis(long now, double first, double second) {
        // The reference compensates for its jitter/fatigue distribution with this scale.
        double min = Math.max(.5, first * 1.5);
        double max = Math.max(min + .1, second * 1.5);
        if (!initialized) {
            targetCps = (min + max) * .5;
            currentCps = clamp(targetCps + normal() * .25, min, max);
            initialized = true;
            nextDrift = now + duration(now, 1200, 3200);
            nextTarget = now + duration(now, 30000, 90000);
            targetOffset = (random.nextDouble() * 2 - 1) * .75;
            targetCps = clamp((min + max) * .5 + targetOffset, min, max);
            lastClick = now;
        }
        long idle = lastClick == 0 ? 0 : now - lastClick;
        if (idle >= 1200) {
            fatigue = Math.max(0, fatigue - .004 * (Math.min(5000L, idle) / 10.0));
            burstClicks = random.nextDouble() < .7 ? 2 + random.nextInt(4) : 0;
            repeatChance = .03 + random.nextDouble() * .06;
            pauseOffset = random.nextDouble() * .04;
        }
        if (++variationClicks > 80 + random.nextInt(120)) {
            repeatChance = .03 + random.nextDouble() * .06;
            pauseOffset = random.nextDouble() * .04;
            variationClicks = 0;
        }
        if (now >= nextDrift) {
            currentCps += .25 * (targetCps - currentCps) + .45 * normal();
            if (random.nextDouble() < .03) currentCps += (random.nextDouble() - .5) * 1.2;
            currentCps = clamp(currentCps, min, max);
            nextDrift = now + duration(now, 1200, 3200);
        }
        if (now >= nextTarget) {
            targetOffset = (random.nextDouble() * 2 - 1) * .75;
            targetCps = clamp((min + max) * .5 + targetOffset, min, max);
            nextTarget = now + duration(now, 30000, 90000);
        }
        boolean burst = burstClicks > 0;
        double effectiveCps = currentCps;
        if (burst) {
            effectiveCps *= 1 + .05 * (.4 + .8 * random.nextDouble());
            burstClicks--;
        }
        effectiveCps = clamp(effectiveCps * (1 - Math.min(.4, fatigue)), min, max);
        double delay = 1000 / effectiveCps * Math.exp(.24 * noise());
        int jitter = 35 + random.nextInt(11) - 5;
        delay += random.nextInt(Math.max(1, jitter) + 1);
        if (burst) delay += random.nextInt(15);
        if (previousDelay > 0 && random.nextDouble() < repeatChance)
            delay = previousDelay + random.nextInt(7) - 3L;
        if (random.nextDouble() < .07) delay *= .7 + random.nextDouble() * .2;
        if (random.nextDouble() < Math.min(.04 + .12 * fatigue + pauseOffset, .18))
            delay += 50 + random.nextInt(101);
        fatigue = Math.min(.4, fatigue + .015);
        lastClick = now;
        return previousDelay = (long) Math.max(1, Math.min(delay, Integer.MAX_VALUE));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
