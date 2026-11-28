package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import static com.blanoir.tempos.client.module.impl.combat.cushionaura.CusionRunSettings.PLACE_DELAY;
import static com.blanoir.tempos.client.module.impl.combat.cushionaura.CusionRunSettings.TIMEOUT;
import static com.blanoir.tempos.client.module.impl.combat.cushionaura.CusionRunSettings.TURN;

import com.blanoir.tempos.client.access.CushionAccess;
import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.AutoBlock;
import com.blanoir.tempos.client.module.impl.combat.AutoClicker;
import com.blanoir.tempos.client.module.impl.combat.AutoMace;
import com.blanoir.tempos.client.module.impl.combat.critical.Critical;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionPlacement.Plan;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/** Travels through real cushion mounts; movement and acknowledgements stay server-owned. */
@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description =
                "Moves along the camera direction through reachable cushions; temporarily pauses automatic combat.")
public final class CusionRun extends ClientFeature {
    // Temporarily withheld from this build, including saved profiles and direct activation.
    private static final boolean ENABLED_IN_BUILD = false;
    public static final CusionRun INSTANCE = new CusionRun();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("cusionrun.enabled").defaultValue(false).build();
    private final HotbarLease hotbar = new HotbarLease("CusionRun", HotbarLease.PRIORITY_PLACEMENT);
    private final CushionCycle cycle = new CushionCycle();
    private final PlacementCoordinator.Owner owner = PlacementCoordinator.Owner.CUSION_RUN;
    private LocalPlayer player;
    private Object level;
    private Plan plan;
    private Entity seat;
    private boolean serverMounted;
    private boolean ownsRotation;
    private int tickNumber;
    private int retryAt;
    private String status = "Idle";

    private CusionRun() {
        super("cusionrun", "CusionRun");
    }

    public static boolean available() {
        return ENABLED_IN_BUILD && CushionAccess.supported();
    }

    @Override
    public boolean isEnabled() {
        return available() && enabled.get();
    }

    @Override
    public String statusTag() {
        return status;
    }

    @Override
    protected Setting[] defineSettings() {
        return CusionRunSettings.descriptors();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        if (value && !available()) return 0;
        if (value == isEnabled()) return 1;
        shutdown(client);
        enabled.set(value);
        if (value) {
            // Release execution state, never rewrite another module's saved toggle or timers.
            SilentAuraRuntime.reset(client);
            Critical.INSTANCE.cancelAutomaticPrediction(client);
            CushionAura.INSTANCE.shutdown(client);
            AutoClicker.INSTANCE.reset(client);
            AutoMace.INSTANCE.reset(client);
            AutoBlock.INSTANCE.reset(client);
            CombatInputController.suspendAutomaticAttacks(client);
        }
        ClientChat.send(client, "CusionRun " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    @Override
    public void registerEvents() {
        if (!available()) return;
        PlacementCoordinator.register(owner, this::isEnabled);
        EventBus.CLIENT_CONTEXT_CHANGED.register("CusionRun.context", event -> shutdown(null));
        EventBus.TICK.register(
                "CusionRun.tick",
                EventPriority.HIGHEST,
                event -> {
                    tickNumber++;
                    advance(event.client());
                });
        EventBus.FRAME.register(
                "CusionRun.guard", EventPriority.HIGHEST, event -> guard(event.client()));
        EventBus.PLAYER_UPDATE.register(
                "CusionRun.use",
                EventPriority.HIGHEST,
                event -> {
                    if (!event.isCancelled()) interact(event.client());
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "CusionRun.passengers",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (player != null
                            && client.player == player
                            && client.level == level
                            && event.listener() == client.getConnection()
                            && seat != null
                            && client.level.getEntity(seat.getId()) == seat
                            && event.packet() instanceof ClientboundSetPassengersPacket passengers
                            && passengers.getVehicle() == seat.getId()) {
                        serverMounted =
                                Arrays.stream(passengers.getPassengers())
                                        .anyMatch(id -> id == player.getId());
                    }
                });
        EventBus.MOVEMENT_INPUT_UPDATED.register(
                "CusionRun.dismount",
                EventPriority.LOWEST,
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!isEnabled()
                            || !valid(client)
                            || manualExit(client)
                            || cycle.phase() != CushionCycle.Phase.DISMOUNT
                            || !ridingSeat(client)) return;
                    Input input = event.input().keyPresses;
                    event.input().keyPresses =
                            new Input(
                                    input.forward(),
                                    input.backward(),
                                    input.left(),
                                    input.right(),
                                    input.jump(),
                                    true,
                                    false);
                });
    }

    private static boolean valid(Minecraft client) {
        return ClientReady.aliveGameplay(client)
                && !client.isPaused()
                && !client.player.isSpectator()
                && !client.player.isUsingItem()
                && client.getCameraEntity() == client.player;
    }

