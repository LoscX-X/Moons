/*
 * AutoWeb for Tempos.
 *
 * Two triggers share one instant silent packet-RotationA placement path (the
 * first-person camera stays fixed while the third-person model turns):
 * - Auto: scores feet, body and eye voxels along the target's trajectory.
 * - Wall (attack only): follows observed knockback, resolves the next 3-6 ticks
 *   against block collisions and scores body coverage at the wall contact.
 * Continuous mode waits for its cooldown after each successful placement;
 * single mode disables after one confirmed placement and its slot/rotation cleanup.
 */
package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.PostHitLandingWindow;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.AngleMath;
import com.blanoir.tempos.client.utils.math.RandomChance;
import com.blanoir.tempos.client.utils.player.HotbarQueries;
import com.blanoir.tempos.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.tempos.client.utils.prediction.TrajectoryPrediction.TrajectoryStep;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

@ModuleInfo(category = ModuleCategory.WORLD, description = "Places cobwebs near a target.")
public final class AutoWeb extends ClientFeature {
    public static final AutoWeb INSTANCE = new AutoWeb();

    private final PlacementRayPolicy rays = new PlacementRayPolicy("autoweb");
    private static final int MIN_WALL_PREDICTION_TICKS = 3;
    private static final int MAX_WALL_PREDICTION_TICKS = 6;
    private static final int ATTACK_REQUEST_LIFETIME_TICKS = 24;
    private static final int MAX_PLACE_CONFIRM_TICKS = 40;
    private static final int MAX_HOLD_TICKS = 20;
    private final Direction[] supportDirections = {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
    };
    private final Direction[] horizontalDirections = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final double SELF_SAFETY_MARGIN = 0.28D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 0.5D;
    private static final double MIN_COOLDOWN_SECONDS = 0.0D;
    private static final double MAX_COOLDOWN_SECONDS = 30.0D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private final double[] faceSamples = {0.18D, 0.5D, 0.82D};

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("autoweb.enabled").defaultValue(false).build();

    private final ModeSetting<String> triggerMode =
            new ModeSetting.Builder<String>()
                    .name("autoweb.triggerMode")
                    .defaultValue("continuous")
                    .option("continuous", "continuous")
                    .option("single", "single")
                    .build();

    private final BooleanSetting wallEnabled =
            new BooleanSetting.Builder().name("autoweb.wall").defaultValue(false).build();

    private final BooleanSetting groundEnabled =
            new BooleanSetting.Builder().name("autoweb.ground").defaultValue(true).build();

    private final IntSetting groundWindowTicks =
            new IntSetting.Builder()
                    .name("autoweb.groundWindow")
                    .defaultValue(5)
                    .range(1, 12)
                    .build();
    private final DoubleSetting groundMaxSpeed =
            new DoubleSetting.Builder()
                    .name("autoweb.groundMaxSpeed")
                    .defaultValue(0.30D)
                    .range(0.05D, 1.5D)
                    .build();
    private final DoubleSetting groundMaxRelativeSpeed =
            new DoubleSetting.Builder()
                    .name("autoweb.groundMaxRelativeSpeed")
                    .defaultValue(0.45D)
                    .range(0.05D, 2.0D)
                    .build();

    private final BooleanSetting waitConfirmRotation =
            new BooleanSetting.Builder()
                    .name("autoweb.waitConfirmRotation")
                    .defaultValue(false)
                    .build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("autoweb.range")
                    .defaultValue(4.5D)
                    .range(2.0D, 6.0D)
                    .build();

    private final IntSetting delayTicks =
            new IntSetting.Builder().name("autoweb.delay").defaultValue(0).range(0, 100).build();

    private final IntSetting predictionTicks =
            new IntSetting.Builder()
                    .name("autoweb.prediction")
                    .defaultValue(4)
                    .range(0, 12)
                    .build();

    private final IntSetting holdTicks =
            new IntSetting.Builder()
                    .name("autoweb.holdTicks")
                    .defaultValue(0)
                    .range(0, MAX_HOLD_TICKS)
                    .build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("autoweb.chance")
                    .defaultValue(1.0D)
                    .range(0.0D, 1.0D)
                    .build();

    private final DoubleSetting cooldownSeconds =
            new DoubleSetting.Builder()
                    .name("autoweb.cooldown")
                    .defaultValue(DEFAULT_COOLDOWN_SECONDS)
                    .range(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
                    .build();

    private int remainingCooldownTicks;
    private PlacementPlan heldPlan;
    private int heldWebSlot = -1;
    private int originalSlot = -1;
    private WebActionPhase phase = WebActionPhase.IDLE;
    private int pendingAttackTargetId = -1;
    private int pendingAttackTicks;
    private boolean pendingWallAttempted;
    private final PostHitLandingWindow groundLandingWindow = new PostHitLandingWindow();
    private int postPlaceHoldRemainingTicks;
    private boolean postPlaceHoldStarted;
    private BlockPos pendingPlaceConfirmationPos;
    private int placeConfirmTicks;
    private boolean disableAfterPlacement;

    private AutoWeb() {
        super("autoweb", "AutoWeb");
    }

    @Override
    public void registerEvents() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoWeb.context", event -> shutdown(null));
        PlacementCoordinator.register(
                PlacementCoordinator.Owner.AUTO_WEB, this::isBusy, this::resetPlan);
        EventBus.PLAYER_UPDATE.register(
                "AutoWeb.playerUpdate",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private void tick(Minecraft client) {
        if (!this.enabled.get()) {
            resetAll(client);
            return;
        }

        if (!ClientReady.gameplay(client)) {
            // A completed single use stays consumed even if a screen/world change interrupts
            // return.
            if (this.disableAfterPlacement) this.enabled.set(false);
            resetAll(client);

            return;
        }

        if (this.remainingCooldownTicks > 0) {
            this.remainingCooldownTicks--;
        }
        if (this.pendingAttackTicks > 0) {
            this.pendingAttackTicks--;
            Player pendingTarget =
                    client.level.getEntity(this.pendingAttackTargetId) instanceof Player player
                            ? player
                            : null;
            this.groundLandingWindow.update(pendingTarget, client.player.getDeltaMovement());
        } else {
            this.pendingAttackTargetId = -1;
            this.pendingWallAttempted = false;
            this.groundLandingWindow.clear();
        }

        observePlacementConfirmation(client);
        if (finishSingleTrigger(client)) return;

        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_WEB)) {
            return;
        }

        if (this.phase != WebActionPhase.IDLE || this.heldPlan != null) {
            tickPlacement(client);
            finishSingleTrigger(client);
            return;
        }
        if (this.remainingCooldownTicks > 0) {
            return;
        }
        // A fast return releases slot/rotation ownership immediately, but a
        // second web must not start until the first server result is known.
        if (this.pendingPlaceConfirmationPos != null) {
            return;
        }
        processIdleTick(client);
    }

