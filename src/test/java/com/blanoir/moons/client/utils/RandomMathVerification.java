package com.blanoir.moons.client.utils;

import com.blanoir.moons.client.utils.math.RandomMath;

/** Probability and range contracts, including limits that overflow exclusive upper bounds. */
public final class RandomMathVerification {
    private static int checks;

    private RandomMathVerification() {}

    public static void main(String[] args) {
        probabilityBounds();
        ranges();
        accumulatedChance();
        seededAndSecureSources();
        System.out.println("RandomMath verification passed: " + checks + " assertions");
    }

    private static void probabilityBounds() {
        for (double value :
                new double[] {
                    -1, 0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
                }) {
            check(!RandomMath.chance(value), "invalid or zero fractional chance cannot trigger");
            check(!RandomMath.chancePercent(value), "invalid or zero percent cannot trigger");
        }
        check(
                RandomMath.chance(1) && RandomMath.chance(2),
                "finite full probability always triggers");
        check(
                RandomMath.chancePercent(100) && RandomMath.chancePercent(200),
                "finite full percent always triggers");
        check(RandomMath.between(0.75D, 0.75D) == 0.75D, "fixed inclusive variation remains exact");
        check(
                RandomMath.nextDouble(0.75D, 0.75D) == 0.75D,
                "fixed half-open variation remains exact");
        check(
                RandomMath.betweenInclusive(Integer.MAX_VALUE, Integer.MAX_VALUE)
                        == Integer.MAX_VALUE,
                "fixed maximum integer");
        check(
                RandomMath.betweenInclusive(Long.MAX_VALUE, Long.MAX_VALUE) == Long.MAX_VALUE,
                "fixed maximum long");
        expectInvalidBounds(() -> RandomMath.between(Double.NaN, 1));
        expectInvalidBounds(() -> RandomMath.nextDouble(0, Double.POSITIVE_INFINITY));
    }

    private static void ranges() {
        for (int sample = 0; sample < 1024; sample++) {
            double unit = RandomMath.nextDouble();
            check(unit >= 0 && unit < 1, "unit samples exclude one");
            double halfOpen = RandomMath.nextDouble(0.08D, 0.05D);
            check(
                    halfOpen >= 0.05D && halfOpen < 0.08D,
                    "reversed half-open bounds retain endpoint semantics");
            double inclusive = RandomMath.between(0.08D, 0.05D);
            check(
                    inclusive >= 0.05D && inclusive <= 0.08D,
                    "inclusive double bounds remain ordered");
            double wide = RandomMath.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE);
            check(
                    Double.isFinite(wide) && wide >= -Double.MAX_VALUE && wide < Double.MAX_VALUE,
                    "wide half-open ranges never overflow or include the upper endpoint");
            double maximum = RandomMath.between(Math.nextDown(Double.MAX_VALUE), Double.MAX_VALUE);
            check(
                    Double.isFinite(maximum)
                            && maximum >= Math.nextDown(Double.MAX_VALUE)
                            && maximum <= Double.MAX_VALUE,
                    "maximum finite inclusive doubles remain sampleable");
            int integer = RandomMath.betweenInclusive(Integer.MAX_VALUE, Integer.MAX_VALUE - 2);
            check(integer >= Integer.MAX_VALUE - 2, "inclusive maximum integer does not overflow");
            long value = RandomMath.betweenInclusive(Long.MAX_VALUE, Long.MAX_VALUE - 2);
            check(value >= Long.MAX_VALUE - 2, "inclusive maximum long does not overflow");
            long crossingZero = RandomMath.betweenInclusive(-1L, Long.MAX_VALUE);
            check(crossingZero >= -1L, "long ranges crossing the signed span remain bounded");
            int signed = RandomMath.betweenInclusive(Integer.MIN_VALUE, Integer.MAX_VALUE);
            check(
                    (long) signed >= Integer.MIN_VALUE && (long) signed <= Integer.MAX_VALUE,
                    "the complete integer domain is sampleable");
            RandomMath.betweenInclusive(Long.MIN_VALUE, Long.MAX_VALUE);
        }
    }

    private static void accumulatedChance() {
        var quarter = new RandomMath.PercentAccumulator();
        for (int attempt = 1; attempt <= 20; attempt++) {
            check(
                    quarter.test(25) == (attempt % 4 == 0),
                    "25-percent cadence retains every fourth trigger");
        }
        var changing = new RandomMath.PercentAccumulator();
        check(!changing.test(25), "partial progress does not immediately trigger");
        check(
                !changing.test(0) && !changing.test(Double.NaN),
                "disabled/invalid trials do not advance progress");
        check(changing.test(75), "changed probability retains previous partial progress");
        check(!changing.test(25), "a completed cadence starts a new cycle");
        var independent = new RandomMath.PercentAccumulator();
        check(!independent.test(75), "consumers have independent accumulated state");
        check(
                independent.test(100) && independent.test(100),
                "full probability remains unconditional");
    }

    private static void seededAndSecureSources() {
        check(
                RandomMath.seededSigned(0, 0) == -1.0D,
                "the existing deterministic seed mapping is retained");
        for (long index = -20; index <= 20; index++) {
            double first = RandomMath.seededSigned(index, 7L);
            RandomMath.nextLong();
            RandomMath.nextBoolean();
            check(
                    first == RandomMath.seededSigned(index, 7L),
                    "seeded variation ignores unrelated samples");
            check(first >= -1 && first < 1, "seeded variation remains signed and bounded");
        }
        check(
                RandomMath.secureBytes(32).length == 32,
                "credentials retain a 32-byte cryptographic source");
        check(RandomMath.secureBytes(0).length == 0, "empty secure request remains empty");
        var id = RandomMath.uuid();
        check(id.version() == 4 && id.variant() == 2, "session IDs retain random UUID semantics");
    }

    private static void expectInvalidBounds(Runnable action) {
        try {
            action.run();
            throw new AssertionError("non-finite bounds were accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