    private static boolean manualExit(Minecraft client) {
        return PhysicalInput.isGameplayKeyDown(client, client.options.keyShift)
                || PhysicalInput.isGameplayKeyDown(client, client.options.keyJump);
    }

    private boolean guard(Minecraft client) {
        if (!isEnabled() || !ClientReady.world(client)) {
            shutdown(client);
            return false;
        }
        if (player != null && (player != client.player || level != client.level)) shutdown(client);
        if (client.player.isDeadOrDying()) {
            setEnabled(client, false);
            return false;
        }
        if (!valid(client) || manualExit(client)) {
            releaseRotation();
            hotbar.release(client);
            // Keep outstanding server acknowledgements across a menu/manual pause.
            switch (cycle.phase()) {
                case TURN_PLACE, PLACE, TURN_MOUNT, MOUNT -> resetCycle(client);
                default -> {}
            }
            status = "Paused";
            return false;
        }
        return true;
    }

    private void advance(Minecraft client) {
        if (!guard(client)) return;
        if (player == null) {
            player = client.player;
            level = client.level;
        }
        long now = System.nanoTime();
        if (cycle.phase() == CushionCycle.Phase.DISMOUNT) {
            if (cycle.acknowledgeDismount(player.isPassenger(), serverMounted, now)) {
                plan = null;
                seat = null;
                retryAt = 0;
                // Always replan from the server-selected exit position, using the current camera.
            } else {
                if (cycle.elapsed(now, TIMEOUT.get())) {
                    setEnabled(client, false);
                    ClientChat.send(client, "CusionRun: dismount confirmation timed out.");
                }
                return;
            }
        }
        if (PlacementCoordinator.busyFor(owner)) {
            status = "Placement busy";
            return;
        }
        switch (cycle.phase()) {
            case SEARCH -> {
                if (tickNumber < retryAt) return;
                retryAt = tickNumber + 1;
                if (player.isPassenger()) {
                    status = "Leave current seat";
                    return;
                }
                Plan next = CusionRunPlacement.find(client);
                if (next == null) status = "No reachable cushion spot";
                else startPlan(client, next);
            }
            case WAIT_ENTITY -> {
                if (ownsRotation && !SilentPacketRotation.isUseDone()) {
                    if (cycle.elapsed(now, TIMEOUT.get())) fail(client, "Use timeout");
                    return;
                }
                releaseRotation();
                Entity found = CushionPlacement.findSeat(client, plan.position());
                if (found != null) turnToSeat(client, found);
                if (cycle.phase() == CushionCycle.Phase.WAIT_ENTITY
                        && cycle.elapsed(now, TIMEOUT.get()))
                    fail(client, found == null ? "Spawn timeout" : "Rotation busy");
            }
            case WAIT_RIDE -> {
                if (cycle.acknowledgeRide(ridingSeat(client), serverMounted, now, tickNumber)) {
                    releaseRotation();
                    retryAt = 0;
                    status = "Seated";
                } else if (cycle.elapsed(now, TIMEOUT.get())) fail(client, "Mount timeout");
            }
            case SEATED -> {
                if (!ridingSeat(client) || !serverMounted) {
                    resetCycle(client);
                    return;
                }
                if (tickNumber < retryAt) return;
                retryAt = tickNumber + 1;
                if (CusionRunPlacement.find(client) == null) {
                    status = "Seated | No forward spot";
                    return;
                }
                cycle.enter(CushionCycle.Phase.DISMOUNT, now, tickNumber);
                status = "Changing seat";
            }
            case TURN_PLACE, PLACE, TURN_MOUNT, MOUNT -> {
                if (cycle.elapsed(now, TIMEOUT.get())) fail(client, "Turn timeout");
            }
            case DISMOUNT -> {}
        }
    }

    private void startPlan(Minecraft client, Plan next) {
        if (SilentPacketRotation.isBusy()) return;
        if (next.existing()) {
            plan = next;
            turnToSeat(client, client.level.getEntity(next.entityId()));
            return;
        }
        if (!hotbar.acquire(client, next.slot())) return;
        plan = next;
        cycle.enter(CushionCycle.Phase.TURN_PLACE, System.nanoTime(), tickNumber);
        ownsRotation = true;
        status = "Placing";
        SilentPacketRotation.beginRotation(
                client,
                next.hit().getLocation(),
                TURN.get(),
                () -> {
                    if (cycle.phase() == CushionCycle.Phase.TURN_PLACE)
                        cycle.enter(CushionCycle.Phase.PLACE, System.nanoTime(), tickNumber);
                });
    }

