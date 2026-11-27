package com.blanoir.tempos.client.module.impl.player;


import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.Settings;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.movement.PlayerUpdateEvent;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.placement.PlacementRayPolicy;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.framework.PlacementSettings;
import com.blanoir.tempos.client.module.impl.player.antilava.LavaSupportTargetSelector;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.math.AngleMath;
import com.blanoir.tempos.client.utils.math.RandomRange;
import com.blanoir.tempos.client.utils.math.ViewCone;
import com.blanoir.tempos.client.utils.text.NumberText;
import com.blanoir.tempos.client.utils.world.FluidQueries;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

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
@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Covers a nearby lava source with a block from your hotbar or offhand.")
public final class AntiLava extends ClientFeature {
    public static final AntiLava INSTANCE = new AntiLava();

    private final PlacementRayPolicy rays = new PlacementRayPolicy("antilava");
    private static final double ENEMY_PLACE_REACH = 5.0D;
    private static final double ENEMY_LOOK_DOT = -0.10D;
    private static final double DEFAULT_FOV = 180.0D;
    private static final int ROTATION_SMOOTH_TICKS = 1;
    private static final long EVENT_LIFETIME_MS = 2000L;
    private static final long SAME_SOURCE_DEBOUNCE_MS = 500L;
    private final double[] faceSamples = {0.22D, 0.5D, 0.78D};
    private final Direction[] supportFaces = {
        Direction.UP,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST,
        Direction.DOWN
    };

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("antilava.enabled").defaultValue(false).build();

    private final IntSetting delayMin =
            new IntSetting.Builder()
                    .name("antilava.delay.min")
                    .defaultValue(1)
                    .range(1, 20)
                    .build();

    private final IntSetting delayMax =
            new IntSetting.Builder()
                    .name("antilava.delay.max")
                    .defaultValue(3)
                    .range(1, 20)
                    .build();

    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("antilava.range")
                    .defaultValue(4.5D)
                    .range(1.0D, 6.0D)
                    .build();

    private final DoubleSetting fov =
            new DoubleSetting.Builder()
                    .name("antilava.fov")
                    .defaultValue(DEFAULT_FOV)
                    .range(1.0D, 360.0D)
                    .build();

    private volatile BlockPos pendingSource;
    private volatile long pendingDetectedAtMs;
    private BlockPos lastSource;
    private long lastSourceAtMs;
    private PlacementPlan activePlan;
    private InteractionHand activeHand;
    private int activeHotbarSlot = -1;
    private int originalHotbarSlot = -1;
    private PlacementPhase placementPhase = PlacementPhase.IDLE;
    private int actionDelayRemainingTicks;

    private AntiLava() {
        super("antilava", "AntiLava");
    }

    @Override
    public void registerEvents() {
        normalizeDelay();
        EventBus.CLIENT_CONTEXT_CHANGED.register("AntiLava.context", event -> shutdown(null));
        PlacementCoordinator.register(PlacementCoordinator.Owner.ANTI_LAVA, this::isBusy);
        EventBus.PLAYER_UPDATE.register("AntiLava.playerUpdate", this::tick);
    }

