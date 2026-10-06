package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.compat.math.Mth;
import com.blanoir.moons.client.compat.math.VecMath;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.placement.PlacementCoordinator;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
import com.blanoir.moons.client.manager.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.inventory.LegacyItems;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.rotation.aim.LavaSupportTargetSelector;
import com.blanoir.moons.client.utils.world.FluidQueries;
import com.blanoir.moons.client.utils.world.LegacyWorld;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.LegacyPlacement;
import com.blanoir.moons.client.utils.world.placement.PlacementRaycast;

import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/** Covers a newly placed hostile lava source with a hotbar block. */
public final class AntiLava {
    private static final PlacementRaycast RAYS = new PlacementRaycast("antilava");
    private static final double ENEMY_PLACE_REACH = 5.0D;
    private static final double ENEMY_LOOK_DOT = -0.10D;
    private static final double DEFAULT_FOV = 180.0D;
    private static final int ROTATION_SMOOTH_TICKS = 1;
    private static final long EVENT_LIFETIME_MS = 2000L;
    private static final long SAME_SOURCE_DEBOUNCE_MS = 500L;
    private static final double[] FACE_SAMPLES = {0.22D, 0.5D, 0.78D};
    private static final EnumFacing[] SUPPORT_FACES = {
        EnumFacing.UP,
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.WEST,
        EnumFacing.EAST,
        EnumFacing.DOWN
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
    private static int activeHotbarSlot = -1;
    private static int originalHotbarSlot = -1;
    private static PlacementPhase placementPhase = PlacementPhase.IDLE;
    private static int actionDelayRemainingTicks;

    private AntiLava() {}

    public static void init() {
        normalizeDelay();
        EventBus.CLIENT_CONTEXT_CHANGED.register("AntiLava.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.ANTI_LAVA, AntiLava::isBusy);
        EventBus.PLAYER_UPDATE.register("AntiLava.thePlayerUpdate", AntiLava::tick);
    }

    /** Called from ClientLevel block-update hooks; state is only acted on next tick. */
    public static void onBlockUpdate(BlockPos pos, IBlockState state) {
        if (!ENABLED.get()
                || pos == null
                || state == null
                || !LegacyWorld.fluid(state).is(Material.lava)
                || !LegacyWorld.fluid(state).isSource()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pos.equals(lastSource) && now - lastSourceAtMs < SAME_SOURCE_DEBOUNCE_MS) {
            return;
        }
        pendingSource = new BlockPos(pos);
        pendingDetectedAtMs = now;
        lastSource = new BlockPos(pos);
        lastSourceAtMs = now;
    }

    private static void tick(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!ENABLED.get() || !ClientReady.aliveGameplay(client)) {
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

        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.ANTI_LAVA)) {
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
                || !withinFov(client, VecMath.atCenterOf(source))
                || responsibleEnemy(client, source) == null) {
            return;
        }

        PlacementMaterial material = findPlacementMaterial(client);
        MovingObjectPosition hit = findSupportHit(client, source);
        if (material == null || hit == null) {
            return;
        }

        beginPlacement(client, new PlacementPlan(source, hit), material);
    }