    private void turnToSeat(Minecraft client, Entity found) {
        if (SilentPacketRotation.isBusy()) return;
        if (!CusionRunPlacement.reusable(client, found)) {
            fail(client, "Seat unreachable");
            return;
        }
        seat = found;
        serverMounted = false;
        ownsRotation = true;
        cycle.enter(CushionCycle.Phase.TURN_MOUNT, System.nanoTime(), tickNumber);
        status = plan.existing() ? "Reusing seat" : "Mounting";
        SilentPacketRotation.beginRotation(
                client,
                found.getBoundingBox().getCenter(),
                TURN.get(),
                () -> {
                    if (cycle.phase() == CushionCycle.Phase.TURN_MOUNT)
                        cycle.enter(CushionCycle.Phase.MOUNT, System.nanoTime(), tickNumber);
                });
    }

    boolean placementReady(boolean rotationSent) {
        return cycle.phase() == CushionCycle.Phase.PLACE
                && rotationSent
                && cycle.elapsedTicks(tickNumber, PLACE_DELAY.get());
    }

    private void interact(Minecraft client) {
        advance(client);
        if (!isEnabled()
                || !valid(client)
                || manualExit(client)
                || player != client.player
                || level != client.level
                || PlacementCoordinator.busyFor(owner)) return;
        if (placementReady(SilentPacketRotation.isRotationPacketSent())) {
            if (!hotbar.active() || !CushionAccess.isItem(player.getMainHandItem())) {
                fail(client, "Missing cushion");
                return;
            }
            Plan existing = CusionRunPlacement.existing(client);
            if (existing != null) {
                releaseRotation();
                hotbar.release(client);
                GameAccess.syncCarriedItem(client.gameMode);
                startPlan(client, existing);
                return;
            }
            var actual =
                    BlockPlacementUtils.traceFace(
                            client,
                            player.getEyePosition(),
                            SilentPacketRotation.getInteractionYaw(client),
                            SilentPacketRotation.getInteractionPitch(client),
                            player.blockInteractionRange(),
                            plan.hit().getBlockPos(),
                            Direction.UP);
            if (actual == null
                    || !CushionPlacement.validPlan(client, actual, player.getMainHandItem())) {
                fail(client, "Placement obstructed");
                return;
            }
            Vec3 position =
                    CushionAccess.placementPosition(client, player.getMainHandItem(), actual);
            if (!Double.isFinite(CusionRunPlacement.course(client).progress(position))) {
                fail(client, "Camera changed");
                return;
            }
            plan = new Plan(actual, position, plan.slot());
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, actual, true)) {
                hotbar.release(client);
                GameAccess.syncCarriedItem(client.gameMode);
                cycle.enter(CushionCycle.Phase.WAIT_ENTITY, System.nanoTime(), tickNumber);
            }
        } else if (cycle.phase() == CushionCycle.Phase.MOUNT
                && SilentPacketRotation.isRotationPacketSent()) {
            // Native cushion interaction rejects secondary use; the previous dismount must clear.
            if (player.isSecondaryUseActive()) return;
            if (player.isPassenger()
                    || seat == null
                    || client.level.getEntity(seat.getId()) != seat
                    || !CusionRunPlacement.reusable(client, seat)) {
                fail(client, "Seat unreachable");
                return;
            }
            Vec3 eye = player.getEyePosition();
            var hit =
                    CushionPlacement.pickEntity(
                            client,
                            eye,
                            eye.add(
                                    SilentPacketRotation.getInteractionLookVector(client)
                                            .scale(player.entityInteractionRange())));
            if (hit == null
                    || hit.getEntity() != seat
                    || !CushionPlacement.visible(client, hit.getLocation())) {
                fail(client, "Seat obstructed");
                return;
            }
            if (!RotationLease.holdManual(SilentPacketRotation.packetRotation(client))) return;
            GameAccess.syncCarriedItem(client.gameMode);
            if (client.gameMode
                    .interact(player, seat, hit, InteractionHand.MAIN_HAND)
                    .consumesAction())
                cycle.enter(CushionCycle.Phase.WAIT_RIDE, System.nanoTime(), tickNumber);
            else fail(client, "Seat interaction rejected");
        }
    }

    private boolean ridingSeat(Minecraft client) {
        return player != null
                && client.player == player
                && seat != null
                && player.getVehicle() == seat;
    }

    private void releaseRotation() {
        if (!ownsRotation) return;
        SilentPacketRotation.reset();
        ownsRotation = false;
    }

    private void resetCycle(Minecraft client) {
        releaseRotation();
        hotbar.release(client);
        cycle.enter(CushionCycle.Phase.SEARCH, System.nanoTime(), tickNumber);
        plan = null;
        seat = null;
        serverMounted = false;
    }

    private void fail(Minecraft client, String reason) {
        resetCycle(client);
        retryAt = tickNumber + 1;
        status = reason;
    }

    public void shutdown(Minecraft client) {
        resetCycle(client);
        player = null;
        level = null;
        retryAt = 0;
        status = "Idle";
    }
}
