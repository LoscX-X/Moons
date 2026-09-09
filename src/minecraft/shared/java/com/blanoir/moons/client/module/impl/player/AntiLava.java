package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.RotationUtils;
import com.blanoir.moons.client.utils.world.FluidQueries;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Covers a newly placed hostile lava source with a hotbar/offhand block. */
public final class AntiLava {
    private static final double ENEMY_PLACE_REACH = 5.0D;
    private static final double ENEMY_LOOK_DOT = -0.10D;
    private static final double DEFAULT_FOV = 180.0D;
    private static final int ROTATION_SMOOTH_TICKS = 1;
    private static final long EVENT_LIFETIME_MS = 2000L;
    private static final long SAME_SOURCE_DEBOUNCE_MS = 500L;
    private static final double[] FACE_SAMPLES = {0.22D, 0.5D, 0.78D};
    private static final Direction[] SUPPORT_FACES = {
        Direction.UP,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.DOWN
    };

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("antilava.enabled").defaultValue(false).build();

    private static final IntSetting DELAY_MIN =
            new IntSetting.Builder()
                    .name("antilava.delay.min")
                    .defaultValue(1)
                    .range(1, 20)
                    .build();

    private static final IntSetting DELAY_MAX =
            new IntSetting.Builder()
                    .name("antilava.delay.max")
                    .defaultValue(3)
                    .range(1, 20)
                    .build();

    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("antilava.range")
                    .defaultValue(4.5D)
                    .range(1.0D, 6.0D)
                    .build();

    private static final DoubleSetting FOV =
            new DoubleSetting.Builder()
                    .name("antilava.fov")
                    .defaultValue(DEFAULT_FOV)
                    .range(1.0D, 360.0D)
                    .build();

    private static volatile BlockPos pendingSource;
    private static volatile long pendingDetectedAtMs;
    private static BlockPos lastSource;
    private static long lastSourceAtMs;
    private static PlacementPlan activePlan;
    private static InteractionHand activeHand;
    private static int activeHotbarSlot = -1;
    private static int originalHotbarSlot = -1;
    private static PlacementPhase placementPhase = PlacementPhase.IDLE;
    private static int actionDelayRemainingTicks;

    private AntiLava() {}

    public static void init() {
        normalizeDelay();
        EventBus.PLAYER_UPDATE.register("AntiLava.playerUpdate", AntiLava::tick);
    }

