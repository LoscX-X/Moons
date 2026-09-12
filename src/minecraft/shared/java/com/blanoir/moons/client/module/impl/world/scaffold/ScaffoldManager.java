package com.blanoir.moons.client.module.impl.world.scaffold;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.frame.FrameEvent;
import com.blanoir.moons.client.event.frame.HudRenderEvent;
import com.blanoir.moons.client.event.frame.WorldRenderEvent;
import com.blanoir.moons.client.event.movement.MoveInputEvent;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationManager;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.render.WorldOverlayRenderer;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.prediction.TrajectoryPrediction;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Minecraft 26.x scaffold state, placement and rotation engine.
 *
 * <p>The original module targets 1.8. This port preserves its rotation modes,
 * rotation-speed limiter, Keep-Y/Telly stages, Tower state machines, 1/32 face
 * scan, movement correction, safe-walk, item spoof and multi-place behavior while
 * routing interactions through modern vanilla {@code useItemOn}.</p>
 */
public final class ScaffoldManager {
    private static final int SEARCH_RADIUS = 4;
    private static final int MAX_FACE_CANDIDATES = 32;
    private static final double[] PRIMARY_FACE_OFFSETS = {
        0.5D, 0.375D, 0.625D, 0.25D, 0.75D, 0.125D, 0.875D, 0.0625D, 0.9375D
    };
    private static final double[] STANDARD_FACE_OFFSETS = {
        0.03125D, 0.09375D, 0.15625D, 0.21875D,
        0.28125D, 0.34375D, 0.40625D, 0.46875D,
        0.53125D, 0.59375D, 0.65625D, 0.71875D,
        0.78125D, 0.84375D, 0.90625D, 0.96875D
    };
    private static final double[] DENSE_FACE_OFFSETS = {
        0.015625D, 0.03125D, 0.09375D, 0.15625D, 0.21875D, 0.28125D, 0.34375D, 0.40625D, 0.46875D,
        0.53125D, 0.59375D, 0.65625D, 0.71875D, 0.78125D, 0.84375D, 0.90625D, 0.96875D, 0.984375D
    };
    private static final BooleanSetting ENABLED = bool("scaffold.enabled", false);
    private static final ModeSetting<ScaffoldMode> MODE =
            new ModeSetting.Builder<ScaffoldMode>()
                    .name("scaffold.mode")
                    .defaultValue(ScaffoldMode.LEGIT)
                    .option(ScaffoldMode.LEGIT, "legit")
                    .option(ScaffoldMode.TELLY, "telly")
                    .option(ScaffoldMode.JUMP, "jump")
                    .build();
    private static final ModeSetting<MoveFix> MOVE_FIX =
            new ModeSetting.Builder<MoveFix>()
                    .name("scaffold.moveFix")
                    .defaultValue(MoveFix.SILENT)
                    .option(MoveFix.NONE, "none")
                    .option(MoveFix.SILENT, "silent")
                    .build();
    private static final ModeSetting<TellyDelay> TELLY_DELAY_MODE =
            new ModeSetting.Builder<TellyDelay>()
                    .name("scaffold.tellyDelayMode")
                    .defaultValue(TellyDelay.FIXED)
                    .option(TellyDelay.FIXED, "fixed")
                    .option(TellyDelay.ADAPTIVE, "adaptive")
                    .build();
    private static final ModeSetting<FaceSampling> FACE_SAMPLING =
            new ModeSetting.Builder<FaceSampling>()
                    .name("scaffold.faceSampling")
                    .defaultValue(FaceSampling.STANDARD)
                    .option(FaceSampling.STANDARD, "standard")
                    .option(FaceSampling.CENTER, "center")
                    .option(FaceSampling.DENSE, "dense")
                    .option(FaceSampling.ADAPTIVE, "adaptive")
                    .build();
    private static final ModeSetting<SprintMode> SPRINT_MODE =
            new ModeSetting.Builder<SprintMode>()
                    .name("scaffold.sprintMode")
                    .defaultValue(SprintMode.VANILLA)
                    .option(SprintMode.NONE, "none")
                    .option(SprintMode.VANILLA, "vanilla")
                    .build();
    private static final ModeSetting<TowerMode> TOWER =
            new ModeSetting.Builder<TowerMode>()
                    .name("scaffold.tower")
                    .defaultValue(TowerMode.VANILLA)
                    .option(TowerMode.NONE, "none")
                    .option(TowerMode.VANILLA, "vanilla")
                    .build();

    private static final DoubleSetting TELLY_START_SPEED =
            decimal("scaffold.tellyStartSpeed", 85.0D, 1.0D, 180.0D);
    private static final DoubleSetting TELLY_RETURN_SPEED =
            decimal("scaffold.tellyReturnSpeed", 45.0D, 1.0D, 90.0D);
    private static final DoubleSetting TELLY_TRACK_SPEED =
            decimal("scaffold.tellyTrackSpeed", 45.0D, 1.0D, 180.0D);
    private static final DoubleSetting TELLY_PLACE_ANGLE =
            decimal("scaffold.tellyPlaceAngle", 6.0D, 0.5D, 20.0D);
    private static final DoubleSetting RENDER_RESPONSE =
            decimal("scaffold.renderRotationResponse", 18.0D, 2.0D, 40.0D);
    private static final DoubleSetting LEGIT_EDGE_OFFSET =
            decimal("scaffold.legitEdgeOffset", 0.0D, 0.0D, 0.3D);

    private static final BooleanSetting LEGIT_SNEAK_CHECK = bool("scaffold.legitSneakCheck", false);
    private static final BooleanSetting KEEP_Y_ON_PRESS = bool("scaffold.keepYOnPress", false);
    private static final BooleanSetting SWING = bool("scaffold.swing", true);
    private static final BooleanSetting ITEM_SPOOF = bool("scaffold.itemSpoof", false);
    private static final BooleanSetting BLOCK_COUNTER = bool("scaffold.blockCounter", true);
    private static final BooleanSetting RENDER = bool("scaffold.render", true);
    private static final BooleanSetting SHOW_SHADE = bool("scaffold.showTargetShade", false);
    private static final BooleanSetting OUTLINE_FADE = bool("scaffold.outlineFadeOut", true);
    private static final BooleanSetting TELLY_BPS_LIMIT =
            bool("scaffold.tellyBlocksPerSecondEnabled", true);
    private static final BooleanSetting TELLY_FLAT = bool("scaffold.tellyFlat", false);
    private static final BooleanSetting TELLY_FALL_RESCUE = bool("scaffold.tellyFallRescue", false);

    private static final IntSetting TOWER_FLAT_TICKS = integer("scaffold.towerFlatTicks", 4, 0, 20);
    private static final IntSetting LEGIT_DELAY_MIN =
            integer("scaffold.legitDelayMinMs", 100, 0, 500);
    private static final IntSetting LEGIT_DELAY_MAX =
            integer("scaffold.legitDelayMaxMs", 150, 0, 500);
    private static final IntSetting TELLY_MAX_FORWARD_BLOCKS =
            integer("scaffold.tellyMaxForwardBlocks", 3, 1, 6);
    private static final IntSetting TELLY_PLACE_DELAY =
            integer("scaffold.tellyPlaceDelay", 4, 0, 8);
    private static final IntSetting TELLY_BLOCKS_PER_SECOND_MIN =
            integer("scaffold.tellyBlocksPerSecondMin", 3, 1, 20);
    private static final IntSetting TELLY_BLOCKS_PER_SECOND_MAX =
            integer("scaffold.tellyBlocksPerSecondMax", 4, 1, 20);
    private static final List<PlacedMark> PLACED = new ArrayList<>();
    private static boolean initialized;
    private static BlockPos renderTarget;
    private static BlockHitResult renderHit;
    private static final HotbarLease HOTBAR =
            new HotbarLease("Scaffold", HotbarLease.PRIORITY_PLACEMENT);
    private static final RotationLease ROTATION =
            new RotationLease(
                    "Scaffold",
                    RotationLease.PRIORITY_SCRIPT_PLACEMENT,
                    ScaffoldManager::shouldApplyRotation,
                    ScaffoldManager::shouldCorrectMovement,
                    ScaffoldManager::packetRotation);
    private static int blockCount = -1;
    private static int rotationTick;
    private static int stage;
    private static int startY = 256;
    private static int snapCooldown;
    private static int currentFlatTick;
    private static int rotationZeroCount;
    private static boolean shouldKeepY;
    private static boolean wasInAir;
    private static boolean placedThisJump;
    private static boolean rotationInitialized;
    private static boolean placementRotationStarted;
    private static boolean canRotate;
    private static boolean onAirPlace;
    private static boolean legitSneaking;
    private static long legitUnsneakStartedNanos = -1;
    private static int legitUnsneakDelayMillis = -1;
    private static int tellyBlocksThisJump;
    private static int tellyAirTicks;
    private static BlockPos lastTellyPlacePos;
    private static int lastTellyPlaceTick = Integer.MIN_VALUE;
    private static float outgoingYaw;
    private static float outgoingPitch;
    private static float renderYaw;
    private static float renderPitch;
    private static final PlacementRotations PLACEMENT_ROTATIONS = new PlacementRotations();
    private static Object placementRotationConnection;
    private static boolean silentInputAllowsSprint = true;
    private static double lastServerX;
    private static double lastServerY;
    private static double lastServerZ;
    private static boolean serverPositionValid;
    private static final ForwardSpeedLimiter FORWARD_SPEED = new ForwardSpeedLimiter();
    private static int forwardSampleTick = Integer.MIN_VALUE;
    private static double forwardSampleX;
    private static double forwardSampleZ;
    private static double forwardBps;
    private static int lastTellyReturnTick = Integer.MIN_VALUE;
    private static boolean tellyFlatStarted;
    private static TellyPhase tellyPhase = TellyPhase.SELECT_POINT;
    private static ScaffoldPath scaffoldPath = ScaffoldPath.IDLE;
    private static boolean tellyTriggered;
    private static boolean tellyWasAirborne;
    private static boolean tellyBelowRowRescue;
    private static boolean towerRotationInitialized;
    private static float towerRotationYaw;

