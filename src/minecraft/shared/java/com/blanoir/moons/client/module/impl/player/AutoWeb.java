/*
 * AutoWeb for Moons.
 *
 * Two triggers share one instant silent packet-RotationA placement path (the
 * first-person camera stays fixed while the third-person model turns):
 * - Auto: scores feet, body and eye voxels along the target's trajectory.
 * - Wall (attack only): follows observed knockback, resolves the next 3-6 ticks
 *   against block collisions and scores body coverage at the wall contact.
 * A short cooldown after each successful placement keeps the pressure
 * continuous without spamming the same cell.
 */
package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.PostHitLandingWindow;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction.TrajectoryStep;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

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

public final class AutoWeb {
    private static final int MIN_WALL_PREDICTION_TICKS = 3;
    private static final int MAX_WALL_PREDICTION_TICKS = 6;
    private static final int ATTACK_REQUEST_LIFETIME_TICKS = 24;
    private static final int MAX_PLACE_CONFIRM_TICKS = 40;
    private static final int MAX_HOLD_TICKS = 20;
    private static final Direction[] SUPPORT_DIRECTIONS = {
        Direction.DOWN,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.UP
    };
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final double SELF_SAFETY_MARGIN = 0.28D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 0.5D;
    private static final double MIN_COOLDOWN_SECONDS = 0.0D;
    private static final double MAX_COOLDOWN_SECONDS = 30.0D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final double[] FACE_SAMPLES = {0.18D, 0.5D, 0.82D};

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autoweb.enabled").defaultValue(false).build();

    private static final BooleanSetting WALL_ENABLED =
            new BooleanSetting.Builder().name("autoweb.wall").defaultValue(false).build();

    private static final BooleanSetting GROUND_ENABLED =
            new BooleanSetting.Builder().name("autoweb.ground").defaultValue(true).build();

    private static final IntSetting GROUND_WINDOW_TICKS =
            new IntSetting.Builder()
                    .name("autoweb.groundWindow")
                    .defaultValue(5)
                    .range(1, 12)
                    .build();
    private static final DoubleSetting GROUND_MAX_SPEED =
            new DoubleSetting.Builder()
                    .name("autoweb.groundMaxSpeed")
                    .defaultValue(0.30D)
                    .range(0.05D, 1.5D)
                    .build();
    private static final DoubleSetting GROUND_MAX_RELATIVE_SPEED =
            new DoubleSetting.Builder()
                    .name("autoweb.groundMaxRelativeSpeed")
                    .defaultValue(0.45D)
                    .range(0.05D, 2.0D)
                    .build();

    private static final BooleanSetting WAIT_CONFIRM_ROTATION =
            new BooleanSetting.Builder()
                    .name("autoweb.waitConfirmRotation")
                    .defaultValue(false)
                    .build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("autoweb.range")
                    .defaultValue(4.5D)
                    .range(2.0D, 6.0D)
                    .build();

    private static final IntSetting DELAY_TICKS =
            new IntSetting.Builder().name("autoweb.delay").defaultValue(0).range(0, 100).build();

    private static final IntSetting PREDICTION_TICKS =
            new IntSetting.Builder()
                    .name("autoweb.prediction")
                    .defaultValue(4)
                    .range(0, 12)
                    .build();

    private static final IntSetting HOLD_TICKS =
            new IntSetting.Builder()
                    .name("autoweb.holdTicks")
                    .defaultValue(0)
                    .range(0, MAX_HOLD_TICKS)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("autoweb.chance")
                    .defaultValue(1.0D)
                    .range(0.0D, 1.0D)
                    .build();

