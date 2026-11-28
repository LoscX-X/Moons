package com.blanoir.tempos.client.manager.input.click;

import java.util.Random;

/** OpenVape's Normal/Extra/Extra+ timing, independent of platform input and wall clocks. */
public final class ClickTiming {
    public enum Mode {
        NORMAL,
        EXTRA,
        EXTRA_PLUS
    }

    private final Random random;
    private final ClickDrift drift;
    private long previousDelay;
    private boolean burst;
    private int burstLength;
    private int burstCount;
    private boolean fastPhase = true;
    private int fastLength;
    private int fastCount;
    private int slowLength;
    private int slowCount;

    public ClickTiming(long seed) {
        random = new Random(seed);
        drift = new ClickDrift(seed);
    }

    public long nextDelayMillis(long nowMillis, double first, double second, Mode mode) {
        double minimum = Math.max(1, Math.min(first, second));
        double maximum = Math.max(minimum, Math.max(first, second));
        double cps;
        if (minimum == maximum) cps = minimum;
        else if (minimum == Math.rint(minimum) && maximum == Math.rint(maximum))
            cps = minimum + 1 + random.nextInt((int) (maximum - minimum));
        else cps = minimum + random.nextDouble() * (maximum - minimum);
        if (mode == Mode.EXTRA_PLUS) return drift.nextDelayMillis(nowMillis, minimum, maximum);
        if (mode == Mode.NORMAL) return previousDelay = Math.max(1, (long) (1000 / cps));
        return extraDelay(cps);
    }

    private long extraDelay(double cps) {
        if (!burst) {
            previousDelay = Math.max(1, (long) (1000 / cps));
            if (random.nextInt(4) == 1) {
                burst = true;
                burstLength = 1 + random.nextInt(5);
            } else if (random.nextInt(10) != 1 && random.nextInt(10) == 1) {
                burst = true;
                burstLength = 5 + random.nextInt(10);
            }
        }
        if (burst && ++burstCount >= burstLength) {
            burstCount = 0;
            burst = false;
        }
        if (random.nextInt(48) % (fastPhase ? 6 : 10) == 0 && !burst)
            previousDelay += random.nextInt(45) + 40L;
        if (fastPhase) {
            if (++fastCount >= fastLength) {
                slowLength = 75 + random.nextInt(125);
                fastPhase = false;
                fastCount = 0;
            }
            return previousDelay + (random.nextInt(5) == 3 ? 50L : 25L);
        }
        if (++slowCount >= slowLength) {
            fastPhase = true;
            fastLength = 7 + random.nextInt(8);
            slowCount = 0;
        }
        return previousDelay;
    }
}