    private static void beginPlacement(
            Minecraft client, PlacementPlan plan, PlacementMaterial material) {
        PlacementCoordinator.yieldTo(PlacementCoordinator.Owner.ANTI_LAVA, client);
        activePlan = plan;
        activeHotbarSlot = material.hotbarSlot();
        originalHotbarSlot = client.thePlayer.inventory.currentItem;
        if (activeHotbarSlot != originalHotbarSlot) {
            client.thePlayer.inventory.currentItem = activeHotbarSlot;
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
                    activePlan.hit().hitVec,
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
            if (MathUtils.withinRotationTolerance(
                    client.thePlayer.rotationYaw - SilentPacketRotation.getSentYaw(),
                    client.thePlayer.rotationPitch - SilentPacketRotation.getSentPitch(),
                    0.35F)) {
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
                || !ClientReady.aliveGameplay(client)
                || plan == null
                || !isLavaSource(client, plan.source())
                || isUnderPlayerFeet(client, plan.source())
                || !withinPlayerRange(client, plan.source())
                || (client.thePlayer.inventory.currentItem != activeHotbarSlot)) {
            beginReturnRotation(client);
            return;
        }

        MovingObjectPosition currentHit = findSupportHit(client, plan.source());
        if (currentHit != null && canPlace(client, activeHotbarSlot, currentHit)) {
            boolean result = useOnSilently(client, currentHit);
            if (result) {
                MinecraftClientAccess.animatePlacement(client.thePlayer, true);
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

    private static EntityPlayer responsibleEnemy(Minecraft client, BlockPos source) {
        Vec3 center = VecMath.atCenterOf(source);
        EntityPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (EntityPlayer target : client.theWorld.playerEntities) {
            if (!Targeting.isValidTargetPlayer(client, target)) {
                continue;
            }
            Vec3 toSource = center.subtract(target.getPositionEyes(1.0F));
            double distance = toSource.lengthVector();
            if (distance > ENEMY_PLACE_REACH || distance < 1.0E-5D) {
                continue;
            }
            double lookDot =
                    target.getLookVec().dotProduct(VecMath.scale(toSource, 1.0D / distance));
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
        InventoryPlayer inventory = client.thePlayer.inventory;
        int selected = inventory.currentItem;
        if (isValidBlock(client, inventory.getStackInSlot(selected))) {
            return new PlacementMaterial(selected);
        }

        int bestSlot = -1;
        int bestCount = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (isValidBlock(client, stack) && stack.stackSize > bestCount) {
                bestSlot = slot;
                bestCount = stack.stackSize;
            }
        }
        return bestSlot == -1 ? null : new PlacementMaterial(bestSlot);
    }

    private static boolean isValidBlock(Minecraft client, ItemStack stack) {
        if (LegacyItems.empty(stack)
                || !(stack.getItem() instanceof ItemBlock blockItem)
                || blockItem.getBlock() instanceof BlockFalling) {
            return false;
        }
        IBlockState state = blockItem.getBlock().getDefaultState();
        return BlockPlacementUtils.hasSolidPlacementShape(client.theWorld, state);
    }

    private static boolean canPlace(Minecraft client, int hotbarSlot, MovingObjectPosition hit) {
        ItemStack stack =
                hotbarSlot >= 0
                        ? client.thePlayer.inventory.getStackInSlot(hotbarSlot)
                        : LegacyItems.EMPTY;
        if (!isValidBlock(client, stack) || !(stack.getItem() instanceof ItemBlock blockItem)) {
            return false;
        }
        LegacyPlacement.Context use = new LegacyPlacement.Context(client.thePlayer, stack, hit);
        return LegacyPlacement.state(blockItem.getBlock(), use) != null;
    }

    private static boolean useOnSilently(Minecraft client, MovingObjectPosition hit) {
        if (!RAYS.canUse(client, hit)) return false;
        float cameraYaw = client.thePlayer.rotationYaw;
        float cameraPitch = client.thePlayer.rotationPitch;
        client.thePlayer.rotationYaw = SilentPacketRotation.getInteractionYaw(client);
        client.thePlayer.rotationPitch = SilentPacketRotation.getInteractionPitch(client);
        try {
            return client.playerController.onPlayerRightClick(
                    client.thePlayer,
                    client.theWorld,
                    client.thePlayer.getHeldItem(),
                    hit.getBlockPos(),
                    hit.sideHit,
                    hit.hitVec);
        } finally {
            client.thePlayer.rotationYaw = cameraYaw;
            client.thePlayer.rotationPitch = cameraPitch;
        }
    }

    private static MovingObjectPosition findSupportHit(Minecraft client, BlockPos source) {
        if (!isLavaSource(client, source)) return null;
        return LavaSupportTargetSelector.select(
                RAYS,
                client,
                source,
                client.thePlayer.getPositionEyes(1.0F),
                effectiveRange(client),
                SUPPORT_FACES,
                FACE_SAMPLES);
    }

    private static boolean isLavaSource(Minecraft client, BlockPos source) {
        IBlockState state = client.theWorld.getBlockState(source);
        return FluidQueries.isSource(LegacyWorld.fluid(state), Material.lava);
    }

    private static boolean withinPlayerRange(Minecraft client, BlockPos source) {
        return client.thePlayer.getPositionEyes(1.0F).squareDistanceTo(VecMath.atCenterOf(source))
                <= effectiveRange(client) * effectiveRange(client);
    }

    /**
     * Ignores lava occupying the player's feet cell or the block immediately
     * below their footprint. Horizontal AxisAlignedBB overlap also handles standing on
     * a block edge instead of relying only on the player's center position.
     */
    private static boolean isUnderPlayerFeet(Minecraft client, BlockPos source) {
        AxisAlignedBB playerBox = client.thePlayer.getEntityBoundingBox();
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
        return Math.min(
                RANGE.get(), Minecraft.getMinecraft().playerController.getBlockReachDistance());
    }

    private static boolean withinFov(Minecraft client, Vec3 point) {
        return MathUtils.withinFov(
                MathUtils.viewAngle(
                        client.thePlayer.getPositionEyes(1.0F),
                        client.thePlayer.getLookVec(),
                        point),
                FOV.get());
    }

    public static boolean isBusy() {
        return activePlan != null;
    }

    private static void restoreMaterial(Minecraft client) {
        var currentPlayer = client == null ? null : client.thePlayer;
        boolean ownedRotation = activePlan != null;
        if (client != null
                && currentPlayer != null
                && activeHotbarSlot >= 0
                && originalHotbarSlot >= 0
                && currentPlayer.inventory.currentItem == activeHotbarSlot) {
            currentPlayer.inventory.currentItem = originalHotbarSlot;
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.ANTI_LAVA);
        activePlan = null;
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
                        + ", material: hotbar."
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

    private record PlacementPlan(BlockPos source, MovingObjectPosition hit) {}

    private record PlacementMaterial(int hotbarSlot) {}

    private enum PlacementPhase {
        IDLE,
        WAITING_DELAY,
        TURNING_TO_PLACE,
        WAITING_FOR_PLACE_ROTATION,
        WAITING_FOR_PLACE_PACKET,
        TURNING_BACK_TO_CAMERA,
        WAITING_FOR_RETURN_ROTATION
    }

    /** End this feature's pending work without changing its configured toggle. */
    public static void shutdown(Minecraft client) {
        reset(client);
        clearPending();
    }
}