    private static final DoubleSetting COOLDOWN_SECONDS =
            new DoubleSetting.Builder()
                    .name("autoweb.cooldown")
                    .defaultValue(DEFAULT_COOLDOWN_SECONDS)
                    .range(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
                    .build();

    private static int remainingCooldownTicks;
    private static PlacementPlan heldPlan;
    private static int heldWebSlot = -1;
    private static int originalSlot = -1;
    private static WebActionPhase phase = WebActionPhase.IDLE;
    private static String lastDecision = "waiting_attack";
    private static int pendingAttackTargetId = -1;
    private static int pendingAttackTicks;
    private static boolean pendingWallAttempted;
    private static final PostHitLandingWindow GROUND_LANDING_WINDOW = new PostHitLandingWindow();
    private static int postPlaceHoldRemainingTicks;
    private static boolean postPlaceHoldStarted;
    private static BlockPos pendingPlaceConfirmationPos;
    private static int placeConfirmTicks;

    private AutoWeb() {}

    public static void init() {
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoWeb.context", event -> shutdown(null));
        PlacementCoordinator.register(
                PlacementCoordinator.Owner.AUTO_WEB, AutoWeb::isBusy, AutoWeb::resetPlan);
        EventBus.PLAYER_UPDATE.register(
                "AutoWeb.playerUpdate",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get()) {
            resetAll(client);
            return;
        }

        if (!ClientReady.gameplay(client)) {
            resetAll(client);
            lastDecision = "not_ready";
            return;
        }

        if (remainingCooldownTicks > 0) {
            remainingCooldownTicks--;
        }
        if (pendingAttackTicks > 0) {
            pendingAttackTicks--;
            Player pendingTarget =
                    client.level.getEntity(pendingAttackTargetId) instanceof Player player
                            ? player
                            : null;
            GROUND_LANDING_WINDOW.update(pendingTarget, client.player.getDeltaMovement());
        } else {
            pendingAttackTargetId = -1;
            pendingWallAttempted = false;
            GROUND_LANDING_WINDOW.clear();
        }

        observePlacementConfirmation(client);

        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_WEB)) {
            lastDecision = "other_placement";
            return;
        }

