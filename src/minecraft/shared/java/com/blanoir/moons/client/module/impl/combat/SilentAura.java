package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.management.combat.CombatDecisionEngine;
import com.blanoir.moons.client.module.impl.combat.critical.Critical;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraPlacementDebugger;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.module.impl.render.Animations;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Compatibility facade; behavior is isolated in the silentaura package. */
public final class SilentAura {
    private SilentAura() {}

    public static void init() {
        Animations.bindAuraState(SilentAura::isEnabled, SilentAura::shouldRenderBlock);
        SilentAuraRuntime.init();
        SilentAuraPlacementDebugger.init();
        EventBus.HUD_RENDER.register("SilentAura.debugger", SilentAura::drawDebugger);
    }

    public static boolean isActivationHeld(Minecraft client) {
        return SilentAuraRuntime.activationHeld(client);
    }

    public static boolean shouldApplyRotation() {
        return SilentAuraRuntime.shouldApplyRotation();
    }

    public static boolean shouldApplyManualUseRotation() {
        return SilentAuraRuntime.shouldApplyManualUseRotation();
    }

    public static float getManualUseYaw() {
        return SilentAuraRuntime.manualUseYaw();
    }

    public static float getManualUsePitch() {
        return SilentAuraRuntime.manualUsePitch();
    }

    public static boolean shouldCorrectMovement() {
        return SilentAuraRuntime.shouldCorrectMovement();
    }

    public static boolean shouldSuppressBlockBreaking() {
        return SilentAuraRuntime.shouldSuppressBlockBreaking();
    }

    public static boolean shouldSuppressUseAction(Minecraft client) {
        return SilentAuraRuntime.shouldSuppressUseAction(client);
    }

    public static boolean isLockMode() {
        return SilentAuraConfig.lockMode();
    }

    public static boolean isFullLockMode() {
        return SilentAuraConfig.fullLockMode();
    }

    public static float getYaw() {
        return SilentAuraRuntime.yaw();
    }

    public static float getPitch() {
        return SilentAuraRuntime.pitch();
    }

    public static float getPacketYaw() {
        return SilentAuraRuntime.packetYaw();
    }

    public static float getPacketPitch() {
        return SilentAuraRuntime.packetPitch();
    }

    public static boolean isCrossingTarget() {
        return SilentAuraRuntime.crossingTarget();
    }

    public static float getBodyYaw() {
        return SilentAuraRuntime.bodyYaw();
    }

    public static float getMovementYaw() {
        return SilentAuraRuntime.movementYaw();
    }

    public static LivingEntity currentTarget(Minecraft client) {
        return SilentAuraRuntime.currentTarget(client);
    }

    public static boolean isEnabled() {
        return SilentAuraConfig.enabled();
    }

    /** Render-time truth; avoids depending on FRAME vs hand-submit ordering. */
    public static boolean shouldRenderBlock(Minecraft client) {
        return SilentAuraConfig.block() && isActivationHeld(client) && hasLockedTarget(client);
    }

