package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * One-shot player-head obsidian trap for 26.1.2.
 *
 * <p>The planner recognises a two-wall corner or a three-wall doorway around
 * the target, places a bed in the open lane, pours lava into the target's head
 * cell from the ceiling, then places water beside it.  Every template is
 * rotated through the four horizontal directions and both bed orientations.
 */
public final class AutoObsidian {
    private static final int MAX_ACTION_WAIT_TICKS = 30;
    private static final int MAX_CONFIRM_TICKS = 12;
    private static final int MAX_OBSIDIAN_WAIT_TICKS = 40;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final double[] FACE_SAMPLES = {
            -0.36D, -0.18D, 0.0D, 0.18D, 0.36D
    };
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] SUPPORT_DIRECTIONS = {
            Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.DOWN
    };

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("autoobsidian.enabled")
                    .defaultValue(false)
                    .build();
    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("autoobsidian.range")
                    .defaultValue(4.5D)
                    .range(1.0D, 8.0D)
                    .build();
    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("autoobsidian.fov")
                    .defaultValue(100.0D)
                    .range(1.0D, 360.0D)
                    .build();
    private static final IntSetting MIN_WALLS =
            new IntSetting.Builder()
                    .name("autoobsidian.minWalls")
                    .defaultValue(2)
                    .range(2, 3)
                    .build();
    private static final IntSetting SMOOTH_TICKS =
            new IntSetting.Builder()
                    .name("autoobsidian.smoothTicks")
                    .defaultValue(2)
                    .range(1, 20)
                    .build();
    private static final IntSetting SWITCH_DELAY_MS =
            new IntSetting.Builder()
                    .name("autoobsidian.switchDelayMs")
                    .defaultValue(50)
                    .range(0, 500)
                    .build();
    private static final BooleanSetting COLLECT_WATER =
            new BooleanSetting.Builder()
                    .name("autoobsidian.collectWater")
                    .defaultValue(true)
                    .build();

    private static boolean initialized;
    private static Phase phase = Phase.IDLE;
    private static Phase phaseAfterSwitch = Phase.IDLE;
    private static int phaseTicks;
    private static long switchReadyAtNanos;
    private static int targetId = -1;
    private static int originalSlot = -1;
    private static int bedSlot = -1;
    private static int lavaSlot = -1;
    private static int waterSlot = -1;
    private static TrapPlan plan;
    private static PickupPlan pickupPlan;
    private static String finishMessage;

    private AutoObsidian() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.PLAYER_UPDATE.register("AutoObsidian.playerUpdate", event -> tick(event.client()));
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get()) {
            if (isBusy()) {
                cleanup(client, false, null);
            }
            return;
        }
        if (!ready(client)) {
            return;
        }
        if (phase == Phase.IDLE) {
            begin(client);
            return;
        }

        phaseTicks++;
        if (phase == Phase.WAITING_FOR_SWITCH) {
            if (System.nanoTime() >= switchReadyAtNanos) {
                continueAfterSwitch(client);
            }
            return;
        }
        if (isTurning(phase)) {
            if (phaseTicks > Math.max(MAX_ACTION_WAIT_TICKS, SMOOTH_TICKS.get() * 5)) {
                fail(client, "rotation timed out");
            }
            return;
        }
        switch (phase) {
            case WAITING_FOR_BED_ROTATION -> queueBed(client);
            case CLICKING_BED -> confirmQueuedUse(client, Phase.WAITING_FOR_BED_CONFIRM);
            case WAITING_FOR_BED_CONFIRM -> confirmBed(client);
            case WAITING_FOR_LAVA_ROTATION -> queueLava(client);
            case CLICKING_LAVA -> confirmQueuedUse(client, Phase.WAITING_FOR_LAVA_CONFIRM);
            case WAITING_FOR_LAVA_CONFIRM -> confirmLava(client);
            case WAITING_FOR_WATER_ROTATION -> queueWater(client);
            case CLICKING_WATER -> confirmQueuedUse(client, Phase.WAITING_FOR_OBSIDIAN);
            case WAITING_FOR_OBSIDIAN -> confirmObsidian(client);
            case WAITING_FOR_PICKUP_ROTATION -> queuePickup(client);
            case CLICKING_PICKUP -> confirmQueuedUse(client, Phase.WAITING_FOR_PICKUP_CONFIRM);
            case WAITING_FOR_PICKUP_CONFIRM -> confirmPickup(client);
            case WAITING_FOR_RETURN_ROTATION -> confirmReturn(client);
            default -> {
            }
        }
    }

    private static void begin(Minecraft client) {
        if (otherRotationOwnerBusy()) {
            return;
        }
        if (client.level.dimension() == Level.NETHER) {
            cleanup(client, true,
                    "AutoObsidian disabled: water cannot be placed in this dimension.");
            return;
        }
        Player target = findTarget(client);
        if (target == null) {
            cleanup(client, true,
                    "AutoObsidian disabled: no target inside scan range and FOV.");
            return;
        }
        int foundBed = findBedSlot(client);
        int foundLava = findSlot(client, Items.LAVA_BUCKET);
        int foundWater = findSlot(client, Items.WATER_BUCKET);
        if (foundBed < 0 || foundLava < 0 || foundWater < 0) {
            cleanup(client, true,
                    "AutoObsidian disabled: a bed, lava bucket and water bucket are required in the hotbar.");
            return;
        }
        TrapPlan foundPlan = findPlan(client, target);
        if (foundPlan == null) {
            cleanup(client, true,
                    "AutoObsidian disabled: no valid two-wall or three-wall trap layout was found.");
            return;
        }

        targetId = target.getId();
        originalSlot = client.player.getInventory().getSelectedSlot();
        bedSlot = foundBed;
        lavaSlot = foundLava;
        waterSlot = foundWater;
        plan = foundPlan;
        finishMessage = null;
        CombatInputController.suppressAttack(
                client, CombatInputController.Owner.AUTO_OBSIDIAN);
        switchAndRotate(client, bedSlot, Phase.TURNING_TO_BED);
    }

    private static void queueBed(Minecraft client) {
        if (!rotationReady(client, plan.bedHit()) || !validBedAction(client)) {
            if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                fail(client, "bed placement became invalid");
            }
            return;
        }
        if (SilentPacketRotation.queueUse(client, plan.bedHit())) {
            transition(Phase.CLICKING_BED);
        }
    }

    private static void confirmBed(Minecraft client) {
        if (isBed(client, plan.bedFoot()) && isBed(client, plan.bedHead())) {
            switchAndRotate(client, lavaSlot, Phase.TURNING_TO_LAVA);
            return;
        }
        if (phaseTicks > MAX_CONFIRM_TICKS) {
            fail(client, "bed placement was not confirmed");
        }
    }

    private static void queueLava(Minecraft client) {
        if (!rotationReady(client, plan.lavaHit()) || !validLavaAction(client)) {
            if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                fail(client, "lava placement became invalid");
            }
            return;
        }
        if (SilentPacketRotation.queueUse(client, plan.lavaHit())) {
            transition(Phase.CLICKING_LAVA);
        }
    }

    private static void confirmLava(Minecraft client) {
        if (isSource(client, plan.lavaPos(), FluidTags.LAVA)) {
            switchAndRotate(client, waterSlot, Phase.TURNING_TO_WATER);
            return;
        }
        if (phaseTicks > MAX_CONFIRM_TICKS) {
            fail(client, "lava source was not confirmed");
        }
    }

    private static void queueWater(Minecraft client) {
        if (!rotationReady(client, plan.waterHit()) || !validWaterAction(client)) {
            if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                fail(client, "water placement became invalid");
            }
            return;
        }
        if (SilentPacketRotation.queueUse(client, plan.waterHit())) {
            transition(Phase.CLICKING_WATER);
        }
    }

    private static void confirmObsidian(Minecraft client) {
        if (client.level.getBlockState(plan.lavaPos()).is(Blocks.OBSIDIAN)) {
            finishMessage = "AutoObsidian completed and disabled.";
            if (COLLECT_WATER.get()
                    && isSource(client, plan.waterPos(), FluidTags.WATER)
                    && client.player.getInventory().getItem(waterSlot).is(Items.BUCKET)) {
                beginPickup(client, new PickupPlan(
                        plan.waterPos(), waterSlot, Items.WATER_BUCKET, false));
            } else {
                beginReturn(client);
            }
            return;
        }
        if (phaseTicks > MAX_OBSIDIAN_WAIT_TICKS) {
            fail(client, "water did not convert the target-head lava into obsidian");
        }
    }

    private static void beginPickup(Minecraft client, PickupPlan value) {
        pickupPlan = value;
        switchAndRotate(client, value.slot(), Phase.TURNING_TO_PICKUP);
    }

    private static void queuePickup(Minecraft client) {
        if (pickupPlan == null
                || client.player.getInventory().getSelectedSlot() != pickupPlan.slot()
                || !client.player.getInventory().getItem(pickupPlan.slot()).is(Items.BUCKET)
                || !isPickupSource(client, pickupPlan)
                || !sentLookReachesSource(client, pickupPlan.pos())) {
            if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                beginReturn(client);
            }
            return;
        }
        if (SilentPacketRotation.queueUse(client, sourceHit(pickupPlan.pos()))) {
            transition(Phase.CLICKING_PICKUP);
        }
    }

    private static void confirmPickup(Minecraft client) {
        if (pickupPlan == null) {
            beginReturn(client);
            return;
        }
        boolean refilled = client.player.getInventory().getItem(pickupPlan.slot())
                .is(pickupPlan.refilledItem());
        if (refilled || !isPickupSource(client, pickupPlan)) {
            beginReturn(client);
            return;
        }
        if (phaseTicks > MAX_CONFIRM_TICKS) {
            beginReturn(client);
        }
    }

    private static void confirmQueuedUse(Minecraft client, Phase next) {
        if (SilentPacketRotation.isUseDone()) {
            transition(next);
        } else if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
            fail(client, "server use packet timed out");
        }
    }

    private static void switchAndRotate(Minecraft client, int slot, Phase turningPhase) {
        if (selectSlot(client, slot)) {
            phaseAfterSwitch = turningPhase;
            switchReadyAtNanos = System.nanoTime()
                    + SWITCH_DELAY_MS.get() * 1_000_000L;
            transition(Phase.WAITING_FOR_SWITCH);
            if (SWITCH_DELAY_MS.get() == 0) {
                continueAfterSwitch(client);
            }
        } else {
            beginRotation(client, turningPhase);
        }
    }

    private static void continueAfterSwitch(Minecraft client) {
        Phase next = phaseAfterSwitch;
        phaseAfterSwitch = Phase.IDLE;
        switchReadyAtNanos = 0L;
        beginRotation(client, next);
    }

    private static void beginRotation(Minecraft client, Phase turningPhase) {
        Vec3 target;
        Phase waitingPhase;
        if (turningPhase == Phase.TURNING_TO_BED) {
            target = plan.bedHit().getLocation();
            waitingPhase = Phase.WAITING_FOR_BED_ROTATION;
        } else if (turningPhase == Phase.TURNING_TO_LAVA) {
            target = plan.lavaHit().getLocation();
            waitingPhase = Phase.WAITING_FOR_LAVA_ROTATION;
        } else if (turningPhase == Phase.TURNING_TO_WATER) {
            target = plan.waterHit().getLocation();
            waitingPhase = Phase.WAITING_FOR_WATER_ROTATION;
        } else if (turningPhase == Phase.TURNING_TO_PICKUP && pickupPlan != null) {
            target = Vec3.atCenterOf(pickupPlan.pos());
            waitingPhase = Phase.WAITING_FOR_PICKUP_ROTATION;
        } else {
            fail(client, "invalid action state");
            return;
        }
        transition(turningPhase);
        SilentPacketRotation.beginRotation(
                client, target, SMOOTH_TICKS.get(),
                () -> transitionIf(turningPhase, waitingPhase));
    }

    private static void beginReturn(Minecraft client) {
        if (phase == Phase.TURNING_BACK || phase == Phase.WAITING_FOR_RETURN_ROTATION) {
            return;
        }
        if (!SilentPacketRotation.shouldApplyRotation()) {
            cleanup(client, true, finishMessage);
            return;
        }
        transition(Phase.TURNING_BACK);
        SilentPacketRotation.beginReturnToCamera(
                client, SMOOTH_TICKS.get(),
                () -> transitionIf(Phase.TURNING_BACK, Phase.WAITING_FOR_RETURN_ROTATION));
    }

    private static void confirmReturn(Minecraft client) {
        if (SilentPacketRotation.isRotationPacketSent()) {
            cleanup(client, true, finishMessage);
        } else if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
            cleanup(client, true,
                    finishMessage != null ? finishMessage
                            : "AutoObsidian disabled after return rotation timeout.");
        }
    }

    /** Fail safely: if lava was already placed, first try to collect it. */
    private static void fail(Minecraft client, String reason) {
        if (phase == Phase.TURNING_TO_PICKUP
                || phase == Phase.WAITING_FOR_PICKUP_ROTATION
                || phase == Phase.CLICKING_PICKUP
                || phase == Phase.WAITING_FOR_PICKUP_CONFIRM
                || phase == Phase.TURNING_BACK
                || phase == Phase.WAITING_FOR_RETURN_ROTATION) {
            return;
        }
        finishMessage = "AutoObsidian disabled: " + reason + ".";
        if (plan != null
                && isSource(client, plan.lavaPos(), FluidTags.LAVA)
                && lavaSlot >= 0
                && client.player.getInventory().getItem(lavaSlot).is(Items.BUCKET)) {
            beginPickup(client, new PickupPlan(
                    plan.lavaPos(), lavaSlot, Items.LAVA_BUCKET, true));
            return;
        }
        if (plan != null
                && isSource(client, plan.waterPos(), FluidTags.WATER)
                && waterSlot >= 0
                && client.player.getInventory().getItem(waterSlot).is(Items.BUCKET)) {
            beginPickup(client, new PickupPlan(
                    plan.waterPos(), waterSlot, Items.WATER_BUCKET, false));
            return;
        }
        beginReturn(client);
    }

    private static TrapPlan findPlan(Minecraft client, Player target) {
        BlockPos targetFoot = targetFoot(target);
        BlockPos lavaPos = targetFoot.above();
        BlockPos ceiling = targetFoot.above(2);
        if (!replaceableDry(client, lavaPos) || !solid(client, ceiling)) {
            return null;
        }
        BlockHitResult lavaHit = bestVisibleFace(
                client, ceiling, Direction.DOWN);
        if (lavaHit == null || !withinReach(client, lavaHit.getLocation())) {
            return null;
        }

        TrapPlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction opening : HORIZONTAL) {
            Direction back = opening.getOpposite();
            Direction left = opening.getCounterClockWise();
            Direction right = opening.getClockWise();
            if (!solid(client, targetFoot.relative(back))) {
                continue;
            }
            int wallCount = 1;
            if (solid(client, targetFoot.relative(left))) {
                wallCount++;
            }
            if (solid(client, targetFoot.relative(right))) {
                wallCount++;
            }
            if (wallCount < MIN_WALLS.get()) {
                continue;
            }

            BlockPos near = targetFoot.relative(opening);
            BlockPos far = near.relative(opening);
            if (!validBedCell(client, target, near)
                    || !validBedCell(client, target, far)) {
                continue;
            }
            BlockPos waterPos = near.above();
            if (!replaceableDry(client, waterPos)) {
                continue;
            }

            for (boolean footNear : new boolean[]{true, false}) {
                BlockPos foot = footNear ? near : far;
                BlockPos head = footNear ? far : near;
                Direction facing = footNear ? opening : opening.getOpposite();
                BlockHitResult bedHit = findBedPlacementHit(
                        client, foot, facing);
                if (bedHit == null) {
                    continue;
                }
                Set<BlockPos> forbiddenSupports = Set.of(
                        foot.immutable(), head.immutable(), lavaPos.immutable());
                BlockHitResult waterHit = findPlacementHit(
                        client, waterPos, forbiddenSupports);
                if (waterHit == null) {
                    continue;
                }
                int headWalls = 0;
                for (Direction wall : new Direction[]{back, left, right}) {
                    if (solid(client, lavaPos.relative(wall))) {
                        headWalls++;
                    }
                }
                double viewCost = squaredDistanceToViewRay(
                        client.player.getEyePosition(), client.player.getLookAngle(),
                        bedHit.getLocation(), client.player.blockInteractionRange());
                double score = -wallCount * 1_000.0D - headWalls * 100.0D
                        + viewCost * 10.0D
                        + client.player.getEyePosition().distanceToSqr(
                        bedHit.getLocation());
                if (score < bestScore) {
                    bestScore = score;
                    best = new TrapPlan(
                            targetFoot.immutable(), foot.immutable(), head.immutable(),
                            near.immutable(), waterPos.immutable(), lavaPos.immutable(),
                            facing, wallCount, bedHit, lavaHit, waterHit);
                }
            }
        }
        return best;
    }

    private static BlockHitResult findBedPlacementHit(
            Minecraft client, BlockPos foot, Direction requiredFacing) {
        BlockPos floor = foot.below();
        if (!solid(client, floor)) {
            return null;
        }
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double x : FACE_SAMPLES) {
            for (double z : FACE_SAMPLES) {
                Vec3 requested = pointOnFace(floor, Direction.UP, x, z);
                BlockHitResult hit = visibleFaceHit(
                        client, floor, Direction.UP, requested);
                if (hit == null || !withinReach(client, hit.getLocation())
                        || Direction.fromYRot(yawTo(eye, hit.getLocation()))
                        != requiredFacing) {
                    continue;
                }
                double distance = eye.distanceToSqr(hit.getLocation());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = hit;
                }
            }
        }
        return best;
    }

    private static BlockHitResult findPlacementHit(
            Minecraft client, BlockPos target, Set<BlockPos> forbiddenSupports) {
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction supportDirection : SUPPORT_DIRECTIONS) {
            BlockPos support = target.relative(supportDirection);
            if (forbiddenSupports.contains(support) || !solid(client, support)) {
                continue;
            }
            Direction face = supportDirection.getOpposite();
            BlockHitResult hit = bestVisibleFace(client, support, face);
            if (hit == null || !withinReach(client, hit.getLocation())) {
                continue;
            }
            double distance = client.player.getEyePosition()
                    .distanceToSqr(hit.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = hit;
            }
        }
        return best;
    }

    private static BlockHitResult bestVisibleFace(
            Minecraft client, BlockPos support, Direction face) {
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double first : FACE_SAMPLES) {
            for (double second : FACE_SAMPLES) {
                BlockHitResult hit = visibleFaceHit(
                        client, support, face,
                        pointOnFace(support, face, first, second));
                if (hit == null) {
                    continue;
                }
                double distance = client.player.getEyePosition()
                        .distanceToSqr(hit.getLocation());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = hit;
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(
            BlockPos support, Direction face, double first, double second) {
        double x = support.getX() + 0.5D;
        double y = support.getY() + 0.5D;
        double z = support.getZ() + 0.5D;
        return switch (face.getAxis()) {
            case X -> new Vec3(x + face.getStepX() * 0.5D, y + first, z + second);
            case Y -> new Vec3(x + first, y + face.getStepY() * 0.5D, z + second);
            case Z -> new Vec3(x + first, y + second, z + face.getStepZ() * 0.5D);
        };
    }

    private static BlockHitResult visibleFaceHit(
            Minecraft client, BlockPos support, Direction face, Vec3 requested) {
        Vec3 justInside = requested.add(
                -face.getStepX() * RAY_EPSILON,
                -face.getStepY() * RAY_EPSILON,
                -face.getStepZ() * RAY_EPSILON);
        BlockHitResult hit = client.level.clip(new ClipContext(
                client.player.getEyePosition(), justInside,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(support)
                || hit.getDirection() != face) {
            return null;
        }
        return new BlockHitResult(hit.getLocation(), face, support, hit.isInside());
    }

    private static boolean validBedAction(Minecraft client) {
        if (plan == null || !targetStillInCell(client)
                || !selectedIsBed(client)
                || !validBedCell(client, targetById(client), plan.bedFoot())
                || !validBedCell(client, targetById(client), plan.bedHead())
                || Direction.fromYRot(SilentPacketRotation.getInteractionYaw(client))
                != plan.bedFacing()) {
            return false;
        }
        ItemStack stack = client.player.getInventory().getItem(bedSlot);
        BlockPlaceContext context = new BlockPlaceContext(
                new UseOnContext(client.player, InteractionHand.MAIN_HAND, plan.bedHit()));
        return stack.getItem() instanceof BlockItem item
                && item.getBlock().getStateForPlacement(context) != null;
    }

    private static boolean validLavaAction(Minecraft client) {
        return plan != null
                && targetStillInCell(client)
                && selectedIs(client, lavaSlot, Items.LAVA_BUCKET)
                && replaceableDry(client, plan.lavaPos())
                && visibleFaceHit(
                client, plan.lavaHit().getBlockPos(), plan.lavaHit().getDirection(),
                plan.lavaHit().getLocation()) != null;
    }

    private static boolean validWaterAction(Minecraft client) {
        return plan != null
                && selectedIs(client, waterSlot, Items.WATER_BUCKET)
                && isSource(client, plan.lavaPos(), FluidTags.LAVA)
                && replaceableDry(client, plan.waterPos())
                && isBed(client, plan.bedFoot())
                && isBed(client, plan.bedHead())
                && visibleFaceHit(
                client, plan.waterHit().getBlockPos(), plan.waterHit().getDirection(),
                plan.waterHit().getLocation()) != null;
    }

    private static boolean rotationReady(Minecraft client, BlockHitResult hit) {
        return sentLookMatches(client, hit);
    }

    private static boolean sentLookMatches(Minecraft client, BlockHitResult planned) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(SilentPacketRotation.getInteractionLookVector(client)
                .scale(client.player.blockInteractionRange()));
        BlockHitResult actual = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, client.player));
        return actual.getType() == HitResult.Type.BLOCK
                && actual.getBlockPos().equals(planned.getBlockPos())
                && actual.getDirection() == planned.getDirection();
    }

    private static boolean sentLookReachesSource(Minecraft client, BlockPos source) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end = eye.add(SilentPacketRotation.getInteractionLookVector(client)
                .scale(client.player.blockInteractionRange()));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY, client.player));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(source);
    }

    private static BlockHitResult sourceHit(BlockPos source) {
        return BlockHitResult.miss(Vec3.atCenterOf(source), Direction.UP, source);
    }

    private static boolean validBedCell(Minecraft client, Player target, BlockPos pos) {
        if (target == null || !replaceableDry(client, pos)
                || !solid(client, pos.below())) {
            return false;
        }
        AABB box = new AABB(pos);
        return !client.player.getBoundingBox().intersects(box)
                && !target.getBoundingBox().intersects(box);
    }

    private static boolean replaceableDry(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private static boolean solid(Minecraft client, BlockPos pos) {
        return !client.level.getBlockState(pos)
                .getCollisionShape(client.level, pos).isEmpty();
    }

    private static boolean isBed(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).getBlock() instanceof BedBlock;
    }

    private static boolean isSource(
            Minecraft client, BlockPos pos, net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
        BlockState state = client.level.getBlockState(pos);
        return state.getFluidState().is(tag) && state.getFluidState().isSource();
    }

    private static boolean isPickupSource(Minecraft client, PickupPlan pickup) {
        return pickup.lava()
                ? isSource(client, pickup.pos(), FluidTags.LAVA)
                : isSource(client, pickup.pos(), FluidTags.WATER);
    }

    private static Player findTarget(Minecraft client) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player target : client.level.players()) {
            if (!validTarget(client, target)) {
                continue;
            }
            double distance = EntityDistance.squaredToEntity(client, target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }

    private static boolean validTarget(Minecraft client, Player target) {
        if (!Targeting.isValidTargetPlayer(client, target)
                || EntityDistance.squaredToEntity(client, target)
                > RANGE.get() * RANGE.get()) {
            return false;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 point = EntityDistance.closestPoint(eye, target.getBoundingBox());
        return MathUtils.withinFov(
                MathUtils.viewAngle(eye, client.player.getLookAngle(), point), FOV.get());
    }

    private static boolean targetStillInCell(Minecraft client) {
        Player target = targetById(client);
        return Targeting.isValidTargetPlayer(client, target)
                && plan != null
                && targetFoot(target).equals(plan.targetFoot());
    }

    private static BlockPos targetFoot(Player target) {
        return BlockPos.containing(
                target.getX(), target.getBoundingBox().minY + 1.0E-4D, target.getZ());
    }

    private static Player targetById(Minecraft client) {
        return targetId < 0 ? null
                : client.level.getEntity(targetId) instanceof Player player ? player : null;
    }

    private static int findBedSlot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).getItem() instanceof BedItem) {
                return slot;
            }
        }
        return -1;
    }

    private static int findSlot(Minecraft client, net.minecraft.world.item.Item item) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean selectedIs(
            Minecraft client, int slot, net.minecraft.world.item.Item item) {
        return slot >= 0 && slot < 9
                && client.player.getInventory().getSelectedSlot() == slot
                && client.player.getInventory().getItem(slot).is(item);
    }

    private static boolean selectedIsBed(Minecraft client) {
        return bedSlot >= 0 && bedSlot < 9
                && client.player.getInventory().getSelectedSlot() == bedSlot
                && client.player.getInventory().getItem(bedSlot).getItem() instanceof BedItem;
    }

    private static boolean selectSlot(Minecraft client, int slot) {
        if (slot < 0 || slot > 8
                || client.player.getInventory().getSelectedSlot() == slot) {
            return false;
        }
        client.player.getInventory().setSelectedSlot(slot);
        return true;
    }

    private static boolean withinReach(Minecraft client, Vec3 point) {
        double reach = Math.min(RANGE.get(), client.player.blockInteractionRange());
        return client.player.getEyePosition().distanceToSqr(point) <= reach * reach;
    }

    private static float yawTo(Vec3 eye, Vec3 point) {
        return MathUtils.yawTo(eye, point);
    }

    private static double squaredDistanceToViewRay(
            Vec3 eye, Vec3 look, Vec3 point, double rayLength) {
        return MathUtils.squaredDistanceToRay(eye, look, point, rayLength);
    }

    private static boolean otherRotationOwnerBusy() {
        return AutoBed.isBusy() || AutoLava.isBusy() || AutoWeb.isBusy()
                || AntiLava.isBusy() || AntiWeb.isBusy();
    }

    private static boolean ready(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        return client != null && currentPlayer != null && client.level != null
                && client.gameMode != null
                && MinecraftClientAccess.screen(client) == null
                && !currentPlayer.isDeadOrDying();
    }

    private static boolean isTurning(Phase value) {
        return value == Phase.TURNING_TO_BED
                || value == Phase.TURNING_TO_LAVA
                || value == Phase.TURNING_TO_WATER
                || value == Phase.TURNING_TO_PICKUP
                || value == Phase.TURNING_BACK;
    }

    private static void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private static void transitionIf(Phase expected, Phase next) {
        if (phase == expected) {
            transition(next);
        }
    }

    private static void cleanup(Minecraft client, boolean disable, String message) {
        var currentPlayer = client == null ? null : client.player;
        boolean owned = isBusy();
        if (owned && client != null && currentPlayer != null
                && originalSlot >= 0 && originalSlot <= 8) {
            currentPlayer.getInventory().setSelectedSlot(originalSlot);
        }
        CombatInputController.releaseAttack(
                client, CombatInputController.Owner.AUTO_OBSIDIAN);
        if (owned) {
            SilentPacketRotation.reset();
        }
        phase = Phase.IDLE;
        phaseAfterSwitch = Phase.IDLE;
        phaseTicks = 0;
        switchReadyAtNanos = 0L;
        targetId = -1;
        originalSlot = -1;
        bedSlot = -1;
        lavaSlot = -1;
        waterSlot = -1;
        plan = null;
        pickupPlan = null;
        finishMessage = null;
        if (disable) {
            ENABLED.set(false);
        }
        if (message != null) {
            ClientChat.send(client, message);
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean isBusy() {
        return phase != Phase.IDLE;
    }

    public static String hudTag() {
        return isBusy() ? phase.label : MIN_WALLS.get() + " walls";
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (!value) {
            cleanup(client, false, null);
            ENABLED.set(false);
        } else {
            cleanup(client, false, null);
            ENABLED.set(true);
        }
        ClientChat.send(client, "AutoObsidian " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setRange(Minecraft ignoredClient, double value) {
        RANGE.set(Mth.clamp(value, 1.0D, 8.0D));
        return 1;
    }

    public static int setFov(Minecraft ignoredClient, double value) {
        FOV.set(Mth.clamp(value, 1.0D, 360.0D));
        return 1;
    }

    public static int setMinWalls(Minecraft ignoredClient, int value) {
        MIN_WALLS.set(Mth.clamp(value, 2, 3));
        return 1;
    }

    public static int setSmoothTicks(Minecraft ignoredClient, int value) {
        SMOOTH_TICKS.set(Mth.clamp(value, 1, 20));
        return 1;
    }

    public static int setSwitchDelayMs(Minecraft ignoredClient, int value) {
        SWITCH_DELAY_MS.set(Mth.clamp(value, 0, 500));
        return 1;
    }

    public static int setCollectWater(Minecraft ignoredClient, boolean value) {
        COLLECT_WATER.set(value);
        return 1;
    }

    private record TrapPlan(
            BlockPos targetFoot,
            BlockPos bedFoot,
            BlockPos bedHead,
            BlockPos nearBed,
            BlockPos waterPos,
            BlockPos lavaPos,
            Direction bedFacing,
            int wallCount,
            BlockHitResult bedHit,
            BlockHitResult lavaHit,
            BlockHitResult waterHit) {
    }

    private record PickupPlan(
            BlockPos pos,
            int slot,
            net.minecraft.world.item.Item refilledItem,
            boolean lava) {
    }

    private enum Phase {
        IDLE("Idle"),
        WAITING_FOR_SWITCH("Switch"),
        TURNING_TO_BED("Bed aim"),
        WAITING_FOR_BED_ROTATION("Bed"),
        CLICKING_BED("Bed click"),
        WAITING_FOR_BED_CONFIRM("Bed sync"),
        TURNING_TO_LAVA("Lava aim"),
        WAITING_FOR_LAVA_ROTATION("Lava"),
        CLICKING_LAVA("Lava click"),
        WAITING_FOR_LAVA_CONFIRM("Lava sync"),
        TURNING_TO_WATER("Water aim"),
        WAITING_FOR_WATER_ROTATION("Water"),
        CLICKING_WATER("Water click"),
        WAITING_FOR_OBSIDIAN("Obsidian sync"),
        TURNING_TO_PICKUP("Pickup aim"),
        WAITING_FOR_PICKUP_ROTATION("Pickup"),
        CLICKING_PICKUP("Pickup click"),
        WAITING_FOR_PICKUP_CONFIRM("Pickup sync"),
        TURNING_BACK("Return"),
        WAITING_FOR_RETURN_ROTATION("Return sync");

        private final String label;

        Phase(String label) {
            this.label = label;
        }
    }
}