    private void processIdleTick(Minecraft client) {
        if (tryExecutePendingAttack(client)) {
            return;
        }

        // Ground placement is attack-armed and landing-gated. Continuously
        // scanning every normally grounded/running player made GroundWeb a
        // long-horizon prediction problem and selected cells the target only
        // crossed between interpolation samples.
        clearPendingPlan();
    }

    /** Attack trigger: upper-body wall first, then corner/predictive wall and feet. */
    public void onAttack(Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (!this.enabled.get()
                || this.disableAfterPlacement
                || !ClientReady.gameplay(client)
                || !(target instanceof Player player)
                || !Targeting.isValidTargetPlayer(client, player)
                || isTrappedInWeb(client, player)
                || findWebSlot(client) == -1) {
            return;
        }
        // Attack callbacks may run before or after LocalPlayer.tick depending
        // on the host client. Consume the request only from PLAYER_UPDATE so
        // selection, silent rotation, use and sendPosition own one tick.
        this.pendingAttackTargetId = player.getId();
        this.pendingAttackTicks = ATTACK_REQUEST_LIFETIME_TICKS;
        this.pendingWallAttempted = false;

        if (this.groundEnabled.get()) {
            this.groundLandingWindow.arm(
                    player, ATTACK_REQUEST_LIFETIME_TICKS, this.groundWindowTicks.get());
        } else {
            this.groundLandingWindow.clear();
        }
    }

    private boolean tryExecutePendingAttack(Minecraft client) {
        if (this.pendingAttackTargetId == -1) {
            return false;
        }
        Player target =
                client.level.getEntity(this.pendingAttackTargetId) instanceof Player player
                        ? player
                        : null;
        if (!Targeting.isValidTargetPlayer(client, target)
                || isTrappedInWeb(client, target)
                || findWebSlot(client) == -1) {
            clearPendingAttack();
            return false;
        }

        if (tryStartWallPlacement(client, target)) {
            return true;
        }
        return tryStartLandingPlacement(client, target);
    }

    private boolean tryStartWallPlacement(Minecraft client, Player target) {
        if (this.pendingWallAttempted || !this.wallEnabled.get()) {
            return false;
        }
        // Retry as the real knockback arrives. A guessed impulse or merely
        // being near a corner is not evidence that this wall will be reached.
        PlacementPlan plan = findWallPlan(client, target);
        if (plan == null) {
            return false;
        }
        this.pendingWallAttempted = true;
        return consumePlacementAttempt(client, plan);
    }

    private boolean tryStartLandingPlacement(Minecraft client, Player target) {
        if (!this.groundEnabled.get()) {
            if (!this.wallEnabled.get()) {
                clearPendingAttack();
            }
            return false;
        }
        PostHitLandingWindow.Snapshot landing =
                this.groundLandingWindow.update(target, client.player.getDeltaMovement());
        if (landing.expired()) {
            clearPendingAttack();
            return false;
        }
        if (!target.onGround() || !landing.insideLandingWindow()) {
            return false;
        }
        if (landing.targetHorizontalSpeed() > this.groundMaxSpeed.get()
                || landing.relativeHorizontalSpeed() > this.groundMaxRelativeSpeed.get()) {
            return false;
        }
        PlacementPlan plan = findLandingGroundPlan(client, target);
        if (plan == null) {
            return false;
        }
        return consumePlacementAttempt(client, plan);
    }

    private boolean consumePlacementAttempt(Minecraft client, PlacementPlan plan) {
        clearPendingAttack();
        if (RandomChance.chance(this.chance.get())) {
            beginWebHold(client, plan);
        }
        return true;
    }

    /** Aim at the next feet position, without rewarding cells already being left. */
    private PlacementPlan findLandingGroundPlan(Minecraft client, Player target) {
        Vec3 velocity = TrajectoryPrediction.observedVelocity(target);
        int leadTicks = Math.min(1, this.predictionTicks.get());
        Vec3 travel =
                TrajectoryPrediction.horizontalCollisionTravel(client, target, velocity, leadTicks);
        AABB box = target.getBoundingBox().move(travel);
        Vec3 feet = target.position().add(travel);
        Vec3 eye = target.getEyePosition().add(travel);
        // Both samples describe the use-time footprint. Including the old box
        // or swept feet path lets a trailing cell win after knockback.
        return findBestVoxel(
                client, target, box, box, feet, feet, eye, eye, velocity, leadTicks, leadTicks,
                false, false, true);
    }

    private void tickPlacement(Minecraft client) {
        if (this.heldPlan == null) {
            restoreHeldSlot(client);
            return;
        }
        if (this.postPlaceHoldRemainingTicks > 0 && --this.postPlaceHoldRemainingTicks == 0) {
            restoreWebSlotSelection(client);
        }
        // A plan can become unreachable while the silent smooth RotationA is
        // still running. Abort before queuing the right-click instead of
        // sending an interaction that server-side checks reject as out of
        // reach. The failed action returns smoothly and never receives the
        // successful-placement cooldown.
        if ((this.phase == WebActionPhase.TURNING_TO_PLACE
                        || this.phase == WebActionPhase.WAITING_FOR_PLACE_ROTATION)
                && !withinPlacementRange(client, this.heldPlan.hit().getLocation())) {
            failPlacement(client);
            return;
        }
        if (this.phase == WebActionPhase.TURNING_TO_PLACE
                || this.phase == WebActionPhase.TURNING_BACK) {
            return;
        }

        switch (this.phase) {
            case WAITING_FOR_PLACE_ROTATION -> awaitPlacementRotation(client);
            case CLICKING_TO_PLACE -> confirmPlacedWeb(client);
            case HOLDING_AFTER_PLACE -> finishPostPlaceHold(client);
            case WAITING_FOR_RETURN -> confirmReturnRotation(client);
            default -> {}
        }
    }

    private void awaitPlacementRotation(Minecraft client) {
        BlockHitResult confirmedHit = validatePlan(client, this.heldPlan);
        if (confirmedHit == null) {
            failPlacement(client);
            return;
        }
        if (this.rays.invokeUseInPlayerUpdate(client, confirmedHit)) {
            this.placeConfirmTicks = 0;
            this.phase = WebActionPhase.CLICKING_TO_PLACE;
        }
    }

