package com.blanoir.tempos.client.module.impl.combat.aim;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.frame.FrameEvent;
import com.blanoir.tempos.client.input.MouseMotionTracker;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.utils.math.ViewCone;
import com.blanoir.tempos.client.utils.prediction.AimPrediction;
import com.blanoir.tempos.client.utils.prediction.AimPrediction.AimForecast;
import com.blanoir.tempos.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;
import com.blanoir.tempos.client.utils.rotation.aim.AimGeometry;
import com.blanoir.tempos.client.utils.rotation.aim.CenterAimPoints;
import com.blanoir.tempos.client.utils.rotation.aim.ClosestAimPoints;
import com.blanoir.tempos.client.utils.rotation.aim.VisibleAimPoints;
import com.blanoir.tempos.client.utils.text.NumberText;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description =
                "Helps turn your camera toward a target within the selected range and field of view.")
public final class AimAssist extends ClientFeature {
    public static final AimAssist INSTANCE = new AimAssist();

    private static final double MIN_CORRECTION_ANGLE_DEGREES = 0.6D;

    private int lockedEntityId = -1;

    private Vec3 lockedAimPoint;
    private final CenterAimPoints.State centerPoints = new CenterAimPoints.State();
    private final ClosestAimPoints.State closestPoints = new ClosestAimPoints.State();

    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("aimassist.mode")
                    .defaultValue(Mode.LEGIT)
                    .option(Mode.LEGIT, "legit")
                    .option(Mode.CENTER, "center")
                    .option(Mode.CLOSEST, "closest")
                    .build();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("aimassist.enabled").defaultValue(false).build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("aimassist.range")
                    .defaultValue(4.2D)
                    .range(1.0D, 8.0D)
                    .build();

    private final DoubleSetting smooth =
            new DoubleSetting.Builder()
                    .name("aimassist.smooth")
                    .defaultValue(0.35D)
                    .range(0.05D, 1.0D)
                    .build();

    private final DoubleSetting fov =
            new DoubleSetting.Builder()
                    .name("aimassist.fov")
                    .defaultValue(45.0D)
                    .range(1.0D, 360.0D)
                    .build();

    private AimAssist() {
        super("aimassist", "AimAssist");
    }

    public ModuleModel.Setting[] settings() {
        return new ModuleModel.Setting[] {
            this.mode.describe("mode", "Mode", this::setMode),
            this.range.describe("range", "Range", .05D, this::setRange),
            this.fov.describe("fov", "FOV", 1.0D, this::setFov),
            this.smooth.describe("smooth", "Smooth", .01D, this::setSmooth)
        };
    }

    @Override
    public void registerEvents() {
        CombatModuleCoordinator.bindAimAssist(
                this::isEnabled, (client, enabled) -> this.setEnabled(client, enabled));
        EventBus.FRAME.register("AimAssist.frame", this::frame);
        EventBus.CLIENT_CONTEXT_CHANGED.register("AimAssist.context", event -> clearLock());
    }

    /**
     * Entire assist loop runs at render frequency.
     */
    private void frame(FrameEvent event) {
        boolean enabled = this.enabled.get() && !CombatModuleCoordinator.automationSuspended();
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

        if (this.mode.get() == Mode.LEGIT && isCrosshairAlreadyAttackable(client)) {
            return;
        }

        AssistAngleSolver.Result target = findTarget(client);

        if (target == null) {
            clearLock();
            return;
        }

        if (this.mode.get() != Mode.LEGIT) {
            Vec3 eye = currentPlayer.getEyePosition();
            AABB box = target.entity().getBoundingBox();
            Vec3 point =
                    this.mode.get() == Mode.CENTER
                            ? CenterAimPoints.resolve(
                                    this.centerPoints,
                                    client,
                                    target.entity(),
                                    eye,
                                    box,
                                    this.range.get(),
                                    .45D,
                                    9,
                                    true)
                            : ClosestAimPoints.resolve(
                                    this.closestPoints,
                                    client,
                                    target.entity(),
                                    eye,
                                    box,
                                    this.range.get(),
                                    9);
            if (eye.distanceToSqr(point) <= this.range.get() * this.range.get()
                    && LineOfSight.canRayTraceTo(client, eye, point)) {
                target = AssistAngleSolver.solve(client, target.entity(), point);
            }
            if (!ViewCone.withinFov(target.angle(), this.fov.get())) {
                clearLock();
                return;
            }
        }

        this.lockedEntityId = target.entity().getId();

        this.lockedAimPoint = target.aimPoint();

        if (target.angle() <= MIN_CORRECTION_ANGLE_DEGREES) {
            return;
        }

        MouseMotionTracker.Motion motion = MouseMotionTracker.currentMotion(System.nanoTime());

        double frameSmooth = smoothForFrame(event.deltaSeconds()) * mouseSmoothMultiplier(motion);

        float nextYaw =
                CameraAssistBlend.blendAngle(
                        currentPlayer.getYRot(), target.rotation().yaw(), frameSmooth);

        float nextPitch =
                CameraAssistBlend.blendAngle(
                        currentPlayer.getXRot(), target.rotation().pitch(), frameSmooth);

        currentPlayer.setYRot(nextYaw);

        currentPlayer.setXRot(Math.clamp(nextPitch, -90.0F, 90.0F));
    }

