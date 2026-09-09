package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.entity.EntityDistance;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One-shot bed bomb for 26.1.2. Balance mode uses the configured rotation and
 * switch speeds. Blatant mode uses one-tick rotations, immediate switches and
 * may build a support beside the target before placing and exploding the bed.
 */
public final class AutoBed {
    private static final int MAX_ACTION_WAIT_TICKS = 30;
    private static final int MAX_CONFIRM_TICKS = 10;
    private static final int MAX_POINT_RETRIES = 48;
    private static final int BED_SEARCH_RADIUS = 2;
    private static final int BED_FOOT_SEARCH_RADIUS = BED_SEARCH_RADIUS + 1;
    private static final int BED_SEARCH_VERTICAL = 1;
    private static final double MAX_TARGET_EXPLOSION_DISTANCE = 2.5D;
    private static final double MIN_SELF_EXPLOSION_DISTANCE = 2.0D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final Direction[] SUPPORT_FACES = {
        Direction.UP,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.DOWN
    };
    private static final double[] BED_FACE_SAMPLES = {-0.38D, -0.19D, 0.0D, 0.19D, 0.38D};
    private static final String MODE_BALANCE = "balance";
    private static final String MODE_BLATANT = "blatant";

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autobed.enabled").defaultValue(false).build();
    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("autobed.mode")
                    .defaultValue(Mode.BALANCE)
                    .option(Mode.BALANCE, MODE_BALANCE)
                    .option(Mode.BLATANT, MODE_BLATANT)
                    .build();
    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("autobed.fov")
                    .defaultValue(90.0D)
                    .range(1.0D, 360.0D)
                    .build();
    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("autobed.range")
                    .defaultValue(4.5D)
                    .range(1.0D, 10.0D)
                    .build();
    private static final DoubleSetting MIN_DAMAGE =
            new DoubleSetting.Builder()
                    .name("autobed.minDamage")
                    .defaultValue(6.0D)
                    .range(0.0D, 36.0D)
                    .build();
    private static final DoubleSetting MAX_SELF_DAMAGE =
            new DoubleSetting.Builder()
                    .name("autobed.maxSelfDamage")
                    .defaultValue(8.0D)
                    .range(0.0D, 36.0D)
                    .build();
    private static final BooleanSetting ANTI_SUICIDE =
            new BooleanSetting.Builder().name("autobed.antiSuicide").defaultValue(true).build();
    private static final BooleanSetting TARGET_RANGE_RECHECK =
            new BooleanSetting.Builder()
                    .name("autobed.targetRangeRecheck")
                    .defaultValue(true)
                    .build();
    private static final IntSetting SMOOTH_TICKS =
            new IntSetting.Builder()
                    .name("autobed.smoothTicks")
                    .defaultValue(3)
                    .range(1, 20)
                    .build();
    private static final IntSetting BLATANT_SMOOTH_TICKS =
            new IntSetting.Builder()
                    .name("autobed.blatantSmoothTicks")
                    .defaultValue(1)
                    .range(1, 20)
                    .build();
    private static final IntSetting SWITCH_DELAY_MS =
            new IntSetting.Builder()
                    .name("autobed.switchDelayMs")
                    .defaultValue(50)
                    .range(0, 500)
                    .build();
    private static final IntSetting CLICK_DELAY_MS =
            new IntSetting.Builder()
                    .name("autobed.clickDelayMs")
                    .defaultValue(50)
                    .range(0, 500)
                    .build();

    private static boolean initialized;
    private static Phase phase = Phase.IDLE;
    private static int phaseTicks;
    private static int targetId = -1;
    private static int originalSlot = -1;
    private static int materialSlot = -1;
    private static int bedSlot = -1;
    private static InteractionHand materialHand;
    private static BlockItem materialItem;
    private static BlockItem shieldItem;
    private static boolean reusedBalanceBase;
    private static BasePlan basePlan;
    private static BasePlan targetSupportPlan;
    private static BedPlan bedPlan;
    private static boolean interactionCompleted;
    private static long switchReadyAtNanos;
    private static long clickReadyAtNanos;
    private static Phase phaseAfterSwitch = Phase.IDLE;
    private static final Set<BedPointKey> rejectedBedPoints = new HashSet<>();
    private static final Set<BedPlacementKey> rejectedBedPlacements = new HashSet<>();
    private static final Set<BlockPos> rejectedBasePositions = new HashSet<>();
    private static final Set<BlockPos> rejectedTargetSupports = new HashSet<>();
    private static int pointRetries;
    private static boolean invokingBedUse;
    private static boolean usedBeforeMovement;
    private static Runnable afterUseMovement;

