package com.blanoir.moons.client.module.impl.player;

public final class AutoTotemVerification {
    public static void main(String[] args) {
        var history = new AutoTotemDanger();
        for (int tick = 0; tick < 80; tick++) {
            history.sample(tick, 10, 0, 0);
            require(history.incomingDamage(tick) == 0, "Stable half health must not invent danger");
        }
        require(
                AutoTotemDanger.damageBudget(6, false, true, 8) == 6,
                "Prediction ignores legacy threshold/subtract settings when fallback is off");
        require(
                AutoTotemDanger.damageBudget(12, true, true, 8) == 4,
                "Explicit fallback retains the configured damage margin");
        require(!AutoTotemDanger.lethal(10, 9.99F), "Nonlethal damage does not swap");
        require(AutoTotemDanger.lethal(10, 10), "Exact lethal damage swaps");
        require(!AutoTotemDanger.lethal(14, 10), "Absorption contributes to survival");
        require(!AutoTotemDanger.lethal(10, Float.NaN), "Invalid estimates are not lethal");
        require(
                AutoTotemDanger.withinWindow(10) && AutoTotemDanger.withinWindow(0),
                "Include the 10-tick boundary and instant threats");
        require(
                !AutoTotemDanger.withinWindow(11)
                        && !AutoTotemDanger.withinWindow(Integer.MAX_VALUE),
                "Exclude distant and unarmed explosions");

        history.reset();
        history.sample(0, 20, 0, 0);
        history.sample(1, 10, 0, 10);
        require(history.incomingDamage(1) == 0, "One isolated hit is not sustained DPS");
        history.sample(10, 10, 0, 1);
        history.sample(11, 4, 0, 10);
        require(history.incomingDamage(11) == 6, "Two hits establish the next 10-tick hit");
        require(
                AutoTotemDanger.lethal(4, history.incomingDamage(11)),
                "React before the next lethal hit");
        require(history.incomingDamage(24) == 0, "A stopped attack stream expires");
        history.sample(0, 20, 0, 0);
        require(history.incomingDamage(0) == 0, "Tick rollback clears the previous fight");

        history.reset();
        history.sample(0, 20, 0, 0);
        history.sample(1, 16, 0, 10);
        history.sample(21, 12, 0, 10);
        require(history.incomingDamage(30) == 0, "A hit 11 ticks away is outside the window");
        require(history.incomingDamage(31) == 4, "A hit 10 ticks away enters the window");

        history.reset();
        history.sample(0, 20, 0, 0);
        history.sample(1, 18, 0, 10);
        history.sample(2, 15, 0, 9);
        require(history.incomingDamage(2) == 0, "One multi-packet damage burst is not two attacks");

        history.reset();
        history.sample(0, 20, 8, 0);
        history.sample(1, 20, 4, 10);
        history.sample(11, 20, 4, 10);
        history.sample(12, 20, 0, 9);
        require(
                history.incomingDamage(12) == 0,
                "Absorption expiry during an old hurt animation is not another hit");
        history.reset();
        history.sample(0, 20, 8, 0);
        history.sample(1, 20, 4, 10);
        history.sample(10, 20, 4, 1);
        history.sample(11, 20, 0, 10);
        require(
                history.incomingDamage(11) == 4,
                "Real repeated hits through absorption are counted");
        history.reset();
        require(history.incomingDamage(12) == 0, "Disable/context change discards damage history");
        System.out.println("MOONS_AUTOTOTEM_VERIFIED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
