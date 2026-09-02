package com.blanoir.moons.client.management.combat;

import com.blanoir.moons.client.config.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Confirms charged melee attempts against server damage feedback before they
 * enter the player's recorded critical-hit rate.
 */
public final class CriticalHitTracker {
    private static final String SAMPLE_KEY = "hitestimate.recordedSamples";
    private static final String CRITICAL_KEY = "hitestimate.recordedCriticals";
    private static final float MIN_RECORDED_CHARGE = 0.85F;
    private static final float VANILLA_CRITICAL_CHARGE = 0.90F;
    private static final long FEEDBACK_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long PREPARE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_WEIGHTED_SAMPLES = 1_000;

    private static final Map<Object, PreparedAttack> PREPARED = new IdentityHashMap<>();
    private static final ArrayDeque<PendingAttack> PENDING = new ArrayDeque<>();
    private static int samples = Math.max(0, Settings.getInt(SAMPLE_KEY, 0));
    private static int criticals = Math.max(0,
            Math.min(samples, Settings.getInt(CRITICAL_KEY, 0)));

    private CriticalHitTracker() {
    }

    /** Snapshots charge/critical state before lag modules may queue the packet. */
    public static synchronized void recordAttackSendPre(Object packet, int targetId) {
        if (packet == null) return;
        long now = System.nanoTime();
        expire(now);
        if (PREPARED.containsKey(packet)) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) return;

        Entity target = client.level.getEntity(targetId);
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return;
        float charge = client.player.getAttackStrengthScale(0.5F);

        // Exact Player.canCriticalAttack movement predicate. Do not route this
        // through combat target filtering: recorded statistics also include a
        // valid hit on a neutral/friendly LivingEntity.
        boolean critical = charge > VANILLA_CRITICAL_CHARGE
                && client.player.fallDistance > 0.0F
                && !client.player.onGround()
                && !client.player.onClimbable()
                && !client.player.isInWater()
                && !client.player.isMobilityRestricted()
                && !client.player.isPassenger()
                && !client.player.isSprinting();
        PREPARED.put(packet, new PreparedAttack(
                targetId,
                charge + 1.0E-4F >= MIN_RECORDED_CHARGE,
                critical,
                now));
    }

    /** Called after the exact ATTACK packet has actually left the connection. */
    public static synchronized void recordAttackSendPost(Object packet, int targetId) {
        long now = System.nanoTime();
        expire(now);
        PreparedAttack prepared = PREPARED.remove(packet);
        if (prepared == null) {
            recordAttackSendPre(packet, targetId);
            prepared = PREPARED.remove(packet);
        }
        if (prepared == null || !prepared.chargeQualified()) return;
        PENDING.addLast(new PendingAttack(
                prepared.targetId(), prepared.critical(), now));
    }

    /**
     * Called only for an applied server damage packet. sourceCauseId must be
     * the local player, so unrelated damage to the same target cannot confirm
     * one of our attempts.
     */
    public static synchronized void confirmDamageApplied(int targetId, int sourceCauseId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null
                || sourceCauseId != client.player.getId()) return;
        long now = System.nanoTime();
        expire(now);

        PendingAttack confirmed = null;
        for (Iterator<PendingAttack> iterator = PENDING.iterator(); iterator.hasNext();) {
            PendingAttack candidate = iterator.next();
            if (candidate.targetId() == targetId) {
                confirmed = candidate;
                iterator.remove();
                break;
            }
        }
        if (confirmed == null) return;

        if (samples >= MAX_WEIGHTED_SAMPLES) {
            samples = Math.max(1, samples / 2);
            criticals = Math.min(samples, criticals / 2);
        }
        samples++;
        if (confirmed.critical()) criticals++;
        persist();
    }

    public static synchronized int samples() {
        return samples;
    }

    public static synchronized double recordedPercentOr(double fallback) {
        return samples <= 0 ? fallback : criticals * 100.0D / samples;
    }

    private static void expire(long now) {
        PREPARED.values().removeIf(
                prepared -> now - prepared.createdNanos() > PREPARE_TIMEOUT_NANOS);
        while (!PENDING.isEmpty()
                && now - PENDING.peekFirst().sentNanos() > FEEDBACK_TIMEOUT_NANOS) {
            PENDING.removeFirst();
        }
    }

    private static void persist() {
        Settings.beginBatch();
        try {
            Settings.setInt(SAMPLE_KEY, samples);
            Settings.setInt(CRITICAL_KEY, criticals);
        } finally {
            Settings.endBatch();
        }
    }

    private record PendingAttack(int targetId, boolean critical, long sentNanos) {
    }

    private record PreparedAttack(
            int targetId,
            boolean chargeQualified,
            boolean critical,
            long createdNanos
    ) {
    }
}
