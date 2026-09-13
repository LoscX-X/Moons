package com.blanoir.moons.client.module.impl.combat.silentaura.legacy;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.impl.combat.silentaura.*;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.BlockingUse;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.combat.MeleeHitConfirmation;
import com.blanoir.moons.client.utils.render.AnimationPulse;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;

/** Legacy use/release/attack/reblock lifecycle; damage feedback owns only the visual swings. */
public final class LegacyBlock {
    private static final BlockingUse USE = new BlockingUse();
    private static final AnimationPulse BLOCK_HIT = new AnimationPulse(120);
    private static final MeleeHitConfirmation HITS = new MeleeHitConfirmation();
    private static int releasedTick = Integer.MIN_VALUE;
    private static int blockedTick = Integer.MIN_VALUE;
    private static int attackReadyTick = Integer.MIN_VALUE;
    private static int reblockTick = Integer.MIN_VALUE;
    private static boolean lastLegacy;

    private LegacyBlock() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("LegacyBlock.context", event -> discard());
        EventBus.TICK.register("LegacyBlock.tick", event -> refresh(event.client()));
        EventBus.PLAYER_UPDATE.register(
                "LegacyBlock.block",
                EventPriority.HIGHEST,
                event -> {
                    if (!event.isCancelled()) block(event.client());
                });
        EventBus.ATTACK_INPUT_PRE.register(
                "LegacyBlock.beforeAttack",
                EventPriority.HIGHEST,
                event -> {
                    if (!event.isCancelled()
                            && isEnabled()
                            && (USE.owned() || target(event.client()) != null)
                            && !beforeAttack(event.client())) event.cancel();
                });
        EventBus.PACKET_SEND_POST.register(
                "LegacyBlock.attackSent",
                event -> {
                    if (!(event.packet() instanceof ServerboundAttackPacket attack)) return;
                    Minecraft client = Minecraft.getInstance();
                    if (!legacy() || !SilentAuraConfig.blockVisual() || target(client) == null)
                        return;
                    var attacked = client.level.getEntity(attack.entityId());
                    if (attacked instanceof LivingEntity
                            && CombatReach.within(
                                    client, attacked, SilentAuraConfig.blockRange())) {
                        HITS.sent(attack.entityId(), System.nanoTime());
                    }
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "LegacyBlock.damage",
                event -> {
                    if (!(event.packet() instanceof ClientboundDamageEventPacket damage)) return;
                    Minecraft client = Minecraft.getInstance();
                    if (legacy()
                            && SilentAuraConfig.blockVisual()
                            && canRun(client)
                            && event.listener() == client.getConnection()
                            && HITS.confirm(
                                    damage.entityId(),
                                    damage.sourceCauseId(),
                                    damage.sourceDirectId(),
                                    client.player.getId(),
                                    System.nanoTime())) {
                        BLOCK_HIT.enqueue();
                    }
                });
        EventBus.USE_INPUT_PRE.register(
                "LegacyBlock.use",
                EventPriority.HIGHEST,
                event -> {
                    if (SilentPacketRotation.isInvokingSimulatedUse()
                            || USE.owned() && !USE.matches(event.client())) {
                        reset(event.client());
                    } else if (USE.owned()
                            || attackPending()
                            || canRun(event.client()) && reblockTick != Integer.MIN_VALUE
                            || ClientReady.world(event.client())
                                    && releasedTick == SilentAuraCombat.tickId()) {
                        // The owned use request already holds the sword block.
                        event.cancel();
                    }
                });
    }

    private static boolean legacy() {
        return SilentAuraConfig.legacyCombat();
    }

    private static boolean canRun(Minecraft client) {
        return isEnabled()
                && ClientReady.aliveGameplay(client)
                && client.player.getMainHandItem().is(ItemTags.SWORDS)
                && controls(client);
    }

