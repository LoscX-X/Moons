package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
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
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.world.LegacyRay;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.LegacyPlacement;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

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
    private static final PlacementRaycast RAYS = new PlacementRaycast("autobed");
    private static final int MAX_ACTION_WAIT_TICKS = 30;
    private static final int MAX_CONFIRM_TICKS = 10;
    private static final int MAX_POINT_RETRIES = 48;
    private static final int BED_SEARCH_RADIUS = 2;
    private static final int BED_FOOT_SEARCH_RADIUS = BED_SEARCH_RADIUS + 1;
    private static final int BED_SEARCH_VERTICAL = 1;
    private static final double MAX_TARGET_EXPLOSION_DISTANCE = 2.5D;
    private static final double MIN_SELF_EXPLOSION_DISTANCE = 2.0D;
    private static final double RAY_EPSILON = 1.0E-4D;
    private static final EnumFacing[] SUPPORT_FACES = {
        EnumFacing.UP,
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.WEST,
        EnumFacing.EAST,
        EnumFacing.DOWN
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
    private static ItemBlock materialItem;
    private static ItemBlock shieldItem;
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
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoBed.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.AUTO_BED, AutoBed::isBusy);
        EventBus.PLAYER_UPDATE.register("AutoBed.thePlayerUpdate", event -> tick(event.client()));
        EventBus.PACKET_SEND_POST.register(
                "AutoBed.useSent",
                event -> {
                    if (invokingBedUse && event.packet() instanceof C08PacketPlayerBlockPlacement) {
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
        if (!ClientReady.aliveGameplay(client)) {
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
        EntityPlayer target = findTarget(client);
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

        targetId = target == null ? -1 : target.getEntityId();
        originalSlot = client.thePlayer.inventory.currentItem;
        bedSlot = foundBedSlot;
        if (existingBalanceBed != null) {
            materialSlot = -1;
            materialItem = null;
            shieldItem = null;
            reusedBalanceBase = true;
            bedPlan = existingBalanceBed;
            basePlan =
                    new BasePlan(
                            new BlockPos(existingBalanceBed.foot().down()),
                            existingBalanceBed.hit());
            CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BED);
            selectBedAndRotate(client);
            return;
        }
        if (material == null) {
            cleanup(client, true, "AutoBed disabled: no solid block in hotbar.");
            return;
        }
        materialSlot = material.hotbarSlot();
        materialItem = material.item();
        shieldItem = material.item();
        reusedBalanceBase = false;
        basePlan = foundBase;
        CombatInputController.suppressAttack(client, CombatInputController.Owner.AUTO_BED);

        if (selectSlot(client, materialSlot)) {
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
                basePlan.hit().hitVec,
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
        boolean result = useOnSilently(client, basePlan.hit());
        if (!result) {
            if (retryBasePlan(client)) {
                return;
            }
            fail(client, "front block placement was rejected");
            return;
        }
        transition(Phase.WAITING_FOR_BASE_CONFIRM);
    }

    private static void confirmBase(Minecraft client) {
        IBlockState state = client.theWorld.getBlockState(basePlan.pos());
        if ((state.getBlock() == materialItem.getBlock())) {
            EntityPlayer target = targetById(client);
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
            if (LegacyWorld.replaceable(client.theWorld.getBlockState(basePlan.pos()))
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
        EntityPlayer target = targetById(client);
        if (!validFovTarget(client, target) || materialItem == null) {
            return false;
        }
        PlacementMaterial material = new PlacementMaterial(materialSlot, materialItem);
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
        EntityPlayer target = targetById(client);
        if (!validFovTarget(client, target) || basePlan == null || materialItem == null) {
            return false;
        }
        PlacementMaterial material = new PlacementMaterial(materialSlot, materialItem);
        return previewCycle(client, material, target, basePlan.pos()) != null;
    }

    private static boolean prepareTargetSupport(Minecraft client, EntityPlayer target) {
        PlacementMaterial material = findMaterial(client);
        if (material == null) {
            return false;
        }
        BasePlan plan = findTargetSupportPlan(client, material, target, basePlan.pos());
        if (plan == null) {
            return false;
        }
        materialSlot = material.hotbarSlot();
        materialItem = material.item();
        targetSupportPlan = plan;
        if (selectSlot(client, materialSlot)) {
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
                targetSupportPlan.hit().hitVec,
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
        boolean result = useOnSilently(client, targetSupportPlan.hit());
        if (!result) {
            if (retryTargetSupport(client)) {
                return;
            }
            fail(client, "target support placement was rejected");
            return;
        }
        transition(Phase.WAITING_FOR_TARGET_SUPPORT_CONFIRM);
    }

    private static void confirmTargetSupport(Minecraft client) {
        if ((client.theWorld.getBlockState(targetSupportPlan.pos()).getBlock()
                == materialItem.getBlock())) {
            EntityPlayer target = targetById(client);
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
                bedPlan.hit().hitVec,
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
        boolean result = useOnSilently(client, bedPlan.hit());
        if (!result) {
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
        if (!(client.theWorld.getBlockState(foot).getBlock() instanceof BlockBed)
                || !(client.theWorld.getBlockState(head).getBlock() instanceof BlockBed)) {
            if (phaseTicks > MAX_CONFIRM_TICKS) {
                fail(client, "bed was not confirmed before the click delay expired");
            }
            return;
        }
        MovingObjectPosition interactHit = findBedHitOnSentRay(client, foot, head);
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
        boolean result = useOnSilently(client, interactHit);
        if (!result) {
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
                || client.theWorld.getBlockState(bedPlan.foot()).getBlock() instanceof BlockBed
                || client.theWorld.getBlockState(bedPlan.head()).getBlock() instanceof BlockBed) {
            return false;
        }
        if (rejectWholePlacement) {
            rejectedBedPlacements.add(new BedPlacementKey(bedPlan.foot(), bedPlan.head()));
        } else {
            rejectedBedPoints.add(bedPointKey(bedPlan));
        }
        pointRetries++;
        EntityPlayer target = targetById(client);
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
            rejectedTargetSupports.add(new BlockPos(targetSupportPlan.pos()));
        }
        pointRetries++;
        targetSupportPlan = null;
        bedPlan = null;
        EntityPlayer target = targetById(client);
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
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        float sentYaw = SilentPacketRotation.getInteractionYaw(client);
        float sentPitch = SilentPacketRotation.getInteractionPitch(client);
        BedPlan best = null;
        double bestAngle = Double.MAX_VALUE;
        for (EnumFacing face : SUPPORT_FACES) {
            BlockPos support = current.foot().offset(face.getOpposite());
            IBlockState supportState = client.theWorld.getBlockState(support);
            if (LegacyWorld.collision(supportState, client.theWorld, support).isEmpty()) {
                continue;
            }
            for (double first : BED_FACE_SAMPLES) {
                for (double second : BED_FACE_SAMPLES) {
                    Vec3 requested = pointOnFace(support, face, first, second);
                    MovingObjectPosition hit = visibleFaceHit(client, support, face, requested);
                    if (hit == null
                            || !withinReach(client, hit.hitVec)
                            || EnumFacing.fromAngle(yawTo(eye, hit.hitVec)) != current.facing()
                            || rejectedBedPoints.contains(
                                    bedPointKey(current.foot(), current.head(), hit))) {
                        continue;
                    }
                    double angle =
                            MathUtils.angularDistance(
                                    sentYaw, sentPitch, MathUtils.rotationTo(eye, hit.hitVec));
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
            Minecraft client, PlacementMaterial material, EntityPlayer target) {
        if (material == null) {
            return null;
        }
        BlockPos feet =
                new BlockPos(
                        client.thePlayer.posX,
                        client.thePlayer.posY + 0.05D,
                        client.thePlayer.posZ);
        if (!isBlatant()) {
            EnumFacing facing = EnumFacing.fromAngle(client.thePlayer.rotationYaw);
            // Balance is deterministic but not single-cell: try the nearest
            // front position first and only fall back to the second block when
            // the first has no complete, reachable vanilla placement.
            for (int distance = 1; distance <= 2; distance++) {
                BlockPos front = feet.offset(facing, distance);
                if (!LegacyWorld.replaceable(client.theWorld.getBlockState(front))
                        || rejectedBasePositions.contains(front)
                        || occupiedByPlayer(client, front)) {
                    continue;
                }
                BlockPos support = front.down();
                MovingObjectPosition hit =
                        visibleFaceHit(
                                client,
                                support,
                                EnumFacing.UP,
                                pointOnFace(support, EnumFacing.UP, 0.0D, 0.0D));
                if (hit == null
                        || !withinReach(client, hit.hitVec)
                        || !canPlaceMaterial(client, material, hit)) {
                    continue;
                }
                return new BasePlan(new BlockPos(front), hit);
            }
            return null;
        }
        Vec3 towardTarget = VecMath.position(target).subtract(VecMath.position(client.thePlayer));
        Vec3 horizontalTarget = new Vec3(towardTarget.xCoord, 0.0D, towardTarget.zCoord);
        if (VecMath.lengthSqr(horizontalTarget) < 1.0E-8D) {
            horizontalTarget =
                    new Vec3(
                            client.thePlayer.getLookVec().xCoord,
                            0.0D,
                            client.thePlayer.getLookVec().zCoord);
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
                    double forwardScore = candidateDirection.dotProduct(horizontalTarget);
                    if (forwardScore <= 0.05D) {
                        continue;
                    }
                    BlockPos pos = feet.add(xOffset, yOffset, zOffset);
                    if (!LegacyWorld.replaceable(client.theWorld.getBlockState(pos))
                            || rejectedBasePositions.contains(pos)
                            || occupiedByPlayer(client, pos)) {
                        continue;
                    }
                    MovingObjectPosition hit = findSupportHit(client, pos, material);
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
                                    + hit.hitVec.squareDistanceTo(
                                                    client.thePlayer.getPositionEyes(1.0F))
                                            * 0.001D
                                    - preview.bed().targetDamage() * 0.0001D
                                    + preview.bed().selfDamage() * 0.00001D;
                    if (coverage > bestCoverage
                            || coverage == bestCoverage && tieScore < bestTieScore) {
                        bestCoverage = coverage;
                        bestTieScore = tieScore;
                        best = new BasePlan(new BlockPos(pos), hit);
                    }
                }
            }
        }
        return bestCoverage > 0 ? best : null;
    }

    /** Balance reuses the nearest complete bed support one or two blocks ahead. */
    private static BedPlan findExistingBalanceBedPlan(Minecraft client) {
        BlockPos feet =
                new BlockPos(
                        client.thePlayer.posX,
                        client.thePlayer.posY + 0.05D,
                        client.thePlayer.posZ);
        EnumFacing facing = EnumFacing.fromAngle(client.thePlayer.rotationYaw);
        for (int distance = 1; distance <= 2; distance++) {
            BlockPos front = feet.offset(facing, distance);
            IBlockState state = client.theWorld.getBlockState(front);
            if (LegacyWorld.collision(state, client.theWorld, front).isEmpty()) {
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
            Minecraft client,
            PlacementMaterial material,
            EntityPlayer target,
            BlockPos projectedShield) {
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

    private static MovingObjectPosition findSupportHit(
            Minecraft client, BlockPos placePos, PlacementMaterial material) {
        return findSupportHit(client, placePos, material, null);
    }

    private static MovingObjectPosition findSupportHit(
            Minecraft client,
            BlockPos placePos,
            PlacementMaterial material,
            BlockPos projectedShield) {
        MovingObjectPosition best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnumFacing face : SUPPORT_FACES) {
            BlockPos supportPos = placePos.offset(face.getOpposite());
            IBlockState support = client.theWorld.getBlockState(supportPos);
            if (LegacyWorld.collision(support, client.theWorld, supportPos).isEmpty()) {
                continue;
            }
            for (double first : BED_FACE_SAMPLES) {
                for (double second : BED_FACE_SAMPLES) {
                    Vec3 requested = pointOnFace(supportPos, face, first, second);
                    MovingObjectPosition visible =
                            visibleFaceHit(client, supportPos, face, requested);
                    if (visible == null
                            || !withinReach(client, visible.hitVec)
                            || !canPlaceMaterial(client, material, visible)
                            || projectedShieldBlocksRay(
                                    client.thePlayer.getPositionEyes(1.0F),
                                    visible.hitVec,
                                    projectedShield)) {
                        continue;
                    }
                    double distance =
                            visible.hitVec.squareDistanceTo(client.thePlayer.getPositionEyes(1.0F));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = visible;
                    }
                }
            }
        }
        return best;
    }

    private static int shieldRayCoverage(Minecraft client, BlockPos shield, EntityPlayer target) {
        AxisAlignedBB targetBox = target.getEntityBoundingBox();
        BlockPos targetFeet = new BlockPos(target.posX, targetBox.minY + 1.0E-4D, target.posZ);
        int covered = 0;
        for (int yOffset = -BED_SEARCH_VERTICAL; yOffset <= BED_SEARCH_VERTICAL; yOffset++) {
            for (int xOffset = -BED_FOOT_SEARCH_RADIUS;
                    xOffset <= BED_FOOT_SEARCH_RADIUS;
                    xOffset++) {
                for (int zOffset = -BED_FOOT_SEARCH_RADIUS;
                        zOffset <= BED_FOOT_SEARCH_RADIUS;
                        zOffset++) {
                    Vec3 explosion = VecMath.atCenterOf(targetFeet.add(xOffset, yOffset, zOffset));
                    if (EntityDistance.squaredToBox(explosion, targetBox)
                                    > MAX_TARGET_EXPLOSION_DISTANCE * MAX_TARGET_EXPLOSION_DISTANCE
                            || EntityDistance.squaredToBox(
                                            explosion, client.thePlayer.getEntityBoundingBox())
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
            Minecraft client, PlacementMaterial material, EntityPlayer target, BlockPos shield) {
        return findTargetSupportPlan(client, material, target, shield, false);
    }

    private static BasePlan findTargetSupportPlan(
            Minecraft client,
            PlacementMaterial material,
            EntityPlayer target,
            BlockPos shield,
            boolean projectedShield) {
        AxisAlignedBB targetBox = target.getEntityBoundingBox();
        BlockPos targetFeet = new BlockPos(target.posX, targetBox.minY + 1.0E-4D, target.posZ);
        BasePlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int xOffset = -BED_FOOT_SEARCH_RADIUS;
                    xOffset <= BED_FOOT_SEARCH_RADIUS;
                    xOffset++) {
                for (int zOffset = -BED_FOOT_SEARCH_RADIUS;
                        zOffset <= BED_FOOT_SEARCH_RADIUS;
                        zOffset++) {
                    BlockPos support = targetFeet.add(xOffset, yOffset, zOffset);
                    if (!LegacyWorld.replaceable(client.theWorld.getBlockState(support))
                            || !projectedShield && rejectedTargetSupports.contains(support)
                            || occupiedByPlayer(client, support)) {
                        continue;
                    }
                    MovingObjectPosition hit =
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
                                    + hit.hitVec.squareDistanceTo(
                                                    client.thePlayer.getPositionEyes(1.0F))
                                            * 0.01D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BasePlan(new BlockPos(support), hit);
                    }
                }
            }
        }
        return best;
    }

    private static BedPlan findBedPlanOnSupport(
            Minecraft client, EntityPlayer target, BlockPos support, BlockPos shield) {
        return bedPlanOnSupport(client, target, support, shield, true, false);
    }

    /**
     * Balance intentionally performs no combat scoring. It only finds a
     * physically usable point on the top of the fixed front block and lets the
     * sent yaw determine the vanilla bed direction.
     */
    private static BedPlan simpleBalanceBedPlan(Minecraft client, BlockPos support) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        BlockPos foot = support.up();
        if (!LegacyWorld.replaceable(client.theWorld.getBlockState(foot))
                || occupiedByPlayer(client, foot)) {
            return null;
        }
        BedPlan best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double xSample : BED_FACE_SAMPLES) {
            for (double zSample : BED_FACE_SAMPLES) {
                Vec3 requested = pointOnFace(support, EnumFacing.UP, xSample, zSample);
                MovingObjectPosition hit =
                        visibleFaceHit(client, support, EnumFacing.UP, requested);
                if (hit == null || !withinReach(client, hit.hitVec)) {
                    continue;
                }
                EnumFacing facing = EnumFacing.fromAngle(yawTo(eye, hit.hitVec));
                BlockPos head = foot.offset(facing);
                if (!LegacyWorld.replaceable(client.theWorld.getBlockState(head))
                        || occupiedByPlayer(client, head)
                        || !hasFutureBedInteractionPath(client, foot, head, null)) {
                    continue;
                }
                double distance = eye.squareDistanceTo(hit.hitVec);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best =
                            new BedPlan(
                                    new BlockPos(foot),
                                    new BlockPos(head),
                                    facing,
                                    VecMath.atCenterOf(head),
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
            EntityPlayer target,
            BlockPos support,
            BlockPos shield,
            boolean requireVisibleSupport,
            boolean projectedShield) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB targetBox = target.getEntityBoundingBox();
        BlockPos foot = support.up();
        if (!LegacyWorld.replaceable(client.theWorld.getBlockState(foot))
                || occupiedByPlayer(client, foot)) {
            return null;
        }
        BedPlan best = null;
        double bestScore = Double.MAX_VALUE;
        Map<BlockPos, DamageEstimate> damageCache = new HashMap<>();
        for (double xSample : BED_FACE_SAMPLES) {
            for (double zSample : BED_FACE_SAMPLES) {
                Vec3 requested = pointOnFace(support, EnumFacing.UP, xSample, zSample);
                MovingObjectPosition hit =
                        requireVisibleSupport
                                ? visibleFaceHit(client, support, EnumFacing.UP, requested)
                                : futureFaceHit(
                                        client,
                                        support,
                                        EnumFacing.UP,
                                        requested,
                                        projectedShield ? shield : null);
                if (hit == null || !withinReach(client, hit.hitVec)) {
                    continue;
                }
                if (projectedShieldBlocksRay(eye, hit.hitVec, projectedShield ? shield : null)
                        && !support.equals(shield)) {
                    continue;
                }
                EnumFacing facing = EnumFacing.fromAngle(yawTo(eye, hit.hitVec));
                BlockPos head = foot.offset(facing);
                if (!projectedShield
                        && rejectedBedPlacements.contains(new BedPlacementKey(foot, head))) {
                    continue;
                }
                BedPointKey pointKey = bedPointKey(foot, head, hit);
                if (!projectedShield && rejectedBedPoints.contains(pointKey)) {
                    continue;
                }
                if (!LegacyWorld.replaceable(client.theWorld.getBlockState(head))
                        || occupiedByPlayer(client, head)
                        || !hasFutureBedInteractionPath(
                                client, foot, head, projectedShield ? shield : null)) {
                    continue;
                }
                Vec3 explosion = VecMath.atCenterOf(head);
                Vec3 targetPoint = EntityDistance.closestPoint(explosion, targetBox);
                double targetDistanceSquared = explosion.squareDistanceTo(targetPoint);
                if (targetDistanceSquared
                                > MAX_TARGET_EXPLOSION_DISTANCE * MAX_TARGET_EXPLOSION_DISTANCE
                        || !safeBehindShield(client, shield, explosion)) {
                    continue;
                }
                DamageEstimate damage =
                        damageCache.computeIfAbsent(
                                new BlockPos(head),
                                ignored -> bedDamage(client, target, explosion));
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
                                + hit.hitVec.squareDistanceTo(eye) * 0.01D;
                if (score < bestScore) {
                    bestScore = score;
                    best =
                            new BedPlan(
                                    new BlockPos(foot),
                                    new BlockPos(head),
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
    private static BedPlan findBedPlan(Minecraft client, EntityPlayer target, BlockPos shield) {
        return findBedPlan(client, target, shield, false);
    }

    private static BedPlan findBedPlan(
            Minecraft client, EntityPlayer target, BlockPos shield, boolean projectedShield) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB targetBox = target.getEntityBoundingBox();
        BlockPos targetFeet = new BlockPos(target.posX, targetBox.minY + 1.0E-4D, target.posZ);
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
                    BlockPos foot = targetFeet.add(xOffset, yOffset, zOffset);
                    if (!LegacyWorld.replaceable(client.theWorld.getBlockState(foot))
                            || occupiedByPlayer(client, foot)) {
                        continue;
                    }
                    for (EnumFacing face : SUPPORT_FACES) {
                        BlockPos support = foot.offset(face.getOpposite());
                        IBlockState supportState = client.theWorld.getBlockState(support);
                        if (LegacyWorld.collision(supportState, client.theWorld, support)
                                .isEmpty()) {
                            continue;
                        }
                        for (double first : BED_FACE_SAMPLES) {
                            for (double second : BED_FACE_SAMPLES) {
                                Vec3 requestedPoint = pointOnFace(support, face, first, second);
                                MovingObjectPosition hit =
                                        visibleFaceHit(client, support, face, requestedPoint);
                                if (hit == null || !withinReach(client, hit.hitVec)) {
                                    continue;
                                }
                                if (projectedShieldBlocksRay(
                                        eye, hit.hitVec, projectedShield ? shield : null)) {
                                    continue;
                                }
                                EnumFacing facing = EnumFacing.fromAngle(yawTo(eye, hit.hitVec));
                                BlockPos head = foot.offset(facing);
                                if (!projectedShield
                                        && rejectedBedPlacements.contains(
                                                new BedPlacementKey(foot, head))) {
                                    continue;
                                }
                                BedPointKey pointKey = bedPointKey(foot, head, hit);
                                if (!projectedShield && rejectedBedPoints.contains(pointKey)) {
                                    continue;
                                }
                                if (!LegacyWorld.replaceable(client.theWorld.getBlockState(head))
                                        || occupiedByPlayer(client, head)
                                        || !hasFutureBedInteractionPath(
                                                client,
                                                foot,
                                                head,
                                                projectedShield ? shield : null)) {
                                    continue;
                                }
                                Vec3 explosionCenter = VecMath.atCenterOf(head);
                                Vec3 closestTargetPoint =
                                        EntityDistance.closestPoint(explosionCenter, targetBox);
                                double targetDistanceSquared =
                                        explosionCenter.squareDistanceTo(closestTargetPoint);
                                if (targetDistanceSquared
                                                > MAX_TARGET_EXPLOSION_DISTANCE
                                                        * MAX_TARGET_EXPLOSION_DISTANCE
                                        || !safeBehindShield(client, shield, explosionCenter)) {
                                    continue;
                                }
                                DamageEstimate damage =
                                        damageCache.computeIfAbsent(
                                                new BlockPos(head),
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
                                                + hit.hitVec.squareDistanceTo(eye) * 0.01D;
                                if (score < bestScore) {
                                    bestScore = score;
                                    best =
                                            new BedPlan(
                                                    new BlockPos(foot),
                                                    new BlockPos(head),
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

    private static Vec3 pointOnFace(
            BlockPos support, EnumFacing face, double first, double second) {
        return BlockPlacementUtils.fullBlockFaceOffset(support, face, first, second);
    }

    /** Returns the exact visible point so a shield or wall cannot hide the support face. */
    private static MovingObjectPosition visibleFaceHit(
            Minecraft client, BlockPos support, EnumFacing face, Vec3 requestedPoint) {
        return RAYS.visibleFaceHit(
                client,
                client.thePlayer.getPositionEyes(1.0F),
                support,
                face,
                requestedPoint,
                RAY_EPSILON);
    }

    /** Visible top/side of a block that the plan will place later. */
    private static MovingObjectPosition futureFaceHit(
            Minecraft client,
            BlockPos futureSupport,
            EnumFacing face,
            Vec3 requested,
            BlockPos projectedShield) {
        if (RAYS.entityBlocked(client, client.thePlayer.getPositionEyes(1.0F), requested))
            return null;
        if (RAYS.throughBlocks()) return LegacyWorld.hit(requested, face, futureSupport, false);
        if (projectedShieldBlocksRay(
                        client.thePlayer.getPositionEyes(1.0F), requested, projectedShield)
                && !futureSupport.equals(projectedShield)) {
            return null;
        }
        Vec3 justBeforeFace =
                requested.addVector(
                        face.getFrontOffsetX() * RAY_EPSILON,
                        face.getFrontOffsetY() * RAY_EPSILON,
                        face.getFrontOffsetZ() * RAY_EPSILON);
        MovingObjectPosition obstruction =
                LegacyWorld.clip(
                        client.theWorld,
                        new LegacyRay(
                                client.thePlayer.getPositionEyes(1.0F),
                                justBeforeFace,
                                LegacyRay.Block.OUTLINE,
                                LegacyRay.Fluid.NONE,
                                client.thePlayer));
        if (obstruction.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        return LegacyWorld.hit(requested, face, futureSupport, false);
    }

    private static boolean projectedShieldBlocksRay(
            Vec3 eye, Vec3 point, BlockPos projectedShield) {
        if (projectedShield == null) {
            return false;
        }
        return LegacyWorld.intercept(
                        LegacyWorld.inflate(LegacyWorld.box(projectedShield), RAY_EPSILON),
                        eye,
                        point)
                .filter(
                        intersection ->
                                eye.squareDistanceTo(intersection)
                                        < eye.squareDistanceTo(point) - 1.0E-6D)
                .isPresent();
    }

    private static boolean safeBehindShield(
            Minecraft client, BlockPos shield, Vec3 explosionCenter) {
        double selfDistanceSquared =
                EntityDistance.squaredToBox(
                        explosionCenter, client.thePlayer.getEntityBoundingBox());
        if (selfDistanceSquared < MIN_SELF_EXPLOSION_DISTANCE * MIN_SELF_EXPLOSION_DISTANCE) {
            return false;
        }
        return shieldRayCoverageForExplosion(client, shield, explosionCenter) >= 3;
    }

    private static int shieldRayCoverageForExplosion(
            Minecraft client, BlockPos shield, Vec3 explosionCenter) {
        AxisAlignedBB playerBox = client.thePlayer.getEntityBoundingBox();
        AxisAlignedBB shieldBox = LegacyWorld.inflate(LegacyWorld.box(shield), 0.02D);
        int covered = 0;
        for (double ySample : new double[] {0.18D, 0.48D, 0.78D}) {
            for (double xSample : new double[] {0.2D, 0.5D, 0.8D}) {
                for (double zSample : new double[] {0.2D, 0.5D, 0.8D}) {
                    Vec3 bodyPoint =
                            new Vec3(
                                    Mth.lerp(xSample, playerBox.minX, playerBox.maxX),
                                    Mth.lerp(ySample, playerBox.minY, playerBox.maxY),
                                    Mth.lerp(zSample, playerBox.minZ, playerBox.maxZ));
                    if (LegacyWorld.intercept(shieldBox, bodyPoint, explosionCenter).isPresent()) {
                        covered++;
                    }
                }
            }
        }
        return covered;
    }

    private static DamageEstimate bedDamage(
            Minecraft client, EntityPlayer target, Vec3 explosionCenter) {
        return new DamageEstimate(
                explosionDamage(client, target, explosionCenter),
                explosionDamage(client, client.thePlayer, explosionCenter));
    }

    /** Mirrors vanilla bed power (5, effective entity radius 10) and reductions. */
    private static float explosionDamage(Minecraft client, EntityPlayer player, Vec3 center) {
        if (player == null || !player.isEntityAlive() || player.capabilities.disableDamage)
            return 0;
        double distance =
                Math.sqrt(player.getDistanceSq(center.xCoord, center.yCoord, center.zCoord)) / 10;
        if (distance > 1) return 0;
        double impact =
                (1 - distance)
                        * client.theWorld.getBlockDensity(center, player.getEntityBoundingBox());
        float damage = (int) ((impact * impact + impact) * 35 + 1);
        damage =
                switch (client.theWorld.getDifficulty()) {
                    case PEACEFUL -> 0;
                    case EASY -> Math.min(damage / 2 + 1, damage);
                    case HARD -> damage * 1.5F;
                    default -> damage;
                };
        damage *= 1 - Math.min(20, player.getTotalArmorValue()) / 25.0F;
        PotionEffect resistance = player.getActivePotionEffect(Potion.resistance);
        if (resistance != null) damage *= Math.max(0, 1 - (resistance.getAmplifier() + 1) * .2F);
        var source =
                net.minecraft.util.DamageSource.setExplosionSource(
                        new net.minecraft.world.Explosion(
                                client.theWorld,
                                null,
                                center.xCoord,
                                center.yCoord,
                                center.zCoord,
                                5,
                                false,
                                true));
        int epf = 0;
        for (ItemStack armor : player.inventory.armorInventory)
            if (armor != null)
                for (var entry : EnchantmentHelper.getEnchantments(armor).entrySet()) {
                    Enchantment enchantment = Enchantment.getEnchantmentById(entry.getKey());
                    if (enchantment != null)
                        epf += enchantment.calcModifierDamage(entry.getValue(), source);
                }
        // 1.8 randomizes EPF per hit; use its expected reduction for stable planning.
        int capped = Math.clamp(epf, 0, 25), base = (capped + 1) / 2;
        float effective = 0;
        for (int bonus = 0; bonus <= capped / 2; bonus++) effective += Math.min(20, base + bonus);
        effective /= capped / 2 + 1;
        return Math.max(0, damage * (1 - effective / 25));
    }

    private static boolean acceptableDamage(Minecraft client, DamageEstimate damage) {
        if (damage.targetDamage() + 1.0E-4F < MIN_DAMAGE.get()
                || damage.selfDamage() - 1.0E-4F > MAX_SELF_DAMAGE.get()) {
            return false;
        }
        float totalHealth = client.thePlayer.getHealth() + client.thePlayer.getAbsorptionAmount();
        return !ANTI_SUICIDE.get() || damage.selfDamage() < totalHealth;
    }

    /** The virtual shield is absent from ServerExplosion exposure until placed. */
    private static boolean acceptableProjectedDamage(Minecraft client, DamageEstimate damage) {
        if (damage.targetDamage() + 1.0E-4F < MIN_DAMAGE.get()) {
            return false;
        }
        float totalHealth = client.thePlayer.getHealth() + client.thePlayer.getAbsorptionAmount();
        return !ANTI_SUICIDE.get() || damage.selfDamage() < totalHealth;
    }

    private static EntityPlayer findTarget(Minecraft client) {
        EntityPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EntityPlayer target : client.theWorld.playerEntities) {
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

    private static boolean validFovTarget(Minecraft client, EntityPlayer target) {
        if (!Targeting.isValidTargetPlayer(client, target)) {
            return false;
        }
        if (EntityDistance.squaredToEntity(client, target) > RANGE.get() * RANGE.get()) {
            return false;
        }
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        Vec3 point = EntityDistance.closestPoint(eye, target.getEntityBoundingBox());
        return MathUtils.withinFov(
                MathUtils.viewAngle(eye, client.thePlayer.getLookVec(), point), FOV.get());
    }

    private static PlacementMaterial findMaterial(Minecraft client) {
        InventoryPlayer inventory = client.thePlayer.inventory;
        int selected = inventory.currentItem;
        ItemStack selectedStack = inventory.getStackInSlot(selected);
        if (validMaterial(client, selectedStack)) {
            return new PlacementMaterial(selected, (ItemBlock) selectedStack.getItem());
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (validMaterial(client, stack)) {
                return new PlacementMaterial(slot, (ItemBlock) stack.getItem());
            }
        }
        return null;
    }

    private static boolean validMaterial(Minecraft client, ItemStack stack) {
        if (LegacyItems.empty(stack)
                || !(stack.getItem() instanceof ItemBlock blockItem)
                || MinecraftClientAccess.isBedItem(blockItem)
                || blockItem.getBlock() instanceof BlockFalling) {
            return false;
        }
        IBlockState state = blockItem.getBlock().getDefaultState();
        return BlockPlacementUtils.hasSolidPlacementShape(client.theWorld, state);
    }

    private static int findBedSlot(Minecraft client) {
        return HotbarQueries.firstMatch(
                client.thePlayer.inventory,
                stack -> MinecraftClientAccess.isBedItem(stack.getItem()));
    }

    private static boolean canPlaceMaterial(
            Minecraft client, PlacementMaterial material, MovingObjectPosition hit) {
        ItemStack stack = client.thePlayer.inventory.getStackInSlot(material.hotbarSlot());
        if (!validMaterial(client, stack) || stack.getItem() != material.item()) {
            return false;
        }
        LegacyPlacement.Context context = new LegacyPlacement.Context(client.thePlayer, hit);
        return LegacyPlacement.state(material.item().getBlock(), context) != null;
    }

    private static boolean validBasePlan(Minecraft client) {
        if (basePlan == null
                || materialItem == null
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(basePlan.pos()))
                || occupiedByPlayer(client, basePlan.pos())) {
            return false;
        }
        PlacementMaterial material = new PlacementMaterial(materialSlot, materialItem);
        return canPlaceMaterial(client, material, basePlan.hit())
                && withinReach(client, basePlan.hit().hitVec);
    }

    private static boolean validTargetSupportPlan(Minecraft client) {
        if (targetSupportPlan == null
                || materialItem == null
                || basePlan == null
                || shieldItem == null
                || !(client.theWorld.getBlockState(basePlan.pos()).getBlock()
                        == shieldItem.getBlock())
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(targetSupportPlan.pos()))
                || occupiedByPlayer(client, targetSupportPlan.pos())) {
            return false;
        }
        PlacementMaterial material = new PlacementMaterial(materialSlot, materialItem);
        return canPlaceMaterial(client, material, targetSupportPlan.hit())
                && withinReach(client, targetSupportPlan.hit().hitVec);
    }

    private static boolean validBedPlan(Minecraft client, boolean requireSentFacing) {
        if (bedPlan == null
                || bedSlot < 0
                || client.thePlayer.inventory.currentItem != bedSlot
                || !(MinecraftClientAccess.isBedItem(
                        client.thePlayer.inventory.getStackInSlot(bedSlot).getItem()))
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(bedPlan.foot()))
                || !LegacyWorld.replaceable(client.theWorld.getBlockState(bedPlan.head()))
                || occupiedByPlayer(client, bedPlan.foot())
                || occupiedByPlayer(client, bedPlan.head())
                || !validBedBase(client)
                || !bedPlan.hit().getBlockPos().offset(bedPlan.hit().sideHit).equals(bedPlan.foot())
                || visibleFaceHit(
                                client,
                                bedPlan.hit().getBlockPos(),
                                bedPlan.hit().sideHit,
                                bedPlan.hit().hitVec)
                        == null) {
            return false;
        }
        EnumFacing sentFacing =
                EnumFacing.fromAngle(SilentPacketRotation.getInteractionYaw(client));
        return (!requireSentFacing || sentFacing == bedPlan.facing())
                && withinReach(client, bedPlan.hit().hitVec)
                && (!isBlatant() || !TARGET_RANGE_RECHECK.get() || validExplosionTarget(client));
    }

    private static boolean validBedBase(Minecraft client) {
        if (basePlan == null) {
            return false;
        }
        IBlockState state = client.theWorld.getBlockState(basePlan.pos());
        if (reusedBalanceBase) {
            return !LegacyWorld.collision(state, client.theWorld, basePlan.pos()).isEmpty();
        }
        return shieldItem != null && (state.getBlock() == shieldItem.getBlock());
    }

    private static boolean validExplosionTarget(Minecraft client) {
        EntityPlayer target = targetById(client);
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
        Vec3 explosionCenter = VecMath.atCenterOf(clickedHalf);
        double vanillaEntityRadius = 10.0D;
        return target.getDistanceSq(
                        explosionCenter.xCoord, explosionCenter.yCoord, explosionCenter.zCoord)
                <= vanillaEntityRadius * vanillaEntityRadius;
    }

    private static MovingObjectPosition findVisibleBedHit(
            Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        MovingObjectPosition best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : new BlockPos[] {foot, head}) {
            for (double xOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                for (double zOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                    Vec3 insideTop =
                            new Vec3(
                                    pos.getX() + 0.5D + xOffset,
                                    pos.getY() + 0.55D,
                                    pos.getZ() + 0.5D + zOffset);
                    MovingObjectPosition hit =
                            RAYS.clip(client, eye, insideTop, LegacyRay.Fluid.NONE, pos);
                    if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !hit.getBlockPos().equals(pos)
                            || !withinReach(client, hit.hitVec)) {
                        continue;
                    }
                    double distance = eye.squareDistanceTo(hit.hitVec);
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
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        for (BlockPos pos : new BlockPos[] {foot, head}) {
            for (double xOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                for (double zOffset : new double[] {-0.28D, 0.0D, 0.28D}) {
                    Vec3 point =
                            new Vec3(
                                    pos.getX() + 0.5D + xOffset,
                                    pos.getY() + 0.55D,
                                    pos.getZ() + 0.5D + zOffset);
                    if (!withinReach(client, point) || RAYS.entityBlocked(client, eye, point))
                        continue;
                    if (RAYS.throughBlocks()) return true;
                    if (projectedShieldBlocksRay(eye, point, projectedShield)) continue;
                    Vec3 towardEye = eye.subtract(point);
                    if (VecMath.lengthSqr(towardEye) < 1.0E-10D) {
                        return true;
                    }
                    Vec3 justBeforeBed =
                            point.add(VecMath.scale(towardEye.normalize(), RAY_EPSILON));
                    MovingObjectPosition obstruction =
                            LegacyWorld.clip(
                                    client.theWorld,
                                    new LegacyRay(
                                            eye,
                                            justBeforeBed,
                                            LegacyRay.Block.OUTLINE,
                                            LegacyRay.Fluid.NONE,
                                            client.thePlayer));
                    if (obstruction.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static MovingObjectPosition findBedHitOnSentRay(
            Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        Vec3 end =
                eye.add(
                        VecMath.scale(
                                SilentPacketRotation.getInteractionLookVector(client),
                                Minecraft.getMinecraft().playerController.getBlockReachDistance()));
        MovingObjectPosition hit = RAYS.clip(client, eye, end, LegacyRay.Fluid.NONE, foot);
        MovingObjectPosition headHit = RAYS.clip(client, eye, end, LegacyRay.Fluid.NONE, head);
        if (headHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                        || eye.squareDistanceTo(headHit.hitVec) < eye.squareDistanceTo(hit.hitVec)))
            hit = headHit;
        if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || (!hit.getBlockPos().equals(foot) && !hit.getBlockPos().equals(head))
                || !(client.theWorld.getBlockState(hit.getBlockPos()).getBlock()
                        instanceof BlockBed)
                || !withinReach(client, hit.hitVec)) {
            return null;
        }
        return hit;
    }

    private static boolean useOnSilently(Minecraft client, MovingObjectPosition hit) {
        if (!RAYS.canUse(client, hit)) return false;
        float cameraYaw = client.thePlayer.rotationYaw;
        float cameraPitch = client.thePlayer.rotationPitch;
        client.thePlayer.rotationYaw = SilentPacketRotation.getInteractionYaw(client);
        client.thePlayer.rotationPitch = SilentPacketRotation.getInteractionPitch(client);
        invokingBedUse = true;
        try {
            boolean result =
                    client.playerController.onPlayerRightClick(
                            client.thePlayer,
                            client.theWorld,
                            client.thePlayer.getHeldItem(),
                            hit.getBlockPos(),
                            hit.sideHit,
                            hit.hitVec);
            if (result) {
                client.thePlayer.swingItem();
            }
            return result;
        } finally {
            invokingBedUse = false;
            client.thePlayer.rotationYaw = cameraYaw;
            client.thePlayer.rotationPitch = cameraPitch;
        }
    }

    private static void beginBedClickRotation(Minecraft client, MovingObjectPosition hit) {
        if (deferAfterUse(() -> beginBedClickRotation(client, hit))) return;
        transition(Phase.TURNING_TO_BED_CLICK);
        SilentPacketRotation.beginRotation(
                client,
                hit.hitVec,
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
        double reach = Minecraft.getMinecraft().playerController.getBlockReachDistance();
        return BlockPlacementUtils.withinReach(
                client.thePlayer.getPositionEyes(1.0F), point, reach);
    }

    /**
     * Mirrors the vanilla obstruction rule used by solid block and bed
     * placement. A replaceable block is not actually placeable while any
     * player's collision box occupies the destination cell.
     */
    private static boolean occupiedByPlayer(Minecraft client, BlockPos pos) {
        AxisAlignedBB blockBox = LegacyWorld.box(pos);
        for (EntityPlayer player : client.theWorld.playerEntities) {
            if (player.getEntityBoundingBox().intersectsWith(blockBox)) {
                return true;
            }
        }
        return false;
    }

    private static float yawTo(Vec3 eye, Vec3 point) {
        return MathUtils.yawTo(eye, point);
    }

    private static BlockPos nearestBedHalf(Minecraft client, BlockPos foot, BlockPos head) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        return eye.squareDistanceTo(VecMath.atCenterOf(foot))
                        <= eye.squareDistanceTo(VecMath.atCenterOf(head))
                ? foot
                : head;
    }

    private static BedPointKey bedPointKey(BedPlan plan) {
        return bedPointKey(plan.foot(), plan.head(), plan.hit());
    }

    private static BedPointKey bedPointKey(BlockPos foot, BlockPos head, MovingObjectPosition hit) {
        Vec3 point = hit.hitVec;
        return new BedPointKey(
                new BlockPos(foot),
                new BlockPos(head),
                new BlockPos(hit.getBlockPos()),
                hit.sideHit,
                (int) Math.round(point.xCoord * 1_000.0D),
                (int) Math.round(point.yCoord * 1_000.0D),
                (int) Math.round(point.zCoord * 1_000.0D));
    }

    private static EntityPlayer targetById(Minecraft client) {
        return targetId < 0
                ? null
                : client.theWorld.getEntityByID(targetId) instanceof EntityPlayer player
                        ? player
                        : null;
    }

    private static boolean selectSlot(Minecraft client, int slot) {
        if (slot < 0 || slot > 8 || client.thePlayer.inventory.currentItem == slot) {
            return false;
        }
        client.thePlayer.inventory.currentItem = slot;
        return true;
    }

    private static boolean otherRotationOwnerBusy() {
        return PlacementCoordinator.busyFor(PlacementCoordinator.Owner.AUTO_BED);
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
        var currentPlayer = client == null ? null : client.thePlayer;
        invokingBedUse = usedBeforeMovement = false;
        afterUseMovement = null;
        boolean ownedRotation = isBusy();
        if (ownedRotation
                && client != null
                && currentPlayer != null
                && originalSlot >= 0
                && originalSlot <= 8) {
            currentPlayer.inventory.currentItem = originalSlot;
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

    public static String modeName() {
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

    private record PlacementMaterial(int hotbarSlot, ItemBlock item) {}

    private record BasePlan(BlockPos pos, MovingObjectPosition hit) {}

    private record BedPlan(
            BlockPos foot,
            BlockPos head,
            EnumFacing facing,
            Vec3 closestTargetPoint,
            float targetDamage,
            float selfDamage,
            MovingObjectPosition hit,
            MovingObjectPosition interactHit) {
        BedPlan withInteractHit(MovingObjectPosition value) {
            return new BedPlan(
                    foot, head, facing, closestTargetPoint, targetDamage, selfDamage, hit, value);
        }
    }

    private record DamageEstimate(float targetDamage, float selfDamage) {}

    private record CyclePreview(BasePlan targetSupport, BedPlan bed) {}

    private record BedPlacementKey(BlockPos foot, BlockPos head) {}

    private record BedPointKey(
            BlockPos foot, BlockPos head, BlockPos support, EnumFacing face, int x, int y, int z) {}

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

    /** End this feature's pending work without changing its configured toggle. */
    public static void shutdown(Minecraft client) {
        cleanup(client, false, null);
    }
}