    /** Called from ClientLevel block-update hooks; state is only acted on next tick. */
    public void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!this.enabled.get()
                || pos == null
                || state == null
                || !state.getFluidState().is(FluidTags.LAVA)
                || !state.getFluidState().isSource()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (pos.equals(this.lastSource) && now - this.lastSourceAtMs < SAME_SOURCE_DEBOUNCE_MS) {
            return;
        }
        this.pendingSource = pos.immutable();
        this.pendingDetectedAtMs = now;
        this.lastSource = pos.immutable();
        this.lastSourceAtMs = now;
    }

    private void tick(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!this.enabled.get() || !ClientReady.aliveGameplay(client)) {
            if (isBusy()) {
                reset(client);
            }
            clearPending();
            return;
        }

        if (this.activePlan != null) {
            tickPlacement(client);
            return;
        }

        if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.ANTI_LAVA)) {
            return;
        }
        processPending(client);
    }

    private void processPending(Minecraft client) {

        BlockPos source = this.pendingSource;
        long detectedAt = this.pendingDetectedAtMs;
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

    private void beginPlacement(Minecraft client, PlacementPlan plan, PlacementMaterial material) {
        PlacementCoordinator.yieldTo(PlacementCoordinator.Owner.ANTI_LAVA, client);
        this.activePlan = plan;
        this.activeHand = material.hand();
        this.activeHotbarSlot = material.hotbarSlot();
        this.originalHotbarSlot = client.player.getInventory().getSelectedSlot();
        if (this.activeHand == InteractionHand.MAIN_HAND
                && this.activeHotbarSlot != this.originalHotbarSlot) {
            client.player.getInventory().setSelectedSlot(this.activeHotbarSlot);
        }

        CombatInputController.suppressAttack(client, CombatInputController.Owner.ANTI_LAVA);
        this.actionDelayRemainingTicks =
                RandomRange.betweenInclusive(this.delayMin.get(), this.delayMax.get());
        this.placementPhase = PlacementPhase.WAITING_DELAY;
    }

    private void tickPlacement(Minecraft client) {
        if (this.placementPhase == PlacementPhase.WAITING_DELAY) {
            if (--this.actionDelayRemainingTicks > 0) {
                return;
            }
            this.placementPhase = PlacementPhase.TURNING_TO_PLACE;
            SilentPacketRotation.beginRotation(
                    client,
                    this.activePlan.hit().getLocation(),
                    ROTATION_SMOOTH_TICKS,
                    SilentPacketRotation.Mode.INSTANT,
                    () -> this.placementPhase = PlacementPhase.WAITING_FOR_PLACE_ROTATION);
        }

        if (this.placementPhase == PlacementPhase.TURNING_TO_PLACE
                || this.placementPhase == PlacementPhase.TURNING_BACK_TO_CAMERA) {
            return;
        }

        if (this.placementPhase == PlacementPhase.WAITING_FOR_RETURN_ROTATION) {
            if (!SilentPacketRotation.isRotationPacketSent()) {
                return;
            }
            if (AngleMath.withinRotationTolerance(
                    client.player.getYRot() - SilentPacketRotation.getSentYaw(),
                    client.player.getXRot() - SilentPacketRotation.getSentPitch(),
                    0.35F)) {
                restoreMaterial(client);
            } else {
                beginReturnRotation(client);
            }
            return;
        }

        if (this.placementPhase == PlacementPhase.WAITING_FOR_PLACE_PACKET) {
            if (SilentPacketRotation.isRotationPacketSent()) {
                beginReturnRotation(client);
            }
            return;
        }

        if (this.placementPhase != PlacementPhase.WAITING_FOR_PLACE_ROTATION) {
            return;
        }

        PlacementPlan plan = this.activePlan;
        if (!this.enabled.get()
                || !ClientReady.aliveGameplay(client)
                || plan == null
                || !isLavaSource(client, plan.source())
                || isUnderPlayerFeet(client, plan.source())
                || !withinPlayerRange(client, plan.source())
                || (this.activeHand == InteractionHand.MAIN_HAND
                        && client.player.getInventory().getSelectedSlot()
                                != this.activeHotbarSlot)) {
            beginReturnRotation(client);
            return;
        }

        BlockHitResult currentHit = findSupportHit(client, plan.source());
        if (currentHit != null
                && canPlace(client, this.activeHand, this.activeHotbarSlot, currentHit)) {
            InteractionResult result = useOnSilently(client, this.activeHand, currentHit);
            if (result.consumesAction()) {
                MinecraftClientAccess.animatePlacement(client.player, this.activeHand, true);
            }
        }
        this.placementPhase = PlacementPhase.WAITING_FOR_PLACE_PACKET;
    }

    private void beginReturnRotation(Minecraft client) {
        this.placementPhase = PlacementPhase.TURNING_BACK_TO_CAMERA;
        SilentPacketRotation.beginReturnToCamera(
                client,
                ROTATION_SMOOTH_TICKS,
                () -> this.placementPhase = PlacementPhase.WAITING_FOR_RETURN_ROTATION);
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

    private InteractionResult useOnSilently(
            Minecraft client, InteractionHand hand, BlockHitResult hit) {
        if (!this.rays.canUse(client, hit)) return InteractionResult.PASS;
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

    private BlockHitResult findSupportHit(Minecraft client, BlockPos source) {
        if (!isLavaSource(client, source)) return null;
        return LavaSupportTargetSelector.select(
                this.rays,
                client,
                source,
                client.player.getEyePosition(),
                effectiveRange(client),
                this.supportFaces,
                this.faceSamples);
    }

    private static boolean isLavaSource(Minecraft client, BlockPos source) {
        BlockState state = client.level.getBlockState(source);
        return FluidQueries.isSource(state.getFluidState(), FluidTags.LAVA);
    }

    private boolean withinPlayerRange(Minecraft client, BlockPos source) {
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

    private double effectiveRange(Minecraft client) {
        return Math.min(this.range.get(), client.player.blockInteractionRange());
    }

    private boolean withinFov(Minecraft client, Vec3 point) {
        return ViewCone.withinFov(
                ViewCone.viewAngle(
                        client.player.getEyePosition(), client.player.getLookAngle(), point),
                this.fov.get());
    }

    public boolean isBusy() {
        return this.activePlan != null;
    }

    private void restoreMaterial(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        boolean ownedRotation = this.activePlan != null;
        if (client != null
                && currentPlayer != null
                && this.activeHand == InteractionHand.MAIN_HAND
                && this.activeHotbarSlot >= 0
                && this.originalHotbarSlot >= 0
                && currentPlayer.getInventory().getSelectedSlot() == this.activeHotbarSlot) {
            currentPlayer.getInventory().setSelectedSlot(this.originalHotbarSlot);
        }
        CombatInputController.releaseAttack(client, CombatInputController.Owner.ANTI_LAVA);
        this.activePlan = null;
        this.activeHand = null;
        this.activeHotbarSlot = -1;
        this.originalHotbarSlot = -1;
        this.placementPhase = PlacementPhase.IDLE;
        this.actionDelayRemainingTicks = 0;
        if (ownedRotation) {
            SilentPacketRotation.reset();
        }
    }

    private void reset(Minecraft client) {
        if (isBusy()) {
            restoreMaterial(client);
        }
    }

    private void clearPending() {
        this.pendingSource = null;
        this.pendingDetectedAtMs = 0L;
    }

    public int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AntiLava: "
                        + (this.enabled.get() ? "enabled" : "disabled")
                        + ", delay: "
                        + this.delayMin.get()
                        + "-"
                        + this.delayMax.get()
                        + " ticks"
                        + ", range: "
                        + NumberText.compactDouble(this.range.get())
                        + ", fov: "
                        + NumberText.compactDouble(this.fov.get())
                        + " degrees"
                        + ", material: offhand first."
                        + " Usage: .tempos antilava <enable|disable|delay 1-20 [1-20]|range 1-6|fov 1-360>.");
        return 1;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        reset(client);
        clearPending();
        return showStatus(client);
    }

    public int setDelay(Minecraft client, int min, int max) {
        this.delayMin.set(Math.min(min, max));
        this.delayMax.set(Math.max(min, max));
        reset(client);
        return showStatus(client);
    }

    public int setRange(Minecraft client, double value) {
        this.range.set(value);
        reset(client);
        return showStatus(client);
    }

    public int setFov(Minecraft client, double value) {
        this.fov.set(value);
        reset(client);
        clearPending();
        return showStatus(client);
    }

    private void normalizeDelay() {
        int low = Math.min(this.delayMin.get(), this.delayMax.get());
        int high = Math.max(this.delayMin.get(), this.delayMax.get());
        this.delayMin.set(low);
        this.delayMax.set(high);
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

    /** End this feature's pending work without changing its configured toggle. */
    public void shutdown(Minecraft client) {
        reset(client);
        clearPending();
    }

    @Override
    public boolean isEnabled() {
        return Settings.getBoolean("antilava.enabled", false);
    }

    @Override
    protected String statusTag() {
        return "Predict";
    }

    @Override
    protected Setting[] defineSettings() {
        return PlacementSettings.append(
                "antilava",
                new Setting[] {
                    delayMin.describeStoredRange(
                            "delay", "Delay ticks", delayMax, 1, this::setDelay),
                    range.describeStored("range", "Range", .1, this::setRange),
                    fov.describeStored("fov", "FOV", 1, this::setFov)
                });
    }
}
