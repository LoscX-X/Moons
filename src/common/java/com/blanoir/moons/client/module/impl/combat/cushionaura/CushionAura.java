package com.blanoir.moons.client.module.impl.combat.cushionaura;

import static com.blanoir.moons.client.module.impl.combat.cushionaura.CushionPlacement.*;
import static com.blanoir.moons.client.module.impl.combat.cushionaura.CushionSettings.*;

import com.blanoir.moons.client.access.CushionAccess;
import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.input.PhysicalInput;
import com.blanoir.moons.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.moons.client.manager.input.CombatInputController;
import com.blanoir.moons.client.manager.lease.HotbarLease;
import com.blanoir.moons.client.manager.lease.RotationLease;
import com.blanoir.moons.client.manager.placement.PlacementCoordinator;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
import com.blanoir.moons.client.manager.targeting.Targeting;
import com.blanoir.moons.client.module.framework.ModuleRegistry;
import com.blanoir.moons.client.module.impl.combat.cushionaura.CushionPlacement.Plan;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraBlock;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraCombat;
import com.blanoir.moons.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/** Places real seats around SilentAura's selected target and attacks only after a server mount. */
public final class CushionAura {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("cushionaura.enabled").defaultValue(false).build();
    private static final HotbarLease HOTBAR =
            new HotbarLease("CushionAura", HotbarLease.PRIORITY_PLACEMENT);
    private static final CushionCycle CYCLE = new CushionCycle();
    private static final PlacementCoordinator.Owner OWNER = PlacementCoordinator.Owner.CUSHION_AURA;
    private static LocalPlayer player;
    private static Object level;
    private static Plan plan;
    private static Vec3 lastSeat;
    private static int seatId = -1;
    private static int plannedTarget = -1;
    private static boolean serverMounted;
    private static boolean ownsRotation;
    private static int tickNumber;
    private static int retryAt;
    private static int placedAt;
    private static boolean instantPlace;
    private static boolean instantMount;
    private static String status = "Idle";

    private CushionAura() {}

    public static boolean supported() {
        return CushionAccess.supported();
    }

    public static boolean isEnabled() {
        return supported() && ENABLED.get();
    }

