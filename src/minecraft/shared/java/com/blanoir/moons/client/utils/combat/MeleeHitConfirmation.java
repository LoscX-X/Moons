package com.blanoir.moons.client.utils.combat;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Matches actual melee sends to individual server damage notifications, including delayed hits. */
public final class MeleeHitConfirmation {
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private final ArrayDeque<Attempt> pending = new ArrayDeque<>();

    public synchronized void sent(int targetId, long now) {
        expire(now);
        pending.addLast(new Attempt(targetId, now));
    }

    public synchronized boolean confirm(
            int targetId, int causeId, int directId, int playerId, long now) {
        expire(now);
        // Old-server protocol translation can omit both sources (-1). In that case only
        // target/time correlation is available; a hurt notification alone cannot prove who hit.
        if (causeId != -1 && causeId != playerId || directId != -1 && directId != playerId) {
            return false;
        }
        for (Iterator<Attempt> iterator = pending.iterator(); iterator.hasNext(); ) {
            if (iterator.next().targetId() == targetId) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public synchronized void reset() {
        pending.clear();
    }

    private void expire(long now) {
        while (!pending.isEmpty() && now - pending.peekFirst().sentAt() > TIMEOUT_NANOS) {
            pending.removeFirst();
        }
    }

    private record Attempt(int targetId, long sentAt) {}
}