    /** Visual blocking follows target ownership, not the attack cooldown/range gate. */
    public static boolean hasLockedTarget(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return false;
        LivingEntity target = currentTarget(client);
        return target != null && target.isAlive();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (value)
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.SILENT_AURA);
        SilentAuraConfig.enabled(value);
        SilentAuraRuntime.reset(client);
        ClientChat.send(client, "SilentAura " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int showStatus(Minecraft client) {
        boolean fullLock = SilentAuraConfig.fullLockMode();
        String aimProfile =
                fullLock
                        ? "full_lock/center-corridor"
                        : SilentAuraConfig.aimMode() + "/" + SilentAuraConfig.aimPoint();
        double activePrediction =
                fullLock ? SilentAuraConfig.fullLockPrediction() : SilentAuraConfig.prediction();
        ClientChat.send(
                client,
                String.format(
                        Locale.ROOT,
                        "SilentAura %s | range %.2f+%.2f | FOV %.0f | %s/%s | prediction %.2f | gate %s.",
                        isEnabled() ? "enabled" : "disabled",
                        SilentAuraConfig.aimRange(),
                        SilentAuraConfig.scanExtra(),
                        SilentAuraConfig.fov(),
                        SilentAuraConfig.targetMode(),
                        aimProfile,
                        activePrediction,
                        TriggerBot.silentAuraGate()));
        return 1;
    }

    public static int setRange(Minecraft ignoredClient, double value) {
        SilentAuraConfig.range(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setScanExtra(Minecraft ignoredClient, double value) {
        SilentAuraConfig.scanExtra(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setFov(Minecraft ignoredClient, double value) {
        SilentAuraConfig.fov(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setHurtTime(Minecraft ignoredClient, int value) {
        SilentAuraConfig.hurtTime(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setSmooth(Minecraft ignoredClient, double value) {
        SilentAuraConfig.smooth(value);
        return 1;
    }

    public static int setFullLockAngleStep(Minecraft ignoredClient, int value) {
        SilentAuraConfig.fullLockAngleStep(value);
        return 1;
    }

    public static int setFullLockSmoothing(Minecraft ignoredClient, double value) {
        SilentAuraConfig.fullLockSmoothing(value);
        return 1;
    }

    public static int setFullLockPrediction(Minecraft ignoredClient, double value) {
        SilentAuraConfig.fullLockPrediction(value);
        return 1;
    }

    public static int setReturnRotation(Minecraft client, boolean value) {
        SilentAuraConfig.returnRotation(value);
        if (!value) SilentAuraRuntime.finishReturn(client);
        return 1;
    }

    public static int setReturnSmooth(Minecraft ignoredClient, double value) {
        SilentAuraConfig.returnSmooth(value);
        return 1;
    }

    public static int setJitter(Minecraft ignoredClient, double value) {
        SilentAuraConfig.jitter(value);
        return 1;
    }

    public static int setJitterSpeed(Minecraft ignoredClient, double value) {
        SilentAuraConfig.jitterSpeed(value);
        return 1;
    }

    public static int setSettledJitter(Minecraft ignoredClient, double value) {
        SilentAuraConfig.settledJitter(value);
        return 1;
    }

    public static int setAimWander(Minecraft ignoredClient, double value) {
        SilentAuraConfig.aimWander(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setAimWanderTicks(Minecraft ignoredClient, int value) {
        SilentAuraConfig.aimWanderTicks(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setPredictionLead(Minecraft ignoredClient, double value) {
        SilentAuraConfig.predictionLead(value);
        return 1;
    }

    public static int setPredictionStrength(Minecraft ignoredClient, double value) {
        SilentAuraConfig.prediction(value);
        return 1;
    }

    public static int setBlock(Minecraft ignoredClient, boolean value) {
        SilentAuraConfig.block(value);
        if (!value) SilentAuraRuntime.clearVisualBlock();
        return 1;
    }

    public static int setMatrixCompatibility(Minecraft ignoredClient, boolean value) {
        SilentAuraConfig.matrixCompatibility(value);
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setCriticalIntegration(Minecraft client, boolean value) {
        SilentAuraConfig.criticalIntegration(value);
        if (!value) Critical.cancelAutomaticPrediction(client);
        return 1;
    }

    public static int setAimMode(Minecraft client, String value) {
        if (!SilentAuraConfig.aimMode(value)) {
            ClientChat.send(client, "Aim mode must be balance, lock, or full_lock.");
            return 0;
        }
        // Each aim profile owns independent selector, controller and packet
        // history. A mode switch must not revive inertia from its last use.
        SilentAuraRuntime.reset(client);
        return 1;
    }

    public static int setAimPoint(Minecraft client, String value) {
        if (!SilentAuraConfig.aimPoint(value)) {
            ClientChat.send(client, "Aim point must be center or closest.");
            return 0;
        }
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setTargetMode(Minecraft client, String value) {
        if (!SilentAuraConfig.targetMode(value)) {
            ClientChat.send(client, "Target mode must be switch or single.");
            return 0;
        }
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static int setCharge(Minecraft client, String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            String[] parts = raw.trim().split("[-,:]", 2);
            double min = Double.parseDouble(parts[0].trim());
            double max = parts.length == 1 ? min : Double.parseDouble(parts[1].trim());
            SilentAuraConfig.charge(min, max);
            SilentAuraRuntime.resetTargeting();
            return 1;
        } catch (NumberFormatException exception) {
            ClientChat.send(client, "Attack charge must be a number or min-max.");
            return 0;
        }
    }

    public static int showTargetStatus(Minecraft client) {
        ClientChat.send(client, "SilentAura targets: " + SilentAuraConfig.targetStatus() + ".");
        return 1;
    }

    public static int setTargetCategory(Minecraft client, String category, boolean add) {
        if (!SilentAuraConfig.targetCategory(category, add)) {
            ClientChat.send(client, "Target category must be player or mob.");
            return 0;
        }
        SilentAuraRuntime.resetTargeting();
        return 1;
    }

    public static String hudTag() {
        return TriggerBot.attackChargePercent(Minecraft.getInstance()) + "%";
    }

    // Debug
    public static int setDebugger(Minecraft ignoredClient, boolean value) {
        SilentAuraConfig.debugger(value);
        return 1;
    }

    private static void drawDebugger(HudRenderEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!SilentAuraConfig.debugger()
                || client.player == null
                || client.level == null
                || MinecraftClientAccess.isHudHidden(client)) return;

        LivingEntity target = SilentAuraRuntime.currentTarget(client);
        SilentAuraRuntime.AttackRotation candidate = SilentAuraRuntime.attackRotation(client);
        SilentAuraRuntime.SentRotation sent = SilentAuraRuntime.sentRotation();
        double reach = CombatReach.entityInteractionRange(client, SilentAuraConfig.aimRange());
        String candidateRay =
                rayState(
                        client,
                        target,
                        candidate.valid(),
                        candidate.eye(),
                        candidate.look(),
                        candidate.targetId(),
                        reach);
        String sentRay =
                rayState(
                        client,
                        target,
                        sent.valid(),
                        sent.eye(),
                        sent.look(),
                        sent.targetId(),
                        reach);
        CombatDecisionEngine.Decision decision = Critical.getPlannedDecision();
        String targetText =
                target == null ? "-" : target.getName().getString() + "#" + target.getId();
        String gate = TriggerBot.silentAuraGate();
        String[] lines = {
            "SilentAura debugger",
            "held="
                    + SilentAuraRuntime.activationHeld(client)
                    + " target="
                    + targetText
                    + " profile="
                    + (SilentAuraConfig.fullLockMode()
                            ? "full-lock"
                            : SilentAuraConfig.matrixCompatibility() ? "matrix" : "generic"),
            "candidate="
                    + candidateRay
                    + " sent="
                    + sentRay
                    + " crossing="
                    + SilentAuraRuntime.crossingTarget(),
            "gate=" + gate,
            "critical="
                    + Critical.modeName()
                    + " enabled="
                    + Critical.isEnabled()
                    + " linked="
                    + SilentAuraConfig.criticalIntegration()
                    + " aimingWindow="
                    + Critical.isAimingWindowActive(),
            "decision="
                    + decision.attackKind().name().toLowerCase(Locale.ROOT)
                    + "+"
                    + decision.ticksAhead()
                    + " "
                    + decision.reason()
        };
        int x = 6;
        int y = Math.max(6, event.graphics().guiHeight() / 2 - 42);
        for (int index = 0; index < lines.length; index++) {
            int color =
                    index == 0
                            ? 0xFFBFA3FF
                            : index == 3
                                            && (gate.contains("critical wait")
                                                    || gate.contains("critical block"))
                                    ? 0xFFFF7777
                                    : 0xFFEDE9FF;
            event.graphics()
                    .text(
                            client.font,
                            lines[index],
                            x,
                            y + index * (client.font.lineHeight + 1),
                            color,
                            true);
        }
        String[] timingLines = SilentAuraPlacementDebugger.debugLines();
        for (int index = 0; index < timingLines.length; index++) {
            String line = timingLines[index];
            int color =
                    line.contains("MISMATCH") ? 0xFFFF6666 : index == 0 ? 0xFFFFCC66 : 0xFFD8E8FF;
            event.graphics()
                    .text(
                            client.font,
                            line,
                            x,
                            y + (lines.length + index) * (client.font.lineHeight + 1),
                            color,
                            true);
        }
    }

    private static String rayState(
            Minecraft client,
            LivingEntity target,
            boolean valid,
            Vec3 eye,
            Vec3 look,
            int targetId,
            double reach) {
        if (target == null) return "no-target";
        if (!valid || targetId != target.getId()) return "waiting";
        return RaytraceUtils.traceEntity(client, eye, look, reach, target)
                .name()
                .toLowerCase(Locale.ROOT);
    }
}
