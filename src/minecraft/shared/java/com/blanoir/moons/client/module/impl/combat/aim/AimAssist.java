package com.blanoir.moons.client.module.impl.combat.aim;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.management.input.MouseInputTracker;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.utils.combat.CombatModuleCoordinator;
import com.blanoir.moons.client.utils.input.MouseInfluenceA;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.prediction.AimPrediction;
import com.blanoir.moons.client.utils.prediction.AimPrediction.AimForecast;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.aim.AimGeometry;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsA;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsB;
import com.blanoir.moons.client.utils.rotation.aim.AimPointsC;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverA;
import com.blanoir.moons.client.utils.rotation.aim.TargetSelectorB;
import com.blanoir.moons.client.utils.rotation.smooth.SmoothC;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AimAssist {

    private static final double MIN_CORRECTION_ANGLE_DEGREES = 0.6D;

    private static int lockedEntityId = -1;

    private static Vec3 lockedAimPoint;
    private static final AimPointsA.State CENTER_POINTS = new AimPointsA.State();
    private static final AimPointsB.State CLOSEST_POINTS = new AimPointsB.State();

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("aimassist.mode")
                    .defaultValue(Mode.LEGIT)
                    .option(Mode.LEGIT, "legit")
                    .option(Mode.CENTER, "center")
                    .option(Mode.CLOSEST, "closest")
                    .build();

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("aimassist.enabled").defaultValue(false).build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("aimassist.range")
                    .defaultValue(4.2D)
                    .range(1.0D, 8.0D)
                    .build();

    private static final DoubleSetting SMOOTH =
            new DoubleSetting.Builder()
                    .name("aimassist.smooth")
                    .defaultValue(0.35D)
                    .range(0.05D, 1.0D)
                    .build();

    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("aimassist.fov")
                    .defaultValue(45.0D)
                    .range(1.0D, 360.0D)
                    .build();

    private AimAssist() {}

    public static ModuleRegistry.Setting[] settings() {
        return new ModuleRegistry.Setting[] {
            MODE.describe("mode", "Mode", AimAssist::setMode),
            RANGE.describe("range", "Range", .05D, AimAssist::setRange),
            FOV.describe("fov", "FOV", 1.0D, AimAssist::setFov),
            SMOOTH.describe("smooth", "Smooth", .01D, AimAssist::setSmooth)
        };
    }

    public static void init() {
        CombatModuleCoordinator.bindAimAssist(
                AimAssist::isEnabled, (client, enabled) -> AimAssist.setEnabled(client, enabled));
        EventBus.FRAME.register("AimAssist.frame", AimAssist::frame);
        EventBus.CLIENT_CONTEXT_CHANGED.register("AimAssist.context", event -> clearLock());
    }

    /**
     * Entire assist loop runs at render frequency.
     */
    private static void frame(FrameEvent event) {
        boolean enabled = ENABLED.get();
        Minecraft client = event.client();
        var currentPlayer = client == null ? null : client.player;

        if (!enabled) {
            clearLock();
            return;
        }

        if (client == null
                || currentPlayer == null
                || client.level == null
                || MinecraftClientAccess.screen(client) != null) {
            clearLock();
            return;
        }

        if (!Targeting.isHoldingTriggerWeapon(client)) {
            clearLock();
            return;
        }

        if (MODE.get() == Mode.LEGIT && isCrosshairAlreadyAttackable(client)) {
            return;
        }

        AimSolverA.Result target = findTarget(client);

        if (target == null) {
            clearLock();
            return;
        }

        if (MODE.get() != Mode.LEGIT) {
            Vec3 eye = currentPlayer.getEyePosition();
            AABB box = target.entity().getBoundingBox();
            Vec3 point =
                    MODE.get() == Mode.CENTER
                            ? AimPointsA.resolve(
                                    CENTER_POINTS,
                                    client,
                                    target.entity(),
                                    eye,
                                    box,
                                    RANGE.get(),
                                    .45D,
                                    9,
                                    true)
                            : AimPointsB.resolve(
                                    CLOSEST_POINTS,
                                    client,
                                    target.entity(),
                                    eye,
                                    box,
                                    RANGE.get(),
                                    9);
            if (eye.distanceToSqr(point) <= RANGE.get() * RANGE.get()
                    && RaytraceUtils.canRayTraceTo(client, eye, point)) {
                target = AimSolverA.solve(client, target.entity(), point);
            }
            if (!MathUtils.withinFov(target.angle(), FOV.get())) {
                clearLock();
                return;
            }
        }

        lockedEntityId = target.entity().getId();

        lockedAimPoint = target.aimPoint();

        if (target.angle() <= MIN_CORRECTION_ANGLE_DEGREES) {
            return;
        }

        MouseInputTracker.Motion motion = MouseInputTracker.currentMotion(System.nanoTime());

        double frameSmooth = smoothForFrame(event.deltaSeconds()) * mouseSmoothMultiplier(motion);

        float nextYaw =
                SmoothC.blendAngle(currentPlayer.getYRot(), target.rotation().yaw(), frameSmooth);

        float nextPitch =
                SmoothC.blendAngle(currentPlayer.getXRot(), target.rotation().pitch(), frameSmooth);

        currentPlayer.setYRot(nextYaw);

        currentPlayer.setXRot(Mth.clamp(nextPitch, -90.0F, 90.0F));
    }

    public static AimForecast forecastAttack(Minecraft client, Entity target, int ticksAhead) {
        var currentPlayer = client == null ? null : client.player;
        Vec3 futureEye =
                client != null && currentPlayer != null
                        ? TrajectoryPrediction.linearPosition(
                                currentPlayer.getEyePosition(),
                                currentPlayer.getDeltaMovement(),
                                Math.max(0, ticksAhead))
                        : Vec3.ZERO;

        return forecastAttack(client, target, ticksAhead, futureEye);
    }

    public static AimForecast forecastAttack(
            Minecraft client, Entity target, int ticksAhead, Vec3 futureEye) {
        if (client == null
                || client.player == null
                || client.level == null
                || !(target instanceof LivingEntity living)
                || !isValidTarget(client, living)) {
            return AimForecast.unavailable();
        }
        boolean enabled = ENABLED.get();
        double inputMultiplier =
                enabled
                        ? mouseSmoothMultiplier(MouseInputTracker.currentMotion(System.nanoTime()))
                        : 1.0D;
        return AimPrediction.forecastAttack(
                client,
                living,
                ticksAhead,
                futureEye,
                enabled,
                SMOOTH.get(),
                RANGE.get(),
                inputMultiplier,
                MIN_CORRECTION_ANGLE_DEGREES,
                switch (MODE.get()) {
                    case LEGIT -> null;
                    case CENTER -> AimGeometry.Mode.CENTER;
                    case CLOSEST -> AimGeometry.Mode.CLOSEST;
                });
    }

    /**
     * Converts the configured 20 Hz smoothing coefficient into a render-frame
     * coefficient, keeping the response approximately independent of FPS.
     */
    private static double smoothForFrame(double frameDeltaSeconds) {
        return SmoothC.frameCoefficient(SMOOTH.get(), frameDeltaSeconds);
    }

    private static boolean isCrosshairAlreadyAttackable(Minecraft client) {
        double range = RANGE.get();
        if (client == null || client.player == null) {
            return false;
        }

        HitResult hitResult = client.hitResult;
        if (hitResult instanceof EntityHitResult entityHitResult) {
            Entity target = entityHitResult.getEntity();
            if (target instanceof LivingEntity livingTarget
                    && isValidTarget(client, livingTarget)
                    && Targeting.isWithinInteractionRange(client, target)
                    && AimPointsC.hasVisiblePoint(client, livingTarget, range)) {
                return true;
            }
        }

        // client.hitResult updates on ticks, so use the real camera ray as a
        // render-frequency fallback.
        return crosshairRayHitsValidTarget(client);
    }

    private static boolean crosshairRayHitsValidTarget(Minecraft client) {
        return Targeting.findTargetOnViewRay(
                        client,
                        entity ->
                                entity instanceof LivingEntity living
                                        && isValidTarget(client, living)
                                        && Targeting.isWithinInteractionRange(client, living),
                        false)
                != null;
    }

    private static AimSolverA.Result findTarget(Minecraft client) {
        return TargetSelectorB.select(
                client,
                RANGE.get(),
                FOV.get(),
                switch (MODE.get()) {
                    case LEGIT -> null;
                    case CENTER -> AimGeometry.Mode.CENTER;
                    case CLOSEST -> AimGeometry.Mode.CLOSEST;
                },
                lockedEntityId,
                lockedAimPoint);
    }

    /**
     * Reduces assistance while the player is actively moving the mouse.
     */
    private static double mouseSmoothMultiplier(MouseInputTracker.Motion motion) {
        return MouseInfluenceA.mouseMultiplier(
                motion.recentlyMoved(),
                motion.velocityPxPerSecond(),
                motion.accelerationPxPerSecondSquared());
    }

    private static void clearLock() {
        lockedEntityId = -1;
        lockedAimPoint = null;
        CENTER_POINTS.reset();
        CLOSEST_POINTS.reset();
    }

    public static String mode() {
        return MODE.serialized();
    }

    public static int setMode(Minecraft client, String value) {
        if (!MODE.tryDeserialize(value)) return 0;
        clearLock();
        return 1;
    }

    private static boolean isValidTarget(Minecraft client, LivingEntity entity) {
        return Targeting.isEnemyPlayer(client, entity);
    }

    public static int showStatus(Minecraft client) {
        double range = RANGE.get();
        double smooth = SMOOTH.get();
        double fov = FOV.get();
        ClientChat.send(
                client,
                "AimAssist: "
                        + statusText()
                        + ", mode: "
                        + mode()
                        + ", range: "
                        + format(range)
                        + ", smooth: "
                        + format(smooth)
                        + ", fov: "
                        + format(fov)
                        + ". Usage: .moons aimassist <enable|disable|range [smooth]|fov>");

        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        if (newEnabled) {
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.AIM_ASSIST);
        }
        ENABLED.set(newEnabled);

        if (!ENABLED.get()) {
            clearLock();
        }

        ClientChat.send(
                client,
                "AimAssist "
                        + statusText()
                        + ". Range: "
                        + format(RANGE.get())
                        + ", smooth: "
                        + format(SMOOTH.get())
                        + ", fov: "
                        + format(FOV.get())
                        + ".");

        return 1;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setSettings(Minecraft client, double newRange, double newSmooth) {
        RANGE.set(newRange);
        SMOOTH.set(newSmooth);

        ClientChat.send(
                client,
                "AimAssist settings set to range "
                        + format(RANGE.get())
                        + ", smooth "
                        + format(SMOOTH.get())
                        + ".");

        return 1;
    }

    public static int setRange(Minecraft client, double newRange) {
        double smooth = SMOOTH.get();
        return setSettings(client, newRange, smooth);
    }

    public static int setSmooth(Minecraft client, double newSmooth) {
        double range = RANGE.get();
        return setSettings(client, range, newSmooth);
    }

    public static int setFov(Minecraft client, double newFov) {
        FOV.set(newFov);

        ClientChat.send(client, "AimAssist fov set to " + format(FOV.get()) + " degrees.");

        return 1;
    }

    private static String statusText() {
        boolean enabled = ENABLED.get();
        return enabled ? "enabled" : "disabled";
    }

    private static String format(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    private enum Mode {
        LEGIT,
        CENTER,
        CLOSEST
    }
}