    private void confirmPlacedWeb(Minecraft client) {
        if (!SilentPacketRotation.isUseInvocationDone()) {
            return;
        }
        // Start the visible-slot hold when vanilla has actually invoked USE,
        // not after the later server block confirmation/settle wait. The
        // interaction packet no longer needs the web selected at this point.
        if (!this.postPlaceHoldStarted) {
            this.postPlaceHoldStarted = true;
            this.postPlaceHoldRemainingTicks = this.holdTicks.get();
            if (this.postPlaceHoldRemainingTicks == 0) {
                restoreWebSlotSelection(client);
            }
        }
        if (!SilentPacketRotation.isUseDone()) {
            return;
        }
        if (this.pendingPlaceConfirmationPos == null) {
            this.pendingPlaceConfirmationPos = this.heldPlan.placePos().immutable();
            this.placeConfirmTicks = 0;
        }
        if (!this.waitConfirmRotation.get()) {
            // Match AutoLava's responsive ownership release. Confirmation and
            // ping settle continue in observePlacementConfirmation().
            beginReturnRotation(client);
        }
    }

    private void observePlacementConfirmation(Minecraft client) {
        if (this.pendingPlaceConfirmationPos == null) {
            return;
        }
        this.placeConfirmTicks++;
        BlockState confirmedState = client.level.getBlockState(this.pendingPlaceConfirmationPos);
        if (!confirmedState.is(Blocks.COBWEB)) {
            // isUseDone only means the local invocation and packet order
            // completed. Remote server block updates arrive later, so keep
            // observing in the background instead of keeping slot/rotation
            // ownership merely because the first frame is still air.
            if (!confirmedState.canBeReplaced()
                    || this.placeConfirmTicks >= MAX_PLACE_CONFIRM_TICKS) {
                boolean strictWait =
                        this.waitConfirmRotation.get()
                                && this.phase == WebActionPhase.CLICKING_TO_PLACE;
                clearPlacementConfirmation();
                if (strictWait) {
                    failPlacement(client);
                }
            }
            return;
        }
        // Client prediction renders the web before the server validates the
        // placement. Keep observing through one measured round-trip so a
        // strict-server correction cannot be mistaken for success.
        if (this.placeConfirmTicks < requiredServerSettleTicks(client)) {
            return;
        }
        boolean strictWait =
                this.waitConfirmRotation.get() && this.phase == WebActionPhase.CLICKING_TO_PLACE;
        clearPlacementConfirmation();
        this.remainingCooldownTicks = (int) Math.ceil(this.cooldownSeconds.get() * 20.0D);
        if (this.triggerMode.get().equals("single")) {
            this.disableAfterPlacement = true;
            clearPendingAttack();
        }
        if (strictWait) {
            beginReturnRotation(client);
        }
    }

    /** Let the current hold/return finish before disabling; never start a second placement. */
    private boolean finishSingleTrigger(Minecraft client) {
        if (!this.disableAfterPlacement || isBusy()) return false;
        setEnabled(client, false);
        return true;
    }

    private void finishPostPlaceHold(Minecraft client) {
        if (this.postPlaceHoldRemainingTicks <= 0) {
            restoreHeldSlot(client);
        }
    }

    private void confirmReturnRotation(Minecraft client) {
        if (!SilentPacketRotation.isRotationPacketSent()) {
            return;
        }
        if (AngleMath.withinRotationTolerance(
                client.player.getYRot() - SilentPacketRotation.getSentYaw(),
                client.player.getXRot() - SilentPacketRotation.getSentPitch(),
                0.35F)) {
            if (this.postPlaceHoldRemainingTicks <= 0) {
                restoreHeldSlot(client);
            } else {
                // RotationA is already back at the camera. Continue only
                // the independently configured hotbar hold timer.
                SilentPacketRotation.reset();
                this.phase = WebActionPhase.HOLDING_AFTER_PLACE;
            }
        } else {
            // The user moved the real camera after the final return
            // packet was sampled. Follow the new angle smoothly rather
            // than exposing that movement as a one-packet snap.
            beginReturnRotation(client);
        }
    }

    /** Ends an unsuccessful attempt without treating it as a placed web. */
    private void failPlacement(Minecraft client) {
        clearPendingPlan();
        this.postPlaceHoldRemainingTicks = 0;
        this.postPlaceHoldStarted = false;
        clearPlacementConfirmation();
        restoreWebSlotSelection(client);
        beginReturnRotation(client);
    }

    private void beginWebHold(Minecraft client, PlacementPlan plan) {
        int webSlot = findWebSlot(client);
        if (webSlot == -1) {
            clearPendingPlan();
            return;
        }

        Inventory inventory = client.player.getInventory();
        this.heldPlan = plan;
        this.postPlaceHoldRemainingTicks = 0;
        this.postPlaceHoldStarted = false;
        clearPlacementConfirmation();
        this.heldWebSlot = webSlot;
        this.originalSlot = inventory.getSelectedSlot();
        inventory.setSelectedSlot(webSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_WEB);

        this.phase = WebActionPhase.TURNING_TO_PLACE;
        SilentPacketRotation.beginRotation(
                client,
                plan.hit().getLocation(),
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> this.phase = WebActionPhase.WAITING_FOR_PLACE_ROTATION);
        if (this.phase == WebActionPhase.WAITING_FOR_PLACE_ROTATION) {
            tickPlacement(client);
        }
    }

    private void beginReturnRotation(Minecraft client) {
        this.phase = WebActionPhase.TURNING_BACK;
        SilentPacketRotation.beginReturnToCamera(
                client,
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> this.phase = WebActionPhase.WAITING_FOR_RETURN);
    }

    /**
     * Searches one coherent trajectory instead of treating the player as a
     * single BlockPos. Every sample scores feet/body overlap, the swept eye
     * path and any wall support in the direction of travel.
     */
    private PlacementPlan findTrajectoryPlan(
            Minecraft client,
            Player target,
            Vec3 initialVelocity,
            int horizon,
            boolean requireWallCollision,
            boolean upperBodyOnly,
            boolean groundOnly) {
        Vec3 position = target.position();
        Vec3 velocity = initialVelocity;
        boolean grounded = target.onGround();
        AABB previousBox = target.getBoundingBox();
        Vec3 previousFeet = position;
        Vec3 previousEye = predictedEyePosition(target, position);
        PlacementPlan best = null;

        for (int tick = 0; tick <= horizon; tick++) {
            Vec3 incomingVelocity = velocity;
            if (tick > 0) {
                TrajectoryStep step =
                        TrajectoryPrediction.advance(client, target, position, velocity, grounded);
                position = step.position();
                velocity = step.velocity();
                grounded = step.grounded();
            }

            AABB targetBox = target.getBoundingBox().move(position.subtract(target.position()));
            Vec3 predictedEye = predictedEyePosition(target, position);
            PlacementPlan candidate =
                    findBestVoxel(
                            client,
                            target,
                            targetBox,
                            requireWallCollision ? targetBox : previousBox,
                            position,
                            requireWallCollision ? position : previousFeet,
                            predictedEye,
                            requireWallCollision ? predictedEye : previousEye,
                            incomingVelocity,
                            tick,
                            horizon,
                            requireWallCollision,
                            upperBodyOnly,
                            groundOnly);
            if (candidate != null && (best == null || candidate.score() < best.score())) {
                best = candidate;
            }
            previousBox = targetBox;
            previousFeet = position;
            previousEye = predictedEye;
        }
        return best;
    }