    public static String statusTag() {
        return "Seated".equals(status) ? "Seated | " + SilentAuraCombat.gate() : status;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        if (value && !supported()) return 0;
        if (value == isEnabled()) return 1;
        shutdown(client);
        if (value)
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.SILENT_AURA);
        ENABLED.set(value);
        SilentAuraRuntime.reset(client);
        ClientChat.send(client, "CushionAura " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static ModuleRegistry.Setting[] settings() {
        return CushionSettings.descriptors();
    }

    public static void init() {
        if (!supported()) return;
        PlacementCoordinator.register(OWNER, CushionAura::busy);
        EventBus.CLIENT_CONTEXT_CHANGED.register("CushionAura.context", event -> shutdown(null));
        EventBus.TICK.register(
                "CushionAura.tick",
                EventPriority.HIGHEST,
                event -> {
                    tickNumber++;
                    advance(event.client());
                });
        EventBus.FRAME.register(
                "CushionAura.guard", EventPriority.HIGHEST, event -> guard(event.client()));
        EventBus.PLAYER_UPDATE.register(
                "CushionAura.use",
                EventPriority.HIGHEST,
                event -> {
                    if (!event.isCancelled()) interact(event.client());
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "CushionAura.passengers",
                event -> {
                    if (player != null
                            && event.packet() instanceof ClientboundSetPassengersPacket passengers
                            && passengers.getVehicle() == seatId) {
                        serverMounted =
                                Arrays.stream(passengers.getPassengers())
                                        .anyMatch(id -> id == player.getId());
                    }
                });
        EventBus.PACKET_SEND_POST.register(
                "CushionAura.attackSent",
                event -> {
                    if (event.packet() instanceof ServerboundAttackPacket
                            && CombatInputController.isInvokingTargetAttack()
                            && canAttack(Minecraft.getInstance())) CYCLE.recordAttack();
                });
        EventBus.MOVEMENT_INPUT_UPDATED.register(
                "CushionAura.dismount",
                EventPriority.LOWEST,
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!isEnabled()
                            || !valid(client)
                            || CYCLE.phase() != CushionCycle.Phase.DISMOUNT
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

    /** The saved SilentAura toggle is untouched; CushionAura supplies a temporary execution owner. */
    public static boolean canAttack(Minecraft client) {
        return isEnabled()
                && valid(client)
                && !manualExit(client)
                && !PlacementCoordinator.busyFor(OWNER)
                && CYCLE.mayAttack(ridingSeat(client), serverMounted)
                && CYCLE.elapsedTicks(tickNumber, ATTACK_DELAY.get());
    }

    private static boolean busy() {
        return ownsRotation || HOTBAR.active() || CYCLE.phase() == CushionCycle.Phase.DISMOUNT;
    }

    private static boolean valid(Minecraft client) {
        return ClientReady.aliveGameplay(client)
                && !client.isPaused()
                && !client.player.isSpectator()
                && (!client.player.isUsingItem() || SilentAuraBlock.ownsNativeUse(client))
                && client.getCameraEntity() == client.player;
    }

    private static boolean manualExit(Minecraft client) {
        return PhysicalInput.isGameplayKeyDown(client, client.options.keyShift)
                || PhysicalInput.isGameplayKeyDown(client, client.options.keyJump);
    }

    private static void guard(Minecraft client) {
        if (!isEnabled()) {
            if (player != null || busy()) shutdown(client);
            return;
        }
        if (client != null && client.player != null && client.player.isDeadOrDying()) {
            setEnabled(client, false);
            return;
        }
        if (!valid(client) || manualExit(client)) {
            if (client != null
                    && client.player != null
                    && !manualExit(client)
                    && ridingSeat(client)
                    && serverMounted) {
                releaseRotation();
                HOTBAR.release(client);
                if (CYCLE.phase() != CushionCycle.Phase.SEATED)
                    CYCLE.enter(CushionCycle.Phase.SEATED, System.nanoTime(), tickNumber);
            } else if (player != null || busy()) shutdown(client);
            status = "Paused";
        }
    }

    private static void advance(Minecraft client) {
        guard(client);
        if (!isEnabled() || !valid(client) || manualExit(client)) return;
        long now = System.nanoTime();
        if (player != client.player || level != client.level) {
            shutdown(client);
            player = client.player;
            level = client.level;
        }
        if (CYCLE.phase() == CushionCycle.Phase.DISMOUNT) {
            if (CYCLE.acknowledgeDismount(client.player.isPassenger(), serverMounted, now)) {
                // The server may choose a different exit position; search from that position.
                plan = null;
                seatId = -1;
                plannedTarget = -1;
                retryAt = 0;
            } else if (CYCLE.elapsed(now, TIMEOUT.get())) {
                // An unconfirmed local exit must never authorize the next placement.
                setEnabled(client, false);
                ClientChat.send(client, "CushionAura: could not leave the seat; cycle stopped.");
            }
            if (CYCLE.phase() == CushionCycle.Phase.DISMOUNT || !isEnabled()) return;
        }
        if (PlacementCoordinator.busyFor(OWNER)) {
            if (busy()) resetCycle(client, now);
            status = "Placement busy";
            return;
        }
        LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
        if (target == null || !target.isAlive()) {
            if (ridingSeat(client) && serverMounted) {
                releaseRotation();
                if (CYCLE.phase() != CushionCycle.Phase.SEATED)
                    CYCLE.enter(CushionCycle.Phase.SEATED, now, tickNumber);
            } else resetCycle(client, now);
            status = "No target";
            return;
        }
        if (plannedTarget != -1
                && plannedTarget != target.getId()
                && CYCLE.phase() != CushionCycle.Phase.SEATED) {
            resetCycle(client, now);
            return;
        }
        switch (CYCLE.phase()) {
            case SEARCH -> {
                if (tickNumber < retryAt) return;
                if (client.player.isPassenger()) {
                    status = "Leave current seat";
                    return;
                }
                if (!Targeting.isHoldingTriggerWeapon(client)) {
                    status = "Hold weapon";
                    return;
                }
                Plan next = findPlan(client, target);
                retryAt = tickNumber + RETRY.get();
                if (next == null) return;
                startPlan(client, next, target, now);
            }
            case TURN_PLACE, TURN_MOUNT, PLACE, MOUNT -> {
                if (CYCLE.elapsed(now, TIMEOUT.get())) fail(client, now, "Turn timeout");
            }
            case WAIT_ENTITY -> {
                if (ownsRotation && !SilentPacketRotation.isUseDone()) {
                    if (CYCLE.elapsed(now, TIMEOUT.get())) fail(client, now, "Use timeout");
                    return;
                }
                releaseRotation();
                Entity seat = findSeat(client, plan.position());
                if (seat != null) {
                    if (!CushionCycle.ticksElapsed(tickNumber, placedAt, MOUNT_DELAY.get())) {
                        status = "Mount delay";
                        return;
                    }
                    seatId = seat.getId();
                    serverMounted = false;
                    turnToSeat(client, seat, now);
                } else if (CYCLE.elapsed(now, TIMEOUT.get()))
                    fail(client, now, "Place unconfirmed");
            }
            case WAIT_RIDE -> {
                if (CYCLE.acknowledgeRide(ridingSeat(client), serverMounted, now, tickNumber)) {
                    releaseRotation();
                    lastSeat = client.player.getVehicle().position();
                    retryAt = tickNumber;
                    status = "Seated";
                } else if (CYCLE.elapsed(now, TIMEOUT.get())) fail(client, now, "Ride unconfirmed");
            }
            case SEATED -> {
                if (!ridingSeat(client) || !serverMounted) {
                    fail(client, now, "Dismounted");
                    return;
                }
                status = "Seated";
                if (!CYCLE.mayRelocate(tickNumber, INTERVAL.get(), ATTACK_BEFORE_MOVE.get())
                        || tickNumber < retryAt) return;
                Plan next = findPlan(client, target);
                retryAt = tickNumber + RETRY.get();
                if (next == null) return;
                plan = next;
                plannedTarget = target.getId();
                CYCLE.enter(CushionCycle.Phase.DISMOUNT, now, tickNumber);
                status = "Changing seat";
            }
            case DISMOUNT -> {
                /* Handled before target selection so target loss cannot strand an exit. */
            }
        }
    }

    private static void startPlan(Minecraft client, Plan next, LivingEntity target, long now) {
        if (!next.existing()) {
            startPlacement(client, next, target, now);
            return;
        }
        if (SilentPacketRotation.isBusy()) return;
        Entity seat = client.level.getEntity(next.entityId());
        if (!reusable(client, seat, target, RADIUS.get(), lastSeat)) return;
        plan = next;
        plannedTarget = target.getId();
        seatId = next.entityId();
        serverMounted = false;
        turnToSeat(client, seat, now);
    }

    private static void startPlacement(Minecraft client, Plan next, LivingEntity target, long now) {
        if (SilentPacketRotation.isBusy() || !HOTBAR.acquire(client, next.slot())) return;
        plan = next;
        plannedTarget = target.getId();
        CYCLE.enter(CushionCycle.Phase.TURN_PLACE, now);
        ownsRotation = true;
        status = "Placing";
        instantPlace = PLACE_TURN.get() == 0;
        SilentPacketRotation.beginRotation(
                client,
                next.hit().getLocation(),
                PLACE_TURN.get(),
                instantPlace ? SilentPacketRotation.Mode.INSTANT : SilentPacketRotation.Mode.SMOOTH,
                () -> {
                    if (CYCLE.phase() == CushionCycle.Phase.TURN_PLACE)
                        CYCLE.enter(CushionCycle.Phase.PLACE, System.nanoTime());
                });
    }

    private static void turnToSeat(Minecraft client, Entity seat, long now) {
        if (!seatUsable(client, seat) || SilentPacketRotation.isBusy()) {
            fail(client, now, "Seat unreachable");
            return;
        }
        ownsRotation = true;
        CYCLE.enter(CushionCycle.Phase.TURN_MOUNT, now);
        status = plan != null && plan.existing() ? "Reusing seat" : "Mounting";
        instantMount = MOUNT_TURN.get() == 0;
        SilentPacketRotation.beginRotation(
                client,
                seat.getBoundingBox().getCenter(),
                MOUNT_TURN.get(),
                instantMount ? SilentPacketRotation.Mode.INSTANT : SilentPacketRotation.Mode.SMOOTH,
                () -> {
                    if (CYCLE.phase() == CushionCycle.Phase.TURN_MOUNT)
                        CYCLE.enter(CushionCycle.Phase.MOUNT, System.nanoTime());
                });
    }

    private static void interact(Minecraft client) {
        // Applied spawns/passenger updates can arrive after TICK. Consume them here so a
        // zero-delay transition reaches this same interaction window, without another tick.
        advance(client);
        if (!isEnabled()
                || !valid(client)
                || player != client.player
                || level != client.level
                || PlacementCoordinator.busyFor(OWNER)
                || manualExit(client)) return;
        long now = System.nanoTime();
        if (CYCLE.phase() == CushionCycle.Phase.PLACE) {
            LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
            if (target == null
                    || target.getId() != plannedTarget
                    || !CushionPlacement.aroundTarget(
                            plan.position(), target.position(), RADIUS.get(), lastSeat)) {
                fail(client, now, "Target moved");
                return;
            }
            if (!HOTBAR.active() || !CushionAccess.isItem(client.player.getMainHandItem())) {
                fail(client, now, "Missing cushion");
                return;
            }
            // Another player may have placed/freed a suitable seat while we were turning.
            Plan existing = findExisting(client, target, RADIUS.get(), lastSeat);
            if (existing != null) {
                releaseRotation();
                HOTBAR.release(client);
                GameAccess.syncCarriedItem(client.gameMode);
                startPlan(client, existing, target, now);
                return;
            }
            BlockHitResult actual =
                    BlockPlacementUtils.traceFace(
                            client,
                            client.player.getEyePosition(),
                            SilentPacketRotation.getInteractionYaw(client),
                            SilentPacketRotation.getInteractionPitch(client),
                            client.player.blockInteractionRange(),
                            plan.hit().getBlockPos(),
                            Direction.UP);
            if (actual == null || !validPlan(client, actual, client.player.getMainHandItem())) {
                fail(client, now, "Placement obstructed");
                return;
            }
            plan =
                    new Plan(
                            actual,
                            CushionAccess.placementPosition(
                                    client, client.player.getMainHandItem(), actual),
                            plan.slot());
            if (!aroundTarget(plan.position(), target.position(), RADIUS.get(), lastSeat)
                    || !Double.isFinite(
                            newSeatScore(
                                    client, target, plan.position(), RADIUS.get(), lastSeat))) {
                fail(client, now, "No attack path from seat");
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, actual, !instantPlace)) {
                // Keep the item until vanilla has used it, then restore the user's weapon
                // immediately.
                HOTBAR.release(client);
                GameAccess.syncCarriedItem(client.gameMode);
                placedAt = tickNumber;
                CYCLE.enter(CushionCycle.Phase.WAIT_ENTITY, now, tickNumber);
            }
        } else if (CYCLE.phase() == CushionCycle.Phase.MOUNT
                && (instantMount || SilentPacketRotation.isRotationPacketSent())) {
            // Dismount's synthetic sneak may still be present until vanilla updates input.
            // Cushion.interact returns PASS for secondary use; wait instead of sending it.
            if (client.player.isSecondaryUseActive()) {
                status = "Waiting for sneak release";
                return;
            }
            Entity seat = client.level.getEntity(seatId);
            LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
            if (target == null
                    || target.getId() != plannedTarget
                    || !reusable(client, seat, target, RADIUS.get(), lastSeat)
                    || client.player.isPassenger()) {
                fail(client, now, "Seat unreachable");
                return;
            }
            Vec3 eye = client.player.getEyePosition();
            Vec3 end =
                    eye.add(
                            SilentPacketRotation.getInteractionLookVector(client)
                                    .scale(client.player.entityInteractionRange()));
            EntityHitResult picked = CushionPlacement.pickEntity(client, eye, end);
            if (picked == null
                    || picked.getEntity() != seat
                    || !visible(client, picked.getLocation())) {
                fail(client, now, "Seat obstructed");
                return;
            }
            // Keep the interaction's sampled angle for this window's closing movement.
            if (!RotationLease.holdManual(SilentPacketRotation.packetRotation(client))) return;
            GameAccess.syncCarriedItem(client.gameMode);
            var result =
                    client.gameMode.interact(
                            client.player, seat, picked, InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) {
                fail(client, now, "Seat interaction rejected");
                return;
            }
            CYCLE.enter(CushionCycle.Phase.WAIT_RIDE, now);
        }
    }

    private static Plan findPlan(Minecraft client, LivingEntity target) {
        return preferExisting(
                () -> findExisting(client, target, RADIUS.get(), lastSeat),
                () -> {
                    int slot = CushionPlacement.findSlot(client);
                    if (slot == -1) {
                        status = "No usable seat or cushions";
                        return null;
                    }
                    Plan next = CushionPlacement.find(client, target, slot, RADIUS.get(), lastSeat);
                    if (next == null) status = "No reachable spot";
                    return next;
                });
    }

    private static boolean ridingSeat(Minecraft client) {
        return client != null
                && client.player != null
                && client.player == player
                && client.player.getVehicle() != null
                && client.player.getVehicle().getId() == seatId
                && CushionAccess.isCushion(client.player.getVehicle());
    }

    private static void releaseRotation() {
        if (!ownsRotation) return;
        SilentPacketRotation.reset();
        ownsRotation = false;
    }

    private static void resetCycle(Minecraft client, long now) {
        releaseRotation();
        HOTBAR.release(client);
        CYCLE.enter(CushionCycle.Phase.SEARCH, now);
        plan = null;
        seatId = -1;
        plannedTarget = -1;
        serverMounted = false;
    }

    private static void fail(Minecraft client, long now, String reason) {
        resetCycle(client, now);
        retryAt = tickNumber + RETRY.get();
        status = reason;
    }

    public static void shutdown(Minecraft client) {
        resetCycle(client, System.nanoTime());
        player = null;
        level = null;
        lastSeat = null;
        retryAt = 0;
        placedAt = 0;
        status = "Idle";
    }
}