        if (phase != WebActionPhase.IDLE || heldPlan != null) {
            tickPlacement(client);
            return;
        }
        if (remainingCooldownTicks > 0) {
            lastDecision = "cooldown";
            return;
        }
        // A fast return releases slot/rotation ownership immediately, but a
        // second web must not start until the first server result is known.
        if (pendingPlaceConfirmationPos != null) {
            lastDecision = "awaiting_confirmation";
            return;
        }
        processIdleTick(client);
    }

    private static void processIdleTick(Minecraft client) {

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
    public static void onAttack(Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (!ENABLED.get()
                || !ClientReady.gameplay(client)
                || !(target instanceof Player player)
                || !Targeting.isValidTargetPlayer(client, player)
                || isTrappedInWeb(client, player)
                || findWebSlot(client) == -1) {
            lastDecision = "attack_ineligible_or_no_web";
            return;
        }
        // Attack callbacks may run before or after LocalPlayer.tick depending
        // on the host client. Consume the request only from PLAYER_UPDATE so
        // selection, silent rotation, use and sendPosition own one tick.
        pendingAttackTargetId = player.getId();
        pendingAttackTicks = ATTACK_REQUEST_LIFETIME_TICKS;
        pendingWallAttempted = false;
        lastDecision = "attack_armed";
        if (GROUND_ENABLED.get()) {
            GROUND_LANDING_WINDOW.arm(
                    player, ATTACK_REQUEST_LIFETIME_TICKS, GROUND_WINDOW_TICKS.get());
        } else {
            GROUND_LANDING_WINDOW.clear();
        }
    }

    private static boolean tryExecutePendingAttack(Minecraft client) {
        if (pendingAttackTargetId == -1) {
            lastDecision = "waiting_attack";
            return false;
        }
        Player target =
                client.level.getEntity(pendingAttackTargetId) instanceof Player player
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

    private static boolean tryStartWallPlacement(Minecraft client, Player target) {
        if (pendingWallAttempted || !WALL_ENABLED.get()) {
            return false;
        }
        // Retry as the real knockback arrives. A guessed impulse or merely
        // being near a corner is not evidence that this wall will be reached.
        PlacementPlan plan = findWallPlan(client, target);
        if (plan == null) {
            return false;
        }
        pendingWallAttempted = true;
        return consumePlacementAttempt(client, plan);
    }

    private static boolean tryStartLandingPlacement(Minecraft client, Player target) {
        if (!GROUND_ENABLED.get()) {
            if (!WALL_ENABLED.get()) {
                clearPendingAttack();
            }
            return false;
        }
        PostHitLandingWindow.Snapshot landing =
                GROUND_LANDING_WINDOW.update(target, client.player.getDeltaMovement());
        if (landing.expired()) {
            lastDecision = "landing_window_expired";
            clearPendingAttack();
            return false;
        }
        if (!target.onGround() || !landing.insideLandingWindow()) {
            lastDecision = landing.sawAirborne() ? "waiting_landing" : "waiting_airborne";
            return false;
        }
        if (landing.targetHorizontalSpeed() > GROUND_MAX_SPEED.get()
                || landing.relativeHorizontalSpeed() > GROUND_MAX_RELATIVE_SPEED.get()) {
            lastDecision = "ground_speed_limit";
            return false;
        }
        PlacementPlan plan = findLandingGroundPlan(client, target);
        if (plan == null) {
            lastDecision = "no_safe_reachable_cell";
            return false;
        }
        return consumePlacementAttempt(client, plan);
    }

    private static boolean consumePlacementAttempt(Minecraft client, PlacementPlan plan) {
        clearPendingAttack();
        if (RandomMath.chance(CHANCE.get())) {
            lastDecision = "placing";
            beginWebHold(client, plan);
        } else {
            lastDecision = "chance_skipped";
        }
        return true;
    }

    /** Aim at the next feet position, without rewarding cells already being left. */
    private static PlacementPlan findLandingGroundPlan(Minecraft client, Player target) {
        Vec3 velocity = TrajectoryPrediction.observedVelocity(target);
        int leadTicks = Math.min(1, PREDICTION_TICKS.get());
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

    private static void tickPlacement(Minecraft client) {
        if (heldPlan == null) {
            restoreHeldSlot(client);
            return;
        }
        if (postPlaceHoldRemainingTicks > 0 && --postPlaceHoldRemainingTicks == 0) {
            restoreWebSlotSelection(client);
        }
        // A plan can become unreachable while the silent smooth RotationA is
        // still running. Abort before queuing the right-click instead of
        // sending an interaction that server-side checks reject as out of
        // reach. The failed action returns smoothly and never receives the
        // successful-placement cooldown.
        if ((phase == WebActionPhase.TURNING_TO_PLACE
                        || phase == WebActionPhase.WAITING_FOR_PLACE_ROTATION)
                && !withinPlacementRange(client, heldPlan.hit().getLocation())) {
            failPlacement(client);
            return;
        }
        if (phase == WebActionPhase.TURNING_TO_PLACE || phase == WebActionPhase.TURNING_BACK) {
            return;
        }

        switch (phase) {
            case WAITING_FOR_PLACE_ROTATION -> awaitPlacementRotation(client);
            case CLICKING_TO_PLACE -> confirmPlacedWeb(client);
            case HOLDING_AFTER_PLACE -> finishPostPlaceHold(client);
            case WAITING_FOR_RETURN -> confirmReturnRotation(client);
            default -> {}
        }
    }

    private static void awaitPlacementRotation(Minecraft client) {
        BlockHitResult confirmedHit = validatePlan(client, heldPlan);
        if (confirmedHit == null) {
            failPlacement(client);
            return;
        }
        if (SilentPacketRotation.invokeUseInPlayerUpdate(client, confirmedHit)) {
            placeConfirmTicks = 0;
            phase = WebActionPhase.CLICKING_TO_PLACE;
        }
    }

    private static void confirmPlacedWeb(Minecraft client) {
        if (!SilentPacketRotation.isUseInvocationDone()) {
            return;
        }
        // Start the visible-slot hold when vanilla has actually invoked USE,
        // not after the later server block confirmation/settle wait. The
        // interaction packet no longer needs the web selected at this point.
        if (!postPlaceHoldStarted) {
            postPlaceHoldStarted = true;
            postPlaceHoldRemainingTicks = HOLD_TICKS.get();
            if (postPlaceHoldRemainingTicks == 0) {
                restoreWebSlotSelection(client);
            }
        }
        if (!SilentPacketRotation.isUseDone()) {
            return;
        }
        if (pendingPlaceConfirmationPos == null) {
            pendingPlaceConfirmationPos = heldPlan.placePos().immutable();
            placeConfirmTicks = 0;
        }
        if (!WAIT_CONFIRM_ROTATION.get()) {
            // Match AutoLava's responsive ownership release. Confirmation and
            // ping settle continue in observePlacementConfirmation().
            beginReturnRotation(client);
        }
    }

    private static void observePlacementConfirmation(Minecraft client) {
        if (pendingPlaceConfirmationPos == null) {
            return;
        }
        placeConfirmTicks++;
        BlockState confirmedState = client.level.getBlockState(pendingPlaceConfirmationPos);
        if (!confirmedState.is(Blocks.COBWEB)) {
            // isUseDone only means the local invocation and packet order
            // completed. Remote server block updates arrive later, so keep
            // observing in the background instead of keeping slot/rotation
            // ownership merely because the first frame is still air.
            if (!confirmedState.canBeReplaced() || placeConfirmTicks >= MAX_PLACE_CONFIRM_TICKS) {
                boolean strictWait =
                        WAIT_CONFIRM_ROTATION.get() && phase == WebActionPhase.CLICKING_TO_PLACE;
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
        if (placeConfirmTicks < requiredServerSettleTicks(client)) {
            return;
        }
        boolean strictWait =
                WAIT_CONFIRM_ROTATION.get() && phase == WebActionPhase.CLICKING_TO_PLACE;
        clearPlacementConfirmation();
        remainingCooldownTicks = (int) Math.ceil(COOLDOWN_SECONDS.get() * 20.0D);
        if (strictWait) {
            beginReturnRotation(client);
        }
    }

    private static void finishPostPlaceHold(Minecraft client) {
        if (postPlaceHoldRemainingTicks <= 0) {
            restoreHeldSlot(client);
        }
    }

    private static void confirmReturnRotation(Minecraft client) {
        if (!SilentPacketRotation.isRotationPacketSent()) {
            return;
        }
        float cameraYawDifference =
                Math.abs(
                        Mth.wrapDegrees(
                                client.player.getYRot() - SilentPacketRotation.getSentYaw()));
        float cameraPitchDifference =
                Math.abs(client.player.getXRot() - SilentPacketRotation.getSentPitch());
        if (cameraYawDifference <= 0.35F && cameraPitchDifference <= 0.35F) {
            if (postPlaceHoldRemainingTicks <= 0) {
                restoreHeldSlot(client);
            } else {
                // RotationA is already back at the camera. Continue only
                // the independently configured hotbar hold timer.
                SilentPacketRotation.reset();
                phase = WebActionPhase.HOLDING_AFTER_PLACE;
            }
        } else {
            // The user moved the real camera after the final return
            // packet was sampled. Follow the new angle smoothly rather
            // than exposing that movement as a one-packet snap.
            beginReturnRotation(client);
        }
    }

    /** Ends an unsuccessful attempt without treating it as a placed web. */
    private static void failPlacement(Minecraft client) {
        clearPendingPlan();
        postPlaceHoldRemainingTicks = 0;
        postPlaceHoldStarted = false;
        clearPlacementConfirmation();
        restoreWebSlotSelection(client);
        beginReturnRotation(client);
    }

    private static void beginWebHold(Minecraft client, PlacementPlan plan) {
        int webSlot = findWebSlot(client);
        if (webSlot == -1) {
            clearPendingPlan();
            return;
        }

        Inventory inventory = client.player.getInventory();
        heldPlan = plan;
        postPlaceHoldRemainingTicks = 0;
        postPlaceHoldStarted = false;
        clearPlacementConfirmation();
        heldWebSlot = webSlot;
        originalSlot = inventory.getSelectedSlot();
        inventory.setSelectedSlot(webSlot);
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_WEB);

        phase = WebActionPhase.TURNING_TO_PLACE;
        SilentPacketRotation.beginRotation(
                client,
                plan.hit().getLocation(),
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> phase = WebActionPhase.WAITING_FOR_PLACE_ROTATION);
        if (phase == WebActionPhase.WAITING_FOR_PLACE_ROTATION) {
            tickPlacement(client);
        }
    }

    private static void beginReturnRotation(Minecraft client) {
        phase = WebActionPhase.TURNING_BACK;
        SilentPacketRotation.beginReturnToCamera(
                client,
                1,
                SilentPacketRotation.Mode.INSTANT,
                () -> phase = WebActionPhase.WAITING_FOR_RETURN);
    }

    /**
     * Searches one coherent trajectory instead of treating the player as a
     * single BlockPos. Every sample scores feet/body overlap, the swept eye
     * path and any wall support in the direction of travel.
     */
    private static PlacementPlan findTrajectoryPlan(
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

    private static PlacementPlan findBestVoxel(
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
                    int rawLeadTicks = 1 + (requireWallCollision ? 0 : DELAY_TICKS.get());
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
    private static PlacementPlan findWallPlan(Minecraft client, Player target) {
        return findWallPlan(client, target, false);
    }

    private static PlacementPlan findWallPlan(
            Minecraft client, Player target, boolean upperBodyOnly) {
        Vec3 velocity = TrajectoryPrediction.observedVelocity(target);
        int horizon =
                Mth.clamp(
                        PREDICTION_TICKS.get(),
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

    private static WallGeometry findWallGeometry(
            Minecraft client, BlockPos placePos, AABB targetBox, Vec3 velocity) {
        Vec3 expectedTravel = horizontalDirection(velocity);

        Direction bestDirection = null;
        double bestAlignment = 0.0D;
        double bestCoverage = 0.0D;
        double bestGap = Double.POSITIVE_INFINITY;
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
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
    private static boolean rayBlockedByEntity(Minecraft client, Vec3 eye, Vec3 target) {
        Vec3 direction = target.subtract(eye);
        double distanceSqr = direction.lengthSqr();
        if (distanceSqr < 1.0E-8D) {
            return false;
        }
        for (Player other : client.level.players()) {
            if (other == client.player) {
                continue;
            }
            if (other.getBoundingBox().clip(eye, target).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Follow measured displacement; stale delta movement can retain old knockback. */
    private static Vec3 horizontalDirection(Vec3 vector) {
        double length = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        return length < 1.0E-8D ? Vec3.ZERO : new Vec3(vector.x / length, 0.0D, vector.z / length);
    }

    private static BlockHitResult findSupportHit(
            Minecraft client, BlockPos placePos, Direction preferredSupportDirection) {
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Direction supportDirection : SUPPORT_DIRECTIONS) {
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

    private static BlockHitResult supportHit(
            Minecraft client, BlockPos placePos, Direction supportDirection) {
        BlockPos supportPos = placePos.relative(supportDirection);
        BlockState supportState = client.level.getBlockState(supportPos);
        if (supportState.getCollisionShape(client.level, supportPos).isEmpty()) {
            return null;
        }

        Direction supportFace = supportDirection.getOpposite();
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (double first : FACE_SAMPLES) {
            for (double second : FACE_SAMPLES) {
                Vec3 requested = pointOnFace(supportPos, supportFace, first, second);
                Vec3 justInside =
                        requested.add(
                                -supportFace.getStepX() * RAY_EPSILON,
                                -supportFace.getStepY() * RAY_EPSILON,
                                -supportFace.getStepZ() * RAY_EPSILON);
                BlockHitResult actual =
                        client.level.clip(
                                new ClipContext(
                                        client.player.getEyePosition(),
                                        justInside,
                                        ClipContext.Block.OUTLINE,
                                        ClipContext.Fluid.NONE,
                                        client.player));
                if (actual.getType() != HitResult.Type.BLOCK
                        || !actual.getBlockPos().equals(supportPos)
                        || actual.getDirection() != supportFace
                        || !withinPlacementRange(client, actual.getLocation())) {
                    continue;
                }
                double centerOffset = Math.abs(first - 0.5D) + Math.abs(second - 0.5D);
                double score = rotationCost(client, actual.getLocation()) + centerOffset * 0.04D;
                if (score < bestScore) {
                    best =
                            new BlockHitResult(
                                    actual.getLocation(),
                                    supportFace,
                                    supportPos,
                                    actual.isInside());
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
        return 1.0D - Mth.clamp(look.dot(toward), -1.0D, 1.0D);
    }

    private static BlockHitResult validatePlan(Minecraft client, PlacementPlan plan) {
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
            if (!target.onGround() || Math.hypot(velocity.x, velocity.z) > GROUND_MAX_SPEED.get()) {
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
        double reach = Math.min(RANGE.get(), client.player.blockInteractionRange());
        Vec3 end = eye.add(SilentPacketRotation.getInteractionLookVector(client).scale(reach));
        BlockHitResult actual =
                client.level.clip(
                        new ClipContext(
                                eye,
                                end,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
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

    private static boolean withinPlacementRange(Minecraft client, Vec3 hitLocation) {
        double reach = Math.min(RANGE.get(), client.player.blockInteractionRange());
        return BlockPlacementUtils.withinReach(client.player.getEyePosition(), hitLocation, reach);
    }

    private static int findWebSlot(Minecraft client) {
        return HotbarQueries.firstMatch(
                client.player.getInventory(),
                stack -> !stack.isEmpty() && stack.getItem() == Items.COBWEB);
    }

    private static void resetPlan(Minecraft client) {
        if (isBusy()) {
            restoreHeldSlot(client);
        }
        clearPlacementConfirmation();
        clearPendingPlan();
        clearPendingAttack();
    }

    private static void resetAll(Minecraft client) {
        resetPlan(client);
        remainingCooldownTicks = 0;
    }

    public static boolean isBusy() {
        return heldPlan != null || phase != WebActionPhase.IDLE;
    }

    private static void restoreHeldSlot(Minecraft client) {
        restoreWebSlotSelection(client);
        CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_WEB);
        heldPlan = null;
        originalSlot = -1;
        postPlaceHoldRemainingTicks = 0;
        postPlaceHoldStarted = false;
        phase = WebActionPhase.IDLE;
        SilentPacketRotation.reset();
    }

    private static void restoreWebSlotSelection(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client != null && currentPlayer != null && heldWebSlot != -1) {
            Inventory inventory = currentPlayer.getInventory();
            if (inventory.getSelectedSlot() == heldWebSlot && originalSlot >= 0) {
                inventory.setSelectedSlot(originalSlot);
            }
        }
        heldWebSlot = -1;
    }

    private static void clearPendingPlan() {}

    private static void clearPlacementConfirmation() {
        pendingPlaceConfirmationPos = null;
        placeConfirmTicks = 0;
    }

    private static void clearPendingAttack() {
        pendingAttackTargetId = -1;
        pendingAttackTicks = 0;
        pendingWallAttempted = false;
        GROUND_LANDING_WINDOW.clear();
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static String hudTag() {
        return "Instant";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoWeb: "
                        + statusText()
                        + ", range: "
                        + format(RANGE.get())
                        + ", delay: "
                        + DELAY_TICKS.get()
                        + "t"
                        + ", cooldown: "
                        + format(COOLDOWN_SECONDS.get())
                        + "s"
                        + ", hold: "
                        + HOLD_TICKS.get()
                        + "t"
                        + ", rotation: instant"
                        + ", prediction: "
                        + PREDICTION_TICKS.get()
                        + "t"
                        + ", chance: "
                        + format(CHANCE.get())
                        + ", wall: "
                        + (WALL_ENABLED.get() ? "enabled" : "disabled")
                        + ", ground: "
                        + (GROUND_ENABLED.get() ? "enabled" : "disabled")
                        + ", wait confirm rotation: "
                        + (WAIT_CONFIRM_ROTATION.get() ? "enabled" : "disabled")
                        + " (post-hit landing window)"
                        + ". Usage: .moons autoweb <enable|disable|range 2-6|delay 0-100|hold 0-20|cooldown 0-30|prediction 0-12|chance 0-1|wall|ground|wait_confirm_rotation>.");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        resetPlan(client);
        remainingCooldownTicks = 0;
        ClientChat.send(client, "AutoWeb " + statusText() + ".");
        return 1;
    }

    public static int setWallEnabled(Minecraft client, boolean value) {
        WALL_ENABLED.set(value);
        resetPlan(client);
        ClientChat.send(
                client,
                "AutoWeb wall webs "
                        + (value ? "enabled" : "disabled")
                        + " (predictive attack extension; immediate feet remains active).");
        return 1;
    }

    public static int setGroundEnabled(Minecraft client, boolean value) {
        GROUND_ENABLED.set(value);
        resetPlan(client);
        ClientChat.send(
                client,
                "AutoWeb flat-ground prediction "
                        + (value ? "enabled" : "disabled")
                        + " (post-hit landing only).");
        return 1;
    }

    public static int setGroundWindow(Minecraft client, int value) {
        GROUND_WINDOW_TICKS.set(value);
        clearPendingAttack();
        return 1;
    }

    public static int setGroundMaxSpeed(Minecraft client, double value) {
        GROUND_MAX_SPEED.set(value);
        return 1;
    }

    public static int setGroundMaxRelativeSpeed(Minecraft client, double value) {
        GROUND_MAX_RELATIVE_SPEED.set(value);
        return 1;
    }

    public static boolean groundEnabled() {
        return GROUND_ENABLED.get();
    }

    public static int setWaitConfirmRotation(Minecraft client, boolean value) {
        WAIT_CONFIRM_ROTATION.set(value);
        ClientChat.send(
                client,
                "AutoWeb wait for confirmation before rotation return "
                        + (value ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        resetPlan(client);
        ClientChat.send(client, "AutoWeb range set to " + format(RANGE.get()) + ".");
        return 1;
    }

    public static int setDelay(Minecraft client, int value) {
        DELAY_TICKS.set(value);
        clearPendingPlan();
        ClientChat.send(client, "AutoWeb execution delay set to " + DELAY_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setCooldown(Minecraft client, double value) {
        COOLDOWN_SECONDS.set(value);
        ClientChat.send(
                client,
                "AutoWeb successful-placement cooldown set to "
                        + format(COOLDOWN_SECONDS.get())
                        + " seconds.");
        return 1;
    }

    public static int setHoldTicks(Minecraft client, int value) {
        HOLD_TICKS.set(value);
        ClientChat.send(client, "AutoWeb post-place hold set to " + HOLD_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setPrediction(Minecraft client, int value) {
        PREDICTION_TICKS.set(value);
        resetPlan(client);
        ClientChat.send(client, "AutoWeb prediction set to " + PREDICTION_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        ClientChat.send(client, "AutoWeb trigger chance set to " + format(CHANCE.get()) + ".");
        return 1;
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
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
        return Mth.clamp((int) Math.ceil(latencyMs / 50.0D) + 3, 8, MAX_PLACE_CONFIRM_TICKS);
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

    // Debug
    public static String debugState() {
        return phase.name().toLowerCase(java.util.Locale.ROOT)
                + " gate="
                + lastDecision
                + " request="
                + pendingAttackTicks
                + " hold="
                + postPlaceHoldRemainingTicks
                + " confirm="
                + (pendingPlaceConfirmationPos == null ? "none" : placeConfirmTicks + "t")
                + " strict="
                + WAIT_CONFIRM_ROTATION.get()
                + " use="
                + SilentPacketRotation.isUseInvocationDone()
                + "/"
                + SilentPacketRotation.isUseDone();
    }

    /** End this feature's pending work without changing its configured toggle. */
    public static void shutdown(Minecraft client) {
        resetAll(client);
    }
}
