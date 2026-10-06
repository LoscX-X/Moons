package com.blanoir.moons.client.module.impl.combat.hitselect;

/** Per-opponent timing only. An accepted local attack is never treated as confirmed damage. */
public final class HitSelectCycle {
    public enum Gate {
        READY,
        FIRST_HIT,
        REPEAT,
        TRADE
    }

    public record Policy(int pauseMs, int firstMs, int tradeMs, boolean serverTime, int rttMs) {}

    private long lastAttempt = -1, waitStarted = -1, attackedAt = -1;
    private long damagedAt = -1, confirmedAttack = -1, incomingAt = -1;
    private boolean traded;

    public Gate evaluate(long now, Policy policy) {
        if (lastAttempt >= 0 && (now < lastAttempt || now - lastAttempt > 1500)) {
            waitStarted = -1;
            traded = false;
        }
        lastAttempt = now;
        long deadline = attackedAt < 0 ? -1 : attackedAt + policy.pauseMs();
        if (policy.serverTime() && damagedAt >= 0) {
            // Receipt -> next attack arrival spans our downlink plus uplink (RTT).
            // Retain one tick of uncertainty; missing latency means no compensation.
            long serverDeadline =
                    damagedAt + Math.max(0, policy.pauseMs() - Math.max(0, policy.rttMs() - 50));
            deadline =
                    confirmedAttack == attackedAt
                            ? serverDeadline
                            : Math.max(deadline, serverDeadline);
        }
        if (now < deadline) return Gate.REPEAT;
        if (waitStarted < 0) waitStarted = now;
        int wait = traded ? policy.tradeMs() : policy.firstMs();
        boolean counterHit =
                incomingAt >= 0
                        && incomingAt >= attackedAt
                        && incomingAt >= waitStarted - 250
                        && now - incomingAt <= 250;
        if (!counterHit && now - waitStarted < wait) return traded ? Gate.TRADE : Gate.FIRST_HIT;
        return Gate.READY;
    }

    public void attacked(long now) {
        attackedAt = lastAttempt = now;
        waitStarted = -1;
        traded = true;
    }

    public void targetDamaged(long now, boolean byUs) {
        damagedAt = now;
        if (byUs && attackedAt >= 0 && now >= attackedAt && now - attackedAt <= 2000)
            confirmedAttack = attackedAt;
    }

    public void incomingHit(long now) {
        incomingAt = now;
    }
}