    private PlacementPlan findBestVoxel(
            Minecraft client,
            Player target,
            AABB targetBox,
            AABB previousBox,
            Vec3 predictedFeet,
            Vec3 previousFeet,
            Vec3 predictedEye,
            Vec3 previousEye,
            Vec3 velocity,
            int predictionTick,
            int horizon,
            boolean requireWallCollision,
            boolean upperBodyOnly,
            boolean groundOnly) {
        int minX = Mth.floor(Math.min(targetBox.minX, previousBox.minX) + 1.0E-4D);
        int maxX = Mth.floor(Math.max(targetBox.maxX, previousBox.maxX) - 1.0E-4D);
        int minY = Mth.floor(Math.min(targetBox.minY, previousBox.minY) + 1.0E-4D);
        int maxY = Mth.floor(Math.max(targetBox.maxY, previousBox.maxY) - 1.0E-4D);
        int minZ = Mth.floor(Math.min(targetBox.minZ, previousBox.minZ) + 1.0E-4D);
        int maxZ = Mth.floor(Math.max(targetBox.maxZ, previousBox.maxZ) - 1.0E-4D);
        PlacementPlan best = null;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos placePos = new BlockPos(x, y, z);
                    AABB voxel = new AABB(placePos);
                    double upperBodyCenterY =
                            targetBox.minY + (targetBox.maxY - targetBox.minY) * 0.5D;
                    if (upperBodyOnly && placePos.getY() + 0.5D < upperBodyCenterY) {
                        continue;
                    }
                    if (groundOnly && placePos.getY() != Mth.floor(predictedFeet.y + 0.05D)) {
                        continue;
                    }
                    boolean intersectsFeet =
                            intersectsPointPath(voxel, previousFeet, predictedFeet);
                    boolean intersectsEye = intersectsPointPath(voxel, previousEye, predictedEye);
                    if ((!voxel.intersects(targetBox)
                                    && !voxel.intersects(previousBox)
                                    && !intersectsFeet
                                    && !intersectsEye)
                            || !isReplaceableForWeb(client, placePos)
                            || !isSafeForPlayer(client, placePos, predictionTick)) {
                        continue;
                    }

                    WallGeometry wall =
                            groundOnly
                                    ? WallGeometry.NONE
                                    : findWallGeometry(client, placePos, targetBox, velocity);
                    if (requireWallCollision && !wall.collisionOpportunity()) {
                        continue;
                    }

                    BlockHitResult hit =
                            groundOnly
                                    ? supportHit(client, placePos, Direction.DOWN)
                                    : findSupportHit(client, placePos, wall.direction());
                    if (hit == null || !withinPlacementRange(client, hit.getLocation())) {
                        continue;
                    }

                    double wallSupport = wall.direction() == null ? 0.0D : 1.0D;
                    int rawLeadTicks = 1 + (requireWallCollision ? 0 : this.delayTicks.get());
                    int leadTicks = Math.max(1, Math.min(Math.max(1, horizon), rawLeadTicks));
                    double leadError = (double) Math.abs(leadTicks - predictionTick) / leadTicks;
                    // Score against the moment the use packet can actually be
                    // handled. The old wall score only punished early samples;
                    // a visually attractive wall several ticks too far ahead
                    // could therefore beat the next-tick lateral intercept.
                    double predictionError = leadError * leadError;
                    double placementDelay = leadError;
                    double eyeDistance =
                            client.player.getEyePosition().distanceTo(hit.getLocation());
                    double rotationCost = rotationCost(client, hit.getLocation());
                    double entityOcclusion =
                            requireWallCollision
                                            && rayBlockedByEntity(
                                                    client,
                                                    client.player.getEyePosition(),
                                                    hit.getLocation())
                                    ? 1.0D
                                    : 0.0D;

                    // Lower is better. At a wall, use the footprint at contact:
                    // a small eye-path sliver must not beat the main body cell.
                    // An entity crossing the visual ray is a risk signal, not
                    // a hard rejection: the queued use carries the explicit
                    // support face and the target normally stands between us
                    // and the wall by definition.
                    double score =
                            coverageScore(
                                            voxel,
                                            targetBox,
                                            previousBox,
                                            intersectsEye,
                                            intersectsFeet,
                                            requireWallCollision)
                                    - wall.coverage() * 2.0D
                                    - wallSupport * 2.0D
                                    - wall.alignment() * 2.0D
                                    + predictionError * 3.0D
                                    + placementDelay * 2.0D
                                    + entityOcclusion * 0.75D
                                    + rotationCost * 0.65D
                                    + eyeDistance * 0.08D;
                    PlacementPlan candidate =
                            new PlacementPlan(
                                    target.getId(),
                                    placePos,
                                    hit,
                                    predictionTick,
                                    score,
                                    groundOnly);
                    if (best == null || candidate.score() < best.score()) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** Attack-only WallWeb + PredictWeb + FaceWeb opportunity search. */
    private PlacementPlan findWallPlan(Minecraft client, Player target) {
        return findWallPlan(client, target, false);
    }

    private PlacementPlan findWallPlan(Minecraft client, Player target, boolean upperBodyOnly) {
        Vec3 velocity = TrajectoryPrediction.observedVelocity(target);
        int horizon =
                Math.clamp(
                        this.predictionTicks.get(),
                        MIN_WALL_PREDICTION_TICKS,
                        MAX_WALL_PREDICTION_TICKS);
        return findTrajectoryPlan(client, target, velocity, horizon, true, upperBodyOnly, false);
    }

    private static Vec3 predictedEyePosition(Player target, Vec3 feetPosition) {
        return feetPosition.add(0.0D, target.getEyeHeight(), 0.0D);
    }

    private static boolean intersectsPointPath(AABB voxel, Vec3 start, Vec3 end) {
        return voxel.contains(end.x, end.y, end.z)
                || voxel.contains(start.x, start.y, start.z)
                || voxel.clip(start, end).isPresent();
    }

    private static double intersectionRatio(AABB voxel, AABB targetBox) {
        double x =
                Math.max(
                        0.0D,
                        Math.min(voxel.maxX, targetBox.maxX)
                                - Math.max(voxel.minX, targetBox.minX));
        double y =
                Math.max(
                        0.0D,
                        Math.min(voxel.maxY, targetBox.maxY)
                                - Math.max(voxel.minY, targetBox.minY));
        double z =
                Math.max(
                        0.0D,
                        Math.min(voxel.maxZ, targetBox.maxZ)
                                - Math.max(voxel.minZ, targetBox.minZ));
        double targetVolume =
                (targetBox.maxX - targetBox.minX)
                        * (targetBox.maxY - targetBox.minY)
                        * (targetBox.maxZ - targetBox.minZ);
        return targetVolume <= 1.0E-8D ? 0.0D : x * y * z / targetVolume;
    }

    private static double coverageScore(
            AABB voxel,
            AABB targetBox,
            AABB previousBox,
            boolean intersectsEye,
            boolean intersectsFeet,
            boolean wall) {
        double footprint = horizontalIntersectionRatio(voxel, targetBox);
        double eye = intersectsEye ? (wall ? footprint : 1.0D) : 0.0D;
        double overlap =
                Math.max(
                        intersectionRatio(voxel, targetBox), intersectionRatio(voxel, previousBox));
        return -eye * 4.0D
                - overlap * 3.0D
                - (wall ? footprint * 6.0D : 0.0D)
                - (intersectsFeet ? 1.25D : 0.0D);
    }

    private static double horizontalIntersectionRatio(AABB voxel, AABB targetBox) {
        double x =
                Math.max(
                        0.0D,
                        Math.min(voxel.maxX, targetBox.maxX)
                                - Math.max(voxel.minX, targetBox.minX));
        double z =
                Math.max(
                        0.0D,
                        Math.min(voxel.maxZ, targetBox.maxZ)
                                - Math.max(voxel.minZ, targetBox.minZ));
        double area = (targetBox.maxX - targetBox.minX) * (targetBox.maxZ - targetBox.minZ);
        return area <= 1.0E-8D ? 0.0D : x * z / area;
    }

    private WallGeometry findWallGeometry(
            Minecraft client, BlockPos placePos, AABB targetBox, Vec3 velocity) {
        Vec3 expectedTravel = horizontalDirection(velocity);

        Direction bestDirection = null;
        double bestAlignment = 0.0D;
        double bestCoverage = 0.0D;
        double bestGap = Double.POSITIVE_INFINITY;
        for (Direction direction : this.horizontalDirections) {
            double alignment =
                    expectedTravel.x * direction.getStepX()
                            + expectedTravel.z * direction.getStepZ();
            if (alignment < -RAY_EPSILON) {
                continue;
            }
            // A narrow wall may touch only one side of the body. Establish
            // contact across the whole footprint, then let the web candidate
            // use any reachable support face in its own (possibly wider) cell.
            boolean xFace = direction.getAxis() == Direction.Axis.X;
            int first = Mth.floor((xFace ? targetBox.minZ : targetBox.minX) + RAY_EPSILON);
            int last = Mth.floor((xFace ? targetBox.maxZ : targetBox.maxX) - RAY_EPSILON);
            for (int lateral = first; lateral <= last; lateral++) {
                BlockPos wallPos =
                        switch (direction) {
                            case EAST ->
                                    new BlockPos(
                                            Mth.floor(targetBox.maxX + RAY_EPSILON),
                                            placePos.getY(),
                                            lateral);
                            case WEST ->
                                    new BlockPos(
                                            Mth.floor(targetBox.minX - RAY_EPSILON),
                                            placePos.getY(),
                                            lateral);
                            case SOUTH ->
                                    new BlockPos(
                                            lateral,
                                            placePos.getY(),
                                            Mth.floor(targetBox.maxZ + RAY_EPSILON));
                            case NORTH ->
                                    new BlockPos(
                                            lateral,
                                            placePos.getY(),
                                            Mth.floor(targetBox.minZ - RAY_EPSILON));
                            default -> throw new IllegalStateException("Expected horizontal wall");
                        };
                double gap = wallGap(client, targetBox, wallPos, direction);
                if (!Double.isFinite(gap) || gap > RAY_EPSILON) continue;
                double coverage = wallContactCoverage(client, targetBox, wallPos, direction);
                if (coverage > bestCoverage
                        || (coverage > 0.0D
                                && Math.abs(coverage - bestCoverage) < 1.0E-6D
                                && alignment > bestAlignment)) {
                    bestDirection = direction;
                    bestAlignment = alignment;
                    bestCoverage = coverage;
                    bestGap = gap;
                }
            }
        }

        if (bestDirection == null) {
            return WallGeometry.NONE;
        }
        // This sample has reached the wall after collision resolution, so its
        // lateral overlap is the contact area, not a pre-impact guess.
        return new WallGeometry(bestDirection, bestAlignment, bestGap, bestCoverage, true);
    }

    private static double wallContactCoverage(
            Minecraft client, AABB targetBox, BlockPos wallPos, Direction direction) {
        double best = 0.0D;
        for (AABB local :
                client.level
                        .getBlockState(wallPos)
                        .getCollisionShape(client.level, wallPos)
                        .toAabbs()) {
            best = Math.max(best, wallFaceCoverage(targetBox, local.move(wallPos), direction));
        }
        return best;
    }

    private static double wallFaceCoverage(AABB targetBox, AABB shape, Direction direction) {
        boolean xFace = direction.getAxis() == Direction.Axis.X;
        double faceArea =
                (targetBox.maxY - targetBox.minY)
                        * (xFace
                                ? targetBox.maxZ - targetBox.minZ
                                : targetBox.maxX - targetBox.minX);
        if (faceArea <= 1.0E-8D) return 0.0D;
        double gap =
                switch (direction) {
                    case EAST -> shape.minX - targetBox.maxX;
                    case WEST -> targetBox.minX - shape.maxX;
                    case SOUTH -> shape.minZ - targetBox.maxZ;
                    case NORTH -> targetBox.minZ - shape.maxZ;
                    default -> Double.POSITIVE_INFINITY;
                };
        if (Math.abs(gap) > RAY_EPSILON) return 0.0D;
        double vertical =
                Math.max(
                        0.0D,
                        Math.min(targetBox.maxY, shape.maxY)
                                - Math.max(targetBox.minY, shape.minY));
        double lateral =
                xFace
                        ? Math.min(targetBox.maxZ, shape.maxZ)
                                - Math.max(targetBox.minZ, shape.minZ)
                        : Math.min(targetBox.maxX, shape.maxX)
                                - Math.max(targetBox.minX, shape.minX);
        return vertical * Math.max(0.0D, lateral) / faceArea;
    }

    private static double wallGap(
            Minecraft client, AABB targetBox, BlockPos wallPos, Direction direction) {
        BlockState wallState = client.level.getBlockState(wallPos);
        double bestGap = Double.POSITIVE_INFINITY;
        for (AABB localShape : wallState.getCollisionShape(client.level, wallPos).toAabbs()) {
            AABB wallShape = localShape.move(wallPos.getX(), wallPos.getY(), wallPos.getZ());
            boolean overlapsPerpendicular =
                    switch (direction) {
                        case EAST, WEST ->
                                overlaps(
                                                targetBox.minY,
                                                targetBox.maxY,
                                                wallShape.minY,
                                                wallShape.maxY)
                                        && overlaps(
                                                targetBox.minZ,
                                                targetBox.maxZ,
                                                wallShape.minZ,
                                                wallShape.maxZ);
                        case NORTH, SOUTH ->
                                overlaps(
                                                targetBox.minX,
                                                targetBox.maxX,
                                                wallShape.minX,
                                                wallShape.maxX)
                                        && overlaps(
                                                targetBox.minY,
                                                targetBox.maxY,
                                                wallShape.minY,
                                                wallShape.maxY);
                        default -> false;
                    };
            if (!overlapsPerpendicular) {
                continue;
            }
            double gap =
                    switch (direction) {
                        case EAST -> wallShape.minX - targetBox.maxX;
                        case WEST -> targetBox.minX - wallShape.maxX;
                        case SOUTH -> wallShape.minZ - targetBox.maxZ;
                        case NORTH -> targetBox.minZ - wallShape.maxZ;
                        default -> Double.POSITIVE_INFINITY;
                    };
            if (gap >= -RAY_EPSILON) {
                bestGap = Math.min(bestGap, Math.max(0.0D, gap));
            }
        }
        return bestGap;
    }

    private static boolean overlaps(
            double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin + 1.0E-5D && firstMin < secondMax - 1.0E-5D;
    }

    /** Whether any player entity's collision box blocks the ray between the points. */
    private boolean rayBlockedByEntity(Minecraft client, Vec3 eye, Vec3 target) {
        return this.rays.entityBlocked(client, eye, target);
    }

    /** Follow measured displacement; stale delta movement can retain old knockback. */
    private static Vec3 horizontalDirection(Vec3 vector) {
        double length = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        return length < 1.0E-8D ? Vec3.ZERO : new Vec3(vector.x / length, 0.0D, vector.z / length);
    }

    private BlockHitResult findSupportHit(
            Minecraft client, BlockPos placePos, Direction preferredSupportDirection) {
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Direction supportDirection : this.supportDirections) {
            BlockHitResult hit = supportHit(client, placePos, supportDirection);
            if (hit == null || !withinPlacementRange(client, hit.getLocation())) {
                continue;
            }
            double score =
                    rotationCost(client, hit.getLocation())
                            + client.player.getEyePosition().distanceTo(hit.getLocation()) * 0.015D;
            if (supportDirection == preferredSupportDirection) {
                score -= 1.25D;
            } else if (supportDirection == Direction.DOWN) {
                score -= 0.08D;
            }
            if (score < bestScore) {
                best = hit;
                bestScore = score;
            }
        }
        return best;
    }

