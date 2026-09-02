/*
 * AutoWeb for Moons.
 *
 * Two triggers share one instant silent packet-RotationA placement path (the
 * first-person camera stays fixed while the third-person model turns):
 * - Auto: scores feet, body and eye voxels along the target's trajectory.
 * - Wall (attack only): adds the expected knockback impulse, searches the next
 *   3-6 ticks for an actual wall contact and strongly prefers the eye path in
 *   the air voxel directly in front of that wall.
 * A short cooldown after each successful placement keeps the pressure
 * continuous without spamming the same cell.
 */
package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.targeting.PostHitLandingWindow;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.math.RandomMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;


public final class AutoWeb {
    private static final int MIN_WALL_PREDICTION_TICKS = 3;
    private static final int MAX_WALL_PREDICTION_TICKS = 6;
    private static final int ATTACK_REQUEST_LIFETIME_TICKS = 24;
    private static final int GROUND_LANDING_WINDOW_TICKS = 3;
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
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };
    private static final double SELF_SAFETY_MARGIN = 0.28D;
    private static final double DEFAULT_COOLDOWN_SECONDS = 0.5D;
    private static final double MIN_COOLDOWN_SECONDS = 0.0D;
    private static final double MAX_COOLDOWN_SECONDS = 30.0D;
    private static final double KNOCKBACK_HORIZONTAL_RETENTION = 0.5D;
    private static final double HORIZONTAL_DRAG = 0.91D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double WALL_CONTACT_PADDING = 0.08D;
    private static final double MIN_WALL_ALIGNMENT = 0.25D;
    private static final double CORNER_WALL_GAP = 0.35D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final double MAX_GROUND_TARGET_SPEED = 0.18D;
    private static final double MAX_GROUND_RELATIVE_SPEED = 0.28D;
    private static final double[] FACE_SAMPLES = {0.18D, 0.5D, 0.82D};

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("autoweb.enabled")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting WALL_ENABLED =
            new BooleanSetting.Builder()
                    .name("autoweb.wall")
                    .defaultValue(false)
                    .build();

    private static final BooleanSetting GROUND_ENABLED =
            new BooleanSetting.Builder()
                    .name("autoweb.ground")
                    .defaultValue(true)
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
            new IntSetting.Builder()
                    .name("autoweb.delay")
                    .defaultValue(0)
                    .range(0, 100)
                    .build();

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
    private static String pendingPlanKey;
    private static int pendingDelayTicks;
    private static BlockPos lastPlacedPos;
    private static int pendingAttackTargetId = -1;
    private static int pendingAttackTicks;
    private static boolean pendingWallAttempted;
    private static final PostHitLandingWindow GROUND_LANDING_WINDOW =
            new PostHitLandingWindow();
    private static int postPlaceHoldRemainingTicks;
    private static boolean postPlaceHoldStarted;
    private static BlockPos pendingPlaceConfirmationPos;
    private static int placeConfirmTicks;

    private AutoWeb() {
    }

    public static void init() {
        migrateReliableDefaults();
        EventBus.PLAYER_UPDATE.register("AutoWeb.playerUpdate", event -> {
            Minecraft client = event.client();
            tick(client);
        });
    }

    private static void migrateReliableDefaults() {
        if (Settings.getBoolean("autoweb.reliableDefaultsMigrated", false)) return;
        Settings.beginBatch();
        try {
            if (DELAY_TICKS.get() == 2) DELAY_TICKS.set(0);
            if (Math.abs(CHANCE.get() - 0.65D) < 1.0E-9D) CHANCE.set(1.0D);
            Settings.setBoolean("autoweb.reliableDefaultsMigrated", true);
        } finally {
            Settings.endBatch();
        }
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get()) {
            resetAll(client);
            return;
        }

        if (remainingCooldownTicks > 0) {
            remainingCooldownTicks--;
        }
        if (pendingAttackTicks > 0) {
            pendingAttackTicks--;
            Player pendingTarget = client.level.getEntity(pendingAttackTargetId)
                    instanceof Player player ? player : null;
            GROUND_LANDING_WINDOW.update(
                    pendingTarget, client.player.getDeltaMovement());
        } else {
            pendingAttackTargetId = -1;
            pendingWallAttempted = false;
            GROUND_LANDING_WINDOW.clear();
        }

        if (!ready(client)) {
            resetPlan(client);
            return;
        }

        observePlacementConfirmation(client);

        if (AutoLava.isBusy() || AntiLava.isBusy() || AntiWeb.isBusy()
                || AutoBed.isBusy() || AutoObsidian.isBusy()) {
            return;
        }

        if (phase != WebActionPhase.IDLE || heldPlan != null) {
            tickPlacement(client);
            return;
        }
        if (remainingCooldownTicks > 0) {
            return;
        }
        // A fast return releases slot/rotation ownership immediately, but a
        // second web must not start until the first server result is known.
        if (pendingPlaceConfirmationPos != null) {
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
        if (!ENABLED.get() || !ready(client)
                || !(target instanceof Player player)
                || !Targeting.isValidTargetPlayer(client, player)
                || isTrappedInWeb(client, player)
                || findWebSlot(client) == -1) {
            return;
        }
        // Attack callbacks may run before or after LocalPlayer.tick depending
        // on the host client. Consume the request only from PLAYER_UPDATE so
        // selection, silent rotation, use and sendPosition own one tick.
        pendingAttackTargetId = player.getId();
        pendingAttackTicks = ATTACK_REQUEST_LIFETIME_TICKS;
        pendingWallAttempted = false;
        if (GROUND_ENABLED.get()) {
            GROUND_LANDING_WINDOW.arm(
                    player,
                    ATTACK_REQUEST_LIFETIME_TICKS,
                    GROUND_LANDING_WINDOW_TICKS);
        } else {
            GROUND_LANDING_WINDOW.clear();
        }
    }

    private static boolean tryExecutePendingAttack(Minecraft client) {
        if (pendingAttackTargetId == -1) {
            return false;
        }
        Player target = client.level.getEntity(pendingAttackTargetId) instanceof Player player
                ? player : null;
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
        if (pendingWallAttempted) {
            return false;
        }
        pendingWallAttempted = true;
        PlacementPlan plan = null;
        if (WALL_ENABLED.get()) {
            // The trajectory search already includes tick zero and strongly
            // rewards upper-body/eye-path cells. Running CurrentWeb first made
            // any presently placeable corner suppress the lateral knockback
            // prediction entirely.
            plan = findWallPlan(client, target);
            if (plan == null) {
                plan = findImmediateFeetPlan(client, target, true);
            }
        }
        return plan != null && consumePlacementAttempt(client, plan);
    }

    private static boolean tryStartLandingPlacement(Minecraft client, Player target) {
        if (!GROUND_ENABLED.get()) {
            clearPendingAttack();
            return false;
        }
        PostHitLandingWindow.Snapshot landing = GROUND_LANDING_WINDOW.update(
                target, client.player.getDeltaMovement());
        if (landing.expired()) {
            clearPendingAttack();
            return false;
        }
        if (!landing.insideLandingWindow()
                || landing.targetHorizontalSpeed() > MAX_GROUND_TARGET_SPEED
                || landing.relativeHorizontalSpeed() > MAX_GROUND_RELATIVE_SPEED) {
            return false;
        }
        PlacementPlan plan = findLandingGroundPlan(client, target);
        if (plan == null || plan.placePos().equals(lastPlacedPos)) {
            return false;
        }
        return consumePlacementAttempt(client, plan);
    }

    private static boolean consumePlacementAttempt(
            Minecraft client, PlacementPlan plan) {
        clearPendingAttack();
        if (RandomMath.chance(CHANCE.get())) {
            beginWebHold(client, plan);
        }
        return true;
    }

    /** First landed feet cell, with only a one-tick fallback instead of a long guess. */
    private static PlacementPlan findLandingGroundPlan(
            Minecraft client, Player target) {
        PlacementPlan current = findImmediateFeetPlan(client, target, false);
        if (current != null) {
            return current;
        }
        return findTrajectoryPlan(
                client, target, observedTargetVelocity(target),
                Math.min(1, PREDICTION_TICKS.get()),
                false, false, true);
    }

    private static void tickPlacement(Minecraft client) {
        if (heldPlan == null) {
            restoreHeldSlot(client);
            return;
        }
        if (postPlaceHoldRemainingTicks > 0
                && --postPlaceHoldRemainingTicks == 0) {
            restoreWebSlotSelection(client);
        }
        // A plan can become unreachable while the silent smooth RotationA is
        // still running. Abort before queuing the right-click instead of
        // sending an interaction that vanilla/Grim must reject as out of
        // reach. The failed action returns smoothly and never receives the
        // successful-placement cooldown.
        if ((phase == WebActionPhase.TURNING_TO_PLACE
                || phase == WebActionPhase.WAITING_FOR_PLACE_ROTATION)
                && !withinPlacementRange(client, heldPlan.hit().getLocation())) {
            failPlacement(client);
            return;
        }
        if (phase == WebActionPhase.TURNING_TO_PLACE
                || phase == WebActionPhase.TURNING_BACK) {
            return;
        }

        switch (phase) {
            case WAITING_FOR_PLACE_ROTATION -> awaitPlacementRotation(client);
            case CLICKING_TO_PLACE -> confirmPlacedWeb(client);
            case HOLDING_AFTER_PLACE -> finishPostPlaceHold(client);
            case WAITING_FOR_RETURN -> confirmReturnRotation(client);
            default -> {
            }
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
        BlockState confirmedState = client.level.getBlockState(
                pendingPlaceConfirmationPos);
        if (!confirmedState.is(Blocks.COBWEB)) {
            // isUseDone only means the local invocation and packet order
            // completed. Remote server block updates arrive later, so keep
            // observing in the background instead of keeping slot/rotation
            // ownership merely because the first frame is still air.
            if (!confirmedState.canBeReplaced()
                    || placeConfirmTicks >= MAX_PLACE_CONFIRM_TICKS) {
                boolean strictWait = WAIT_CONFIRM_ROTATION.get()
                        && phase == WebActionPhase.CLICKING_TO_PLACE;
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
        BlockPos confirmedPos = pendingPlaceConfirmationPos;
        boolean strictWait = WAIT_CONFIRM_ROTATION.get()
                && phase == WebActionPhase.CLICKING_TO_PLACE;
        clearPlacementConfirmation();
        remainingCooldownTicks = (int) Math.ceil(COOLDOWN_SECONDS.get() * 20.0D);
        lastPlacedPos = confirmedPos;
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
        float cameraYawDifference = Math.abs(Mth.wrapDegrees(
                client.player.getYRot() - SilentPacketRotation.getSentYaw()));
        float cameraPitchDifference = Math.abs(
                client.player.getXRot() - SilentPacketRotation.getSentPitch());
        if (cameraYawDifference <= 0.35F
                && cameraPitchDifference <= 0.35F) {
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
        CombatInputController.suppressAttack(
                client, CombatInputController.Owner.AUTO_WEB);

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

    private static PlacementPlan findBestPlan(Minecraft client) {
        PlacementPlan best = null;
        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            if (isTrappedInWeb(client, target)) {
                continue;
            }
            if (client.player.distanceToSqr(target) > RANGE.get() * RANGE.get()) {
                continue;
            }
            PlacementPlan candidate = planForTarget(client, target);
            if (candidate != null && (best == null || candidate.score() < best.score())) {
                best = candidate;
            }
        }
        return best;
    }

    private static PlacementPlan planForTarget(Minecraft client, Player target) {
        PlacementPlan predicted = findGroundPlan(client, target);
        return predicted != null
                ? predicted : findImmediateFeetPlan(client, target, false);
    }

    /** Flat-ground PredictWeb: future feet voxel with floor support only. */
    private static PlacementPlan findGroundPlan(Minecraft client, Player target) {
        if (!GROUND_ENABLED.get() || !target.onGround()) {
            return null;
        }
        return findTrajectoryPlan(
                client,
                target,
                observedTargetVelocity(target),
                PREDICTION_TICKS.get(),
                false,
                false,
                true);
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
            boolean groundOnly
    ) {
        Vec3 position = target.position();
        Vec3 velocity = initialVelocity;
        boolean grounded = target.onGround();
        AABB previousBox = target.getDimensions(Pose.STANDING)
                .makeBoundingBox(position);
        Vec3 previousFeet = position;
        Vec3 previousEye = predictedEyePosition(target, position);
        PlacementPlan best = null;

        for (int tick = 0; tick <= horizon; tick++) {
            if (tick > 0) {
                TrajectoryStep step = advanceTrajectory(position, velocity, grounded);
                position = step.position();
                velocity = step.velocity();
            }

            AABB targetBox = target.getDimensions(Pose.STANDING)
                    .makeBoundingBox(position);
            Vec3 predictedEye = predictedEyePosition(target, position);
            PlacementPlan candidate = findBestVoxel(
                    client,
                    target,
                    targetBox,
                    previousBox,
                    position,
                    previousFeet,
                    predictedEye,
                    previousEye,
                    velocity,
                    tick,
                    horizon,
                    requireWallCollision,
                    upperBodyOnly,
                    groundOnly);
            if (candidate != null
                    && (best == null || candidate.score() < best.score())) {
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
            boolean groundOnly
    ) {
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
                    double upperBodyCenterY = targetBox.minY
                            + (targetBox.maxY - targetBox.minY) * 0.5D;
                    if (upperBodyOnly
                            && placePos.getY() + 0.5D < upperBodyCenterY) {
                        continue;
                    }
                    if (groundOnly
                            && placePos.getY() != Mth.floor(predictedFeet.y + 0.05D)) {
                        continue;
                    }
                    boolean intersectsFeet = intersectsPointPath(
                            voxel, previousFeet, predictedFeet);
                    boolean intersectsEye = intersectsPointPath(
                            voxel, previousEye, predictedEye);
                    if ((!voxel.intersects(targetBox)
                            && !voxel.intersects(previousBox)
                            && !intersectsFeet
                            && !intersectsEye)
                            || !isSafeForPlayer(client, placePos, predictionTick)) {
                        continue;
                    }

                    WallGeometry wall = groundOnly
                            ? WallGeometry.NONE
                            : findWallGeometry(client, placePos, targetBox, velocity);
                    if (requireWallCollision && !wall.collisionOpportunity()) {
                        continue;
                    }

                    if (groundOnly
                            && supportHit(client, placePos, Direction.DOWN) == null) {
                        continue;
                    }
                    BlockHitResult hit = findSupportHit(
                            client, placePos, wall.direction());
                    if (hit == null || !withinPlacementRange(client, hit.getLocation())) {
                        continue;
                    }

                    double eyeIntersection = intersectsEye ? 1.0D : 0.0D;
                    double feetIntersection = intersectsFeet ? 1.0D : 0.0D;
                    double predictedIntersection = Math.max(
                            intersectionRatio(voxel, targetBox),
                            intersectionRatio(voxel, previousBox));
                    double wallSupport = wall.direction() == null ? 0.0D : 1.0D;
                    int rawLeadTicks = 1
                            + (requireWallCollision ? 0 : DELAY_TICKS.get());
                    int leadTicks = Math.max(1, Math.min(Math.max(1, horizon), rawLeadTicks));
                    double leadError = (double) Math.abs(
                            leadTicks - predictionTick) / leadTicks;
                    // Score against the moment the use packet can actually be
                    // handled. The old wall score only punished early samples;
                    // a visually attractive wall several ticks too far ahead
                    // could therefore beat the next-tick lateral intercept.
                    double predictionError = leadError * leadError;
                    double placementDelay = leadError;
                    double eyeDistance = client.player.getEyePosition()
                            .distanceTo(hit.getLocation());
                    double rotationCost = rotationCost(client, hit.getLocation());
                    double entityOcclusion = requireWallCollision
                            && rayBlockedByEntity(
                            client,
                            client.player.getEyePosition(),
                            hit.getLocation()) ? 1.0D : 0.0D;

                    // Lower is better. The weights intentionally make a valid
                    // eye-path wall voxel beat a conventional feet placement.
                    // An entity crossing the visual ray is a risk signal, not
                    // a hard rejection: the queued use carries the explicit
                    // support face and the target normally stands between us
                    // and the wall by definition.
                    double score = -eyeIntersection * 4.0D
                            - predictedIntersection * 3.0D
                            - wallSupport * 2.0D
                            - wall.alignment() * 2.0D
                            - feetIntersection * 1.25D
                            + predictionError * 3.0D
                            + placementDelay * 2.0D
                            + entityOcclusion * 0.75D
                            + rotationCost * 0.65D
                            + eyeDistance * 0.08D;
                    PlacementPlan candidate = new PlacementPlan(
                            target.getId(), placePos, hit, predictionTick, score);
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

    /** Strict first pass: only a wall-supported upper-body/eye voxel is legal. */
    private static PlacementPlan findUpperBodyWallPlan(
            Minecraft client,
            Player target
    ) {
        return findWallPlan(client, target, true);
    }

    private static PlacementPlan findWallPlan(
            Minecraft client,
            Player target,
            boolean upperBodyOnly
    ) {
        Vec3 direction = knockbackDirection(client, target);
        if (direction == null) {
            return null;
        }
        Vec3 velocity = predictedKnockbackVelocity(client, target, direction);
        int horizon = clampInt(
                PREDICTION_TICKS.get(),
                MIN_WALL_PREDICTION_TICKS,
                MAX_WALL_PREDICTION_TICKS);
        return findTrajectoryPlan(
                client, target, velocity, horizon, true, upperBodyOnly, false);
    }

    /** Immediate CurrentWeb used by attack callbacks, optionally only at a wall. */
    private static PlacementPlan findImmediateFeetPlan(
            Minecraft client,
            Player target,
            boolean requireCornerWall
    ) {
        AABB box = target.getBoundingBox();
        int minX = Mth.floor(box.minX + 1.0E-4D);
        int maxX = Mth.floor(box.maxX - 1.0E-4D);
        int minZ = Mth.floor(box.minZ + 1.0E-4D);
        int maxZ = Mth.floor(box.maxZ - 1.0E-4D);
        int feetY = Mth.floor(box.minY + 0.05D);
        PlacementPlan best = null;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos placePos = new BlockPos(x, feetY, z);
                if (!new AABB(placePos).intersects(box)
                        || !isSafeForPlayer(client, placePos, 0)) {
                    continue;
                }
                Direction wallDirection = requireCornerWall
                        ? findCornerWallDirection(client, placePos, box)
                        : null;
                if (requireCornerWall && wallDirection == null) {
                    continue;
                }
                BlockHitResult hit = requireCornerWall
                        ? findSupportHit(client, placePos, wallDirection)
                        : supportHit(client, placePos, Direction.DOWN);
                if (hit == null || !withinPlacementRange(client, hit.getLocation())) {
                    continue;
                }
                double score = client.player.getEyePosition()
                        .distanceTo(hit.getLocation()) * 0.08D
                        - (wallDirection == null ? 0.0D : 3.0D);
                PlacementPlan candidate = new PlacementPlan(
                        target.getId(), placePos, hit, 0, score);
                if (best == null || candidate.score() < best.score()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static Direction findCornerWallDirection(
            Minecraft client,
            BlockPos placePos,
            AABB targetBox
    ) {
        Direction best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        Vec3 expectedTravel = horizontalDirectionFromPlayer(
                client, targetBox.getCenter());
        if (expectedTravel.lengthSqr() < 1.0E-8D) {
            return null;
        }
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            double alignment = expectedTravel.x * direction.getStepX()
                    + expectedTravel.z * direction.getStepZ();
            if (alignment < MIN_WALL_ALIGNMENT) {
                continue;
            }
            if (supportHit(client, placePos, direction) == null) {
                continue;
            }
            double gap = wallGap(
                    client, targetBox, placePos.relative(direction), direction);
            if (Double.isFinite(gap) && gap <= CORNER_WALL_GAP && gap < bestGap) {
                best = direction;
                bestGap = gap;
            }
        }
        return best;
    }

    private static TrajectoryStep advanceTrajectory(
            Vec3 position,
            Vec3 velocity,
            boolean grounded
    ) {
        if (grounded) {
            return new TrajectoryStep(
                    position.add(velocity.x, 0.0D, velocity.z),
                    new Vec3(
                            velocity.x * HORIZONTAL_DRAG,
                            0.0D,
                            velocity.z * HORIZONTAL_DRAG));
        }
        Vec3 nextVelocity = new Vec3(
                velocity.x * HORIZONTAL_DRAG,
                (velocity.y - GRAVITY) * VERTICAL_DRAG,
                velocity.z * HORIZONTAL_DRAG);
        return new TrajectoryStep(position.add(nextVelocity), nextVelocity);
    }

    private static Vec3 predictedEyePosition(Player target, Vec3 feetPosition) {
        return feetPosition.add(0.0D, target.getEyeHeight(Pose.STANDING), 0.0D);
    }

    private static boolean intersectsPointPath(AABB voxel, Vec3 start, Vec3 end) {
        return voxel.contains(end.x, end.y, end.z)
                || voxel.contains(start.x, start.y, start.z)
                || voxel.clip(start, end).isPresent();
    }

    private static double intersectionRatio(AABB voxel, AABB targetBox) {
        double x = Math.max(0.0D,
                Math.min(voxel.maxX, targetBox.maxX)
                        - Math.max(voxel.minX, targetBox.minX));
        double y = Math.max(0.0D,
                Math.min(voxel.maxY, targetBox.maxY)
                        - Math.max(voxel.minY, targetBox.minY));
        double z = Math.max(0.0D,
                Math.min(voxel.maxZ, targetBox.maxZ)
                        - Math.max(voxel.minZ, targetBox.minZ));
        double targetVolume = (targetBox.maxX - targetBox.minX)
                * (targetBox.maxY - targetBox.minY)
                * (targetBox.maxZ - targetBox.minZ);
        return targetVolume <= 1.0E-8D ? 0.0D : x * y * z / targetVolume;
    }

    private static WallGeometry findWallGeometry(
            Minecraft client,
            BlockPos placePos,
            AABB targetBox,
            Vec3 velocity
    ) {
        Vec3 expectedTravel = horizontalDirection(velocity);
        if (expectedTravel.lengthSqr() < 1.0E-8D) {
            expectedTravel = horizontalDirectionFromPlayer(
                    client, targetBox.getCenter());
        }
        if (expectedTravel.lengthSqr() < 1.0E-8D) {
            return WallGeometry.NONE;
        }

        Direction bestDirection = null;
        double bestAlignment = 0.0D;
        double bestGap = Double.POSITIVE_INFINITY;
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            if (supportHit(client, placePos, direction) == null) {
                continue;
            }
            double alignment = expectedTravel.x * direction.getStepX()
                    + expectedTravel.z * direction.getStepZ();
            if (alignment < MIN_WALL_ALIGNMENT) {
                continue;
            }
            double gap = wallGap(
                    client, targetBox, placePos.relative(direction), direction);
            if (!Double.isFinite(gap)) {
                continue;
            }
            if (alignment > bestAlignment
                    || (Math.abs(alignment - bestAlignment) < 1.0E-6D
                    && gap < bestGap)) {
                bestDirection = direction;
                bestAlignment = alignment;
                bestGap = gap;
            }
        }

        if (bestDirection == null) {
            return WallGeometry.NONE;
        }
        // Accept both an existing contact and a wall the sampled motion reaches
        // during the next movement step.
        double closingSpeed = Math.max(0.0D,
                velocity.x * bestDirection.getStepX()
                        + velocity.z * bestDirection.getStepZ());
        boolean collisionOpportunity = bestGap
                <= WALL_CONTACT_PADDING + closingSpeed;
        return new WallGeometry(
                bestDirection, bestAlignment, bestGap, collisionOpportunity);
    }

    private static double wallGap(
            Minecraft client,
            AABB targetBox,
            BlockPos wallPos,
            Direction direction
    ) {
        BlockState wallState = client.level.getBlockState(wallPos);
        double bestGap = Double.POSITIVE_INFINITY;
        for (AABB localShape : wallState
                .getCollisionShape(client.level, wallPos).toAabbs()) {
            AABB wallShape = localShape.move(
                    wallPos.getX(), wallPos.getY(), wallPos.getZ());
            boolean overlapsPerpendicular = switch (direction) {
                case EAST, WEST -> overlaps(targetBox.minY, targetBox.maxY,
                        wallShape.minY, wallShape.maxY)
                        && overlaps(targetBox.minZ, targetBox.maxZ,
                        wallShape.minZ, wallShape.maxZ);
                case NORTH, SOUTH -> overlaps(targetBox.minX, targetBox.maxX,
                        wallShape.minX, wallShape.maxX)
                        && overlaps(targetBox.minY, targetBox.maxY,
                        wallShape.minY, wallShape.maxY);
                default -> false;
            };
            if (!overlapsPerpendicular) {
                continue;
            }
            double gap = switch (direction) {
                case EAST -> wallShape.minX - targetBox.maxX;
                case WEST -> targetBox.minX - wallShape.maxX;
                case SOUTH -> wallShape.minZ - targetBox.maxZ;
                case NORTH -> targetBox.minZ - wallShape.maxZ;
                default -> Double.POSITIVE_INFINITY;
            };
            bestGap = Math.min(bestGap, Math.max(0.0D, gap));
        }
        return bestGap;
    }

    private static boolean overlaps(
            double firstMin,
            double firstMax,
            double secondMin,
            double secondMax
    ) {
        return firstMax > secondMin + 1.0E-5D
                && firstMin < secondMax - 1.0E-5D;
    }

    /** Whether any player entity's collision box blocks the ray between the points. */
    private static boolean rayBlockedByEntity(
            Minecraft client,
            Vec3 eye,
            Vec3 target
    ) {
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

    private static Vec3 predictedKnockbackVelocity(
            Minecraft client,
            Player target,
            Vec3 direction
    ) {
        Vec3 current = observedTargetVelocity(target);
        Vec3 away = direction;
        double levels = Math.max(0.0D,
                client.player.getAttributeValue(Attributes.ATTACK_KNOCKBACK))
                + (client.player.isSprinting() ? 1.0D : 0.0D);
        double expected = levels * 0.5D;
        expected *= 1.0D - Mth.clamp(
                target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE),
                0.0D, 1.0D);
        if (expected <= 1.0E-6D) return current;
        return new Vec3(
                current.x * KNOCKBACK_HORIZONTAL_RETENTION + away.x * expected,
                current.y,
                current.z * KNOCKBACK_HORIZONTAL_RETENTION + away.z * expected);
    }

    /** Remote-player delta movement often trails interpolation by one update. */
    private static Vec3 observedTargetVelocity(Player target) {
        Vec3 reported = target.getDeltaMovement();
        Vec3 observed = new Vec3(
                target.getX() - target.xo,
                target.getY() - target.yo,
                target.getZ() - target.zo);
        double horizontal = Math.hypot(observed.x, observed.z);
        if (!Double.isFinite(horizontal) || horizontal > 1.5D) return reported;
        if (horizontal < 1.0E-4D) return reported;
        return new Vec3(
                observed.x * 0.72D + reported.x * 0.28D,
                reported.y,
                observed.z * 0.72D + reported.z * 0.28D);
    }

    private static Vec3 knockbackDirection(Minecraft client, Player target) {
        double dx = target.getX() - client.player.getX();
        double dz = target.getZ() - client.player.getZ();
        if (dx * dx + dz * dz < 1.0E-6D) {
            return null;
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        return new Vec3(dx / length, 0.0D, dz / length);
    }

    private static Vec3 horizontalDirectionFromPlayer(
            Minecraft client,
            Vec3 point
    ) {
        double dx = point.x - client.player.getX();
        double dz = point.z - client.player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        return length < 1.0E-8D
                ? Vec3.ZERO : new Vec3(dx / length, 0.0D, dz / length);
    }

    private static Vec3 horizontalDirection(Vec3 vector) {
        double length = Math.sqrt(vector.x * vector.x + vector.z * vector.z);
        return length < 1.0E-8D
                ? Vec3.ZERO : new Vec3(vector.x / length, 0.0D, vector.z / length);
    }

    private static BlockHitResult findSupportHit(
            Minecraft client,
            BlockPos placePos
    ) {
        return findSupportHit(client, placePos, null);
    }

    private static BlockHitResult findSupportHit(
            Minecraft client,
            BlockPos placePos,
            Direction preferredSupportDirection
    ) {
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Direction supportDirection : SUPPORT_DIRECTIONS) {
            BlockHitResult hit = supportHit(client, placePos, supportDirection);
            if (hit == null || !withinPlacementRange(client, hit.getLocation())) {
                continue;
            }
            double score = rotationCost(client, hit.getLocation())
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
            Minecraft client,
            BlockPos placePos,
            Direction supportDirection
    ) {
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
                Vec3 justInside = requested.add(
                        -supportFace.getStepX() * RAY_EPSILON,
                        -supportFace.getStepY() * RAY_EPSILON,
                        -supportFace.getStepZ() * RAY_EPSILON);
                BlockHitResult actual = client.level.clip(new ClipContext(
                        client.player.getEyePosition(),
                        justInside,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        client.player));
                if (actual.getType() != HitResult.Type.BLOCK
                        || !actual.getBlockPos().equals(supportPos)
                        || actual.getDirection() != supportFace) {
                    continue;
                }
                double centerOffset = Math.abs(first - 0.5D)
                        + Math.abs(second - 0.5D);
                double score = rotationCost(client, actual.getLocation())
                        + centerOffset * 0.04D;
                if (score < bestScore) {
                    best = new BlockHitResult(
                            actual.getLocation(), supportFace, supportPos,
                            actual.isInside());
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(
            BlockPos supportPos,
            Direction face,
            double first,
            double second
    ) {
        double x = supportPos.getX();
        double y = supportPos.getY();
        double z = supportPos.getZ();
        return switch (face.getAxis()) {
            case X -> new Vec3(
                    x + (face == Direction.EAST ? 1.0D : 0.0D),
                    y + first,
                    z + second);
            case Y -> new Vec3(
                    x + first,
                    y + (face == Direction.UP ? 1.0D : 0.0D),
                    z + second);
            case Z -> new Vec3(
                    x + first,
                    y + second,
                    z + (face == Direction.SOUTH ? 1.0D : 0.0D));
        };
    }

    private static double rotationCost(Minecraft client, Vec3 point) {
        Vec3 toward = point.subtract(client.player.getEyePosition()).normalize();
        Vec3 look = client.player.getLookAngle().normalize();
        return 1.0D - Mth.clamp(look.dot(toward), -1.0D, 1.0D);
    }

    private static BlockHitResult validatePlan(
            Minecraft client,
            PlacementPlan plan
    ) {
        Player target = client.level.getEntity(plan.targetId()) instanceof Player player
                ? player : null;
        if (!Targeting.isValidTargetPlayer(client, target)) {
            return null;
        }
        if (isTrappedInWeb(client, target)) {
            return null;
        }

        if (!isSafeForPlayer(client, plan.placePos(), plan.predictionTick())) {
            return null;
        }
        BlockState placeState = client.level.getBlockState(plan.placePos());
        if (placeState.is(Blocks.COBWEB) || !placeState.canBeReplaced()) {
            return null;
        }

        Vec3 eye = client.player.getEyePosition();
        double reach = Math.min(RANGE.get(), client.player.blockInteractionRange());
        Vec3 end = eye.add(SilentPacketRotation.getInteractionLookVector(client).scale(reach));
        BlockHitResult actual = client.level.clip(new ClipContext(
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

    private static boolean isSafeForPlayer(
            Minecraft client,
            BlockPos placePos,
            int predictionTick
    ) {
        AABB webBox = new AABB(placePos);
        AABB currentPlayerBox = client.player.getBoundingBox()
                .inflate(SELF_SAFETY_MARGIN, 0.05D, SELF_SAFETY_MARGIN);
        AABB predictedPlayerBox = predictTargetBox(client.player, predictionTick)
                .inflate(SELF_SAFETY_MARGIN, 0.05D, SELF_SAFETY_MARGIN);
        AABB sweptPlayerBox = new AABB(
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
                    if (client.level.getBlockState(new BlockPos(x, y, z))
                            .is(Blocks.COBWEB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static AABB predictTargetBox(Player target, int ticks) {
        Vec3 position = target.position();
        Vec3 velocity = target.getDeltaMovement();
        boolean grounded = target.onGround();
        for (int tick = 0; tick < ticks; tick++) {
            if (grounded) {
                position = position.add(velocity.x, 0.0D, velocity.z);
                velocity = new Vec3(velocity.x * 0.91D, 0.0D, velocity.z * 0.91D);
            } else {
                double nextY = (velocity.y - 0.08D) * 0.98D;
                velocity = new Vec3(
                        velocity.x * 0.91D,
                        nextY,
                        velocity.z * 0.91D);
                position = position.add(velocity);
            }
        }
        return target.getDimensions(Pose.STANDING).makeBoundingBox(position);
    }

    private static boolean withinPlacementRange(Minecraft client, Vec3 hitLocation) {
        double allowed = Math.min(RANGE.get(), client.player.blockInteractionRange());
        return client.player.getEyePosition().distanceToSqr(hitLocation)
                <= allowed * allowed;
    }

    private static int findWebSlot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.COBWEB) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean ready(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.gameMode != null
                && MinecraftClientAccess.screen(client) == null;
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

    public static void yieldForAntiLava(Minecraft client) {
        resetPlan(client);
    }

    public static boolean isBusy() {
        return heldPlan != null || phase != WebActionPhase.IDLE;
    }

    private static void restoreHeldSlot(Minecraft client) {
        restoreWebSlotSelection(client);
        CombatInputController.releaseAttack(
                client, CombatInputController.Owner.AUTO_WEB);
        heldPlan = null;
        originalSlot = -1;
        postPlaceHoldRemainingTicks = 0;
        postPlaceHoldStarted = false;
        phase = WebActionPhase.IDLE;
        SilentPacketRotation.reset();
    }

    private static void restoreWebSlotSelection(Minecraft client) {
        if (client != null && client.player != null && heldWebSlot != -1) {
            Inventory inventory = client.player.getInventory();
            if (inventory.getSelectedSlot() == heldWebSlot && originalSlot >= 0) {
                inventory.setSelectedSlot(originalSlot);
            }
        }
        heldWebSlot = -1;
    }

    private static void clearPendingPlan() {
        pendingPlanKey = null;
        pendingDelayTicks = 0;
    }

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
        ClientChat.send(client,
                "AutoWeb: " + statusText()
                        + ", range: " + format(RANGE.get())
                        + ", delay: " + DELAY_TICKS.get() + "t"
                        + ", cooldown: " + format(COOLDOWN_SECONDS.get()) + "s"
                        + ", hold: " + HOLD_TICKS.get() + "t"
                        + ", rotation: instant"
                        + ", prediction: " + PREDICTION_TICKS.get() + "t"
                        + ", chance: " + format(CHANCE.get())
                        + ", wall: " + (WALL_ENABLED.get() ? "enabled" : "disabled")
                        + ", ground: " + (GROUND_ENABLED.get() ? "enabled" : "disabled")
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
        ClientChat.send(client, "AutoWeb wall webs "
                + (value ? "enabled" : "disabled")
                + " (predictive attack extension; immediate feet remains active).");
        return 1;
    }

    public static int setGroundEnabled(Minecraft client, boolean value) {
        GROUND_ENABLED.set(value);
        resetPlan(client);
        ClientChat.send(client, "AutoWeb flat-ground prediction "
                + (value ? "enabled" : "disabled")
                + " (post-hit landing only).");
        return 1;
    }

    public static int setWaitConfirmRotation(Minecraft client, boolean value) {
        WAIT_CONFIRM_ROTATION.set(value);
        ClientChat.send(client, "AutoWeb wait for confirmation before rotation return "
                + (value ? "enabled" : "disabled") + ".");
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
        ClientChat.send(client, "AutoWeb execution delay set to "
                + DELAY_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setCooldown(Minecraft client, double value) {
        COOLDOWN_SECONDS.set(value);
        ClientChat.send(client, "AutoWeb successful-placement cooldown set to "
                + format(COOLDOWN_SECONDS.get()) + " seconds.");
        return 1;
    }

    public static int setHoldTicks(Minecraft client, int value) {
        HOLD_TICKS.set(value);
        ClientChat.send(client, "AutoWeb post-place hold set to "
                + HOLD_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setPrediction(Minecraft client, int value) {
        PREDICTION_TICKS.set(value);
        resetPlan(client);
        ClientChat.send(client,
                "AutoWeb prediction set to " + PREDICTION_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        ClientChat.send(client, "AutoWeb trigger chance set to "
                + format(CHANCE.get()) + ".");
        return 1;
    }

    private static String format(double value) {
        return value == (long) value
                ? Long.toString((long) value) : Double.toString(value);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int requiredServerSettleTicks(Minecraft client) {
        int latencyMs = 0;
        if (client.getConnection() != null && client.player != null) {
            var info = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (info != null) {
                latencyMs = Math.max(0, info.getLatency());
            }
        }
        return clampInt((int) Math.ceil(latencyMs / 50.0D) + 3,
                8, MAX_PLACE_CONFIRM_TICKS);
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

    private record TrajectoryStep(Vec3 position, Vec3 velocity) {
    }

    private record WallGeometry(
            Direction direction,
            double alignment,
            double gap,
            boolean collisionOpportunity
    ) {
        private static final WallGeometry NONE = new WallGeometry(
                null, 0.0D, Double.POSITIVE_INFINITY, false);
    }

    private record PlacementPlan(
            int targetId,
            BlockPos placePos,
            BlockHitResult hit,
            int predictionTick,
            double score
    ) {
    }

    // Debug
    public static String debugState() {
        return phase.name().toLowerCase(java.util.Locale.ROOT)
                + " hold=" + postPlaceHoldRemainingTicks
                + " confirm=" + (pendingPlaceConfirmationPos == null
                ? "none" : placeConfirmTicks + "t")
                + " strict=" + WAIT_CONFIRM_ROTATION.get()
                + " use=" + SilentPacketRotation.isUseInvocationDone()
                + "/" + SilentPacketRotation.isUseDone();
    }
}