    private ScaffoldManager() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "ScaffoldManager.context",
                event -> {
                    disableState(null);
                    ScaffoldPlacementDebugger.context(Minecraft.getInstance());
                    rotationInitialized =
                            placementRotationStarted = towerRotationInitialized = false;
                    serverPositionValid = false;
                    placementRotationConnection = null;
                    PLACEMENT_ROTATIONS.reset();
                    if (enabled() && ClientReady.gameplay(event.client()))
                        enableState(event.client());
                });
        EventBus.PLAYER_UPDATE.register(
                "ScaffoldManager.playerUpdate", ScaffoldManager::playerUpdate);
        EventBus.MOVE_INPUT.register("ScaffoldManager.moveInput", ScaffoldManager::moveInput);
        EventBus.FRAME.register("ScaffoldManager.frame", ScaffoldManager::frame);
        EventBus.HUD_RENDER.register("ScaffoldManager.hud", ScaffoldManager::hud);
        EventBus.WORLD_RENDER.register("ScaffoldManager.render", ScaffoldManager::render);
        EventBus.PACKET_SEND_POST.register(
                "ScaffoldManager.onPacketSendPost", ScaffoldManager::onPacketSendPost);
        EventBus.PACKET_RECEIVE_APPLY.register(
                "ScaffoldManager.debugAlert",
                event -> {
                    if (event.packet()
                            instanceof
                            net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) {
                        ScaffoldPlacementDebugger.alert(Minecraft.getInstance(), chat);
                    }
                });
        normalizeRanges();
        if (enabled()) enableState(Minecraft.getInstance());
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    private static boolean placementSuspended() {
        return com.blanoir.moons.client.utils.world.placement.PlacementCoordinator.busy(
                com.blanoir.moons.client.utils.world.placement.PlacementCoordinator.Owner.BLOCK_IN);
    }

    public static boolean shouldApplyRotation() {
        Minecraft client = Minecraft.getInstance();
        return enabled()
                && !placementSuspended()
                && tellyMode()
                && (jumpMode() || canRotate)
                && rotationInitialized
                && ROTATION.active()
                && ClientReady.gameplay(client)
                && !SilentPacketRotation.shouldApplyRotation()
                && !RotationLease.busyFor(ROTATION);
    }

    public static boolean shouldCorrectMovement() {
        return shouldApplyRotation() && moveFix() == MoveFix.SILENT;
    }

    public static float yaw() {
        return outgoingYaw;
    }

    public static float pitch() {
        return outgoingPitch;
    }

    public static float renderYaw() {
        return rotationInitialized ? renderYaw : outgoingYaw;
    }

    public static float renderPitch() {
        return rotationInitialized ? renderPitch : outgoingPitch;
    }

    public static float movementYaw() {
        return packetRotation().yaw();
    }

    public static Rotation packetRotation() {
        Rotation result =
                ROTATION.commit(
                        new Rotation(outgoingYaw, outgoingPitch),
                        true,
                        moveFix() == MoveFix.SILENT);
        return result != null ? result : RotationManager.start(Minecraft.getInstance());
    }

    private static float sentYaw() {
        return RotationManager.start(Minecraft.getInstance()).yaw();
    }

    private static float sentPitch() {
        return RotationManager.start(Minecraft.getInstance()).pitch();
    }

    /** Acquire before any trajectory/face search can reuse the previous tenure's state. */
    private static boolean acquireRotation(Minecraft client) {
        if (ROTATION.active()) return true;
        Rotation camera = new Rotation(client.player.getYRot(), client.player.getXRot());
        return ROTATION.acquire(
                new RotationRequest(camera.yaw(), camera.pitch(), 1, .35F, null),
                camera,
                start -> {
                    outgoingYaw = renderYaw = start.yaw();
                    outgoingPitch = renderPitch = start.pitch();
                    rotationInitialized =
                            placementRotationStarted = towerRotationInitialized = false;
                    canRotate = false;
                });
    }

    public static boolean cancelManualActions() {
        return enabled() && !placementSuspended() && tellyMode();
    }

    public static boolean cancelUseAction() {
        return cancelManualActions();
    }

    public static boolean shouldSuppressSprint(Minecraft client) {
        return enabled()
                && !placementSuspended()
                && tellyMode()
                && ClientReady.gameplay(client)
                && shouldStopSprint();
    }

    /**
     * Keeps the physical hotbar on the leased block slot while the requested
     * user slot is remembered for rendering and restoration on disable.
     */
    public static boolean handleHotbarSwap(int slot, int offset) {
        if (!cancelManualActions()) return false;
        int clampedOffset = Math.max(-1, Math.min(1, offset));
        if (slot >= 0 && slot <= 8) HOTBAR.userSelected(slot);
        else HOTBAR.userScrolled(clampedOffset);
        return true;
    }

    /** Item used by the first-person hand renderer while ItemSpoof is active. */
    public static ItemStack spoofedItem(ItemStack original) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled() || !tellyMode() || !ITEM_SPOOF.get() || client.player == null)
            return original;
        return HOTBAR.userStack(client, original);
    }

    private static void onPacketSendPost(PacketSendEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer == null) return;
        if (event.connection() != currentPlayer.connection.getConnection()) return;
        if (DEBUGGER.get())
            ScaffoldPlacementDebugger.packet(
                    client,
                    event,
                    new Vec3(lastServerX, lastServerY, lastServerZ),
                    serverPositionValid);
        if (placementRotationConnection != currentPlayer.connection) {
            placementRotationConnection = currentPlayer.connection;
            PLACEMENT_ROTATIONS.reset();
        }
        if (event.packet() instanceof ServerboundUseItemOnPacket) {
            // A locally rejected prediction may still have sent this packet.
            PLACEMENT_ROTATIONS.placementSent();
            return;
        }
        if (!(event.packet() instanceof ServerboundMovePlayerPacket movement)) return;
        if (!RotationManager.observed(event.packet())) return;

        double fallbackX = serverPositionValid ? lastServerX : currentPlayer.getX();
        double fallbackY = serverPositionValid ? lastServerY : currentPlayer.getY();
        double fallbackZ = serverPositionValid ? lastServerZ : currentPlayer.getZ();
        lastServerX = movement.getX(fallbackX);
        lastServerY = movement.getY(fallbackY);
        lastServerZ = movement.getZ(fallbackZ);
        if (movement.hasRotation()) PLACEMENT_ROTATIONS.rotationSent(sentYaw());
        serverPositionValid = true;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        boolean changed = ENABLED.get() != value;
        ENABLED.set(value);
        if (changed && value) enableState(client);
        else if (changed) disableState(client);
        ClientChat.send(client, "Scaffold " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "Scaffold: "
                        + (enabled() ? "enabled" : "disabled")
                        + " | "
                        + scaffoldMode().configName()
                        + " | tower "
                        + towerMode().configName()
                        + " | "
                        + countBlocks(client)
                        + " blocks.");
        return 1;
    }

    public static String statusTag() {
        return scaffoldMode() == ScaffoldMode.LEGIT
                ? "legit"
                : String.format(
                        Locale.ROOT,
                        "%s %.1f BPS %s",
                        jumpMode() ? "jump" : "telly",
                        currentBps(),
                        towerMode().configName());
    }

    public static int setMode(Minecraft client, String value) {
        ScaffoldMode before = scaffoldMode();
        MODE.deserialize(value);
        if (enabled() && scaffoldMode() != before) {
            disableState(client);
            enableState(client);
        }
        return 1;
    }

    public static int setMoveFix(Minecraft c, String v) {
        MOVE_FIX.deserialize(v);
        return 1;
    }

    public static int setSprintMode(Minecraft c, String v) {
        SPRINT_MODE.deserialize(v);
        return 1;
    }

    public static int setTower(Minecraft c, String v) {
        TOWER.deserialize(v);
        return 1;
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static List<String> tellyDelayModeOptions() {
        return TELLY_DELAY_MODE.optionIds();
    }

    public static List<String> faceSamplingOptions() {
        return FACE_SAMPLING.optionIds();
    }

    public static List<String> moveFixOptions() {
        return MOVE_FIX.optionIds();
    }

    public static List<String> sprintModeOptions() {
        return SPRINT_MODE.optionIds();
    }

    public static List<String> towerOptions() {
        return TOWER.optionIds();
    }

    public static boolean legitSelected() {
        return scaffoldMode() == ScaffoldMode.LEGIT;
    }

    public static boolean tellySelected() {
        return tellyMode();
    }

    public static boolean smoothTellySelected() {
        return tellySelected();
    }

    public static boolean tellyBpsLimitSelected() {
        return TELLY_BPS_LIMIT.get();
    }

    public static boolean returningTellySelected() {
        return scaffoldMode() == ScaffoldMode.TELLY;
    }

    public static int setTellyReturnSpeed(Minecraft client, double value) {
        TELLY_RETURN_SPEED.set(value);
        return 1;
    }

    public static int setLegitSneakCheck(Minecraft client, boolean value) {
        releaseLegitSneaking(client);
        LEGIT_SNEAK_CHECK.set(value);
        return 1;
    }

    public static int setLegitEdgeOffset(Minecraft client, double value) {
        LEGIT_EDGE_OFFSET.set(value);
        return 1;
    }

    public static int setTellyStartSpeed(Minecraft c, double v) {
        TELLY_START_SPEED.set(v);
        normalizeRanges();
        return 1;
    }

    public static int setTellyTrackSpeed(Minecraft c, double v) {
        TELLY_TRACK_SPEED.set(v);
        normalizeRanges();
        return 1;
    }

    public static int setTellyDelayMode(Minecraft c, String v) {
        TELLY_DELAY_MODE.deserialize(v);
        return 1;
    }

    public static int setFaceSampling(Minecraft c, String v) {
        FACE_SAMPLING.deserialize(v);
        return 1;
    }

    public static int setTellyPlaceAngle(Minecraft c, double v) {
        TELLY_PLACE_ANGLE.set(v);
        return 1;
    }

    public static int setTellyMaxForwardBlocks(Minecraft c, int v) {
        TELLY_MAX_FORWARD_BLOCKS.set(v);
        return 1;
    }

    public static int setTellyPlaceDelay(Minecraft c, int v) {
        TELLY_PLACE_DELAY.set(v);
        return 1;
    }

    public static int setTellyBlocksPerSecondEnabled(Minecraft c, boolean v) {
        TELLY_BPS_LIMIT.set(v);
        resetTellyBpsState(c);
        return 1;
    }

    public static int setTellyBlocksPerSecond(Minecraft c, int min, int max) {
        TELLY_BLOCKS_PER_SECOND_MIN.set(min);
        TELLY_BLOCKS_PER_SECOND_MAX.set(max);
        normalizeRanges();
        resetTellyBpsState(c);
        return 1;
    }

    public static int setTellyFlat(Minecraft c, boolean v) {
        TELLY_FLAT.set(v);
        tellyFlatStarted = false;
        return 1;
    }

    public static int setTellyFallRescue(Minecraft c, boolean v) {
        TELLY_FALL_RESCUE.set(v);
        return 1;
    }

    public static int setLegitDelay(Minecraft c, int min, int max) {
        LEGIT_DELAY_MIN.set(min);
        LEGIT_DELAY_MAX.set(max);
        normalizeRanges();
        return 1;
    }

    public static int setBlockCounter(Minecraft c, boolean v) {
        BLOCK_COUNTER.set(v);
        return 1;
    }

    private static void tick(Minecraft client) {
        if (!enabled()) return;
        if (placementSuspended()) {
            shutdown(client);
            return;
        }
        if (!ClientReady.gameplay(client)) {
            renderTarget = null;
            renderHit = null;
            return;
        }
        // Legit remains the existing edge-sneak helper. It must never acquire
        // a block slot, send a placement or own server rotation.
        if (scaffoldMode() == ScaffoldMode.LEGIT) {
            renderTarget = null;
            renderHit = null;
            HOTBAR.release(client);
            ROTATION.release();
            rotationInitialized = placementRotationStarted = false;
            return;
        }
        if (SilentPacketRotation.shouldApplyRotation()) return;

        updateGroundState(client);
        updateBlockSlot(client);
        if (jumpMode() && !towerRequested(client) && !tellyBelowRowRescue) tickJump(client);
        else tickTelly(client);
    }

    /** Jump keeps the placement view through takeoff, covered cells and landing. */
    private static void tickJump(Minecraft client) {
        if (TELLY_FALL_RESCUE.get() && !client.player.onGround() && belowTellyTargetRow(client)) {
            enterBelowRowRescue();
            tickTelly(client);
            return;
        }
        if (scaffoldPath == ScaffoldPath.TOWER) rotationInitialized = false;
        scaffoldPath = ScaffoldPath.TELLY;
        towerRotationInitialized = false;
        tellyTriggered = true;
        if (!acquireRotation(client)) return;
        if (!rotationInitialized) {
            publishTellyRotation(client, client.player.getYRot() + 180.0F, 80.0F);
        }
        ROTATION.acquire(new RotationRequest(outgoingYaw, outgoingPitch, 1, .35F, null));
        if (blockCount <= 0 || tellyFeetCovered(client)) {
            clearTellyAim();
            transitionTelly(TellyPhase.SELECT_POINT, "jump; holding backward view");
            return;
        }
        attemptTellyPlacement(
                client,
                new PlacementIntent(nextTellyPlacementPos(client, client.player.position()), false),
                false,
                0);
    }

    /** Telly jump state; target, rotation and placement are rebuilt every tick. */
    private static void tickTelly(Minecraft client) {
        if (tellyBelowRowRescue && client.player.onGround()) {
            finishBelowRowRescue();
        } else if (scaffoldPath == ScaffoldPath.TELLY
                && tellyTriggered
                && TELLY_FALL_RESCUE.get()
                && !client.player.onGround()
                && belowTellyTargetRow(client)) {
            enterBelowRowRescue();
        }

        if (scaffoldPath == ScaffoldPath.TELLY && client.player.onGround() && tellyWasAirborne) {
            finishTellyLanding();
        }

        activateAirborneTelly(client);

        resolveScaffoldPath(client);
        if (scaffoldPath == ScaffoldPath.IDLE) {
            clearTellyAim();
            transitionTelly(TellyPhase.SELECT_POINT, "waiting launch");
            return;
        }

        if (scaffoldPath == ScaffoldPath.TELLY) {
            if (!tellyTriggered) {
                BlockPos belowFeet = tellyFeetPlacementPos(client);
                if (client.player.onGround() || !client.level.getBlockState(belowFeet).isAir()) {
                    clearTellyAim();
                    transitionTelly(TellyPhase.SELECT_POINT, "waiting until feet are over air");
                    return;
                }
                tellyTriggered = true;
                if (TELLY_FLAT.get()) {
                    tellyFlatStarted = true;
                }
            }

            PlacementIntent intent =
                    new PlacementIntent(
                            nextTellyPlacementPos(client, client.player.position()), false);
            int remainingAirDelay =
                    tellyBlocksThisJump == 0
                            ? Math.max(0, effectiveTellyDelay(client, intent) - tellyAirTicks)
                            : 0;
            if (tellyBlocksThisJump > 0 && tellyFeetCovered(client)) {
                clearTellyAim();
                transitionTelly(TellyPhase.SELECT_POINT, "feet covered; waiting landing");
                return;
            }
            attemptTellyPlacement(client, intent, false, remainingAirDelay);
            return;
        }

        Vec3 playerPosition = client.player.position();
        if (scaffoldPath == ScaffoldPath.RESCUE) {
            attemptTellyPlacement(client, belowFeetRescueIntent(playerPosition), false, 0);
        } else if (scaffoldPath == ScaffoldPath.TOWER) {
            boolean verticalTower = !moving(client);
            if (verticalTower) {
                if (!holdTowerRotation(client)) return;
                // Enter vertical Tower with an emitted downward angle before the first use.
                if (!RotationManager.latest().valid() || Math.abs(sentPitch() - 90.0F) > 0.01F) {
                    tellyDebugReason = "waiting for downward rotation packet";
                    return;
                }
            } else {
                towerRotationInitialized = false;
            }
            attemptTellyPlacement(
                    client,
                    new PlacementIntent(desiredPlacementPos(client, playerPosition), false),
                    verticalTower,
                    0);
        }
    }

    /** Tower owns one downward angle across takeoff, landing and empty searches. */
    private static boolean holdTowerRotation(Minecraft client) {
        if (!acquireRotation(client)) return false;
        if (!towerRotationInitialized) {
            float cameraYaw = sentYaw();
            towerRotationYaw =
                    SilentPacketRotation.quantizePacketYaw(
                            cameraYaw, cameraYaw + SilentPacketRotation.packetRotationJitter());
            towerRotationInitialized = true;
        }
        Float variedYaw =
                PLACEMENT_ROTATIONS.variedYaw(
                        towerRotationYaw,
                        SilentPacketRotation.packetRotationJitter(),
                        yaw -> SilentPacketRotation.quantizePacketYaw(sentYaw(), yaw),
                        yaw -> true);
        if (variedYaw != null) towerRotationYaw = variedYaw;
        if (!ROTATION.acquire(new RotationRequest(towerRotationYaw, 90.0F, 1, 0.35F, null)))
            return false;
        publishTellyRotation(client, towerRotationYaw, 90.0F);
        return true;
    }

    /**
     * Airborne state is the primary Telly trigger. Ground movement prediction
     * remains an early takeoff hint, but missing that one input frame must not
     * leave a manually-started or edge-started jump in the idle path.
     */
    private static void activateAirborneTelly(Minecraft client) {
        if (!tellyMode()
                || client.player.onGround()
                || blockCount <= 0
                || tellyBelowRowRescue
                || towerRequested(client)
                || !client.level.getBlockState(tellyFeetPlacementPos(client)).isAir()) {
            return;
        }
        scaffoldPath = ScaffoldPath.TELLY;
        tellyWasAirborne = true;
        if (tellyTriggered) return;
        tellyTriggered = true;
        transitionTelly(TellyPhase.SELECT_POINT, "airborne launch");
        if (TELLY_FLAT.get()) {
            tellyFlatStarted = true;
        }
    }

    /** One tick owns the complete selection, rotation and placement attempt. */
    private static void attemptTellyPlacement(
            Minecraft client,
            PlacementIntent requestedIntent,
            boolean verticalTower,
            int remainingAirDelay) {
        if (!acquireRotation(client)) {
            tellyDebugReason = "rotation lease busy";
            return;
        }
        Vec3 planningPosition = client.player.position();
        Vec3 planningEye = client.player.getEyePosition();
        PlacementIntent intent = requestedIntent;
        PlacementAim aim =
                verticalTower
                        ? findVanillaTowerAim(
                                client, planningPosition, planningEye, intent.desired())
                        : findPlacementAim(client, planningPosition, planningEye, intent.desired());

        renderTarget = aim == null ? intent.desired() : aim.target().placePos();
        renderHit = aim == null ? null : aim.hit();
        transitionTelly(
                TellyPhase.SELECT_POINT, intent.rescue() ? "selected rescue" : "selected point");
        if (aim == null || placementHand(client) == null || blockCount <= 0) {
            if (aim == null) clearTellyAim();
            tellyDebugReason = aim == null ? "no reachable point" : "no blocks";
            return;
        }

        PlacementTarget target = aim.target();
        if (!replaceable(client, target.placePos())) {
            if (!verticalTower) {
                lastTellyPlacePos = target.placePos();
                lastTellyPlaceTick = client.player.tickCount;
            }
            tellyDebugReason = "point already filled";
            return;
        }

        transitionTelly(TellyPhase.ROTATE, "rotating");
        PlacementStep step =
                verticalTower
                        ? PlacementStep.ready(aim)
                        : resolvePlacementStep(
                                client, planningEye, aim, intent.rescue(), remainingAirDelay);
        if (remainingAirDelay > 0) {
            Rotation rotation = step.rotation();
            if (ROTATION.acquire(
                    new RotationRequest(rotation.yaw(), rotation.pitch(), 1, 0.35F, null))) {
                publishTellyRotation(client, rotation.yaw(), rotation.pitch());
                tellyDebugReason = "pre-rotating during air delay";
            } else {
                tellyDebugReason = "rotation lease busy";
            }
            return;
        }
        PlacementStep published = publishPlacementRotation(client, step, verticalTower);
        if (published == null) {
            tellyDebugReason = "rotation lease busy";
            return;
        }
        placementRotationStarted = true;
        renderHit = published.hit();
        double remaining =
                rotationDistance(
                        aim.rotation(), published.rotation().yaw(), published.rotation().pitch());
        if (published.hit() == null || remaining > TELLY_PLACE_ANGLE.get()) {
            tellyDebugReason = String.format(Locale.ROOT, "rotating %.1f deg", remaining);
            return;
        }

        if (PLACEMENT_ROTATIONS.needsSettling()) {
            // The latest already-sent look belongs to this placement. Changing
            // only the upcoming look cannot repair that delta. Wait for a real
            // look send on a tick without UseItemOn, then select/trace again.
            publishSettlingRotation(client, verticalTower);
            tellyDebugReason = "settling repeated rotation delta";
            return;
        }

        transitionTelly(TellyPhase.PLACE, "rotation ready");
        Rotation finalRotation = packetRotation();
        if (!RotationManager.same(finalRotation, published.rotation())) {
            tellyDebugReason = "earlier movement owns this angle; retry next tick";
            return;
        }
        if (place(client, published.target(), published.hit().getLocation())) {
            if (!verticalTower) {
                lastTellyPlacePos = target.placePos();
                lastTellyPlaceTick = client.player.tickCount;
            }
            tellyDebugReason = "placed";
        } else {
            tellyDebugReason = "interaction rejected; retry next tick";
            // useItemOn can send before returning PASS/FAIL. Preserve this
            // attempt's angle for the following vanilla movement packet.
        }
        if (shouldSuppressSprint(client)) client.player.setSprinting(false);
    }

    /**
     * Publishes a micro-jittered placement rotation. The jitter is re-traced so
     * a rotation which no longer intersects the frozen face can never unlock
     * PLACE.
     */
    private static PlacementStep publishPlacementRotation(
            Minecraft client, PlacementStep step, boolean verticalTower) {
        float pubYaw = step.rotation().yaw();
        float pubPitch = step.rotation().pitch();
        BlockHitResult hit = step.hit();
        if (!verticalTower && !jumpMode()) {
            double traceRange = client.player.blockInteractionRange();
            float jitteredYaw =
                    SilentPacketRotation.quantizePacketYaw(
                            pubYaw, pubYaw + SilentPacketRotation.packetRotationJitter());
            float jitteredPitch =
                    SilentPacketRotation.quantizePacketPitch(
                            pubPitch, pubPitch + SilentPacketRotation.packetRotationJitter());
            if (jitteredYaw != pubYaw || jitteredPitch != pubPitch) {
                BlockHitResult jitterHit =
                        hit == null
                                ? null
                                : BlockPlacementUtils.traceFace(
                                        client,
                                        client.player.getEyePosition(),
                                        jitteredYaw,
                                        jitteredPitch,
                                        traceRange,
                                        step.target().support(),
                                        step.target().face());
                if (jitterHit != null) {
                    pubYaw = jitteredYaw;
                    pubPitch = jitteredPitch;
                    hit = jitterHit;
                } else if (hit == null) {
                    pubYaw = jitteredYaw;
                    pubPitch = jitteredPitch;
                }
            }
        }
        final float checkedPitch = pubPitch;
        final boolean requireHit = hit != null;
        Float variedYaw =
                PLACEMENT_ROTATIONS.variedYaw(
                        pubYaw,
                        SilentPacketRotation.packetRotationJitter(),
                        yaw -> SilentPacketRotation.quantizePacketYaw(sentYaw(), yaw),
                        yaw ->
                                !requireHit
                                        || BlockPlacementUtils.traceFace(
                                                        client,
                                                        client.player.getEyePosition(),
                                                        yaw,
                                                        checkedPitch,
                                                        client.player.blockInteractionRange(),
                                                        step.target().support(),
                                                        step.target().face())
                                                != null);
        if (variedYaw == null) {
            // No reachable nearby alternative: publish a look without placing.
            if (!publishSettlingRotation(client, verticalTower)) return null;
            return new PlacementStep(step.target(), new Rotation(outgoingYaw, outgoingPitch), null);
        }
        if (variedYaw != pubYaw && requireHit) {
            hit =
                    BlockPlacementUtils.traceFace(
                            client,
                            client.player.getEyePosition(),
                            variedYaw,
                            pubPitch,
                            client.player.blockInteractionRange(),
                            step.target().support(),
                            step.target().face());
        }
        pubYaw = variedYaw;
        if (verticalTower) towerRotationYaw = pubYaw;
        publishTellyRotation(client, pubYaw, pubPitch);
        if (!ROTATION.acquire(new RotationRequest(outgoingYaw, outgoingPitch, 1, 0.35F, null)))
            return null;
        return new PlacementStep(step.target(), new Rotation(pubYaw, pubPitch), hit);
    }

    private static boolean publishSettlingRotation(Minecraft client, boolean verticalTower) {
        float settledYaw =
                PLACEMENT_ROTATIONS.settlingYaw(SilentPacketRotation.packetRotationJitter());
        publishTellyRotation(client, settledYaw, verticalTower ? 90.0F : sentPitch());
        return ROTATION.acquire(new RotationRequest(outgoingYaw, outgoingPitch, 1, 0.35F, null));
    }

    private static void publishTellyRotation(Minecraft client, float yaw, float pitch) {
        outgoingYaw = yaw;
        outgoingPitch = pitch;
        canRotate = true;
        if (!rotationInitialized) {
            renderYaw = RotationManager.latest().valid() ? sentYaw() : client.player.getYRot();
            renderPitch = RotationManager.latest().valid() ? sentPitch() : client.player.getXRot();
        }
        rotationInitialized = true;
    }

    private static void transitionTelly(TellyPhase next, String reason) {
        tellyPhase = next;
        tellyDebugReason = reason;
    }

    private static void finishTellyLanding() {
        tellyTriggered = false;
        tellyWasAirborne = false;
        scaffoldPath = ScaffoldPath.IDLE;
        clearTellyAim();
        transitionTelly(TellyPhase.SELECT_POINT, "landed; jump ready");
    }

    private static boolean belowTellyTargetRow(Minecraft client) {
        return Mth.floor(client.player.getY()) < startY;
    }

    private static PlacementIntent belowFeetRescueIntent(Vec3 playerPosition) {
        BlockPos belowFeet =
                new BlockPos(
                        Mth.floor(playerPosition.x),
                        Mth.floor(playerPosition.y) - 1,
                        Mth.floor(playerPosition.z));
        return new PlacementIntent(belowFeet, true);
    }

    private static void enterBelowRowRescue() {
        tellyBelowRowRescue = true;
        tellyTriggered = false;
        scaffoldPath = ScaffoldPath.RESCUE;
        transitionTelly(TellyPhase.SELECT_POINT, "below row; rescue");
    }

    private static void finishBelowRowRescue() {
        tellyBelowRowRescue = false;
        tellyTriggered = false;
        tellyWasAirborne = false;
        lastTellyPlacePos = null;
        scaffoldPath = ScaffoldPath.IDLE;
        clearTellyAim();
        transitionTelly(TellyPhase.SELECT_POINT, "rescue landed; tower available");
    }

    private static BlockPos nextTellyPlacementPos(Minecraft client, Vec3 playerPosition) {
        Vec3 velocity = client.player.getDeltaMovement();
        if (Math.abs(velocity.x) > 0.05D && Math.abs(velocity.z) > 0.05D) {
            playerPosition = playerPosition.add(velocity.x, 0.0D, velocity.z);
        }
        return desiredPlacementPos(client, playerPosition);
    }

    private static BlockPos tellyFeetPlacementPos(Minecraft client) {
        return desiredPlacementPos(client, client.player.position());
    }

    /** An accepted placement counts while client prediction/server ack settles. */
    private static boolean tellyFeetCovered(Minecraft client) {
        return tellyPointCovered(client, tellyFeetPlacementPos(client));
    }

    private static boolean tellyPointCovered(Minecraft client, BlockPos point) {
        if (!replaceable(client, point)) return true;
        return point.equals(lastTellyPlacePos)
                && lastTellyPlaceTick != Integer.MIN_VALUE
                && client.player.tickCount - lastTellyPlaceTick <= 3;
    }

    private static void resolveScaffoldPath(Minecraft client) {
        if (tellyBelowRowRescue) {
            scaffoldPath = ScaffoldPath.RESCUE;
            return;
        }
        if (towerRequested(client)) {
            if (scaffoldPath != ScaffoldPath.TOWER) {
                tellyTriggered = false;
                tellyWasAirborne = false;
                tellyBlocksThisJump = 0;
                lastTellyPlacePos = null;
                lastTellyPlaceTick = Integer.MIN_VALUE;
            }
            scaffoldPath = ScaffoldPath.TOWER;
            return;
        }
        towerRotationInitialized = false;
        if (scaffoldPath == ScaffoldPath.TELLY
                && (tellyTriggered || !client.player.onGround() || moving(client))) return;
        if (tellyTriggered) {
            scaffoldPath = ScaffoldPath.TELLY;
            return;
        }
        scaffoldPath = ScaffoldPath.IDLE;
    }

    private static int effectiveTellyDelay(Minecraft client, PlacementIntent intent) {
        if (intent.rescue()) return 0;
        int delay = TELLY_PLACE_DELAY.get();
        if (TELLY_DELAY_MODE.get() == TellyDelay.FIXED) return delay;

        Vec3 velocity = client.player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
        if (horizontalSpeed >= 0.27D) delay--;
        if (horizontalSpeed >= 0.34D) delay--;
        if (velocity.y <= 0.10D) delay--;
        if (velocity.y <= 0.0D) delay = 0;
        return Math.max(0, delay);
    }

    private static void clearTellyAim() {
        ScaffoldPlacementDebugger.clearAim();
        renderTarget = null;
        renderHit = null;
        // Missing a placeable cell between jumps must not turn Tower back to
        // the camera. Exiting Tower clears its initialization before cleanup.
        if (scaffoldPath == ScaffoldPath.TOWER && towerRotationInitialized) return;
        if (jumpMode() && ROTATION.active() && rotationInitialized) {
            canRotate = true;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (ROTATION.active() && rotationInitialized && ClientReady.gameplay(client)) {
            returnTellyRotation(client);
            return;
        }
        releaseTellyRotation();
    }

    private static void returnTellyRotation(Minecraft client) {
        if (lastTellyReturnTick == client.player.tickCount) return;
        lastTellyReturnTick = client.player.tickCount;
        Rotation camera = new Rotation(client.player.getYRot(), client.player.getXRot());
        if (rotationDistance(camera, sentYaw(), sentPitch()) < .5D
                && rotationDistance(camera, renderYaw, renderPitch) < 1.0D) {
            releaseTellyRotation();
            return;
        }
        Rotation next =
                ScaffoldTurn.step(
                        new Rotation(outgoingYaw, outgoingPitch), camera, TELLY_RETURN_SPEED.get());
        float yaw = SilentPacketRotation.quantizePacketYaw(outgoingYaw, next.yaw());
        float pitch = SilentPacketRotation.quantizePacketPitch(outgoingPitch, next.pitch());
        publishTellyRotation(client, yaw, pitch);
        ROTATION.acquire(new RotationRequest(yaw, pitch, 1, .35F, null));
        placementRotationStarted = false;
    }

    private static void releaseTellyRotation() {
        ROTATION.release();
        canRotate = false;
        rotationInitialized = placementRotationStarted = false;
    }

    private static void playerUpdate(PlayerUpdateEvent event) {
        ScaffoldPlacementDebugger.update(
                event.client(),
                DEBUGGER.get() && enabled() && ClientReady.gameplay(event.client()));
        tick(event.client());
        if (DEBUGGER.get() && enabled() && ClientReady.gameplay(event.client())) {
            ScaffoldPlacementDebugger.state(
                    event.client(),
                    "path="
                            + scaffoldPath.configName()
                            + " phase="
                            + tellyPhase.configName()
                            + " reason="
                            + tellyDebugReason);
        }
    }

    private static void moveInput(MoveInputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled() || placementSuspended() || !ClientReady.gameplay(client)) {
            silentInputAllowsSprint = true;
            return;
        }
        if (scaffoldMode() == ScaffoldMode.LEGIT) {
            updateLegitSneaking(client);
            return;
        }
        releaseLegitSneaking(client);
        inspectSilentMovementInput(client);
        boolean tellyTakeoff =
                tellyMode()
                        && client.player.onGround()
                        && moving(client)
                        && !towerRequested(client)
                        && blockCount > 0
                        && scaffoldPath != ScaffoldPath.TOWER
                        && scaffoldPath != ScaffoldPath.RESCUE;
        if (tellyTakeoff) {
            if (scaffoldPath != ScaffoldPath.TELLY) {
                scaffoldPath = ScaffoldPath.TELLY;
                transitionTelly(TellyPhase.SELECT_POINT, "jump requested");
            }
        }

        if (tellyTakeoff) {
            client.player.input.makeJump();
        }
    }

    /** Runs before MoveFix, so releasing forward preserves independently requested strafing. */
    public static Input filterMovementInput(Minecraft client, Input requested) {
        if (!enabled() || placementSuspended() || !tellyMode() || !ClientReady.gameplay(client)) {
            resetTellyBpsState(client);
            return requested;
        }
        int tick = client.player.tickCount;
        if (forwardSampleTick != tick) {
            Vec3 travel =
                    forwardSampleTick == tick - 1
                            ? new Vec3(
                                    client.player.getX() - forwardSampleX,
                                    0,
                                    client.player.getZ() - forwardSampleZ)
                            : client.player.getDeltaMovement();
            double yaw = Math.toRadians(client.player.getYRot());
            forwardBps = (travel.x * -Math.sin(yaw) + travel.z * Math.cos(yaw)) * 20.0D;
            forwardSampleTick = tick;
            forwardSampleX = client.player.getX();
            forwardSampleZ = client.player.getZ();
        }
        if (!TELLY_BPS_LIMIT.get() || towerRequested(client)) {
            FORWARD_SPEED.reset();
            return requested;
        }
        Input filtered =
                FORWARD_SPEED.filter(
                        requested,
                        forwardBps,
                        TELLY_BLOCKS_PER_SECOND_MIN.get(),
                        TELLY_BLOCKS_PER_SECOND_MAX.get());
        if (filtered != requested) {
            silentInputAllowsSprint = false;
            client.player.setSprinting(false);
        }
        return filtered;
    }

    private static void resetTellyBpsState(Minecraft client) {
        FORWARD_SPEED.reset();
        forwardSampleTick = Integer.MIN_VALUE;
        forwardBps = 0.0D;
    }

    /**
     * Keeps vanilla input magnitude while expressing it against the silent
     * server yaw. No velocity, friction or movement packet is rewritten.
     */
    private static void inspectSilentMovementInput(Minecraft client) {
        silentInputAllowsSprint = true;
        if (!shouldCorrectMovement()) return;
        // FeatureHooks has already remapped this tick's input using
        // MoveFix. Scaffold only derives its sprint policy here so
        // the input cannot be rotated a second time.
        Input fixed = client.player.input.keyPresses;
        float forward = impulse(fixed.forward(), fixed.backward());
        float sideways = impulse(fixed.left(), fixed.right());
        if (forward == 0.0F && sideways == 0.0F) return;
        silentInputAllowsSprint = forward > 0.0F;
        boolean sprint = fixed.sprint() && silentInputAllowsSprint;
        if (!silentInputAllowsSprint && client.player.isSprinting()) {
            client.player.setSprinting(false);
        }
        client.player.input.keyPresses =
                new Input(
                        fixed.forward(),
                        fixed.backward(),
                        fixed.left(),
                        fixed.right(),
                        fixed.jump(),
                        fixed.shift(),
                        sprint);
    }

    private static float impulse(boolean positive, boolean negative) {
        if (positive == negative) return 0.0F;
        return positive ? 1.0F : -1.0F;
    }

    /** Edge-sneak state machine for LEGIT mode using modern Input. */
    private static void updateLegitSneaking(Minecraft client) {
        boolean physicalSneak =
                CombatInputController.isPhysicallyDown(client, client.options.keyShift);
        if (LEGIT_SNEAK_CHECK.get() && !physicalSneak) {
            releaseLegitSneaking(client);
            return;
        }
        if (!LEGIT_SNEAK_CHECK.get() && physicalSneak) {
            resetLegitSneakState();
            return;
        }
        if (client.player.getAbilities().flying || placementHand(client) == null) {
            releaseLegitSneaking(client);
            return;
        }

        // With Sneak check, the physical key activates the helper; the helper owns its
        // effective sneak state instead of leaving the player permanently crouched.
        if (LEGIT_SNEAK_CHECK.get()) setInputSneaking(client, legitSneaking);
        double edgeDistance = legitEdgeDistance(client, TrajectoryPrediction.nextInputBox(client));
        boolean jumping = client.player.input.keyPresses.jump();
        boolean needsSneak =
                Double.isNaN(edgeDistance)
                        ? !jumping && client.player.onGround()
                        : edgeDistance > LEGIT_EDGE_OFFSET.get();
        if (needsSneak) {
            startLegitSneaking(client);
        } else if (legitSneaking) {
            continueOrEndLegitSneaking(client);
        }
    }

    private static void startLegitSneaking(Minecraft client) {
        setInputSneaking(client, true);
        legitSneaking = true;
        legitUnsneakStartedNanos = -1;
        legitUnsneakDelayMillis = -1;
    }

    private static void continueOrEndLegitSneaking(Minecraft client) {
        long now = System.nanoTime();
        if (legitUnsneakStartedNanos == -1) {
            legitUnsneakStartedNanos = now;
            legitUnsneakDelayMillis =
                    randomIntInclusive(LEGIT_DELAY_MIN.get(), LEGIT_DELAY_MAX.get());
        }
        if (now - legitUnsneakStartedNanos < legitUnsneakDelayMillis * 1_000_000L) {
            setInputSneaking(client, true);
            return;
        }
        setInputSneaking(client, false);
        resetLegitSneakState();
    }

    private static void releaseLegitSneaking(Minecraft client) {
        if (legitSneaking && client != null && client.player != null) {
            setInputSneaking(
                    client,
                    CombatInputController.isPhysicallyDown(client, client.options.keyShift));
        }
        resetLegitSneakState();
    }

    private static void resetLegitSneakState() {
        legitSneaking = false;
        legitUnsneakStartedNanos = -1;
        legitUnsneakDelayMillis = -1;
    }

    private static void setInputSneaking(Minecraft client, boolean sneaking) {
        Input input = client.player.input.keyPresses;
        client.player.input.keyPresses =
                new Input(
                        input.forward(),
                        input.backward(),
                        input.left(),
                        input.right(),
                        input.jump(),
                        sneaking,
                        input.sprint());
    }

    private static int randomIntInclusive(int min, int max) {
        return min >= max ? min : RandomMath.betweenInclusive(min, max);
    }

    private static double legitEdgeDistance(Minecraft client, AABB predicted) {
        AABB feet =
                new AABB(
                        predicted.minX,
                        predicted.minY - 0.01D,
                        predicted.minZ,
                        predicted.maxX,
                        predicted.minY,
                        predicted.maxZ);
        double centerX = (predicted.minX + predicted.maxX) * 0.5D;
        double centerZ = (predicted.minZ + predicted.maxZ) * 0.5D;
        double closestDistance = Double.MAX_VALUE;
        for (VoxelShape collision : client.level.getCollisions(client.player, feet)) {
            for (AABB box : collision.toAabbs()) {
                double closestX = Mth.clamp(centerX, box.minX, box.maxX);
                double closestZ = Mth.clamp(centerZ, box.minZ, box.maxZ);
                double xDistance = Math.abs(centerX - closestX);
                double zDistance = Math.abs(centerZ - closestZ);
                closestDistance = Math.min(closestDistance, Math.max(xDistance, zDistance));
            }
        }
        return closestDistance == Double.MAX_VALUE ? Double.NaN : closestDistance;
    }

    private static void updateGroundState(Minecraft client) {
        if (client.player.onGround()) {
            tellyBlocksThisJump = 0;
            tellyAirTicks = 0;
            if (wasInAir && placedThisJump && ++rotationZeroCount >= 2) {
                currentFlatTick = TOWER_FLAT_TICKS.get();
                rotationZeroCount = 0;
            }
            placedThisJump = false;
            wasInAir = false;
        } else {
            wasInAir = true;
            if (scaffoldPath == ScaffoldPath.TELLY) {
                tellyWasAirborne = true;
            }
            tellyAirTicks++;
        }
        if (currentFlatTick > 0) currentFlatTick--;
        if (!onAirPlace && client.player.onGround()) onAirPlace = true;
        if (rotationTick > 0) rotationTick--;
        if (snapCooldown > 0) snapCooldown--;
        if (!client.player.onGround()) return;

        if (stage > 0) stage--;
        if (stage < 0) stage++;
        if (stage == 0
                && keepYMode() != KeepYMode.NONE
                && (!KEEP_Y_ON_PRESS.get()
                        || CombatInputController.isPhysicallyDown(client, client.options.keyUse))
                && !CombatInputController.isPhysicallyDown(client, client.options.keyJump)) {
            stage = 1;
        }
        boolean flatTelly = tellyMode() && TELLY_FLAT.get();
        if (flatTelly && tellyTriggered && !tellyFlatStarted) {
            startY = Mth.floor(client.player.getY());
            tellyFlatStarted = true;
        } else if (!shouldKeepY && (!flatTelly || !tellyFlatStarted)) {
            startY = Mth.floor(client.player.getY());
        }
        shouldKeepY = false;
    }

    private static void updateBlockSlot(Minecraft client) {
        InteractionHand hand = placementHand(client);
        if (hand != null) {
            blockCount = client.player.getItemInHand(hand).getCount();
            return;
        }
        blockCount = 0;
        int slot = client.player.getInventory().getSelectedSlot();
        slot--;
        for (int index = slot; index > slot - 9; index--) {
            int hotbar = Math.floorMod(index, 9);
            ItemStack candidate = client.player.getInventory().getItem(hotbar);
            if (!validBlock(client, candidate)) continue;
            if (HOTBAR.acquire(client, hotbar)) {
                blockCount = candidate.getCount();
                break;
            }
        }
    }

    private static boolean place(Minecraft client, PlacementTarget target, Vec3 hitVec) {
        // Flat keeps its bridge plane until a requested Tower raises the support.
        if (tellyMode()
                && TELLY_FLAT.get()
                && tellyFlatStarted
                && !tellyBelowRowRescue
                && !towerActive(client)
                && target.placePos().getY() != startY - 1) {
            return false;
        }
        InteractionHand hand = placementHand(client);
        if (hand == null || blockCount <= 0) return false;
        var committed = RotationLease.submission();
        ScaffoldPlacementDebugger.begin(
                client,
                target.support(),
                target.face(),
                hitVec,
                committed == null ? null : committed.rotation());
        InteractionResult result;
        try {
            result =
                    client.gameMode.useItemOn(
                            client.player,
                            hand,
                            new BlockHitResult(hitVec, target.face(), target.support(), false));
        } catch (RuntimeException | Error failure) {
            ScaffoldPlacementDebugger.result("threw " + failure.getClass().getSimpleName());
            throw failure;
        }
        ScaffoldPlacementDebugger.result(result.toString());
        if (!result.consumesAction()) return false;
        if (!client.player.getAbilities().instabuild) blockCount--;
        MinecraftClientAccess.animatePlacement(client.player, hand, SWING.get());
        PLACED.add(new PlacedMark(target.placePos(), System.currentTimeMillis()));
        if (towerActive(client)) {
            startY = Math.max(startY, target.placePos().getY() + 1);
        }
        if (!client.player.onGround()) {
            placedThisJump = true;
            if (tellyMode() && !towerActive(client)) {
                tellyBlocksThisJump++;
            }
        }
        return true;
    }

    private static void frame(FrameEvent event) {
        if (!enabled() || !rotationInitialized || event.client() == null) return;
        double delta = Mth.clamp(event.deltaSeconds(), 0.0D, 0.05D);
        float blend = (float) (1.0D - Math.exp(-RENDER_RESPONSE.get() * delta));
        renderYaw += Mth.wrapDegrees(outgoingYaw - renderYaw) * blend;
        renderPitch += (outgoingPitch - renderPitch) * blend;
    }

    private static void hud(HudRenderEvent event) {
        if (!enabled()) return;
        Minecraft client = Minecraft.getInstance();
        if (!ClientReady.gameplay(client) || MinecraftClientAccess.isHudHidden(client)) return;
        if (BLOCK_COUNTER.get()) {
            int count = countBlocks(client);
            String text = count + " block" + (count == 1 ? "" : "s") + " left";
            int x = event.graphics().guiWidth() / 2 + client.font.lineHeight * 2;
            int y = event.graphics().guiHeight() / 2 - client.font.lineHeight / 2;
            event.graphics()
                    .text(client.font, text, x, y, count > 0 ? 0xFFFFFFFF : 0xFFFF5555, true);
        }
        if (DEBUGGER.get() && tellyMode()) {
            drawTellyDebugger(event, client);
        }
    }

    private static void render(WorldRenderEvent event) {
        if (!enabled() || !RENDER.get()) return;
        Minecraft client = Minecraft.getInstance();
        if (!ClientReady.gameplay(client) || MinecraftClientAccess.isHudHidden(client)) return;
        long now = System.currentTimeMillis();
        PLACED.removeIf(mark -> now - mark.time() > 700L);
        List<WorldOverlayRenderer.ColoredBox> boxes = new ArrayList<>();
        for (PlacedMark mark : PLACED) {
            if (mark.pos().equals(renderTarget)) continue;
            float remaining = (float) Mth.clamp(1.0D - (now - mark.time()) / 700.0D, 0.0D, 1.0D);
            float alpha = OUTLINE_FADE.get() ? 0.38F * remaining * remaining : 0.25F;
            ScaffoldTargetRenderer.placed(boxes, mark.pos(), alpha);
        }
        if (renderTarget != null) {
            ScaffoldTargetRenderer.target(boxes, renderTarget, SHOW_SHADE.get());
            if (renderHit != null) ScaffoldTargetRenderer.hit(boxes, renderHit);
        }
        if (boxes.isEmpty()) return;
        Vec3 camera = MinecraftClientAccess.camera(client).position();
        PoseStack pose = event.poseStack();
        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            WorldOverlayRenderer.render(client, pose, boxes, "scaffold-target");
        } finally {
            pose.popPose();
        }
    }

    private static int countBlocks(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) return 0;
        int result = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = currentPlayer.getInventory().getItem(slot);
            if (validBlock(client, stack)) result += stack.getCount();
        }
        ItemStack offhand = currentPlayer.getOffhandItem();
        if (validBlock(client, offhand)) result += offhand.getCount();
        return result;
    }

    private static double currentBps() {
        return Math.max(0.0D, forwardBps);
    }

    private static InteractionHand placementHand(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null) return null;
        if (validBlock(client, currentPlayer.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (validBlock(client, currentPlayer.getOffhandItem())) return InteractionHand.OFF_HAND;
        return null;
    }

    private static boolean validBlock(Minecraft client, ItemStack stack) {
        if (client == null
                || client.level == null
                || stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem item)) return false;
        return !isInteractable(item.getBlock().defaultBlockState())
                && isSolid(client, item.getBlock().defaultBlockState(), BlockPos.ZERO);
    }

    private static boolean moving(Minecraft client) {
        boolean forward = CombatInputController.isPhysicallyDown(client, client.options.keyUp);
        boolean back = CombatInputController.isPhysicallyDown(client, client.options.keyDown);
        boolean left = CombatInputController.isPhysicallyDown(client, client.options.keyLeft);
        boolean right = CombatInputController.isPhysicallyDown(client, client.options.keyRight);
        return forward != back || left != right;
    }

    /** Physical jump requests ascent; automatic Telly jumps keep the Flat plane. */
    private static boolean towerRequested(Minecraft client) {
        return towerMode() != TowerMode.NONE
                && CombatInputController.isPhysicallyDown(client, client.options.keyJump)
                && !hasCollisionAbove(client);
    }

    private static boolean towerActive(Minecraft client) {
        return scaffoldPath == ScaffoldPath.TOWER && towerRequested(client);
    }

    private static BlockPos desiredPlacementPos(Minecraft client, Vec3 playerPosition) {
        int playerY = Mth.floor(playerPosition.y);
        int targetX = Mth.floor(playerPosition.x);
        int targetZ = Mth.floor(playerPosition.z);
        int targetY =
                tellyMode()
                        ? (towerActive(client) ? playerY - 1 : startY - 1)
                        : (stage != 0 && !shouldKeepY ? Math.min(playerY, startY) : playerY) - 1;
        return new BlockPos(targetX, targetY, targetZ);
    }

    /**
     * Builds every useful support/face pair instead of committing to one face
     * before the ray test. The desired cell is still the dominant ordering;
     * alternate faces only act as a rescue when the nearest face is occluded.
     */
    private static List<PlacementTarget> collectPlacementTargets(
            Minecraft client, Vec3 playerPosition, BlockPos desired) {
        if (!replaceable(client, desired)) return List.of();

        List<PlacementTarget> targets = new ArrayList<>();
        Vec3 targetCenter = Vec3.atCenterOf(desired);
        double reachSqr = Math.pow(client.player.blockInteractionRange(), 2.0D);
        boolean keepHeight = stage != 0 && !shouldKeepY && !towerActive(client);
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= 0; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    BlockPos support = desired.offset(x, y, z);
                    BlockState state = client.level.getBlockState(support);
                    if (state.canBeReplaced()
                            || isInteractable(state)
                            || playerPosition.distanceToSqr(Vec3.atCenterOf(support)) > reachSqr
                            || keepHeight && support.getY() >= startY) continue;
                    for (Direction face : Direction.values()) {
                        if (face == Direction.DOWN) continue;
                        BlockPos placed = support.relative(face);
                        if (placed.getY() > desired.getY() || !replaceable(client, placed))
                            continue;
                        PlacementTarget candidate = new PlacementTarget(support, face);
                        if (!targets.contains(candidate)) targets.add(candidate);
                    }
                }
            }
        }
        targets.sort(
                Comparator.comparingDouble(
                                (PlacementTarget target) ->
                                        Vec3.atCenterOf(target.placePos())
                                                .distanceToSqr(targetCenter))
                        .thenComparingDouble(
                                target ->
                                        Vec3.atCenterOf(target.support())
                                                .distanceToSqr(targetCenter))
                        .thenComparingInt(target -> target.face() == Direction.UP ? 0 : 1));
        return targets;
    }

    /** Selects the nearest place cell, then the reachable face requiring least rotation. */
    private static PlacementAim findPlacementAim(
            Minecraft client, Vec3 playerPosition, Vec3 eye, BlockPos desired) {
        Vec3 desiredCenter = Vec3.atCenterOf(desired);
        float baseYaw = placementBaseYaw(client);
        float basePitch = placementBasePitch(client);
        PlacementAim best = null;
        double bestScore = Double.MAX_VALUE;
        double bestPlaceDistance = Double.MAX_VALUE;
        int evaluated = 0;
        for (PlacementTarget target : collectPlacementTargets(client, playerPosition, desired)) {
            double placeDistance = Vec3.atCenterOf(target.placePos()).distanceToSqr(desiredCenter);
            if (best != null && placeDistance > bestPlaceDistance) break;
            if (evaluated++ >= MAX_FACE_CANDIDATES) break;
            PlacementAim candidate = findFaceAim(client, target, eye);
            if (candidate == null) continue;
            double continuity =
                    lastTellyPlacePos != null && target.support().equals(lastTellyPlacePos)
                            ? -2.0D
                            : 0.0D;
            double score = rotationDistance(candidate.rotation(), baseYaw, basePitch) + continuity;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
                bestPlaceDistance = placeDistance;
            }
            // An exact cell with a near-continuous angle cannot be improved by
            // distant chain candidates; avoid unnecessary ray scans.
            if (placeDistance == 0.0D
                    && rotationDistance(candidate.rotation(), baseYaw, basePitch) <= 2.0D) break;
        }
        return best;
    }

    /** Preserves the original single-support/single-face Vanilla Tower path. */
    private static PlacementAim findVanillaTowerAim(
            Minecraft client, Vec3 playerPosition, Vec3 eye, BlockPos desired) {
        Vec3 desiredCenter = Vec3.atCenterOf(desired);
        List<PlacementTarget> targets =
                new ArrayList<>(collectPlacementTargets(client, playerPosition, desired));
        targets.sort(
                Comparator.comparingDouble(
                                (PlacementTarget target) ->
                                        Vec3.atCenterOf(target.support())
                                                .distanceToSqr(desiredCenter))
                        .thenComparingDouble(
                                target ->
                                        Vec3.atCenterOf(target.placePos())
                                                .distanceToSqr(desiredCenter))
                        .thenComparingInt(target -> target.face() == Direction.UP ? 0 : 1));
        for (PlacementTarget target : targets) {
            BlockHitResult hit =
                    BlockPlacementUtils.traceFace(
                            client,
                            eye,
                            towerRotationYaw,
                            90.0F,
                            client.player.blockInteractionRange(),
                            target.support(),
                            target.face());
            if (hit != null) {
                return new PlacementAim(target, new Rotation(towerRotationYaw, 90.0F), hit);
            }
        }
        return null;
    }

    /** Face scan validated after modern mouse-GCD quantization. */
    private static PlacementAim findFaceAim(Minecraft client, PlacementTarget target, Vec3 eye) {
        float baseYaw = placementBaseYaw(client);
        float basePitch = placementBasePitch(client);
        return switch (FACE_SAMPLING.get()) {
            case STANDARD ->
                    scanFaceAim(client, target, eye, baseYaw, basePitch, STANDARD_FACE_OFFSETS);
            case CENTER ->
                    scanFaceAim(client, target, eye, baseYaw, basePitch, PRIMARY_FACE_OFFSETS);
            case DENSE -> scanFaceAim(client, target, eye, baseYaw, basePitch, DENSE_FACE_OFFSETS);
            case ADAPTIVE -> {
                PlacementAim primary =
                        scanFaceAim(client, target, eye, baseYaw, basePitch, PRIMARY_FACE_OFFSETS);
                yield primary != null
                        ? primary
                        : scanFaceAim(client, target, eye, baseYaw, basePitch, DENSE_FACE_OFFSETS);
            }
        };
    }

    private static PlacementAim scanFaceAim(
            Minecraft client,
            PlacementTarget target,
            Vec3 eye,
            float baseYaw,
            float basePitch,
            double[] offsets) {
        PlacementAim best = null;
        double bestScore = Double.MAX_VALUE;
        // Server-side placement ray tracing uses exactly BLOCK_INTERACTION_RANGE;
        // a face our own trace needs beyond that can never survive its check.
        double range = client.player.blockInteractionRange();

        for (double u : offsets) {
            for (double v : offsets) {
                Vec3 point =
                        BlockPlacementUtils.facePoint(
                                client, target.support(), target.face(), u, v);
                Vec3 delta = point.subtract(eye);
                double horizontal = Math.hypot(delta.x, delta.z);
                if (delta.lengthSqr() < 1.0E-8D) continue;

                float rawYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
                float rawPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
                float yaw =
                        SilentPacketRotation.quantizePacketYaw(
                                baseYaw, baseYaw + Mth.wrapDegrees(rawYaw - baseYaw));
                float pitch = SilentPacketRotation.quantizePacketPitch(basePitch, rawPitch);

                BlockHitResult hit =
                        BlockPlacementUtils.traceFace(
                                client, eye, yaw, pitch, range, target.support(), target.face());
                if (hit == null) continue;

                double angleScore =
                        Math.abs(Mth.wrapDegrees(yaw - baseYaw)) + Math.abs(pitch - basePitch);
                double centerScore = (u - 0.5D) * (u - 0.5D) + (v - 0.5D) * (v - 0.5D);
                double score = angleScore + centerScore * 0.01D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new PlacementAim(target, new Rotation(yaw, pitch), hit);
                }
            }
        }
        return best;
    }

    private static double rotationDistance(Rotation rotation, float baseYaw, float basePitch) {
        return Math.abs(Mth.wrapDegrees(rotation.yaw() - baseYaw))
                + Math.abs(rotation.pitch() - basePitch);
    }

    /** Use the remaining air delay for turning; the placement deadline stays unchanged. */
    private static PlacementStep resolvePlacementStep(
            Minecraft client, Vec3 eye, PlacementAim exact, boolean rescue, int remainingAirDelay) {
        if (rescue) {
            return PlacementStep.ready(exact);
        }
        float baseYaw = placementBaseYaw(client);
        float basePitch = placementBasePitch(client);
        double speed = placementRotationStarted ? TELLY_TRACK_SPEED.get() : TELLY_START_SPEED.get();
        float steps = remainingAirDelay + 1.0F;
        float yawDelta = Mth.wrapDegrees(exact.rotation().yaw() - baseYaw) / steps;
        float pitchDelta = (exact.rotation().pitch() - basePitch) / steps;
        float limitedYaw = baseYaw + (float) Mth.clamp(yawDelta, -speed, speed);
        float limitedPitch = basePitch + (float) Mth.clamp(pitchDelta, -speed, speed);
        limitedYaw = SilentPacketRotation.quantizePacketYaw(baseYaw, limitedYaw);
        limitedPitch = SilentPacketRotation.quantizePacketPitch(basePitch, limitedPitch);
        Rotation rotation = new Rotation(limitedYaw, limitedPitch);
        if (remainingAirDelay > 0) {
            return new PlacementStep(exact.target(), rotation, null);
        }

        BlockHitResult hit =
                BlockPlacementUtils.traceFace(
                        client,
                        eye,
                        limitedYaw,
                        limitedPitch,
                        client.player.blockInteractionRange(),
                        exact.target().support(),
                        exact.target().face());
        return new PlacementStep(exact.target(), rotation, hit);
    }

    private static float placementBaseYaw(Minecraft client) {
        return rotationInitialized
                ? outgoingYaw
                : RotationManager.latest().valid() ? sentYaw() : client.player.getYRot();
    }

    private static float placementBasePitch(Minecraft client) {
        return rotationInitialized
                ? outgoingPitch
                : RotationManager.latest().valid() ? sentPitch() : client.player.getXRot();
    }

    private static boolean shouldStopSprint() {
        // This helper returns false during Telly/Tower and Snap
        // even when the visible option was "None".  ACA remembers sprinting
        // for 400 ms before a scaffold placement, so that exception produces
        // its deterministic Sprinting +45VL.  Keep Vanilla as the explicit
        // opt-in, but make None mean no sprint in every Scaffold phase.
        return sprintMode() == SprintMode.NONE
                || shouldCorrectMovement() && !silentInputAllowsSprint;
    }

    private static boolean hasCollisionBelow(Minecraft client) {
        return !client.level.noCollision(
                client.player, client.player.getBoundingBox().move(0.0D, -1.0D, 0.0D));
    }

    private static boolean hasCollisionAbove(Minecraft client) {
        return !client.level.noCollision(
                client.player, client.player.getBoundingBox().move(0.0D, 1.0D, 0.0D));
    }

    private static boolean replaceable(Minecraft client, BlockPos pos) {
        return client.level.getBlockState(pos).canBeReplaced();
    }

    private static boolean isInteractable(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.hasBlockEntity()) return true;
        if (path.equals("crafting_table")
                || path.contains("anvil")
                || path.endsWith("_bed")
                || path.endsWith("_trapdoor")
                || path.endsWith("_fence_gate")
                || path.endsWith("_fence")
                || path.endsWith("_button")
                || path.equals("lever")
                || path.equals("jukebox")) return true;
        return path.endsWith("_door") && !path.equals("iron_door");
    }

    private static boolean isSolid(Minecraft client, BlockState state, BlockPos pos) {
        if (state.canBeReplaced() || state.getBlock() instanceof FallingBlock) return false;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.contains("stairs")
                || path.endsWith("_slab")
                || path.equals("end_portal_frame")
                || path.equals("end_portal")
                || path.equals("vine")
                || path.contains("pumpkin")
                || path.equals("jack_o_lantern")
                || path.equals("cactus")
                || path.equals("cobweb")
                || path.endsWith("_pane")
                || path.endsWith("_carpet")
                || path.equals("snow")
                || path.endsWith("_fence")
                || path.endsWith("_fence_gate")
                || path.endsWith("_wall")
                || path.equals("ladder")
                || path.contains("torch")
                || path.equals("redstone_wire")
                || path.contains("repeater")
                || path.contains("comparator")
                || path.contains("pressure_plate")
                || path.equals("tripwire")
                || path.equals("tripwire_hook")
                || path.contains("rail")
                || path.equals("slime_block")
                || path.equals("tnt")) return false;
        return !state.getCollisionShape(client.level, pos).isEmpty();
    }

    private static void enableState(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (client != null && currentPlayer != null) {
            HOTBAR.userSelected(currentPlayer.getInventory().getSelectedSlot());
            startY = Mth.floor(currentPlayer.getY());
            wasInAir = !currentPlayer.onGround();
        }
        blockCount = -1;
        rotationTick = snapCooldown = currentFlatTick = rotationZeroCount = 0;
        canRotate = false;
        placedThisJump = false;
        resetLegitSneakState();
        onAirPlace = client != null && currentPlayer != null && currentPlayer.onGround();
        rotationInitialized = placementRotationStarted = false;
        silentInputAllowsSprint = true;
        float cameraYaw = client != null && currentPlayer != null ? currentPlayer.getYRot() : 0.0F;
        float cameraPitch =
                client != null && currentPlayer != null ? currentPlayer.getXRot() : 0.0F;
        outgoingYaw = renderYaw = cameraYaw;
        outgoingPitch = renderPitch = cameraPitch;
        serverPositionValid = false;
        renderTarget = null;
        renderHit = null;
        tellyBlocksThisJump = 0;
        tellyAirTicks = 0;
        lastTellyPlacePos = null;
        lastTellyPlaceTick = Integer.MIN_VALUE;
        tellyFlatStarted = false;
        tellyPhase = TellyPhase.SELECT_POINT;
        tellyDebugReason = "enabled";
        scaffoldPath = ScaffoldPath.IDLE;
        tellyTriggered = false;
        tellyWasAirborne = false;
        tellyBelowRowRescue = false;
        towerRotationInitialized = false;
        resetTellyBpsState(client);
        lastTellyReturnTick = Integer.MIN_VALUE;
        PLACED.clear();
    }

    private static void disableState(Minecraft client) {
        ScaffoldPlacementDebugger.update(client, false);
        HOTBAR.release(client);
        ROTATION.release();
        releaseLegitSneaking(client);
        onAirPlace = false;
        renderTarget = null;
        renderHit = null;
        tellyBlocksThisJump = 0;
        tellyAirTicks = 0;
        lastTellyPlacePos = null;
        lastTellyPlaceTick = Integer.MIN_VALUE;
        tellyFlatStarted = false;
        tellyPhase = TellyPhase.SELECT_POINT;
        tellyDebugReason = "disabled";
        scaffoldPath = ScaffoldPath.IDLE;
        tellyTriggered = false;
        tellyWasAirborne = false;
        tellyBelowRowRescue = false;
        towerRotationInitialized = false;
        resetTellyBpsState(client);
        lastTellyReturnTick = Integer.MIN_VALUE;
        silentInputAllowsSprint = true;
    }

    public static void shutdown(Minecraft client) {
        disableState(client);
        rotationInitialized = placementRotationStarted = false;
        PLACEMENT_ROTATIONS.reset();
        PLACED.clear();
    }

    private static void normalizeRanges() {
        if (TELLY_TRACK_SPEED.get() > TELLY_START_SPEED.get()) {
            TELLY_TRACK_SPEED.set(TELLY_START_SPEED.get());
        }
        if (LEGIT_DELAY_MIN.get() > LEGIT_DELAY_MAX.get()) {
            LEGIT_DELAY_MAX.set(LEGIT_DELAY_MIN.get());
        }
        if (TELLY_BLOCKS_PER_SECOND_MIN.get() > TELLY_BLOCKS_PER_SECOND_MAX.get()) {
            TELLY_BLOCKS_PER_SECOND_MAX.set(TELLY_BLOCKS_PER_SECOND_MIN.get());
        }
    }

    private static ScaffoldMode scaffoldMode() {
        return MODE.get();
    }

    private static boolean tellyMode() {
        return scaffoldMode() == ScaffoldMode.TELLY || jumpMode();
    }

    private static boolean jumpMode() {
        return scaffoldMode() == ScaffoldMode.JUMP;
    }

    private static MoveFix moveFix() {
        return MOVE_FIX.get();
    }

    private static SprintMode sprintMode() {
        return SPRINT_MODE.get();
    }

    private static KeepYMode keepYMode() {
        return tellyMode() ? KeepYMode.TELLY : KeepYMode.NONE;
    }

    private static TowerMode towerMode() {
        return scaffoldMode() == ScaffoldMode.LEGIT ? TowerMode.NONE : TOWER.get();
    }

    private static BooleanSetting bool(String key, boolean fallback) {
        return new BooleanSetting.Builder().name(key).defaultValue(fallback).build();
    }

    private static DoubleSetting decimal(String key, double fallback, double min, double max) {
        return new DoubleSetting.Builder().name(key).defaultValue(fallback).range(min, max).build();
    }

    private static IntSetting integer(String key, int fallback, int min, int max) {
        return new IntSetting.Builder().name(key).defaultValue(fallback).range(min, max).build();
    }

    private record PlacementTarget(BlockPos support, Direction face) {
        BlockPos placePos() {
            return support.relative(face);
        }
    }

    private record PlacementIntent(BlockPos desired, boolean rescue) {}

    private record PlacementAim(PlacementTarget target, Rotation rotation, BlockHitResult hit) {}

    private record PlacementStep(PlacementTarget target, Rotation rotation, BlockHitResult hit) {
        static PlacementStep ready(PlacementAim aim) {
            return new PlacementStep(aim.target(), aim.rotation(), aim.hit());
        }
    }

    private record PlacedMark(BlockPos pos, long time) {}

    private enum ScaffoldPath {
        IDLE,
        TELLY,
        RESCUE,
        TOWER;

        String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private enum TellyPhase {
        SELECT_POINT(0xFF69D2E7),
        ROTATE(0xFFFFD166),
        PLACE(0xFF80ED99);

        private final int color;

        TellyPhase(int color) {
            this.color = color;
        }

        int color() {
            return color;
        }

        String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private enum ScaffoldMode {
        LEGIT,
        TELLY,
        JUMP;

        String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private enum MoveFix {
        NONE,
        SILENT;
    }

    private enum TellyDelay {
        FIXED,
        ADAPTIVE;
    }

    private enum FaceSampling {
        STANDARD,
        CENTER,
        DENSE,
        ADAPTIVE;
    }

    private enum SprintMode {
        NONE,
        VANILLA;
    }

    private enum KeepYMode {
        NONE,
        TELLY;
    }

    private enum TowerMode {
        NONE,
        VANILLA;

        String configName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // Debug
    private static final BooleanSetting DEBUGGER = bool("scaffold.debugger", false);
    private static String tellyDebugReason = "reset";

    public static int setDebugger(Minecraft c, boolean v) {
        DEBUGGER.set(v);
        ScaffoldPlacementDebugger.update(c, v && enabled() && ClientReady.gameplay(c));
        ClientChat.send(
                c,
                v
                        ? "Scaffold debugger: first RotationPlace alert auto-saves to logs/moons."
                        : "Scaffold debugger stopped; recent trace queued for saving to logs/moons.");
        return 1;
    }

    private static void drawTellyDebugger(HudRenderEvent event, Minecraft client) {
        List<String> lines =
                new ArrayList<>(
                        List.of(
                                "Scaffold debugger",
                                "path="
                                        + scaffoldPath.configName()
                                        + " phase="
                                        + tellyPhase.configName(),
                                "reason=" + tellyDebugReason,
                                "point=" + debugPos(renderTarget),
                                "triggered="
                                        + tellyTriggered
                                        + " rescue="
                                        + tellyBelowRowRescue
                                        + " ground="
                                        + client.player.onGround(),
                                String.format(
                                        Locale.ROOT,
                                        "air=%d placed=%d vy=%.3f",
                                        tellyAirTicks,
                                        tellyBlocksThisJump,
                                        client.player.getDeltaMovement().y)));
        lines.addAll(ScaffoldPlacementDebugger.lines());
        int x = 6;
        int y = 6;
        for (int index = 0; index < lines.size(); index++) {
            int color = index == 1 ? tellyPhase.color() : 0xFFE7F5FF;
            event.graphics()
                    .text(
                            client.font,
                            lines.get(index),
                            x,
                            y + index * (client.font.lineHeight + 1),
                            color,
                            true);
        }
    }

    private static String debugPos(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Rotation deltas belonging to placements, observed only after packet sends. */
    static final class PlacementRotations {
        private boolean yawKnown;
        private boolean rotatedSincePlacement;
        private float yaw;
        private float deltaYaw;
        private float lastPlacementDeltaYaw;

        void reset() {
            yawKnown = rotatedSincePlacement = false;
            yaw = deltaYaw = lastPlacementDeltaYaw = 0.0F;
        }

        void rotationSent(float sentYaw) {
            if (yawKnown) {
                // Match the raw packet delta, including crossing +/-180 degrees.
                deltaYaw = Math.abs(sentYaw - yaw);
                rotatedSincePlacement = true;
            }
            yaw = sentYaw;
            yawKnown = true;
        }

        void placementSent() {
            if (!rotatedSincePlacement) return;
            lastPlacementDeltaYaw = deltaYaw;
            rotatedSincePlacement = false;
        }

        boolean needsSettling() {
            return rotatedSincePlacement && repeatsPlacementDelta(deltaYaw);
        }

        private boolean repeatsPlacementDelta(float delta) {
            return delta > 2.0F && Math.abs(delta - lastPlacementDeltaYaw) < 0.0001F;
        }

        boolean wouldRepeatNext(float nextYaw) {
            if (!yawKnown) return false;
            float delta = Math.abs(nextYaw - yaw);
            // A UseOn may consume the pending delta before this upcoming look.
            // Check both outcomes, including local rejection and no placement.
            return repeatsPlacementDelta(delta)
                    || rotatedSincePlacement
                            && delta > 2.0F
                            && Math.abs(delta - deltaYaw) < 0.0001F;
        }

        /** Prefer the selected angle, then nearby mouse steps in both directions. */
        Float variedYaw(
                float desiredYaw,
                float mouseStep,
                java.util.function.UnaryOperator<Float> quantize,
                java.util.function.Predicate<Float> reachable) {
            for (int i = 0; i <= 8; i++) {
                int offset = i == 0 ? 0 : ((i + 1) / 2) * (i % 2 == 1 ? 1 : -1);
                float candidate =
                        offset == 0
                                ? desiredYaw
                                : quantize.apply(desiredYaw + offset * Math.abs(mouseStep));
                if (Float.isFinite(candidate)
                        && !wouldRepeatNext(candidate)
                        && reachable.test(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        /** A small look on a tick without a placement; pitch can stay at 90 for Tower. */
        float settlingYaw(float mouseStep) {
            float step = Math.max(Math.abs(mouseStep), Math.ulp(yaw));
            if (!Float.isFinite(step) || step == 0.0F) step = 0.02F;
            // Move toward zero to avoid overflow for large, unwrapped yaw values.
            float direction = yaw > 0.0F ? -1.0F : 1.0F;
            float candidate = yaw + direction * step;
            if (repeatsPlacementDelta(Math.abs(candidate - yaw))) {
                candidate = yaw + direction * (2.0F * step);
            }
            return candidate;
        }
    }
}
