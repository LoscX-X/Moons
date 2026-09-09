package com.blanoir.moons.features;

import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.world.Scaffold;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Owns silent body and head interpolation for one runtime event adapter.
 *
 * <p>Changes only the render state. Packet rotations and feature selection remain
 * with their existing owners. State is scoped to the adapter; weak keys avoid
 * retaining avatars after their world is released.
 */
final class RenderRotationController {
    private final Map<Avatar, BodyRotationState> bodyRotations =
            Collections.synchronizedMap(new WeakHashMap<>());

    void apply(Avatar avatar, AvatarRenderState state, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || avatar.getId() != player.getId()) return;

        BodyRotationState rotation =
                bodyRotations.computeIfAbsent(avatar, ignored -> new BodyRotationState());
        float vanillaBodyYaw = state.bodyRot;
        float vanillaHeadYaw = vanillaBodyYaw + state.yRot;
        boolean packet = SilentPacketRotation.shouldApplyRotation();
        boolean scaffold = !packet && Scaffold.shouldApplyRotation();
        boolean aura = !packet && !scaffold && SilentAura.shouldApplyRotation();
        if (packet || aura || scaffold) {
            long now = System.nanoTime();
            if (!rotation.active) {
                rotation.bodyYaw = state.bodyRot;
                rotation.active = true;
                rotation.lastFrameNanos = now;
                rotation.velocity = 0.0F;
            }
            float seconds = frameSeconds(rotation, now);
            float silentYaw =
                    scaffold
                            ? Scaffold.getRenderYaw()
                            : aura ? SilentAura.getYaw() : SilentPacketRotation.getYaw();
            float silentPitch =
                    scaffold
                            ? Scaffold.getRenderPitch()
                            : aura ? SilentAura.getPitch() : SilentPacketRotation.getPitch();
            boolean fullLock = aura && SilentAura.isFullLockMode();
            if (fullLock) {
                updateFullLockRenderRotation(
                        rotation,
                        silentYaw,
                        silentPitch,
                        vanillaHeadYaw,
                        state.xRot,
                        player.tickCount,
                        partialTick);
                silentYaw = rotation.fullLockRenderYaw;
                silentPitch = rotation.fullLockRenderPitch;
            } else {
                rotation.fullLockRenderActive = false;
                rotation.fullLockRenderTick = Integer.MIN_VALUE;
            }
            boolean aggressiveBody = aura && SilentAura.isLockMode();
            if (aura && SilentAura.isCrossingTarget()) {
                // While the eye is inside the opponent, keep the absolute head
                // at its entry angle and let the body perform the horizontal
                // turn. This avoids the characteristic head-down/head-flip
                // frame produced by locking an interior point of the AABB.
                stepBodyYaw(rotation, SilentAura.getBodyYaw(), seconds, aggressiveBody);
            } else if (aggressiveBody) {
                // Lock is allowed to move the head quickly, so its body must
                // continuously pursue that same absolute yaw. The old slack
                // calculation only advanced a fraction of the remaining gap
                // and could leave the torso behind while both players strafed.
                stepBodyYaw(rotation, silentYaw, seconds, true);
            } else {
                float fromBody = Mth.wrapDegrees(silentYaw - rotation.bodyYaw);
                // The body must keep up with silent head snaps: a large head-body
                // delta both looks wrong to other players and feeds head/body
                // correlation checks, so react early and turn hard.
                float headSlack = 10.0F;
                float turn =
                        Math.max(
                                Math.max(0.0F, Math.abs(fromBody) - headSlack) * 0.85F,
                                Math.max(0.0F, Math.abs(fromBody) - 36.0F));
                stepBodyYaw(
                        rotation, rotation.bodyYaw + Math.copySign(turn, fromBody), seconds, false);
            }
            state.bodyRot = rotation.bodyYaw;
            state.yRot = Mth.wrapDegrees(silentYaw - state.bodyRot);
            state.xRot = silentPitch;
            return;
        }