    /** Called from ClientLevel block-update hooks; state is only acted on next tick. */
    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!ENABLED.get()
                || pos == null
                || state == null
                || !state.getFluidState().is(FluidTags.LAVA)
                || !state.getFluidState().isSource()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pos.equals(lastSource) && now - lastSourceAtMs < SAME_SOURCE_DEBOUNCE_MS) {
            return;
        }
        pendingSource = pos.immutable();
        pendingDetectedAtMs = now;
        lastSource = pos.immutable();
        lastSourceAtMs = now;
    }

    private static void tick(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!ENABLED.get() || !ready(client)) {
            if (isBusy()) {
                reset(client);
            }
            clearPending();
            return;
        }

        if (activePlan != null) {
            tickPlacement(client);
            return;
        }

        if (AutoLava.isBusy() || AntiWeb.isBusy() || AutoBed.isBusy()) {
            return;
        }
        processPending(client);
    }

    private static void processPending(Minecraft client) {

        BlockPos source = pendingSource;
        long detectedAt = pendingDetectedAtMs;
        if (source == null) {
            return;
        }
        clearPending();

        if (System.currentTimeMillis() - detectedAt > EVENT_LIFETIME_MS
                || !isLavaSource(client, source)
                || isUnderPlayerFeet(client, source)
                || !withinPlayerRange(client, source)
                || !withinFov(client, Vec3.atCenterOf(source))
                || responsibleEnemy(client, source) == null) {
            return;
        }

        PlacementMaterial material = findPlacementMaterial(client);
        BlockHitResult hit = findSupportHit(client, source);
        if (material == null || hit == null) {
            return;
        }

        beginPlacement(client, new PlacementPlan(source, hit), material);
    }

    private static void beginPlacement(
            Minecraft client, PlacementPlan plan, PlacementMaterial material) {
        AutoWeb.yieldForAntiLava(client);
        activePlan = plan;
        activeHand = material.hand();
        activeHotbarSlot = material.hotbarSlot();
        originalHotbarSlot = client.player.getInventory().getSelectedSlot();
        if (activeHand == InteractionHand.MAIN_HAND && activeHotbarSlot != originalHotbarSlot) {
            client.player.getInventory().setSelectedSlot(activeHotbarSlot);
        }

        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_LAVA);
        actionDelayRemainingTicks = RandomMath.betweenInclusive(DELAY_MIN.get(), DELAY_MAX.get());
        placementPhase = PlacementPhase.WAITING_DELAY;
    }

    private static void tickPlacement(Minecraft client) {
        if (placementPhase == PlacementPhase.WAITING_DELAY) {
            if (--actionDelayRemainingTicks > 0) {
                return;
            }
            placementPhase = PlacementPhase.TURNING_TO_PLACE;
            SilentPacketRotation.beginRotation(
                    client,
                    activePlan.hit().getLocation(),
                    ROTATION_SMOOTH_TICKS,
                    SilentPacketRotation.Mode.INSTANT,
                    () -> placementPhase = PlacementPhase.WAITING_FOR_PLACE_ROTATION);
        }

        if (placementPhase == PlacementPhase.TURNING_TO_PLACE
                || placementPhase == PlacementPhase.TURNING_BACK_TO_CAMERA) {
            return;
        }

        if (placementPhase == PlacementPhase.WAITING_FOR_RETURN_ROTATION) {
            if (!SilentPacketRotation.isRotationPacketSent()) {
                return;
            }
            float yawDifference =
                    Math.abs(
                            Mth.wrapDegrees(
                                    client.player.getYRot() - SilentPacketRotation.getSentYaw()));
            float pitchDifference =
                    Math.abs(client.player.getXRot() - SilentPacketRotation.getSentPitch());
            if (yawDifference <= 0.35F && pitchDifference <= 0.35F) {
                restoreMaterial(client);
            } else {
                beginReturnRotation(client);
            }
            return;
        }

        if (placementPhase == PlacementPhase.WAITING_FOR_PLACE_PACKET) {
            if (SilentPacketRotation.isRotationPacketSent()) {
                beginReturnRotation(client);
            }
            return;
        }

        if (placementPhase != PlacementPhase.WAITING_FOR_PLACE_ROTATION) {
            return;
        }

        PlacementPlan plan = activePlan;
        if (!ENABLED.get()
                || !ready(client)
                || plan == null
                || !isLavaSource(client, plan.source())
                || isUnderPlayerFeet(client, plan.source())
                || !withinPlayerRange(client, plan.source())
                || (activeHand == InteractionHand.MAIN_HAND
                        && client.player.getInventory().getSelectedSlot() != activeHotbarSlot)) {
            beginReturnRotation(client);
            return;
        }

        BlockHitResult currentHit = findSupportHit(client, plan.source());
        if (currentHit != null && canPlace(client, activeHand, activeHotbarSlot, currentHit)) {
            InteractionResult result = useOnSilently(client, activeHand, currentHit);
            if (result.consumesAction()) {
                MinecraftClientAccess.animatePlacement(client.player, activeHand, true);
            }
        }
        placementPhase = PlacementPhase.WAITING_FOR_PLACE_PACKET;
    }

    private static void beginReturnRotation(Minecraft client) {
        placementPhase = PlacementPhase.TURNING_BACK_TO_CAMERA;
        SilentPacketRotation.beginReturnToCamera(
                client,
                ROTATION_SMOOTH_TICKS,
                () -> placementPhase = PlacementPhase.WAITING_FOR_RETURN_ROTATION);
    }

    private static Player responsibleEnemy(Minecraft client, BlockPos source) {
        Vec3 center = Vec3.atCenterOf(source);
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player target : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            Vec3 toSource = center.subtract(target.getEyePosition());
            double distance = toSource.length();
            if (distance > ENEMY_PLACE_REACH || distance < 1.0E-5D) {
                continue;
            }
            double lookDot = target.getLookAngle().dot(toSource.scale(1.0D / distance));
            if (distance > 2.5D && lookDot < ENEMY_LOOK_DOT) {
                continue;
            }
            double score = distance + Math.max(0.0D, 0.35D - lookDot) * 1.5D;
            if (score < bestScore) {
                best = target;
                bestScore = score;
            }
        }
        return best;
    }

    private static PlacementMaterial findPlacementMaterial(Minecraft client) {
        // Offhand is intentionally first: no hotbar swap is needed.
        if (isValidBlock(client, client.player.getOffhandItem())) {
            return new PlacementMaterial(InteractionHand.OFF_HAND, -1);
        }

        Inventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (isValidBlock(client, inventory.getItem(selected))) {
            return new PlacementMaterial(InteractionHand.MAIN_HAND, selected);
        }

        int bestSlot = -1;
        int bestCount = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isValidBlock(client, stack) && stack.getCount() > bestCount) {
                bestSlot = slot;
                bestCount = stack.getCount();
            }
        }
        return bestSlot == -1 ? null : new PlacementMaterial(InteractionHand.MAIN_HAND, bestSlot);
    }

    private static boolean isValidBlock(Minecraft client, ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || blockItem.getBlock() instanceof FallingBlock) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return BlockPlacementUtils.hasSolidPlacementShape(client.level, state);
    }

    private static boolean canPlace(
            Minecraft client, InteractionHand hand, int hotbarSlot, BlockHitResult hit) {
        ItemStack stack =
                hand == InteractionHand.OFF_HAND
                        ? client.player.getOffhandItem()
                        : hotbarSlot >= 0
                                ? client.player.getInventory().getItem(hotbarSlot)
                                : ItemStack.EMPTY;
        if (!isValidBlock(client, stack) || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        UseOnContext use = new UseOnContext(client.player, hand, hit);
        return blockItem.getBlock().getStateForPlacement(new BlockPlaceContext(use)) != null;
    }

    private static InteractionResult useOnSilently(
            Minecraft client, InteractionHand hand, BlockHitResult hit) {
        float cameraYaw = client.player.getYRot();
        float cameraPitch = client.player.getXRot();
        client.player.setYRot(SilentPacketRotation.getInteractionYaw(client));
        client.player.setXRot(SilentPacketRotation.getInteractionPitch(client));
        try {
            return client.gameMode.useItemOn(client.player, hand, hit);
        } finally {
            client.player.setYRot(cameraYaw);
            client.player.setXRot(cameraPitch);
        }
    }

    private static BlockHitResult findSupportHit(Minecraft client, BlockPos source) {
        if (!isLavaSource(client, source)) {
            return null;
        }
        BlockHitResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        Vec3 eye = client.player.getEyePosition();
        double range = effectiveRange(client);
        for (Direction face : SUPPORT_FACES) {
            BlockPos supportPos = source.relative(face.getOpposite());
            BlockState support = client.level.getBlockState(supportPos);
            if (support.getCollisionShape(client.level, supportPos).isEmpty()) {
                continue;
            }
            for (double u : FACE_SAMPLES) {
                for (double v : FACE_SAMPLES) {
                    Vec3 requested = BlockPlacementUtils.facePoint(client, supportPos, face, u, v);
                    if (eye.distanceToSqr(requested) > range * range) continue;
                    Rotation rotation = RotationUtils.rotationTo(eye, requested);
                    BlockHitResult traced =
                            BlockPlacementUtils.traceFace(
                                    client,
                                    eye,
                                    rotation.yaw(),
                                    rotation.pitch(),
                                    range,
                                    supportPos,
                                    face);
                    if (traced == null) continue;
                    double score =
                            eye.distanceToSqr(traced.getLocation())
                                    + Math.abs(u - 0.5D) * 0.02D
                                    + Math.abs(v - 0.5D) * 0.02D;
                    if (score < bestScore) {
                        best = traced;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isLavaSource(Minecraft client, BlockPos source) {
        BlockState state = client.level.getBlockState(source);
        return FluidQueries.isSource(state.getFluidState(), FluidTags.LAVA);
    }

    private static boolean withinPlayerRange(Minecraft client, BlockPos source) {
        return client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(source))
                <= effectiveRange(client) * effectiveRange(client);
    }

    /**
     * Ignores lava occupying the player's feet cell or the block immediately
     * below their footprint. Horizontal AABB overlap also handles standing on
     * a block edge instead of relying only on the player's center position.
     */
    private static boolean isUnderPlayerFeet(Minecraft client, BlockPos source) {
        AABB playerBox = client.player.getBoundingBox();
        boolean overlapsFootprint =
                playerBox.maxX > source.getX() + 1.0E-4D
                        && playerBox.minX < source.getX() + 1.0D - 1.0E-4D
                        && playerBox.maxZ > source.getZ() + 1.0E-4D
                        && playerBox.minZ < source.getZ() + 1.0D - 1.0E-4D;
        if (!overlapsFootprint) {
            return false;
        }

        int feetY = Mth.floor(playerBox.minY + 1.0E-4D);
        int belowFeetY = Mth.floor(playerBox.minY - 1.0E-4D);
        return source.getY() == feetY || source.getY() == belowFeetY;
    }

    private static double effectiveRange(Minecraft client) {
        return Math.min(RANGE.get(), client.player.blockInteractionRange());
    }

    private static boolean withinFov(Minecraft client, Vec3 point) {
        return MathUtils.withinFov(
                MathUtils.viewAngle(
                        client.player.getEyePosition(), client.player.getLookAngle(), point),
                FOV.get());
    }

    private static boolean ready(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        return ClientReady.aliveGameplay(client, currentPlayer);
    }

    public static boolean isBusy() {
        return activePlan != null;
    }

    private static void restoreMaterial(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        boolean ownedRotation = activePlan != null;
        if (client != null
                && currentPlayer != null
                && activeHand == InteractionHand.MAIN_HAND
                && activeHotbarSlot >= 0
                && originalHotbarSlot >= 0
                && currentPlayer.getInventory().getSelectedSlot() == activeHotbarSlot) {
            currentPlayer.getInventory().setSelectedSlot(originalHotbarSlot);
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.ANTI_LAVA);
        activePlan = null;
        activeHand = null;
        activeHotbarSlot = -1;
        originalHotbarSlot = -1;
        placementPhase = PlacementPhase.IDLE;
        actionDelayRemainingTicks = 0;
        if (ownedRotation) {
            SilentPacketRotation.reset();
        }
    }

    private static void reset(Minecraft client) {
        if (isBusy()) {
            restoreMaterial(client);
        }
    }

    private static void clearPending() {
        pendingSource = null;
        pendingDetectedAtMs = 0L;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AntiLava: "
                        + (ENABLED.get() ? "enabled" : "disabled")
                        + ", delay: "
                        + DELAY_MIN.get()
                        + "-"
                        + DELAY_MAX.get()
                        + " ticks"
                        + ", range: "
                        + format(RANGE.get())
                        + ", fov: "
                        + format(FOV.get())
                        + " degrees"
                        + ", material: offhand first."
                        + " Usage: .moons antilava <enable|disable|delay 1-20 [1-20]|range 1-6|fov 1-360>.");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset(client);
        clearPending();
        return showStatus(client);
    }

    public static int setDelay(Minecraft client, int min, int max) {
        DELAY_MIN.set(Math.min(min, max));
        DELAY_MAX.set(Math.max(min, max));
        reset(client);
        return showStatus(client);
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        reset(client);
        return showStatus(client);
    }

    public static int setFov(Minecraft client, double value) {
        FOV.set(value);
        reset(client);
        clearPending();
        return showStatus(client);
    }

    private static void normalizeDelay() {
        int low = Math.min(DELAY_MIN.get(), DELAY_MAX.get());
        int high = Math.max(DELAY_MIN.get(), DELAY_MAX.get());
        DELAY_MIN.set(low);
        DELAY_MAX.set(high);
    }

    private static String format(double value) {
        return value == (long) value ? Long.toString((long) value) : Double.toString(value);
    }

    private record PlacementPlan(BlockPos source, BlockHitResult hit) {}

    private record PlacementMaterial(InteractionHand hand, int hotbarSlot) {}

    private enum PlacementPhase {
        IDLE,
        WAITING_DELAY,
        TURNING_TO_PLACE,
        WAITING_FOR_PLACE_ROTATION,
        WAITING_FOR_PLACE_PACKET,
        TURNING_BACK_TO_CAMERA,
        WAITING_FOR_RETURN_ROTATION
    }
}