    private BlockHitResult supportHit(
            Minecraft client, BlockPos placePos, Direction supportDirection) {
        BlockPos supportPos = placePos.relative(supportDirection);
        BlockState supportState = client.level.getBlockState(supportPos);
        if (supportState.getCollisionShape(client.level, supportPos).isEmpty()) {
            return null;
        }

        Direction supportFace = supportDirection.getOpposite();
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double first : this.faceSamples) {
            for (double second : this.faceSamples) {
                Vec3 requested = pointOnFace(supportPos, supportFace, first, second);
                BlockHitResult actual =
                        this.rays.visibleFaceHit(
                                client,
                                client.player.getEyePosition(),
                                supportPos,
                                supportFace,
                                requested,
                                RAY_EPSILON);
                if (actual == null || !withinPlacementRange(client, actual.getLocation())) {
                    continue;
                }
                double centerOffset = Math.abs(first - 0.5D) + Math.abs(second - 0.5D);
                double score = rotationCost(client, actual.getLocation()) + centerOffset * 0.04D;
                if (score < bestScore) {
                    best = actual;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(
            BlockPos supportPos, Direction face, double first, double second) {
        return BlockPlacementUtils.fullBlockFacePoint(supportPos, face, first, second);
    }

    private static double rotationCost(Minecraft client, Vec3 point) {
        Vec3 toward = point.subtract(client.player.getEyePosition()).normalize();
        Vec3 look = client.player.getLookAngle().normalize();
        return 1.0D - Math.clamp(look.dot(toward), -1.0D, 1.0D);
    }

    private BlockHitResult validatePlan(Minecraft client, PlacementPlan plan) {
        Player target =
                client.level.getEntity(plan.targetId()) instanceof Player player ? player : null;
        if (target == null || !Targeting.isValidTargetPlayer(client, target)) {
            return null;
        }
        if (isTrappedInWeb(client, target)) {
            return null;
        }

        if (plan.groundOnly()) {
            Vec3 velocity = TrajectoryPrediction.observedVelocity(target);
            // Rotation may have waited a tick or longer. Do not click an old
            // feet cell after another knockback, jump or direction change.
            if (!target.onGround()
                    || Math.hypot(velocity.x, velocity.z) > this.groundMaxSpeed.get()) {
                return null;
            }
            PlacementPlan current = findLandingGroundPlan(client, target);
            if (current == null || !current.placePos().equals(plan.placePos())) {
                return null;
            }
        } else {
            PlacementPlan current = findWallPlan(client, target);
            if (current == null || !current.placePos().equals(plan.placePos())) {
                return null;
            }
        }

        if (!isSafeForPlayer(client, plan.placePos(), plan.predictionTick())) {
            return null;
        }
        if (!isReplaceableForWeb(client, plan.placePos())) {
            return null;
        }

        Vec3 eye = client.player.getEyePosition();
        double reach = Math.min(this.range.get(), client.player.blockInteractionRange());
        BlockHitResult actual =
                this.rays.traceOutline(
                        client,
                        eye,
                        SilentPacketRotation.getInteractionLookVector(client),
                        reach,
                        ClipContext.Fluid.NONE,
                        plan.hit().getBlockPos());
        if (actual.getType() != HitResult.Type.BLOCK
                || !actual.getBlockPos().equals(plan.hit().getBlockPos())
                || actual.getDirection() != plan.hit().getDirection()
                || !withinPlacementRange(client, actual.getLocation())) {
            return null;
        }

        BlockPos resultingPos = actual.getBlockPos().relative(actual.getDirection());
        if (!resultingPos.equals(plan.placePos())) {
            return null;
        }
        return new BlockHitResult(
                actual.getLocation(), actual.getDirection(),
                actual.getBlockPos(), actual.isInside());
    }

    private static boolean isReplaceableForWeb(Minecraft client, BlockPos placePos) {
        BlockState state = client.level.getBlockState(placePos);
        return !state.is(Blocks.COBWEB) && state.canBeReplaced();
    }

    private static boolean isSafeForPlayer(
            Minecraft client, BlockPos placePos, int predictionTick) {
        AABB webBox = new AABB(placePos);
        AABB currentPlayerBox =
                client.player
                        .getBoundingBox()
                        .inflate(SELF_SAFETY_MARGIN, 0.05D, SELF_SAFETY_MARGIN);
        AABB predictedPlayerBox =
                TrajectoryPrediction.freeFlightBox(client.player, predictionTick)
                        .inflate(SELF_SAFETY_MARGIN, 0.05D, SELF_SAFETY_MARGIN);
        AABB sweptPlayerBox =
                new AABB(
                        Math.min(currentPlayerBox.minX, predictedPlayerBox.minX),
                        Math.min(currentPlayerBox.minY, predictedPlayerBox.minY),
                        Math.min(currentPlayerBox.minZ, predictedPlayerBox.minZ),
                        Math.max(currentPlayerBox.maxX, predictedPlayerBox.maxX),
                        Math.max(currentPlayerBox.maxY, predictedPlayerBox.maxY),
                        Math.max(currentPlayerBox.maxZ, predictedPlayerBox.maxZ));
        return !webBox.intersects(sweptPlayerBox);
    }

    /** CurrentWeb guard: an already-intersecting cobweb means the target is trapped. */
    private static boolean isTrappedInWeb(Minecraft client, Player target) {
        AABB box = target.getBoundingBox();
        int minX = Mth.floor(box.minX + 1.0E-4D);
        int minY = Mth.floor(box.minY + 1.0E-4D);
        int minZ = Mth.floor(box.minZ + 1.0E-4D);
        int maxX = Mth.floor(box.maxX - 1.0E-4D);
        int maxY = Mth.floor(box.maxY - 1.0E-4D);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4D);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (client.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean withinPlacementRange(Minecraft client, Vec3 hitLocation) {
        double reach = Math.min(this.range.get(), client.player.blockInteractionRange());
        return BlockPlacementUtils.withinReach(client.player.getEyePosition(), hitLocation, reach);
    }

    private static int findWebSlot(Minecraft client) {
        return HotbarQueries.firstMatch(
                client.player.getInventory(),
                stack -> !stack.isEmpty() && stack.getItem() == Items.COBWEB);
    }

    private void resetPlan(Minecraft client) {
        if (isBusy()) {
            restoreHeldSlot(client);
        }
        clearPlacementConfirmation();
        clearPendingPlan();
        clearPendingAttack();
    }

    private void resetAll(Minecraft client) {
        resetPlan(client);
        this.remainingCooldownTicks = 0;
        this.disableAfterPlacement = false;
    }

    public boolean isBusy() {
        return this.heldPlan != null || this.phase != WebActionPhase.IDLE;
    }

    private void restoreHeldSlot(Minecraft client) {
        restoreWebSlotSelection(client);
        CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_WEB);
        this.heldPlan = null;
        this.originalSlot = -1;
        this.postPlaceHoldRemainingTicks = 0;
        this.postPlaceHoldStarted = false;
        this.phase = WebActionPhase.IDLE;
        SilentPacketRotation.reset();
    }

    private void restoreWebSlotSelection(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client != null && currentPlayer != null && this.heldWebSlot != -1) {
            Inventory inventory = currentPlayer.getInventory();
            if (inventory.getSelectedSlot() == this.heldWebSlot && this.originalSlot >= 0) {
                inventory.setSelectedSlot(this.originalSlot);
            }
        }
        this.heldWebSlot = -1;
    }

    private static void clearPendingPlan() {}

    private void clearPlacementConfirmation() {
        this.pendingPlaceConfirmationPos = null;
        this.placeConfirmTicks = 0;
    }

    private void clearPendingAttack() {
        this.pendingAttackTargetId = -1;
        this.pendingAttackTicks = 0;
        this.pendingWallAttempted = false;
        this.groundLandingWindow.clear();
    }

    public String statusText() {
        return this.enabled.get() ? "enabled" : "disabled";
    }

    public String hudTag() {
        return this.triggerMode.get().equals("single") ? "Single" : "Continuous";
    }

    public ModuleModel.Setting triggerModeSetting() {
        return this.triggerMode.describe("trigger_mode", "Trigger mode", this::setTriggerMode);
    }

    public int setTriggerMode(Minecraft client, String value) {
        if (!this.triggerMode.tryDeserialize(value))
            throw new IllegalArgumentException(
                    "AutoWeb trigger mode must be continuous or single.");
        if (this.triggerMode.get().equals("continuous")) this.disableAfterPlacement = false;
        return 1;
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoWeb: "
                        + statusText()
                        + ", trigger: "
                        + this.triggerMode.serialized()
                        + ", range: "
                        + NumberText.compactDouble(this.range.get())
                        + ", delay: "
                        + this.delayTicks.get()
                        + "t"
                        + ", cooldown: "
                        + NumberText.compactDouble(this.cooldownSeconds.get())
                        + "s"
                        + ", hold: "
                        + this.holdTicks.get()
                        + "t"
                        + ", rotation: instant"
                        + ", prediction: "
                        + this.predictionTicks.get()
                        + "t"
                        + ", chance: "
                        + NumberText.compactDouble(this.chance.get())
                        + ", wall: "
                        + (this.wallEnabled.get() ? "enabled" : "disabled")
                        + ", ground: "
                        + (this.groundEnabled.get() ? "enabled" : "disabled")
                        + ", wait confirm rotation: "
                        + (this.waitConfirmRotation.get() ? "enabled" : "disabled")
                        + " (post-hit landing window)"
                        + ". Usage: .tempos autoweb <enable|disable|trigger_mode continuous/single|range 2-6|delay 0-100|hold 0-20|cooldown 0-30|prediction 0-12|chance 0-1|wall|ground|wait_confirm_rotation>.");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean newEnabled) {
        this.enabled.set(newEnabled);
        resetAll(client);
        ClientChat.send(client, "AutoWeb " + statusText() + ".");
        return 1;
    }

    public int setWallEnabled(Minecraft client, boolean value) {
        this.wallEnabled.set(value);
        resetPlan(client);
        ClientChat.send(
                client,
                "AutoWeb wall webs "
                        + (value ? "enabled" : "disabled")
                        + " (predictive attack extension; immediate feet remains active).");
        return 1;
    }

    public int setGroundEnabled(Minecraft client, boolean value) {
        this.groundEnabled.set(value);
        resetPlan(client);
        ClientChat.send(
                client,
                "AutoWeb flat-ground prediction "
                        + (value ? "enabled" : "disabled")
                        + " (post-hit landing only).");
        return 1;
    }

    public int setGroundWindow(Minecraft client, int value) {
        this.groundWindowTicks.set(value);
        clearPendingAttack();
        return 1;
    }

    public int setGroundMaxSpeed(Minecraft client, double value) {
        this.groundMaxSpeed.set(value);
        return 1;
    }

    public int setGroundMaxRelativeSpeed(Minecraft client, double value) {
        this.groundMaxRelativeSpeed.set(value);
        return 1;
    }

    public boolean groundEnabled() {
        return this.groundEnabled.get();
    }

    public int setWaitConfirmRotation(Minecraft client, boolean value) {
        this.waitConfirmRotation.set(value);
        ClientChat.send(
                client,
                "AutoWeb wait for confirmation before rotation return "
                        + (value ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public int setRange(Minecraft client, double value) {
        this.range.set(value);
        resetPlan(client);
        ClientChat.send(
                client, "AutoWeb range set to " + NumberText.compactDouble(this.range.get()) + ".");
        return 1;
    }

    public int setDelay(Minecraft client, int value) {
        this.delayTicks.set(value);
        clearPendingPlan();
        ClientChat.send(
                client, "AutoWeb execution delay set to " + this.delayTicks.get() + " ticks.");
        return 1;
    }

    public int setCooldown(Minecraft client, double value) {
        this.cooldownSeconds.set(value);
        ClientChat.send(
                client,
                "AutoWeb successful-placement cooldown set to "
                        + NumberText.compactDouble(this.cooldownSeconds.get())
                        + " seconds.");
        return 1;
    }

    public int setHoldTicks(Minecraft client, int value) {
        this.holdTicks.set(value);
        ClientChat.send(
                client, "AutoWeb post-place hold set to " + this.holdTicks.get() + " ticks.");
        return 1;
    }

    public int setPrediction(Minecraft client, int value) {
        this.predictionTicks.set(value);
        resetPlan(client);
        ClientChat.send(
                client, "AutoWeb prediction set to " + this.predictionTicks.get() + " ticks.");
        return 1;
    }

    public int setChance(Minecraft client, double value) {
        this.chance.set(value);
        ClientChat.send(
                client,
                "AutoWeb trigger chance set to "
                        + NumberText.compactDouble(this.chance.get())
                        + ".");
        return 1;
    }

    private static int requiredServerSettleTicks(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        var connectionSnapshot = client == null ? null : client.getConnection();
        int latencyMs = 0;
        if (connectionSnapshot != null && currentPlayer != null) {
            var info = connectionSnapshot.getPlayerInfo(currentPlayer.getUUID());
            if (info != null) {
                latencyMs = Math.max(0, info.getLatency());
            }
        }
        return Math.clamp((int) Math.ceil(latencyMs / 50.0D) + 3, 8, MAX_PLACE_CONFIRM_TICKS);
    }

    private enum WebActionPhase {
        IDLE,
        TURNING_TO_PLACE,
        WAITING_FOR_PLACE_ROTATION,
        CLICKING_TO_PLACE,
        HOLDING_AFTER_PLACE,
        TURNING_BACK,
        WAITING_FOR_RETURN
    }

    private record WallGeometry(
            Direction direction,
            double alignment,
            double gap,
            double coverage,
            boolean collisionOpportunity) {
        private static final WallGeometry NONE =
                new WallGeometry(null, 0.0D, Double.POSITIVE_INFINITY, 0.0D, false);
    }

    private record PlacementPlan(
            int targetId,
            BlockPos placePos,
            BlockHitResult hit,
            int predictionTick,
            double score,
            boolean groundOnly) {}

    /** End pending work; a confirmed single use stays consumed across context changes. */
    public void shutdown(Minecraft client) {
        if (this.disableAfterPlacement) this.enabled.set(false);
        resetAll(client);
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("autoweb.enabled", false);
    }

    @Override
    protected String statusTag() {
        return this.hudTag();
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "autoweb",
                new Setting[] {
                    this.triggerModeSetting(),
                    range.describeStored("range", "Range", .1, this::setRange),
                    delayTicks.describeStored("delay", "Delay ticks", 1, this::setDelay),
                    holdTicks.describeStored("hold", "Hold ticks", 1, this::setHoldTicks),
                    cooldownSeconds.describeStored("cooldown", "Cooldown", .05, this::setCooldown),
                    predictionTicks.describeStored(
                            "prediction", "Prediction", 1, this::setPrediction),
                    chance.describeStored("chance", "Chance", .01, this::setChance),
                    wallEnabled.describeStored("wall", "Wall webs", this::setWallEnabled),
                    groundEnabled.describeStored("ground", "Ground webs", this::setGroundEnabled),
                    groundWindowTicks
                            .describeStored(
                                    "ground_window",
                                    "Landing window ticks",
                                    1,
                                    this::setGroundWindow)
                            .visibleWhen(this::groundEnabled),
                    groundMaxSpeed
                            .describeStored(
                                    "ground_max_speed",
                                    "Max target speed (blocks/tick)",
                                    .01,
                                    this::setGroundMaxSpeed)
                            .visibleWhen(this::groundEnabled),
                    groundMaxRelativeSpeed
                            .describeStored(
                                    "ground_max_relative_speed",
                                    "Max relative speed (blocks/tick)",
                                    .01,
                                    this::setGroundMaxRelativeSpeed)
                            .visibleWhen(this::groundEnabled),
                    waitConfirmRotation.describeStored(
                            "wait_confirm_rotation",
                            "Wait confirm rotation",
                            this::setWaitConfirmRotation)
                });
    }
}