    private AutoBed() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        EventBus.PLAYER_UPDATE.register("AutoBed.playerUpdate", event -> tick(event.client()));
        EventBus.PACKET_SEND_POST.register(
                "AutoBed.useSent",
                event -> {
                    if (invokingBedUse && event.packet() instanceof ServerboundUseItemOnPacket) {
                        usedBeforeMovement = true;
                    }
                });
        EventBus.PLAYER_MOTION_POST.register("AutoBed.afterMovement", event -> finishUseMovement());
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
        if (isTurningPhase(phase)) {
            if (phaseTicks > Math.max(MAX_ACTION_WAIT_TICKS, effectiveSmoothTicks() * 5)) {
                if (phase == Phase.TURNING_TO_BED && retryBedPoint(client, false)) {
                    return;
                }
                if (phase == Phase.TURNING_TO_TARGET_SUPPORT && retryTargetSupport(client)) {
                    return;
                }
                fail(client, "rotation timed out");
            }
            return;
        }
        if (phase == Phase.WAITING_FOR_BASE_ROTATION) {
            placeBase(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_BASE_CONFIRM) {
            confirmBase(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_TARGET_SUPPORT_ROTATION) {
            placeTargetSupport(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_TARGET_SUPPORT_CONFIRM) {
            confirmTargetSupport(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_BED_ROTATION) {
            placeBed(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_BED_CLICK) {
            clickPlacedBed(client);
            return;
        }
        if (phase == Phase.WAITING_FOR_RETURN_ROTATION) {
            if (SilentPacketRotation.isRotationPacketSent()) {
                cleanup(
                        client,
                        true,
                        interactionCompleted
                                ? "AutoBed completed and disabled."
                                : "AutoBed disabled after the failed cycle.");
            } else if (phaseTicks > MAX_ACTION_WAIT_TICKS) {
                cleanup(client, true, "AutoBed disabled: return rotation packet timed out.");
            }
        }
    }

    private static void begin(Minecraft client) {
        if (otherRotationOwnerBusy()) {
            return;
        }
        Player target = findTarget(client);
        if (isBlatant() && target == null) {
            cleanup(client, true, "AutoBed disabled: no player inside scan range and FOV.");
            return;
        }
        PlacementMaterial material = findMaterial(client);
        int foundBedSlot = findBedSlot(client);
        BedPlan existingBalanceBed = !isBlatant() ? findExistingBalanceBedPlan(client) : null;
        BasePlan foundBase = findBasePlan(client, material, target);
        if (foundBedSlot < 0) {
            cleanup(client, true, "AutoBed disabled: no bed in hotbar.");
            return;
        }
        if (foundBase == null && existingBalanceBed == null) {
            cleanup(client, true, "AutoBed disabled: no placeable block in front.");
            return;
        }

        targetId = target == null ? -1 : target.getId();
        originalSlot = client.player.getInventory().getSelectedSlot();
        bedSlot = foundBedSlot;
        if (existingBalanceBed != null) {
            materialHand = null;
            materialSlot = -1;
            materialItem = null;
            shieldItem = null;
            reusedBalanceBase = true;
            bedPlan = existingBalanceBed;
            basePlan =
                    new BasePlan(
                            existingBalanceBed.foot().below().immutable(),
                            existingBalanceBed.hit());
            CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BED);
            selectBedAndRotate(client);
            return;
        }
        if (material == null) {
            cleanup(client, true, "AutoBed disabled: no solid block in offhand or hotbar.");
            return;
        }
        materialHand = material.hand();
        materialSlot = material.hotbarSlot();
        materialItem = material.item();
        shieldItem = material.item();
        reusedBalanceBase = false;
        basePlan = foundBase;
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BED);

        if (materialHand == InteractionHand.MAIN_HAND && selectSlot(client, materialSlot)) {
            waitForSwitch(client, Phase.TURNING_TO_BASE);
        } else {
            beginBaseRotation(client);
        }
    }

    private static void beginBaseRotation(Minecraft client) {
        if (deferAfterUse(() -> beginBaseRotation(client))) return;
        if (!validBasePlan(client)) {
            if (retryBasePlan(client)) {
                return;
            }
            fail(client, "front block is no longer placeable");
            return;
        }
        transition(Phase.TURNING_TO_BASE);
        SilentPacketRotation.beginRotation(
                client,
                basePlan.hit().getLocation(),
                effectiveSmoothTicks(),
                () -> transitionIf(Phase.TURNING_TO_BASE, Phase.WAITING_FOR_BASE_ROTATION));
    }

    private static void placeBase(Minecraft client) {
        if (!SilentPacketRotation.isRotationPacketSent()) {
            return;
        }
        if (!validBasePlan(client) || !completeProjectedCycleStillAvailable(client)) {
            if (retryBasePlan(client)) {
                return;
            }
            fail(client, "front block placement became invalid");
            return;
        }
        InteractionResult result = useOnSilently(client, materialHand, basePlan.hit());
        if (!result.consumesAction()) {
            if (retryBasePlan(client)) {
                return;
            }
            fail(client, "front block placement was rejected");
            return;
        }
        transition(Phase.WAITING_FOR_BASE_CONFIRM);
    }

    private static void confirmBase(Minecraft client) {
        BlockState state = client.level.getBlockState(basePlan.pos());
        if (state.is(materialItem.getBlock())) {
            Player target = targetById(client);
            if (isBlatant() && !validFovTarget(client, target)) {
                fail(client, "target left the scan range or FOV");
                return;
            }
            // Balance is deliberately deterministic: the shield block is the
            // bed support. Blatant keeps the wider target-side search.
            bedPlan =
                    isBlatant()
                            ? findBedPlan(client, target, basePlan.pos())
                            : simpleBalanceBedPlan(client, basePlan.pos());
            if (bedPlan == null) {
                if (isBlatant() && prepareTargetSupport(client, target)) {
                    return;
                }
                fail(
                        client,
                        isBlatant()
                                ? "no reachable target support or safe bed position"
                                : "no reachable safe bed position close to the target");
                return;
            }
            selectBedAndRotate(client);
            return;
        }
        if (phaseTicks > MAX_CONFIRM_TICKS) {
            if (client.level.getBlockState(basePlan.pos()).canBeReplaced()
                    && retryBasePlan(client)) {
                return;
            }
            fail(client, "front block was not confirmed");
        }
    }

    private static boolean retryBasePlan(Minecraft client) {
        if (basePlan != null) {
            rejectedBasePositions.add(basePlan.pos());
        }
        Player target = targetById(client);
        if (!validFovTarget(client, target) || materialItem == null) {
            return false;
        }
        PlacementMaterial material =
                new PlacementMaterial(materialHand, materialSlot, materialItem);
        BasePlan alternate = findBasePlan(client, material, target);
        if (alternate == null) {
            return false;
        }
        basePlan = alternate;
        targetSupportPlan = null;
        bedPlan = null;
        beginBaseRotation(client);
        return true;
    }

    private static boolean completeProjectedCycleStillAvailable(Minecraft client) {
        if (!isBlatant()) {
            return true;
        }
        Player target = targetById(client);
        if (!validFovTarget(client, target) || basePlan == null || materialItem == null) {
            return false;
        }
        PlacementMaterial material =
                new PlacementMaterial(materialHand, materialSlot, materialItem);
        return previewCycle(client, material, target, basePlan.pos()) != null;
    }

    private static boolean prepareTargetSupport(Minecraft client, Player target) {
        PlacementMaterial material = findMaterial(client);
        if (material == null) {
            return false;
        }
        BasePlan plan = findTargetSupportPlan(client, material, target, basePlan.pos());
        if (plan == null) {
            return false;
        }
        materialHand = material.hand();
        materialSlot = material.hotbarSlot();
        materialItem = material.item();
        targetSupportPlan = plan;
        if (materialHand == InteractionHand.MAIN_HAND && selectSlot(client, materialSlot)) {
            waitForSwitch(client, Phase.TURNING_TO_TARGET_SUPPORT);
        } else {
            beginTargetSupportRotation(client);
        }
        return true;
    }

    private static void beginTargetSupportRotation(Minecraft client) {
        if (deferAfterUse(() -> beginTargetSupportRotation(client))) return;
        if (!validTargetSupportPlan(client)) {
            if (retryTargetSupport(client)) {
                return;
            }
            fail(client, "target support is no longer placeable");
            return;
        }
        transition(Phase.TURNING_TO_TARGET_SUPPORT);
        SilentPacketRotation.beginRotation(
                client,
                targetSupportPlan.hit().getLocation(),
                effectiveSmoothTicks(),
                () ->
                        transitionIf(
                                Phase.TURNING_TO_TARGET_SUPPORT,
                                Phase.WAITING_FOR_TARGET_SUPPORT_ROTATION));
    }

    private static void placeTargetSupport(Minecraft client) {
        if (!SilentPacketRotation.isRotationPacketSent()) {
            return;
        }
        if (!validTargetSupportPlan(client)) {
            if (retryTargetSupport(client)) {
                return;
            }
            fail(client, "target support placement became invalid");
            return;
        }
        InteractionResult result = useOnSilently(client, materialHand, targetSupportPlan.hit());
        if (!result.consumesAction()) {
            if (retryTargetSupport(client)) {
                return;
            }
            fail(client, "target support placement was rejected");
            return;
        }
        transition(Phase.WAITING_FOR_TARGET_SUPPORT_CONFIRM);
    }

    private static void confirmTargetSupport(Minecraft client) {
        if (client.level.getBlockState(targetSupportPlan.pos()).is(materialItem.getBlock())) {
            Player target = targetById(client);
            if (!validFovTarget(client, target)) {
                fail(client, "target left the scan range or FOV");
                return;
            }
            bedPlan = findBedPlanOnSupport(client, target, targetSupportPlan.pos(), basePlan.pos());
            if (bedPlan == null) {
                if (retryTargetSupport(client)) {
                    return;
                }
                fail(client, "no safe bed fits on the placed target support");
                return;
            }
            selectBedAndRotate(client);
            return;
        }
        if (phaseTicks > MAX_CONFIRM_TICKS) {
            if (retryTargetSupport(client)) {
                return;
            }
            fail(client, "target support was not confirmed");
        }
    }

    private static void selectBedAndRotate(Minecraft client) {
        if (selectSlot(client, bedSlot)) {
            waitForSwitch(client, Phase.TURNING_TO_BED);
        } else {
            beginBedRotation(client);
        }
    }

    private static void beginBedRotation(Minecraft client) {
        if (deferAfterUse(() -> beginBedRotation(client))) return;
        // The sent yaw still belongs to the previous shield/support action at
        // this point. Validate the required bed facing only after this new
        // rotation has actually sent a packet.
        if (!validBedPlan(client, false)) {
            if (retryBedPoint(client, true)) {
                return;
            }
            fail(client, "bed position is no longer placeable");
            return;
        }
        transition(Phase.TURNING_TO_BED);
        SilentPacketRotation.beginRotation(
                client,
                bedPlan.hit().getLocation(),
                effectiveSmoothTicks(),
                () -> transitionIf(Phase.TURNING_TO_BED, Phase.WAITING_FOR_BED_ROTATION));
    }

    private static void placeBed(Minecraft client) {
        if (!SilentPacketRotation.isRotationPacketSent()) {
            return;
        }
        if (!validBedPlan(client, true)) {
            // A quantized sent yaw can select the neighbouring horizontal bed
            // direction even though the chosen support and bed pair are still
            // valid. Keep that pair and only change its sampled click point;
            // replacing the whole placement here made the server-side head
            // jump between unrelated candidates.
            boolean geometryStillValid = validBedPlan(client, false);
            if (retryBedPoint(client, !geometryStillValid)) {
                return;
            }
            fail(client, "bed placement became invalid");
            return;
        }
        InteractionResult result = useOnSilently(client, InteractionHand.MAIN_HAND, bedPlan.hit());
        if (!result.consumesAction()) {
            if (retryBedPoint(client, false)) {
                return;
            }
            fail(client, "bed placement was rejected");
            return;
        }
        int clickDelayMs = isBlatant() ? CLICK_DELAY_MS.get() : 0;
        clickReadyAtNanos = System.nanoTime() + clickDelayMs * 1_000_000L;
        transition(Phase.WAITING_FOR_BED_CLICK);
        if (clickDelayMs == 0) {
            clickPlacedBed(client);
        }
    }

    private static void clickPlacedBed(Minecraft client) {
        if (System.nanoTime() < clickReadyAtNanos) {
            return;
        }
        BlockPos foot = bedPlan.foot();
        BlockPos head = bedPlan.head();
        if (!(client.level.getBlockState(foot).getBlock() instanceof BedBlock)
                || !(client.level.getBlockState(head).getBlock() instanceof BedBlock)) {
            if (phaseTicks > MAX_CONFIRM_TICKS) {
                fail(client, "bed was not confirmed before the click delay expired");
            }
            return;
        }
        BlockHitResult interactHit = findBedHitOnSentRay(client, foot, head);
        if (interactHit == null) {
            interactHit = findVisibleBedHit(client, foot, head);
            if (interactHit != null) {
                beginBedClickRotation(client, interactHit);
                return;
            }
        }
        if (interactHit == null) {
            fail(client, "the placement view cannot reach the placed bed");
            return;
        }
        bedPlan = bedPlan.withInteractHit(interactHit);
        if (isBlatant() && TARGET_RANGE_RECHECK.get() && !validExplosionTarget(client)) {
            fail(client, "target moved outside the bed explosion radius");
            return;
        }
        InteractionResult result = useOnSilently(client, InteractionHand.MAIN_HAND, interactHit);
        if (!result.consumesAction()) {
            fail(client, "bed interaction was rejected");
            return;
        }
        interactionCompleted = true;
        beginReturn(client);
    }

    private static void beginReturn(Minecraft client) {
        if (phase == Phase.TURNING_BACK || phase == Phase.WAITING_FOR_RETURN_ROTATION) {
            return;
        }
        if (deferAfterUse(() -> beginReturn(client))) return;
        transition(Phase.TURNING_BACK);
        SilentPacketRotation.beginReturnToCamera(
                client,
                effectiveSmoothTicks(),
                () -> transitionIf(Phase.TURNING_BACK, Phase.WAITING_FOR_RETURN_ROTATION));
    }

    private static void fail(Minecraft client, String reason) {
        if (SilentPacketRotation.shouldApplyRotation()) {
            ClientChat.send(client, "AutoBed failed: " + reason + ".");
            beginReturn(client);
        } else {
            cleanup(client, true, "AutoBed disabled: " + reason + ".");
        }
    }

    /**
     * Rejects only the failed Blatant candidate and immediately plans another.
     * A structural failure rejects the foot/head pair; a ray/use failure keeps
     * that placement available through a different sampled face point.
     */
    private static boolean retryBedPoint(Minecraft client, boolean rejectWholePlacement) {
        if (!isBlatant()
                || bedPlan == null
                || pointRetries >= MAX_POINT_RETRIES
                || client.level.getBlockState(bedPlan.foot()).getBlock() instanceof BedBlock
                || client.level.getBlockState(bedPlan.head()).getBlock() instanceof BedBlock) {
            return false;
        }
        if (rejectWholePlacement) {
            rejectedBedPlacements.add(new BedPlacementKey(bedPlan.foot(), bedPlan.head()));
        } else {
            rejectedBedPoints.add(bedPointKey(bedPlan));
        }
        pointRetries++;
        Player target = targetById(client);
        if (!validFovTarget(client, target) || basePlan == null) {
            return false;
        }
        if (!rejectWholePlacement) {
            BedPlan alternate = findAlternatePointForCurrentBed(client);
            if (alternate != null) {
                bedPlan = alternate;
                beginBedRotation(client);
                return true;
            }
            rejectedBedPlacements.add(new BedPlacementKey(bedPlan.foot(), bedPlan.head()));
        }
        bedPlan = findBedPlan(client, target, basePlan.pos());
        if (bedPlan != null) {
            selectBedAndRotate(client);
            return true;
        }
        targetSupportPlan = null;
        return prepareTargetSupport(client, target);
    }

    private static boolean retryTargetSupport(Minecraft client) {
        if (!isBlatant() || pointRetries >= MAX_POINT_RETRIES) {
            return false;
        }
        if (targetSupportPlan != null) {
            rejectedTargetSupports.add(targetSupportPlan.pos().immutable());
        }
        pointRetries++;
        targetSupportPlan = null;
        bedPlan = null;
        Player target = targetById(client);
        return validFovTarget(client, target)
                && basePlan != null
                && prepareTargetSupport(client, target);
    }

    /**
     * Keeps an already selected foot/head/facing stable while trying another
     * reachable support-face sample. The closest angle to the last sent
     * rotation wins, so a rejected click cannot turn into a full head swing.
     */
    private static BedPlan findAlternatePointForCurrentBed(Minecraft client) {
        if (bedPlan == null) {
            return null;
        }
        BedPlan current = bedPlan;
        Vec3 eye = client.player.getEyePosition();
        float sentYaw = SilentPacketRotation.getInteractionYaw(client);
        float sentPitch = SilentPacketRotation.getInteractionPitch(client);
        BedPlan best = null;
        double bestAngle = Double.MAX_VALUE;
        for (Direction face : SUPPORT_FACES) {
            BlockPos support = current.foot().relative(face.getOpposite());
            BlockState supportState = client.level.getBlockState(support);
            if (supportState.getCollisionShape(client.level, support).isEmpty()) {
                continue;
            }
            for (double first : BED_FACE_SAMPLES) {
                for (double second : BED_FACE_SAMPLES) {
                    Vec3 requested = pointOnFace(support, face, first, second);
                    BlockHitResult hit = visibleFaceHit(client, support, face, requested);
                    if (hit == null
                            || !withinReach(client, hit.getLocation())
                            || Direction.fromYRot(yawTo(eye, hit.getLocation())) != current.facing()
                            || rejectedBedPoints.contains(
                                    bedPointKey(current.foot(), current.head(), hit))) {
                        continue;
                    }
                    double angle =
                            MathUtils.angularDistance(
                                    sentYaw,
                                    sentPitch,
                                    MathUtils.rotationTo(eye, hit.getLocation()));
                    if (angle < bestAngle) {
                        bestAngle = angle;
                        best =
                                new BedPlan(
                                        current.foot(),
                                        current.head(),
                                        current.facing(),
                                        current.closestTargetPoint(),
                                        current.targetDamage(),
                                        current.selfDamage(),
                                        hit,
                                        current.interactHit());
                    }
                }
            }
        }
        return best;
    }

    private static void waitForSwitch(Minecraft client, Phase next) {
        phaseAfterSwitch = next;
        int delayMs = effectiveSwitchDelayMs();
        switchReadyAtNanos = System.nanoTime() + delayMs * 1_000_000L;
        transition(Phase.WAITING_FOR_SWITCH);
        if (delayMs == 0) {
            continueAfterSwitch(client);
        }
    }

    private static void continueAfterSwitch(Minecraft client) {
        Phase next = phaseAfterSwitch;
        phaseAfterSwitch = Phase.IDLE;
        switchReadyAtNanos = 0L;
        if (next == Phase.TURNING_TO_BASE) {
            beginBaseRotation(client);
        } else if (next == Phase.TURNING_TO_TARGET_SUPPORT) {
            beginTargetSupportRotation(client);
        } else if (next == Phase.TURNING_TO_BED) {
            beginBedRotation(client);
        } else {
            fail(client, "invalid switch state");
        }
    }

    private static BasePlan findBasePlan(
            Minecraft client, PlacementMaterial material, Player target) {
        if (material == null) {
            return null;
        }
        BlockPos feet =
                BlockPos.containing(
                        client.player.getX(), client.player.getY() + 0.05D, client.player.getZ());
        if (!isBlatant()) {
            Direction facing = Direction.fromYRot(client.player.getYRot());
            // Balance is deterministic but not single-cell: try the nearest
            // front position first and only fall back to the second block when
            // the first has no complete, reachable vanilla placement.
            for (int distance = 1; distance <= 2; distance++) {
                BlockPos front = feet.relative(facing, distance);
                if (!client.level.getBlockState(front).canBeReplaced()
                        || rejectedBasePositions.contains(front)
                        || occupiedByPlayer(client, front)) {
                    continue;
                }
                BlockPos support = front.below();
                BlockHitResult hit =
                        visibleFaceHit(
                                client,
                                support,
                                Direction.UP,
                                pointOnFace(support, Direction.UP, 0.0D, 0.0D));
                if (hit == null
                        || !withinReach(client, hit.getLocation())
                        || !canPlaceMaterial(client, material, hit)) {
                    continue;
                }
                return new BasePlan(front.immutable(), hit);
            }
            return null;
        }
        Vec3 towardTarget = target.position().subtract(client.player.position());
        Vec3 horizontalTarget = new Vec3(towardTarget.x, 0.0D, towardTarget.z);
        if (horizontalTarget.lengthSqr() < 1.0E-8D) {
            horizontalTarget =
                    new Vec3(client.player.getLookAngle().x, 0.0D, client.player.getLookAngle().z);
        }
        horizontalTarget = horizontalTarget.normalize();

        BasePlan best = null;
        int bestCoverage = -1;
        double bestTieScore = Double.MAX_VALUE;
        for (int yOffset = 0; yOffset <= 1; yOffset++) {
            for (int xOffset = -2; xOffset <= 2; xOffset++) {
                for (int zOffset = -2; zOffset <= 2; zOffset++) {
                    int horizontalDistanceSquared = xOffset * xOffset + zOffset * zOffset;
                    if (horizontalDistanceSquared == 0 || horizontalDistanceSquared > 5) {
                        continue;
                    }
                    Vec3 candidateDirection = new Vec3(xOffset, 0.0D, zOffset).normalize();
                    double forwardScore = candidateDirection.dot(horizontalTarget);
                    if (forwardScore <= 0.05D) {
                        continue;
                    }
                    BlockPos pos = feet.offset(xOffset, yOffset, zOffset);
                    if (!client.level.getBlockState(pos).canBeReplaced()
                            || rejectedBasePositions.contains(pos)
                            || occupiedByPlayer(client, pos)) {
                        continue;
                    }
                    BlockHitResult hit = findSupportHit(client, pos, material);
                    if (hit == null) {
                        continue;
                    }
                    CyclePreview preview = previewCycle(client, material, target, pos);
                    if (preview == null) {
                        // Do not place the first shield block unless the same
                        // projected world already contains a complete bed path.
                        continue;
                    }
                    int coverage = shieldRayCoverage(client, pos, target);
                    double tieScore =
                            horizontalDistanceSquared
                                    + yOffset * 0.4D
                                    - forwardScore * 0.25D
                                    + hit.getLocation()
                                                    .distanceToSqr(client.player.getEyePosition())
                                            * 0.001D
                                    - preview.bed().targetDamage() * 0.0001D
                                    + preview.bed().selfDamage() * 0.00001D;
                    if (coverage > bestCoverage
                            || coverage == bestCoverage && tieScore < bestTieScore) {
                        bestCoverage = coverage;
                        bestTieScore = tieScore;
                        best = new BasePlan(pos.immutable(), hit);
                    }
                }
            }
        }
        return bestCoverage > 0 ? best : null;
    }

    /** Balance reuses the nearest complete bed support one or two blocks ahead. */
    private static BedPlan findExistingBalanceBedPlan(Minecraft client) {
        BlockPos feet =
                BlockPos.containing(
                        client.player.getX(), client.player.getY() + 0.05D, client.player.getZ());
        Direction facing = Direction.fromYRot(client.player.getYRot());
        for (int distance = 1; distance <= 2; distance++) {
            BlockPos front = feet.relative(facing, distance);
            BlockState state = client.level.getBlockState(front);
            if (state.getCollisionShape(client.level, front).isEmpty()) {
                continue;
            }
            BedPlan plan = simpleBalanceBedPlan(client, front);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    /**
     * Plans the whole cycle against a virtual shield before changing the
     * world. Balance requires a direct bed; blatant may additionally include
     * one target-side support, but that support and its bed are both checked
     * here before the shield candidate is accepted.
     */
    private static CyclePreview previewCycle(
            Minecraft client, PlacementMaterial material, Player target, BlockPos projectedShield) {
        BedPlan direct =
                isBlatant()
                        ? findBedPlan(client, target, projectedShield, true)
                        : bedPlanOnSupport(
                                client, target, projectedShield, projectedShield, false, true);
        if (direct != null) {
            return new CyclePreview(null, direct);
        }
        if (!isBlatant()) {
            return null;
        }
        BasePlan support = findTargetSupportPlan(client, material, target, projectedShield, true);
        if (support == null) {
            return null;
        }
        BedPlan supported =
                bedPlanOnSupport(client, target, support.pos(), projectedShield, false, true);
        return supported == null ? null : new CyclePreview(support, supported);
    }

    private static BlockHitResult findSupportHit(
            Minecraft client, BlockPos placePos, PlacementMaterial material) {
        return findSupportHit(client, placePos, material, null);
    }

    private static BlockHitResult findSupportHit(
            Minecraft client,
            BlockPos placePos,
            PlacementMaterial material,
            BlockPos projectedShield) {
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : SUPPORT_FACES) {
            BlockPos supportPos = placePos.relative(face.getOpposite());
            BlockState support = client.level.getBlockState(supportPos);
            if (support.getCollisionShape(client.level, supportPos).isEmpty()) {
                continue;
            }
            for (double first : BED_FACE_SAMPLES) {
                for (double second : BED_FACE_SAMPLES) {
                    Vec3 requested = pointOnFace(supportPos, face, first, second);
                    BlockHitResult visible = visibleFaceHit(client, supportPos, face, requested);
                    if (visible == null
                            || !withinReach(client, visible.getLocation())
                            || !canPlaceMaterial(client, material, visible)
                            || projectedShieldBlocksRay(
                                    client.player.getEyePosition(),
                                    visible.getLocation(),
                                    projectedShield)) {
                        continue;
                    }
                    double distance =
                            visible.getLocation().distanceToSqr(client.player.getEyePosition());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = visible;
                    }
                }
            }
        }
        return best;
    }

    private static int shieldRayCoverage(Minecraft client, BlockPos shield, Player target) {
        AABB targetBox = target.getBoundingBox();
        BlockPos targetFeet =
                BlockPos.containing(target.getX(), targetBox.minY + 1.0E-4D, target.getZ());
        int covered = 0;
        for (int yOffset = -BED_SEARCH_VERTICAL; yOffset <= BED_SEARCH_VERTICAL; yOffset++) {
            for (int xOffset = -BED_FOOT_SEARCH_RADIUS;
                    xOffset <= BED_FOOT_SEARCH_RADIUS;
                    xOffset++) {
                for (int zOffset = -BED_FOOT_SEARCH_RADIUS;
                        zOffset <= BED_FOOT_SEARCH_RADIUS;
                        zOffset++) {
                    Vec3 explosion = Vec3.atCenterOf(targetFeet.offset(xOffset, yOffset, zOffset));
                    if (EntityDistance.squaredToBox(explosion, targetBox)
                                    > MAX_TARGET_EXPLOSION_DISTANCE * MAX_TARGET_EXPLOSION_DISTANCE
                            || EntityDistance.squaredToBox(
                                            explosion, client.player.getBoundingBox())
                                    < MIN_SELF_EXPLOSION_DISTANCE * MIN_SELF_EXPLOSION_DISTANCE) {
                        continue;
                    }
                    covered += shieldRayCoverageForExplosion(client, shield, explosion);
                }
            }
        }
        return covered;
    }

    private static BasePlan findTargetSupportPlan(
            Minecraft client, PlacementMaterial material, Player target, BlockPos shield) {
        return findTargetSupportPlan(client, material, target, shield, false);
    }

    private static BasePlan findTargetSupportPlan(
            Minecraft client,
            PlacementMaterial material,
            Player target,
            BlockPos shield,
            boolean projectedShield) {
        AABB targetBox = target.getBoundingBox();
        BlockPos targetFeet =
                BlockPos.containing(target.getX(), targetBox.minY + 1.0E-4D, target.getZ());
        BasePlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int xOffset = -BED_FOOT_SEARCH_RADIUS;
                    xOffset <= BED_FOOT_SEARCH_RADIUS;
                    xOffset++) {
                for (int zOffset = -BED_FOOT_SEARCH_RADIUS;
                        zOffset <= BED_FOOT_SEARCH_RADIUS;
                        zOffset++) {
                    BlockPos support = targetFeet.offset(xOffset, yOffset, zOffset);
                    if (!client.level.getBlockState(support).canBeReplaced()
                            || !projectedShield && rejectedTargetSupports.contains(support)
                            || occupiedByPlayer(client, support)) {
                        continue;
                    }
                    BlockHitResult hit =
                            findSupportHit(
                                    client, support, material, projectedShield ? shield : null);
                    if (hit == null) {
                        continue;
                    }
                    BedPlan preview =
                            bedPlanOnSupport(
                                    client, target, support, shield, false, projectedShield);
                    if (preview == null) {
                        continue;
                    }
                    double score =
                            -preview.targetDamage() * 1_000_000.0D
                                    + preview.selfDamage() * 1_000.0D
                                    + hit.getLocation()
                                                    .distanceToSqr(client.player.getEyePosition())
                                            * 0.01D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BasePlan(support.immutable(), hit);
                    }
                }
            }
        }
        return best;
    }

    private static BedPlan findBedPlanOnSupport(
            Minecraft client, Player target, BlockPos support, BlockPos shield) {
        return bedPlanOnSupport(client, target, support, shield, true, false);
    }

    /**
     * Balance intentionally performs no combat scoring. It only finds a
     * physically usable point on the top of the fixed front block and lets the
     * sent yaw determine the vanilla bed direction.
     */
    private static BedPlan simpleBalanceBedPlan(Minecraft client, BlockPos support) {
        Vec3 eye = client.player.getEyePosition();
        BlockPos foot = support.above();
        if (!client.level.getBlockState(foot).canBeReplaced() || occupiedByPlayer(client, foot)) {
            return null;
        }
        BedPlan best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double xSample : BED_FACE_SAMPLES) {
            for (double zSample : BED_FACE_SAMPLES) {
                Vec3 requested = pointOnFace(support, Direction.UP, xSample, zSample);
                BlockHitResult hit = visibleFaceHit(client, support, Direction.UP, requested);
                if (hit == null || !withinReach(client, hit.getLocation())) {
                    continue;
                }
                Direction facing = Direction.fromYRot(yawTo(eye, hit.getLocation()));
                BlockPos head = foot.relative(facing);
                if (!client.level.getBlockState(head).canBeReplaced()
                        || occupiedByPlayer(client, head)
                        || !hasFutureBedInteractionPath(client, foot, head, null)) {
                    continue;
                }
                double distance = eye.distanceToSqr(hit.getLocation());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best =
                            new BedPlan(
                                    foot.immutable(),
                                    head.immutable(),
                                    facing,
                                    Vec3.atCenterOf(head),
                                    0.0F,
                                    0.0F,
                                    hit,
                                    null);
                }
            }
        }
        return best;
    }

    private static BedPlan bedPlanOnSupport(
            Minecraft client,
            Player target,
            BlockPos support,
            BlockPos shield,
            boolean requireVisibleSupport,
            boolean projectedShield) {
        Vec3 eye = client.player.getEyePosition();
        AABB targetBox = target.getBoundingBox();
        BlockPos foot = support.above();
        if (!client.level.getBlockState(foot).canBeReplaced() || occupiedByPlayer(client, foot)) {
            return null;
        }
        BedPlan best = null;
        double bestScore = Double.MAX_VALUE;
        Map<BlockPos, DamageEstimate> damageCache = new HashMap<>();
        for (double xSample : BED_FACE_SAMPLES) {
            for (double zSample : BED_FACE_SAMPLES) {
                Vec3 requested = pointOnFace(support, Direction.UP, xSample, zSample);
                BlockHitResult hit =
                        requireVisibleSupport
                                ? visibleFaceHit(client, support, Direction.UP, requested)
                                : futureFaceHit(
                                        client,
                                        support,
                                        Direction.UP,
                                        requested,
                                        projectedShield ? shield : null);
                if (hit == null || !withinReach(client, hit.getLocation())) {
                    continue;
                }
                if (projectedShieldBlocksRay(
                                eye, hit.getLocation(), projectedShield ? shield : null)
                        && !support.equals(shield)) {
                    continue;
                }
                Direction facing = Direction.fromYRot(yawTo(eye, hit.getLocation()));
                BlockPos head = foot.relative(facing);
                if (!projectedShield
                        && rejectedBedPlacements.contains(new BedPlacementKey(foot, head))) {
                    continue;
                }
                BedPointKey pointKey = bedPointKey(foot, head, hit);
                if (!projectedShield && rejectedBedPoints.contains(pointKey)) {
                    continue;
                }
                if (!client.level.getBlockState(head).canBeReplaced()
                        || occupiedByPlayer(client, head)
                        || !hasFutureBedInteractionPath(
                                client, foot, head, projectedShield ? shield : null)) {
                    continue;
                }
                Vec3 explosion = Vec3.atCenterOf(head);
                Vec3 targetPoint = EntityDistance.closestPoint(explosion, targetBox);
                double targetDistanceSquared = explosion.distanceToSqr(targetPoint);
                if (targetDistanceSquared
                                > MAX_TARGET_EXPLOSION_DISTANCE * MAX_TARGET_EXPLOSION_DISTANCE
                        || !safeBehindShield(client, shield, explosion)) {
                    continue;
                }
                DamageEstimate damage =
                        damageCache.computeIfAbsent(
                                head.immutable(), ignored -> bedDamage(client, target, explosion));
                if (!(projectedShield
                        ? acceptableProjectedDamage(client, damage)
                        : acceptableDamage(client, damage))) {
                    continue;
                }
                BlockPos interactPos = nearestBedHalf(client, foot, head);
                Vec3 interactPoint =
                        new Vec3(
                                interactPos.getX() + 0.5D,
                                interactPos.getY() + 0.32D,
                                interactPos.getZ() + 0.5D);
                if (!withinReach(client, interactPoint)) {
                    continue;
                }
                int shieldCoverage = shieldRayCoverageForExplosion(client, shield, explosion);
                double score =
                        -damage.targetDamage() * 1_000_000.0D
                                + damage.selfDamage() * 1_000.0D
                                - shieldCoverage * 0.01D
                                + hit.getLocation().distanceToSqr(eye) * 0.01D;
                if (score < bestScore) {
                    bestScore = score;
                    best =
                            new BedPlan(
                                    foot.immutable(),
                                    head.immutable(),
                                    facing,
                                    targetPoint,
                                    damage.targetDamage(),
                                    damage.selfDamage(),
                                    hit,
                                    null);
                }
            }
        }
        return best;
    }

    /**
     * Searches around the enemy, not above the shield block. The shield remains
     * between the local player's torso and the selected bed head while the bed
     * head itself is scored against the closest point of the enemy hitbox.
     */
    private static BedPlan findBedPlan(Minecraft client, Player target, BlockPos shield) {
        return findBedPlan(client, target, shield, false);
    }

    private static BedPlan findBedPlan(
            Minecraft client, Player target, BlockPos shield, boolean projectedShield) {
        Vec3 eye = client.player.getEyePosition();
        AABB targetBox = target.getBoundingBox();
        BlockPos targetFeet =
                BlockPos.containing(target.getX(), targetBox.minY + 1.0E-4D, target.getZ());
        BedPlan best = null;
        double bestScore = Double.MAX_VALUE;
        Map<BlockPos, DamageEstimate> damageCache = new HashMap<>();
        for (int yOffset = -BED_SEARCH_VERTICAL; yOffset <= BED_SEARCH_VERTICAL; yOffset++) {
            for (int xOffset = -BED_FOOT_SEARCH_RADIUS;
                    xOffset <= BED_FOOT_SEARCH_RADIUS;
                    xOffset++) {
                for (int zOffset = -BED_FOOT_SEARCH_RADIUS;
                        zOffset <= BED_FOOT_SEARCH_RADIUS;
                        zOffset++) {
                    BlockPos foot = targetFeet.offset(xOffset, yOffset, zOffset);
                    if (!client.level.getBlockState(foot).canBeReplaced()
                            || occupiedByPlayer(client, foot)) {
                        continue;
                    }
                    for (Direction face : SUPPORT_FACES) {
                        BlockPos support = foot.relative(face.getOpposite());
                        BlockState supportState = client.level.getBlockState(support);
                        if (supportState.getCollisionShape(client.level, support).isEmpty()) {
                            continue;
                        }
                        for (double first : BED_FACE_SAMPLES) {
                            for (double second : BED_FACE_SAMPLES) {
                                Vec3 requestedPoint = pointOnFace(support, face, first, second);
                                BlockHitResult hit =
                                        visibleFaceHit(client, support, face, requestedPoint);
                                if (hit == null || !withinReach(client, hit.getLocation())) {
                                    continue;
                                }
                                if (projectedShieldBlocksRay(
                                        eye, hit.getLocation(), projectedShield ? shield : null)) {
                                    continue;
                                }
                                Direction facing =
                                        Direction.fromYRot(yawTo(eye, hit.getLocation()));
                                BlockPos head = foot.relative(facing);
                                if (!projectedShield
                                        && rejectedBedPlacements.contains(
                                                new BedPlacementKey(foot, head))) {
                                    continue;
                                }
                                BedPointKey pointKey = bedPointKey(foot, head, hit);
                                if (!projectedShield && rejectedBedPoints.contains(pointKey)) {
                                    continue;
                                }
                                if (!client.level.getBlockState(head).canBeReplaced()
                                        || occupiedByPlayer(client, head)
                                        || !hasFutureBedInteractionPath(
                                                client,
                                                foot,
                                                head,
                                                projectedShield ? shield : null)) {
                                    continue;
                                }
                                Vec3 explosionCenter = Vec3.atCenterOf(head);
                                Vec3 closestTargetPoint =
                                        EntityDistance.closestPoint(explosionCenter, targetBox);
                                double targetDistanceSquared =
                                        explosionCenter.distanceToSqr(closestTargetPoint);
                                if (targetDistanceSquared
                                                > MAX_TARGET_EXPLOSION_DISTANCE
                                                        * MAX_TARGET_EXPLOSION_DISTANCE
                                        || !safeBehindShield(client, shield, explosionCenter)) {
                                    continue;
                                }
                                DamageEstimate damage =
                                        damageCache.computeIfAbsent(
                                                head.immutable(),
                                                ignored ->
                                                        bedDamage(client, target, explosionCenter));
                                if (!(projectedShield
                                        ? acceptableProjectedDamage(client, damage)
                                        : acceptableDamage(client, damage))) {
                                    continue;
                                }
                                BlockPos interactPos = nearestBedHalf(client, foot, head);
                                Vec3 interactPoint =
                                        new Vec3(
                                                interactPos.getX() + 0.5D,
                                                interactPos.getY() + 0.32D,
                                                interactPos.getZ() + 0.5D);
                                if (!withinReach(client, interactPoint)) {
                                    continue;
                                }

                                int shieldCoverage =
                                        shieldRayCoverageForExplosion(
                                                client, shield, explosionCenter);
                                double score =
                                        -damage.targetDamage() * 1_000_000.0D
                                                + damage.selfDamage() * 1_000.0D
                                                - shieldCoverage * 0.01D
                                                + hit.getLocation().distanceToSqr(eye) * 0.01D;
                                if (score < bestScore) {
                                    bestScore = score;
                                    best =
                                            new BedPlan(
                                                    foot.immutable(),
                                                    head.immutable(),
                                                    facing,
                                                    closestTargetPoint,
                                                    damage.targetDamage(),
                                                    damage.selfDamage(),
                                                    hit,
                                                    null);
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Vec3 pointOnFace(BlockPos support, Direction face, double first, double second) {
        return BlockPlacementUtils.fullBlockFaceOffset(support, face, first, second);
    }

    /** Returns the exact visible point so a shield or wall cannot hide the support face. */
    private static BlockHitResult visibleFaceHit(
            Minecraft client, BlockPos support, Direction face, Vec3 requestedPoint) {
        return BlockPlacementUtils.visibleFaceHit(
                client, client.player.getEyePosition(), support, face, requestedPoint, RAY_EPSILON);
    }

    /** Visible top/side of a block that the plan will place later. */
    private static BlockHitResult futureFaceHit(
            Minecraft client,
            BlockPos futureSupport,
            Direction face,
            Vec3 requested,
            BlockPos projectedShield) {
        if (projectedShieldBlocksRay(client.player.getEyePosition(), requested, projectedShield)
                && !futureSupport.equals(projectedShield)) {
            return null;
        }
        Vec3 justBeforeFace =
                requested.add(
                        face.getStepX() * RAY_EPSILON,
                        face.getStepY() * RAY_EPSILON,
                        face.getStepZ() * RAY_EPSILON);
        BlockHitResult obstruction =
                client.level.clip(
                        new ClipContext(
                                client.player.getEyePosition(),
                                justBeforeFace,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
        if (obstruction.getType() == HitResult.Type.BLOCK) {
            return null;
        }
        return new BlockHitResult(requested, face, futureSupport, false);
    }

    private static boolean projectedShieldBlocksRay(
            Vec3 eye, Vec3 point, BlockPos projectedShield) {
        if (projectedShield == null) {
            return false;
        }
        return new AABB(projectedShield)
                .inflate(RAY_EPSILON)
                .clip(eye, point)
                .filter(
                        intersection ->
                                eye.distanceToSqr(intersection)
                                        < eye.distanceToSqr(point) - 1.0E-6D)
                .isPresent();
    }

    private static boolean safeBehindShield(
            Minecraft client, BlockPos shield, Vec3 explosionCenter) {
        double selfDistanceSquared =
                EntityDistance.squaredToBox(explosionCenter, client.player.getBoundingBox());
        if (selfDistanceSquared < MIN_SELF_EXPLOSION_DISTANCE * MIN_SELF_EXPLOSION_DISTANCE) {
            return false;
        }
        return shieldRayCoverageForExplosion(client, shield, explosionCenter) >= 3;
    }

    private static int shieldRayCoverageForExplosion(
            Minecraft client, BlockPos shield, Vec3 explosionCenter) {
        AABB playerBox = client.player.getBoundingBox();
        AABB shieldBox = new AABB(shield).inflate(0.02D);
        int covered = 0;
        for (double ySample : new double[] {0.18D, 0.48D, 0.78D}) {
            for (double xSample : new double[] {0.2D, 0.5D, 0.8D}) {
                for (double zSample : new double[] {0.2D, 0.5D, 0.8D}) {
                    Vec3 bodyPoint =
                            new Vec3(
                                    Mth.lerp(xSample, playerBox.minX, playerBox.maxX),
                                    Mth.lerp(ySample, playerBox.minY, playerBox.maxY),
                                    Mth.lerp(zSample, playerBox.minZ, playerBox.maxZ));
                    if (shieldBox.clip(bodyPoint, explosionCenter).isPresent()) {
                        covered++;
                    }
                }
            }
        }
        return covered;
    }

    private static DamageEstimate bedDamage(Minecraft client, Player target, Vec3 explosionCenter) {
        return new DamageEstimate(
                explosionDamage(client, target, explosionCenter),
                explosionDamage(client, client.player, explosionCenter));
    }

    /** Mirrors vanilla bed power (5, effective entity radius 10) and reductions. */
    private static float explosionDamage(Minecraft client, Player player, Vec3 explosionCenter) {
        if (player == null || player.isDeadOrDying() || player.getAbilities().invulnerable) {
            return 0.0F;
        }
        double normalizedDistance = Math.sqrt(player.distanceToSqr(explosionCenter)) / 10.0D;
        if (normalizedDistance > 1.0D) {
            return 0.0F;
        }

        float exposure = ServerExplosion.getSeenPercent(explosionCenter, player);
        double impact = (1.0D - normalizedDistance) * exposure;
        float damage = (float) ((impact * impact + impact) / 2.0D * 7.0D * 10.0D + 1.0D);
        DamageSource source = player.damageSources().badRespawnPointExplosion(explosionCenter);
        if (source.scalesWithDifficulty()) {
            damage =
                    switch (client.level.getDifficulty()) {
                        case EASY -> Math.min(damage / 2.0F + 1.0F, damage);
                        case HARD -> damage * 1.5F;
                        default -> damage;
                    };
        }

        damage =
                CombatRules.getDamageAfterAbsorb(
                        player,
                        damage,
                        source,
                        player.getArmorValue(),
                        (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            damage *= Math.max(0.0F, 1.0F - (resistance.getAmplifier() + 1) * 0.2F);
        }

        try {
            var enchantments = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            int protection =
                    EnchantmentHelper.getEnchantmentLevel(
                            enchantments.getOrThrow(Enchantments.PROTECTION), player);
            int blastProtection =
                    EnchantmentHelper.getEnchantmentLevel(
                            enchantments.getOrThrow(Enchantments.BLAST_PROTECTION), player);
            damage =
                    CombatRules.getDamageAfterMagicAbsorb(
                            damage, protection + blastProtection * 2.0F);
        } catch (RuntimeException ignored) {
            // Hot-loaded registry wrappers can be unavailable briefly. Armor,
            // toughness and resistance are still included in that frame.
        }
        return Math.max(0.0F, damage);
    }

    private static boolean acceptableDamage(Minecraft client, DamageEstimate damage) {
        if (damage.targetDamage() + 1.0E-4F < MIN_DAMAGE.get()
                || damage.selfDamage() - 1.0E-4F > MAX_SELF_DAMAGE.get()) {
            return false;
        }
        float totalHealth = client.player.getHealth() + client.player.getAbsorptionAmount();
        return !ANTI_SUICIDE.get() || damage.selfDamage() < totalHealth;
    }

    /** The virtual shield is absent from ServerExplosion exposure until placed. */
    private static boolean acceptableProjectedDamage(Minecraft client, DamageEstimate damage) {
        if (damage.targetDamage() + 1.0E-4F < MIN_DAMAGE.get()) {
            return false;
        }
        float totalHealth = client.player.getHealth() + client.player.getAbsorptionAmount();
        return !ANTI_SUICIDE.get() || damage.selfDamage() < totalHealth;
    }

    private static Player findTarget(Minecraft client) {
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player target : client.level.players()) {
            if (!validFovTarget(client, target)) {
                continue;
            }
            double distance = EntityDistance.squaredToEntity(client, target);
            if (distance < bestDistance) {
                best = target;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean validFovTarget(Minecraft client, Player target) {
        if (!Targeting.isValidTargetPlayer(client, target)) {
            return false;
        }
        if (EntityDistance.squaredToEntity(client, target) > RANGE.get() * RANGE.get()) {
            return false;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 point = EntityDistance.closestPoint(eye, target.getBoundingBox());
        return MathUtils.withinFov(
                MathUtils.viewAngle(eye, client.player.getLookAngle(), point), FOV.get());
    }

    private static PlacementMaterial findMaterial(Minecraft client) {
        ItemStack offhand = client.player.getOffhandItem();
        if (validMaterial(client, offhand)) {
            return new PlacementMaterial(
                    InteractionHand.OFF_HAND, -1, (BlockItem) offhand.getItem());
        }
        Inventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack selectedStack = inventory.getItem(selected);
        if (validMaterial(client, selectedStack)) {
            return new PlacementMaterial(
                    InteractionHand.MAIN_HAND, selected, (BlockItem) selectedStack.getItem());
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (validMaterial(client, stack)) {
                return new PlacementMaterial(
                        InteractionHand.MAIN_HAND, slot, (BlockItem) stack.getItem());
            }
        }
        return null;
    }

    private static boolean validMaterial(Minecraft client, ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || MinecraftClientAccess.isBedItem(blockItem)
                || blockItem.getBlock() instanceof FallingBlock) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return BlockPlacementUtils.hasSolidPlacementShape(client.level, state);
    }

    private static int findBedSlot(Minecraft client) {
        return HotbarQueries.firstMatch(
                client.player.getInventory(),
                stack -> MinecraftClientAccess.isBedItem(stack.getItem()));
    }

    private static boolean canPlaceMaterial(
            Minecraft client, PlacementMaterial material, BlockHitResult hit) {
        ItemStack stack =
                material.hand() == InteractionHand.OFF_HAND
                        ? client.player.getOffhandItem()
                        : client.player.getInventory().getItem(material.hotbarSlot());
        if (!validMaterial(client, stack) || stack.getItem() != material.item()) {
            return false;
        }
        BlockPlaceContext context =
                new BlockPlaceContext(new UseOnContext(client.player, material.hand(), hit));
        return material.item().getBlock().getStateForPlacement(context) != null;
    }

    private static boolean validBasePlan(Minecraft client) {
        if (basePlan == null
                || materialItem == null
                || !client.level.getBlockState(basePlan.pos()).canBeReplaced()
                || occupiedByPlayer(client, basePlan.pos())) {
            return false;
        }
        PlacementMaterial material =
                new PlacementMaterial(materialHand, materialSlot, materialItem);
        return canPlaceMaterial(client, material, basePlan.hit())
                && withinReach(client, basePlan.hit().getLocation());
    }

    private static boolean validTargetSupportPlan(Minecraft client) {
        if (targetSupportPlan == null
                || materialItem == null
                || basePlan == null
                || shieldItem == null
                || !client.level.getBlockState(basePlan.pos()).is(shieldItem.getBlock())
                || !client.level.getBlockState(targetSupportPlan.pos()).canBeReplaced()
                || occupiedByPlayer(client, targetSupportPlan.pos())) {
            return false;
        }
        PlacementMaterial material =
                new PlacementMaterial(materialHand, materialSlot, materialItem);
        return canPlaceMaterial(client, material, targetSupportPlan.hit())
                && withinReach(client, targetSupportPlan.hit().getLocation());
    }

    private static boolean validBedPlan(Minecraft client, boolean requireSentFacing) {
        if (bedPlan == null
                || bedSlot < 0
                || client.player.getInventory().getSelectedSlot() != bedSlot
                || !(MinecraftClientAccess.isBedItem(
                        client.player.getInventory().getItem(bedSlot).getItem()))
                || !client.level.getBlockState(bedPlan.foot()).canBeReplaced()
                || !client.level.getBlockState(bedPlan.head()).canBeReplaced()
                || occupiedByPlayer(client, bedPlan.foot())
                || occupiedByPlayer(client, bedPlan.head())
                || !validBedBase(client)
                || !bedPlan.hit()
                        .getBlockPos()
                        .relative(bedPlan.hit().getDirection())
                        .equals(bedPlan.foot())
                || visibleFaceHit(
                                client,
                                bedPlan.hit().getBlockPos(),
                                bedPlan.hit().getDirection(),
                                bedPlan.hit().getLocation())
                        == null) {
            return false;
        }
        Direction sentFacing = Direction.fromYRot(SilentPacketRotation.getInteractionYaw(client));
        return (!requireSentFacing || sentFacing == bedPlan.facing())
                && withinReach(client, bedPlan.hit().getLocation())
                && (!isBlatant() || !TARGET_RANGE_RECHECK.get() || validExplosionTarget(client));
    }

    private static boolean validBedBase(Minecraft client) {
        if (basePlan == null) {
            return false;
        }
        BlockState state = client.level.getBlockState(basePlan.pos());
        if (reusedBalanceBase) {
            return !state.getCollisionShape(client.level, basePlan.pos()).isEmpty();
        }
        return shieldItem != null && state.is(shieldItem.getBlock());
    }

    private static boolean validExplosionTarget(Minecraft client) {
        Player target = targetById(client);
        if (bedPlan == null || !Targeting.isValidTargetPlayer(client, target)) {
            return false;
        }
        // Vanilla removes the clicked bed before creating its power-5
        // explosion. Re-running exposure damage while the bed is still in the
        // client world makes the bed occlude its own explosion and falsely
        // cancels the click. This option is only a final target-movement gate,
        // so use the actual clicked half and vanilla's 2 * power entity radius.
        BlockPos clickedHalf =
                bedPlan.interactHit() == null
                        ? bedPlan.head()
                        : bedPlan.interactHit().getBlockPos();
        Vec3 explosionCenter = Vec3.atCenterOf(clickedHalf);
        double vanillaEntityRadius = 10.0D;
        return target.distanceToSqr(explosionCenter) <= vanillaEntityRadius * vanillaEntityRadius;
    }

    private static BlockHitResult findVisibleBedHit(
            Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.player.getEyePosition();
        BlockHitResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : new BlockPos[] {foot, head}) {
            for (double xOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                for (double zOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                    Vec3 insideTop =
                            new Vec3(
                                    pos.getX() + 0.5D + xOffset,
                                    pos.getY() + 0.55D,
                                    pos.getZ() + 0.5D + zOffset);
                    BlockHitResult hit =
                            client.level.clip(
                                    new ClipContext(
                                            eye,
                                            insideTop,
                                            ClipContext.Block.OUTLINE,
                                            ClipContext.Fluid.NONE,
                                            client.player));
                    if (hit.getType() != HitResult.Type.BLOCK
                            || !hit.getBlockPos().equals(pos)
                            || !withinReach(client, hit.getLocation())) {
                        continue;
                    }
                    double distance = eye.distanceToSqr(hit.getLocation());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = hit;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Checks the final bed interaction ray before committing the shield block.
     * The bed does not exist yet, so ray trace to just before sampled points and
     * explicitly include the projected shield as an obstruction.
     */
    private static boolean hasFutureBedInteractionPath(
            Minecraft client, BlockPos foot, BlockPos head, BlockPos projectedShield) {
        Vec3 eye = client.player.getEyePosition();
        for (BlockPos pos : new BlockPos[] {foot, head}) {
            for (double xOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                for (double zOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                    Vec3 point =
                            new Vec3(
                                    pos.getX() + 0.5D + xOffset,
                                    pos.getY() + 0.55D,
                                    pos.getZ() + 0.5D + zOffset);
                    if (!withinReach(client, point)
                            || projectedShieldBlocksRay(eye, point, projectedShield)) {
                        continue;
                    }
                    Vec3 towardEye = eye.subtract(point);
                    if (towardEye.lengthSqr() < 1.0E-10D) {
                        return true;
                    }
                    Vec3 justBeforeBed = point.add(towardEye.normalize().scale(RAY_EPSILON));
                    BlockHitResult obstruction =
                            client.level.clip(
                                    new ClipContext(
                                            eye,
                                            justBeforeBed,
                                            ClipContext.Block.OUTLINE,
                                            ClipContext.Fluid.NONE,
                                            client.player));
                    if (obstruction.getType() == HitResult.Type.MISS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static BlockHitResult findBedHitOnSentRay(
            Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 end =
                eye.add(
                        SilentPacketRotation.getInteractionLookVector(client)
                                .scale(client.player.blockInteractionRange()));
        BlockHitResult hit =
                client.level.clip(
                        new ClipContext(
                                eye,
                                end,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                client.player));
        if (hit.getType() != HitResult.Type.BLOCK
                || (!hit.getBlockPos().equals(foot) && !hit.getBlockPos().equals(head))
                || !(client.level.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock)
                || !withinReach(client, hit.getLocation())) {
            return null;
        }
        return hit;
    }

    private static InteractionResult useOnSilently(
            Minecraft client, InteractionHand hand, BlockHitResult hit) {
        float cameraYaw = client.player.getYRot();
        float cameraPitch = client.player.getXRot();
        client.player.setYRot(SilentPacketRotation.getInteractionYaw(client));
        client.player.setXRot(SilentPacketRotation.getInteractionPitch(client));
        invokingBedUse = true;
        try {
            InteractionResult result = client.gameMode.useItemOn(client.player, hand, hit);
            if (result.consumesAction()) {
                MinecraftClientAccess.animatePlacement(client.player, hand, true);
            }
            return result;
        } finally {
            invokingBedUse = false;
            client.player.setYRot(cameraYaw);
            client.player.setXRot(cameraPitch);
        }
    }

    private static void beginBedClickRotation(Minecraft client, BlockHitResult hit) {
        if (deferAfterUse(() -> beginBedClickRotation(client, hit))) return;
        transition(Phase.TURNING_TO_BED_CLICK);
        SilentPacketRotation.beginRotation(
                client,
                hit.getLocation(),
                effectiveSmoothTicks(),
                () -> transitionIf(Phase.TURNING_TO_BED_CLICK, Phase.WAITING_FOR_BED_CLICK));
    }

    private static boolean deferAfterUse(Runnable action) {
        if (!usedBeforeMovement) return false;
        afterUseMovement = action;
        return true;
    }

    private static void finishUseMovement() {
        // Keep USE -> normal sendPosition -> next turn in the same tick.
        // This is a phase boundary, not a server acknowledgment or angle lock.
        usedBeforeMovement = false;
        Runnable action = afterUseMovement;
        afterUseMovement = null;
        if (action != null) action.run();
    }

    private static boolean withinReach(Minecraft client, Vec3 point) {
        double reach = client.player.blockInteractionRange();
        return BlockPlacementUtils.withinReach(client.player.getEyePosition(), point, reach);
    }

    /**
     * Mirrors the vanilla obstruction rule used by solid block and bed
     * placement. A replaceable block is not actually placeable while any
     * player's collision box occupies the destination cell.
     */
    private static boolean occupiedByPlayer(Minecraft client, BlockPos pos) {
        AABB blockBox = new AABB(pos);
        for (Player player : client.level.players()) {
            if (player.getBoundingBox().intersects(blockBox)) {
                return true;
            }
        }
        return false;
    }

    private static float yawTo(Vec3 eye, Vec3 point) {
        return MathUtils.yawTo(eye, point);
    }

    private static BlockPos nearestBedHalf(Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.player.getEyePosition();
        return eye.distanceToSqr(Vec3.atCenterOf(foot)) <= eye.distanceToSqr(Vec3.atCenterOf(head))
                ? foot
                : head;
    }

    private static BedPointKey bedPointKey(BedPlan plan) {
        return bedPointKey(plan.foot(), plan.head(), plan.hit());
    }

    private static BedPointKey bedPointKey(BlockPos foot, BlockPos head, BlockHitResult hit) {
        Vec3 point = hit.getLocation();
        return new BedPointKey(
                foot.immutable(),
                head.immutable(),
                hit.getBlockPos().immutable(),
                hit.getDirection(),
                (int) Math.round(point.x * 1_000.0D),
                (int) Math.round(point.y * 1_000.0D),
                (int) Math.round(point.z * 1_000.0D));
    }

    private static Player targetById(Minecraft client) {
        return targetId < 0
                ? null
                : client.level.getEntity(targetId) instanceof Player player ? player : null;
    }

    private static boolean selectSlot(Minecraft client, int slot) {
        if (slot < 0 || slot > 8 || client.player.getInventory().getSelectedSlot() == slot) {
            return false;
        }
        client.player.getInventory().setSelectedSlot(slot);
        return true;
    }

    private static boolean otherRotationOwnerBusy() {
        return AutoLava.isBusy() || AutoWeb.isBusy() || AntiLava.isBusy() || AntiWeb.isBusy();
    }

    private static boolean ready(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        return ClientReady.aliveGameplay(client, currentPlayer);
    }

    private static boolean isTurningPhase(Phase current) {
        return current == Phase.TURNING_TO_BASE
                || current == Phase.TURNING_TO_TARGET_SUPPORT
                || current == Phase.TURNING_TO_BED
                || current == Phase.TURNING_TO_BED_CLICK
                || current == Phase.TURNING_BACK;
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
        invokingBedUse = usedBeforeMovement = false;
        afterUseMovement = null;
        boolean ownedRotation = isBusy();
        if (ownedRotation
                && client != null
                && currentPlayer != null
                && originalSlot >= 0
                && originalSlot <= 8) {
            currentPlayer.getInventory().setSelectedSlot(originalSlot);
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.AUTO_BED);
        if (ownedRotation) {
            SilentPacketRotation.reset();
        }
        phase = Phase.IDLE;
        phaseTicks = 0;
        targetId = -1;
        originalSlot = -1;
        materialSlot = -1;
        bedSlot = -1;
        materialHand = null;
        materialItem = null;
        shieldItem = null;
        reusedBalanceBase = false;
        basePlan = null;
        targetSupportPlan = null;
        bedPlan = null;
        interactionCompleted = false;
        switchReadyAtNanos = 0L;
        clickReadyAtNanos = 0L;
        phaseAfterSwitch = Phase.IDLE;
        rejectedBedPoints.clear();
        rejectedBedPlacements.clear();
        rejectedBasePositions.clear();
        rejectedTargetSupports.clear();
        pointRetries = 0;
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
        return isBusy() ? modeName() + "/" + phase.label : modeName();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (!value) {
            ENABLED.set(false);
            cleanup(client, false, null);
            ClientChat.send(client, "AutoBed disabled.");
            return 1;
        }
        cleanup(client, false, null);
        ENABLED.set(true);
        ClientChat.send(client, "AutoBed armed in " + modeName() + " mode.");
        return 1;
    }

    public static int setMode(Minecraft client, String value) {
        MODE.deserialize(value);
        ClientChat.send(client, "AutoBed mode set to " + modeName() + ".");
        return 1;
    }

    public static int setFov(Minecraft client, double value) {
        FOV.set(value);
        ClientChat.send(client, "AutoBed FOV set to " + format(FOV.get()) + " degrees.");
        return 1;
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        ClientChat.send(client, "AutoBed scan range set to " + format(RANGE.get()) + " blocks.");
        return 1;
    }

    public static int setMinDamage(Minecraft client, double value) {
        MIN_DAMAGE.set(value);
        ClientChat.send(
                client, "AutoBed minimum target damage set to " + format(MIN_DAMAGE.get()) + ".");
        return 1;
    }

    public static int setMaxSelfDamage(Minecraft client, double value) {
        MAX_SELF_DAMAGE.set(value);
        ClientChat.send(
                client,
                "AutoBed maximum self damage set to " + format(MAX_SELF_DAMAGE.get()) + ".");
        return 1;
    }

    public static int setAntiSuicide(Minecraft client, boolean value) {
        ANTI_SUICIDE.set(value);
        ClientChat.send(
                client,
                "AutoBed anti suicide " + (ANTI_SUICIDE.get() ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setTargetRangeRecheck(Minecraft client, boolean value) {
        TARGET_RANGE_RECHECK.set(value);
        ClientChat.send(
                client,
                "AutoBed target move check "
                        + (TARGET_RANGE_RECHECK.get() ? "enabled" : "disabled")
                        + ".");
        return 1;
    }

    public static int setSmoothTicks(Minecraft client, int value) {
        SMOOTH_TICKS.set(value);
        ClientChat.send(
                client, "AutoBed balance smooth turn set to " + SMOOTH_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setBlatantSmoothTicks(Minecraft client, int value) {
        BLATANT_SMOOTH_TICKS.set(value);
        ClientChat.send(
                client,
                "AutoBed blatant smooth turn set to " + BLATANT_SMOOTH_TICKS.get() + " ticks.");
        return 1;
    }

    public static int setSwitchDelayMs(Minecraft client, int value) {
        SWITCH_DELAY_MS.set(value);
        ClientChat.send(client, "AutoBed switch delay set to " + SWITCH_DELAY_MS.get() + " ms.");
        return 1;
    }

    public static int setClickDelayMs(Minecraft client, int value) {
        CLICK_DELAY_MS.set(value);
        ClientChat.send(client, "AutoBed bed click delay set to " + CLICK_DELAY_MS.get() + " ms.");
        return 1;
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }

    private static String modeName() {
        return MODE.serialized();
    }

    public static java.util.List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static boolean blatantMode() {
        return MODE.get() == Mode.BLATANT;
    }

    private static boolean isBlatant() {
        return MODE.get() == Mode.BLATANT;
    }

    private static int effectiveSmoothTicks() {
        return isBlatant() ? BLATANT_SMOOTH_TICKS.get() : SMOOTH_TICKS.get();
    }

    private static int effectiveSwitchDelayMs() {
        return isBlatant() ? 0 : SWITCH_DELAY_MS.get();
    }

    private record PlacementMaterial(InteractionHand hand, int hotbarSlot, BlockItem item) {}

    private record BasePlan(BlockPos pos, BlockHitResult hit) {}

    private record BedPlan(
            BlockPos foot,
            BlockPos head,
            Direction facing,
            Vec3 closestTargetPoint,
            float targetDamage,
            float selfDamage,
            BlockHitResult hit,
            BlockHitResult interactHit) {
        BedPlan withInteractHit(BlockHitResult value) {
            return new BedPlan(
                    foot, head, facing, closestTargetPoint, targetDamage, selfDamage, hit, value);
        }
    }

    private record DamageEstimate(float targetDamage, float selfDamage) {}

    private record CyclePreview(BasePlan targetSupport, BedPlan bed) {}

    private record BedPlacementKey(BlockPos foot, BlockPos head) {}

    private record BedPointKey(
            BlockPos foot, BlockPos head, BlockPos support, Direction face, int x, int y, int z) {}

    private enum Mode {
        BALANCE,
        BLATANT
    }

    private enum Phase {
        IDLE("Idle"),
        WAITING_FOR_SWITCH("Switch"),
        TURNING_TO_BASE("Base aim"),
        WAITING_FOR_BASE_ROTATION("Base"),
        WAITING_FOR_BASE_CONFIRM("Base sync"),
        TURNING_TO_TARGET_SUPPORT("Target block aim"),
        WAITING_FOR_TARGET_SUPPORT_ROTATION("Target block"),
        WAITING_FOR_TARGET_SUPPORT_CONFIRM("Target block sync"),
        TURNING_TO_BED("Bed aim"),
        TURNING_TO_BED_CLICK("Bed click aim"),
        WAITING_FOR_BED_ROTATION("Bed"),
        WAITING_FOR_BED_CLICK("Click wait"),
        TURNING_BACK("Return"),
        WAITING_FOR_RETURN_ROTATION("Return sync");

        private final String label;

        Phase(String label) {
            this.label = label;
        }
    }
}
