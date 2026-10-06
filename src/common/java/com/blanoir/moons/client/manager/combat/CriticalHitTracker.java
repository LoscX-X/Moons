package com.blanoir.moons.client.manager.combat;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.EventBus;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.potion.Potion;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Correlates melee attempts with legacy server hurt status before they
 * enter the player's recorded critical-hit rate.
 */
public final class CriticalHitTracker {
    private static final String SAMPLE_KEY = "hitestimate.recordedSamples";
    private static final String CRITICAL_KEY = "hitestimate.recordedCriticals";
    private static final long FEEDBACK_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long PREPARE_TIMEOUT_NANOS = 30_000_000_000L;
    private static final int MAX_WEIGHTED_SAMPLES = 1_000;

    private static final Map<Object, PreparedAttack> PREPARED = new IdentityHashMap<>();
    private static final ArrayDeque<PendingAttack> PENDING = new ArrayDeque<>();
    private static int samples = Math.max(0, Settings.getInt(SAMPLE_KEY, 0));
    private static int criticals = Math.max(0, Math.min(samples, Settings.getInt(CRITICAL_KEY, 0)));

    private CriticalHitTracker() {}

    public static void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "CriticalHitTracker.attackSending",
                event -> {
                    if (event.packet() instanceof C02PacketUseEntity attack
                            && attack.getAction() == C02PacketUseEntity.Action.ATTACK) {
                        var world = Minecraft.getMinecraft().theWorld;
                        if (world == null) return;
                        Entity target = attack.getEntityFromWorld(world);
                        if (target == null) return;
                        recordAttackSendPre(event.packet(), target.getEntityId());
                    }
                });
        EventBus.PACKET_SEND_POST.register(
                "CriticalHitTracker.attackSent",
                event -> {
                    if (event.packet() instanceof C02PacketUseEntity attack
                            && attack.getAction() == C02PacketUseEntity.Action.ATTACK) {
                        var world = Minecraft.getMinecraft().theWorld;
                        if (world == null) return;
                        Entity target = attack.getEntityFromWorld(world);
                        if (target == null) return;
                        recordAttackSendPost(event.packet(), target.getEntityId());
                    }
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "CriticalHitTracker.damageApplied",
                event -> {
                    if (event.packet() instanceof S19PacketEntityStatus damage
                            && damage.getOpCode() == 2) {
                        var world = Minecraft.getMinecraft().theWorld;
                        if (world == null) return;
                        Entity target = damage.getEntity(world);
                        if (target == null) return;
                        confirmDamageApplied(target.getEntityId(), -1);
                    }
                });
    }

    /** Snapshots critical movement state before lag modules may queue the packet. */
    public static synchronized void recordAttackSendPre(Object packet, int targetId) {
        if (packet == null) return;
        long now = System.nanoTime();
        expire(now);
        if (PREPARED.containsKey(packet)) return;
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        if (client == null || currentPlayer == null || currentLevel == null) return;

        Entity target = currentLevel.getEntityByID(targetId);
        if (!(target instanceof EntityLivingBase living) || !living.isEntityAlive()) return;

        // Exact EntityPlayer.attackTargetEntityWithCurrentItem movement predicate. Do not route
        // this
        // through combat target filtering: recorded statistics also include a
        // valid hit on a neutral/friendly LivingEntity.
        boolean critical =
                currentPlayer.fallDistance > 0.0F
                        && !currentPlayer.onGround
                        && !currentPlayer.isOnLadder()
                        && !currentPlayer.isInWater()
                        && !currentPlayer.isPotionActive(Potion.blindness)
                        && !currentPlayer.isRiding();
        PREPARED.put(packet, new PreparedAttack(targetId, critical, now));
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
        if (prepared == null) return;
        PENDING.addLast(new PendingAttack(prepared.targetId(), prepared.critical(), now));
    }

    /**
     * Legacy hurt status has no source id. This records target/time-correlated
     * attack estimates; simultaneous damage from another player cannot be distinguished.
     */
    public static synchronized void confirmDamageApplied(int targetId, int sourceCauseId) {
        Minecraft client = Minecraft.getMinecraft();
        var currentPlayer = client == null ? null : client.thePlayer;
        if (client == null
                || currentPlayer == null
                || sourceCauseId != -1 && sourceCauseId != currentPlayer.getEntityId()) return;
        long now = System.nanoTime();
        expire(now);

        PendingAttack confirmed = null;
        for (Iterator<PendingAttack> iterator = PENDING.iterator(); iterator.hasNext(); ) {
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
        PREPARED.values()
                .removeIf(prepared -> now - prepared.createdNanos() > PREPARE_TIMEOUT_NANOS);
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

    private record PendingAttack(int targetId, boolean critical, long sentNanos) {}

    private record PreparedAttack(int targetId, boolean critical, long createdNanos) {}
}
