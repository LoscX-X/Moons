package com.blanoir.moons.client.render;

import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.smooth.BodyYawIntegration;
import com.blanoir.moons.client.utils.rotation.smooth.TickAngleInterpolation;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Owns silent body and head interpolation for one runtime event adapter.
 *
 * <p>Temporarily changes render rotation fields and restores them at the render exit. Packet rotations and feature selection remain
 * with their existing owners. State is scoped to the adapter; weak keys avoid
 * retaining avatars after their world is released.
 */
public final class RenderRotationController {
    private final Map<EntityLivingBase, BodyRotationState> bodyRotations =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final ThreadLocal<Deque<Capture>> captures = ThreadLocal.withInitial(ArrayDeque::new);

    public void begin(EntityLivingBase entity, float partialTick) {
        Deque<Capture> stack = captures.get();
        Capture capture = new Capture(entity);
        stack.push(capture);
        if (VisualModelCapture.active() || entity != Minecraft.getMinecraft().thePlayer) return;
        RenderPose pose = new RenderPose();
        pose.bodyRot = interpolate(entity.prevRenderYawOffset, entity.renderYawOffset, partialTick);
        pose.yRot =
                Mth.wrapDegrees(
                        interpolate(entity.prevRotationYawHead, entity.rotationYawHead, partialTick)
                                - pose.bodyRot);
        pose.xRot =
                entity.prevRotationPitch
                        + (entity.rotationPitch - entity.prevRotationPitch) * partialTick;
        apply(entity, pose, partialTick);
        entity.prevRenderYawOffset = entity.renderYawOffset = pose.bodyRot;
        entity.prevRotationYawHead = entity.rotationYawHead = pose.bodyRot + pose.yRot;
        entity.prevRotationPitch = entity.rotationPitch = pose.xRot;
    }

    public void end(EntityLivingBase entity) {
        Deque<Capture> stack = captures.get();
        if (stack.isEmpty()) return;
        Capture capture = stack.peek();
        if (capture.entity != entity)
            throw new IllegalStateException("Unbalanced living render boundary");
        stack.pop().restore();
    }

    private static float interpolate(float previous, float current, float partialTick) {
        return previous + Mth.wrapDegrees(current - previous) * partialTick;
    }

    private static final class RenderPose {
        float bodyRot, yRot, xRot;
    }

    private static final class Capture {
        final EntityLivingBase entity;
        final float body, previousBody, head, previousHead, pitch, previousPitch;

        Capture(EntityLivingBase entity) {
            this.entity = entity;
            body = entity.renderYawOffset;
            previousBody = entity.prevRenderYawOffset;
            head = entity.rotationYawHead;
            previousHead = entity.prevRotationYawHead;
            pitch = entity.rotationPitch;
            previousPitch = entity.prevRotationPitch;
        }

        void restore() {
            entity.renderYawOffset = body;
            entity.prevRenderYawOffset = previousBody;
            entity.rotationYawHead = head;
            entity.prevRotationYawHead = previousHead;
            entity.rotationPitch = pitch;
            entity.prevRotationPitch = previousPitch;
        }
    }

    public void apply(EntityLivingBase avatar, RenderPose state, float partialTick) {
        Minecraft client = Minecraft.getMinecraft();
        var player = client.thePlayer;
        if (player == null || avatar.getEntityId() != player.getEntityId()) return;

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
                        player.ticksExisted,
                        partialTick);
                silentYaw = rotation.fullLockRenderYaw;
                silentPitch = rotation.fullLockRenderPitch;
            } else {
                rotation.fullLockRenderActive = false;
                rotation.fullLockRenderTick = Integer.MIN_VALUE;
            }
            boolean aggressiveBody = aura && SilentAura.isLockMode();
            if (scaffold) {
                // Scaffold already smooths this yaw per frame. Share it with
                // the body instead of adding another response curve and dead zone.
                rotation.bodyYaw += Mth.wrapDegrees(silentYaw - rotation.bodyYaw);
                rotation.velocity = 0.0F;
            } else if (aura && SilentAura.isCrossingTarget()) {
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
        Rotation next =
                TickAngleInterpolation.interpolate(
                        new Rotation(state.fullLockPreviousYaw, state.fullLockPreviousPitch),
                        new Rotation(state.fullLockTargetYaw, state.fullLockTargetPitch),
                        progress);
        state.fullLockRenderYaw = next.yaw();
        state.fullLockRenderPitch = next.pitch();
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
        BodyYawIntegration.AxisMotion next =
                BodyYawIntegration.bodyYaw(
                        state.bodyYaw,
                        state.velocity,
                        targetYaw,
                        seconds,
                        aggressive ? 28.0F : 14.0F,
                        aggressive ? 900.0F : 420.0F,
                        aggressive ? 5_000.0F : 1_600.0F);
        state.bodyYaw = next.angle();
        state.velocity = next.velocity();
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