    private static LivingEntity target(Minecraft client) {
        if (!canRun(client)) return null;
        LivingEntity target = SilentAuraRuntime.currentTarget(client);
        return target != null
                        && target.isAlive()
                        && client.level.getEntity(target.getId()) == target
                        && CombatReach.within(client, target, SilentAuraConfig.blockRange())
                ? target
                : null;
    }

    /** Chooses Aura's combat behavior even when its own blocking toggle is off. */
    public static boolean controls(Minecraft client) {
        return SilentAuraRuntime.activationHeld(client);
    }

    private static boolean refresh(Minecraft client) {
        boolean legacy = legacy();
        if (lastLegacy != legacy) reset(client);
        lastLegacy = legacy;
        if (!legacy) return false;
        if (USE.refresh(client)) {
            releasedTick = SilentAuraCombat.tickId();
            blockedTick = attackReadyTick = reblockTick = Integer.MIN_VALUE;
        }
        if (!canRun(client)
                || client.player.isUsingItem() && !USE.ownsNativeUse(client)
                || SilentPacketRotation.isBusy()
                || PlacementCoordinator.busy()) {
            reset(client);
            return false;
        }
        if (target(client) == null) {
            resetBlocking(client);
            return false;
        }
        return true;
    }

    private static void block(Minecraft client) {
        if (!refresh(client)) return;
        int tick = SilentAuraCombat.tickId();
        if (tick == releasedTick) return;
        if (attackPending() && tick <= attackReadyTick + 2) return;
        if (tick < reblockTick) return;
        attackReadyTick = Integer.MIN_VALUE;
        if (USE.owned()) return;
        if (!USE.canStart(client)) return;
        // Resolve the current action/movement window, never the preceding sent rotation.
        RotationManager.Decision decision = RotationManager.resolve();
        if (decision == null) return;
        Rotation rotation = decision.rotation();
        if (!RotationLease.holdManual(rotation)) return;
        if (USE.start(client, rotation)) blockedTick = tick;
    }

    public static boolean attackPending() {
        return attackReadyTick != Integer.MIN_VALUE;
    }

    /** A successful release opens a later attack window, never an attack in the same tick. */
    public static boolean beforeAttack(Minecraft client) {
        if (!isEnabled()) return true;
        int tick = SilentAuraCombat.tickId();
        if (tick == blockedTick || tick == releasedTick || tick < reblockTick) return false;
        if (USE.owned() && USE.stop(client)) {
            releasedTick = tick;
            attackReadyTick = tick + 1;
            return false;
        }
        return !attackPending() || tick >= attackReadyTick;
    }

    public static void afterAttack(Minecraft client) {
        attackReadyTick = Integer.MIN_VALUE;
        reblockTick = SilentAuraCombat.tickId() + 1;
    }

    public static boolean shouldRenderBlock(Minecraft client) {
        // Let confirmed killing blows finish even after the selector loses the dead target.
        return SilentAuraConfig.blockVisual()
                && (target(client) != null || legacy() && canRun(client) && BLOCK_HIT.active());
    }

    public static boolean attackAnimationOnly() {
        return isEnabled();
    }

    public static double animationProgress() {
        return BLOCK_HIT.active() ? BLOCK_HIT.progress() : 0.0;
    }

    public static void reset(Minecraft client) {
        BLOCK_HIT.reset();
        HITS.reset();
        resetBlocking(client);
    }

    private static void resetBlocking(Minecraft client) {
        if (USE.stop(client)) releasedTick = SilentAuraCombat.tickId();
        blockedTick = attackReadyTick = reblockTick = Integer.MIN_VALUE;
    }

    private static void discard() {
        USE.discard();
        BLOCK_HIT.reset();
        HITS.reset();
        releasedTick = Integer.MIN_VALUE;
        blockedTick = attackReadyTick = reblockTick = Integer.MIN_VALUE;
    }

    public static boolean isEnabled() {
        return legacy() && SilentAuraConfig.enabled() && SilentAuraConfig.block();
    }
}