    public AimForecast forecastAttack(Minecraft client, Entity target, int ticksAhead) {
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

    public AimForecast forecastAttack(
            Minecraft client, Entity target, int ticksAhead, Vec3 futureEye) {
        if (client == null
                || client.player == null
                || client.level == null
                || !(target instanceof LivingEntity living)
                || !isValidTarget(client, living)) {
            return AimForecast.unavailable();
        }
        boolean enabled = this.enabled.get();
        double inputMultiplier =
                enabled
                        ? mouseSmoothMultiplier(MouseMotionTracker.currentMotion(System.nanoTime()))
                        : 1.0D;
        return AimPrediction.forecastAttack(
                client,
                living,
                ticksAhead,
                futureEye,
                enabled,
                this.smooth.get(),
                this.range.get(),
                inputMultiplier,
                MIN_CORRECTION_ANGLE_DEGREES,
                switch (this.mode.get()) {
                    case LEGIT -> null;
                    case CENTER -> AimGeometry.Mode.CENTER;
                    case CLOSEST -> AimGeometry.Mode.CLOSEST;
                });
    }

    /**
     * Converts the configured 20 Hz smoothing coefficient into a render-frame
     * coefficient, keeping the response approximately independent of FPS.
     */
    private double smoothForFrame(double frameDeltaSeconds) {
        return CameraAssistBlend.frameCoefficient(this.smooth.get(), frameDeltaSeconds);
    }

    private boolean isCrosshairAlreadyAttackable(Minecraft client) {
        double range = this.range.get();
        if (client == null || client.player == null) {
            return false;
        }

        HitResult hitResult = client.hitResult;
        if (hitResult instanceof EntityHitResult entityHitResult) {
            Entity target = entityHitResult.getEntity();
            if (target instanceof LivingEntity livingTarget
                    && isValidTarget(client, livingTarget)
                    && Targeting.isWithinInteractionRange(client, target)
                    && VisibleAimPoints.hasVisiblePoint(client, livingTarget, range)) {
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

    private AssistAngleSolver.Result findTarget(Minecraft client) {
        return AssistTargetSelector.select(
                client,
                this.range.get(),
                this.fov.get(),
                switch (this.mode.get()) {
                    case LEGIT -> null;
                    case CENTER -> AimGeometry.Mode.CENTER;
                    case CLOSEST -> AimGeometry.Mode.CLOSEST;
                },
                this.lockedEntityId,
                this.lockedAimPoint);
    }

    /**
     * Reduces assistance while the player is actively moving the mouse.
     */
    private static double mouseSmoothMultiplier(MouseMotionTracker.Motion motion) {
        return MouseAssistInfluence.mouseMultiplier(
                motion.recentlyMoved(),
                motion.velocityDegreesPerSecond(),
                motion.accelerationDegreesPerSecondSquared());
    }

    private void clearLock() {
        this.lockedEntityId = -1;
        this.lockedAimPoint = null;
        this.centerPoints.reset();
        this.closestPoints.reset();
    }

    public String mode() {
        return this.mode.serialized();
    }

    public int setMode(Minecraft client, String value) {
        if (!this.mode.tryDeserialize(value)) return 0;
        clearLock();
        return 1;
    }

    private static boolean isValidTarget(Minecraft client, LivingEntity entity) {
        return Targeting.isEnemyPlayer(client, entity);
    }

    public int showStatus(Minecraft client) {
        double range = this.range.get();
        double smooth = this.smooth.get();
        double fov = this.fov.get();
        ClientChat.send(
                client,
                "AimAssist: "
                        + statusText()
                        + ", mode: "
                        + mode()
                        + ", range: "
                        + NumberText.compactDouble(range)
                        + ", smooth: "
                        + NumberText.compactDouble(smooth)
                        + ", fov: "
                        + NumberText.compactDouble(fov)
                        + ". Usage: .tempos aimassist <enable|disable|range [smooth]|fov>");

        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        if (newEnabled) {
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.AIM_ASSIST);
        }
        this.enabled.set(newEnabled);

        if (!this.enabled.get()) {
            clearLock();
        }

        ClientChat.send(
                client,
                "AimAssist "
                        + statusText()
                        + ". Range: "
                        + NumberText.compactDouble(this.range.get())
                        + ", smooth: "
                        + NumberText.compactDouble(this.smooth.get())
                        + ", fov: "
                        + NumberText.compactDouble(this.fov.get())
                        + ".");

        return 1;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    public int setSettings(Minecraft client, double newRange, double newSmooth) {
        this.range.set(newRange);
        this.smooth.set(newSmooth);

        ClientChat.send(
                client,
                "AimAssist settings set to range "
                        + NumberText.compactDouble(this.range.get())
                        + ", smooth "
                        + NumberText.compactDouble(this.smooth.get())
                        + ".");

        return 1;
    }

    public int setRange(Minecraft client, double newRange) {
        double smooth = this.smooth.get();
        return setSettings(client, newRange, smooth);
    }

    public int setSmooth(Minecraft client, double newSmooth) {
        double range = this.range.get();
        return setSettings(client, range, newSmooth);
    }

    public int setFov(Minecraft client, double newFov) {
        this.fov.set(newFov);

        ClientChat.send(
                client,
                "AimAssist fov set to " + NumberText.compactDouble(this.fov.get()) + " degrees.");

        return 1;
    }

    private String statusText() {
        boolean enabled = this.enabled.get();
        return enabled ? "enabled" : "disabled";
    }

    private enum Mode {
        LEGIT,
        CENTER,
        CLOSEST
    }

    @Override
    protected String statusTag() {
        return this.mode();
    }

    @Override
    protected Setting[] defineSettings() {
        return this.settings();
    }
}