        if (!rotation.active) return;
        rotation.fullLockRenderActive = false;
        rotation.fullLockRenderTick = Integer.MIN_VALUE;
        long now = System.nanoTime();
        float seconds = frameSeconds(rotation, now);
        float difference = Mth.wrapDegrees(vanillaBodyYaw - rotation.bodyYaw);
        stepBodyYaw(rotation, vanillaBodyYaw, seconds);
        state.bodyRot = rotation.bodyYaw;
        state.yRot = Mth.wrapDegrees(vanillaHeadYaw - state.bodyRot);
        if (Math.abs(difference) <= 0.5F) {
            rotation.active = false;
            rotation.lastFrameNanos = 0L;
            rotation.velocity = 0.0F;
        }
    }

    /** Interpolates the tick target in local render state without changing packets. */
    static void updateFullLockRenderRotation(
            BodyRotationState state,
            float targetYaw,
            float targetPitch,
            float fallbackYaw,
            float fallbackPitch,
            int tick,
            float partialTick) {
        if (!state.fullLockRenderActive) {
            state.fullLockRenderActive = true;
            state.fullLockRenderTick = tick;
            state.fullLockPreviousYaw = fallbackYaw;
            state.fullLockPreviousPitch = fallbackPitch;
        } else if (state.fullLockRenderTick != tick) {
            state.fullLockRenderTick = tick;
            state.fullLockPreviousYaw = state.fullLockTargetYaw;
            state.fullLockPreviousPitch = state.fullLockTargetPitch;
        }

        state.fullLockTargetYaw =
                state.fullLockPreviousYaw + Mth.wrapDegrees(targetYaw - state.fullLockPreviousYaw);
        state.fullLockTargetPitch = Mth.clamp(targetPitch, -90.0F, 90.0F);
        float progress = Mth.clamp(partialTick, 0.0F, 1.0F);
        state.fullLockRenderYaw =
                state.fullLockPreviousYaw
                        + Mth.wrapDegrees(state.fullLockTargetYaw - state.fullLockPreviousYaw)
                                * progress;
        state.fullLockRenderPitch =
                Mth.lerp(progress, state.fullLockPreviousPitch, state.fullLockTargetPitch);
    }

    static float frameSeconds(BodyRotationState state, long now) {
        float result =
                (float) Mth.clamp((now - state.lastFrameNanos) / 1_000_000_000.0D, 0.0D, 0.1D);
        state.lastFrameNanos = now;
        return result;
    }

    static void stepBodyYaw(BodyRotationState state, float targetYaw, float seconds) {
        stepBodyYaw(state, targetYaw, seconds, false);
    }

    static void stepBodyYaw(
            BodyRotationState state, float targetYaw, float seconds, boolean aggressive) {
        float difference = Mth.wrapDegrees(targetYaw - state.bodyYaw);
        float response = aggressive ? 28.0F : 14.0F;
        float maxSpeed = aggressive ? 900.0F : 420.0F;
        float desiredVelocity = Mth.clamp(difference * response, -maxSpeed, maxSpeed);
        float velocityChange = (aggressive ? 5_000.0F : 1_600.0F) * seconds;
        state.velocity =
                Mth.clamp(
                        desiredVelocity,
                        state.velocity - velocityChange,
                        state.velocity + velocityChange);
        float step = state.velocity * seconds;
        if (Math.signum(step) == Math.signum(difference) && Math.abs(step) > Math.abs(difference)) {
            step = difference;
            state.velocity = 0.0F;
        }
        state.bodyYaw += step;
    }

    static final class BodyRotationState {
        boolean active;
        float bodyYaw;
        long lastFrameNanos;
        float velocity;
        boolean fullLockRenderActive;
        int fullLockRenderTick = Integer.MIN_VALUE;
        float fullLockPreviousYaw;
        float fullLockPreviousPitch;
        float fullLockTargetYaw;
        float fullLockTargetPitch;
        float fullLockRenderYaw;
        float fullLockRenderPitch;
    }
}
