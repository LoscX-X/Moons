package com.blanoir.tempos.client.module.impl.combat.cushionaura;

import static com.blanoir.tempos.client.module.framework.SettingFactory.valid;
import static com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionPlacement.*;
import static com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionSettings.*;

import com.blanoir.tempos.client.access.CushionAccess;
import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.rotation.SilentPacketRotation;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.cushionaura.CushionPlacement.Plan;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraBlock;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraCombat;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.world.placement.BlockPlacementUtils;

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
@ModuleInfo(
        category = ModuleCategory.EXPERIMENT,
        description =
                "Places seats around the selected target and waits for a confirmed mount before attacking.")
public final class CushionAura extends ClientFeature {
    public static final CushionAura INSTANCE = new CushionAura();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("cushionaura.enabled").defaultValue(false).build();
    private final HotbarLease hotbar =
            new HotbarLease("CushionAura", HotbarLease.PRIORITY_PLACEMENT);
    private final CushionCycle cycle = new CushionCycle();
    private final PlacementCoordinator.Owner owner = PlacementCoordinator.Owner.CUSHION_AURA;
    private LocalPlayer player;
    private Object level;
    private Plan plan;
    private Vec3 lastSeat;
    private int seatId = -1;
    private int plannedTarget = -1;
    private boolean serverMounted;
    private boolean ownsRotation;
    private int tickNumber;
    private int retryAt;
    private int placedAt;
    private boolean instantPlace;
    private boolean instantMount;
    private String status = "Idle";

    private CushionAura() {
        super("cushionaura", "CushionAura");
    }

    public static boolean supported() {
        return CushionAccess.supported();
    }

    @Override
    public boolean isEnabled() {
        return supported() && this.enabled.get();
    }

    @Override
    public String statusTag() {
        return "Seated".equals(this.status) ? "Seated | " + SilentAuraCombat.gate() : this.status;
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        if (value && !supported()) return 0;
        if (value == isEnabled()) return 1;
        shutdown(client);
        if (value)
            CombatModuleCoordinator.beforeEnable(client, CombatModuleCoordinator.Role.SILENT_AURA);
        this.enabled.set(value);
        SilentAuraRuntime.reset(client);
        ClientChat.send(client, "CushionAura " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static ModuleModel.Setting[] settings() {
        return CushionSettings.descriptors();
    }

    @Override
    public void registerEvents() {
        if (!supported()) return;
        PlacementCoordinator.register(this.owner, this::busy);
        EventBus.CLIENT_CONTEXT_CHANGED.register("CushionAura.context", event -> shutdown(null));
        EventBus.TICK.register(
                "CushionAura.tick",
                EventPriority.HIGHEST,
                event -> {
                    this.tickNumber++;
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
                    if (this.player != null
                            && event.packet() instanceof ClientboundSetPassengersPacket passengers
                            && passengers.getVehicle() == this.seatId) {
                        this.serverMounted =
                                Arrays.stream(passengers.getPassengers())
                                        .anyMatch(id -> id == this.player.getId());
                    }
                });
        EventBus.PACKET_SEND_POST.register(
                "CushionAura.attackSent",
                event -> {
                    if (event.packet() instanceof ServerboundAttackPacket
                            && CombatInputController.isInvokingTargetAttack()
                            && canAttack(Minecraft.getInstance())) this.cycle.recordAttack();
                });
        EventBus.MOVEMENT_INPUT_UPDATED.register(
                "CushionAura.dismount",
                EventPriority.LOWEST,
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!isEnabled()
                            || !valid(client)
                            || this.cycle.phase() != CushionCycle.Phase.DISMOUNT
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
    public boolean canAttack(Minecraft client) {
        return isEnabled()
                && valid(client)
                && !manualExit(client)
                && !PlacementCoordinator.busyFor(this.owner)
                && this.cycle.mayAttack(ridingSeat(client), this.serverMounted)
                && this.cycle.elapsedTicks(this.tickNumber, ATTACK_DELAY.get());
    }

    private boolean busy() {
        return this.ownsRotation
                || this.hotbar.active()
                || this.cycle.phase() == CushionCycle.Phase.DISMOUNT;
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

    private void guard(Minecraft client) {
        if (!isEnabled()) {
            if (this.player != null || busy()) shutdown(client);
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
                    && this.serverMounted) {
                releaseRotation();
                this.hotbar.release(client);
                if (this.cycle.phase() != CushionCycle.Phase.SEATED)
                    this.cycle.enter(CushionCycle.Phase.SEATED, System.nanoTime(), this.tickNumber);
            } else if (this.player != null || busy()) shutdown(client);
            this.status = "Paused";
        }
    }

    private void advance(Minecraft client) {
        guard(client);
        if (!isEnabled() || !valid(client) || manualExit(client)) return;
        long now = System.nanoTime();
        if (this.player != client.player || this.level != client.level) {
            shutdown(client);
            this.player = client.player;
            this.level = client.level;
        }
        if (this.cycle.phase() == CushionCycle.Phase.DISMOUNT) {
            if (this.cycle.acknowledgeDismount(
                    client.player.isPassenger(), this.serverMounted, now)) {
                // The server may choose a different exit position; search from that position.
                this.plan = null;
                this.seatId = -1;
                this.plannedTarget = -1;
                this.retryAt = 0;
            } else if (this.cycle.elapsed(now, TIMEOUT.get())) {
                // An unconfirmed local exit must never authorize the next placement.
                setEnabled(client, false);
                ClientChat.send(client, "CushionAura: could not leave the seat; cycle stopped.");
            }
            if (this.cycle.phase() == CushionCycle.Phase.DISMOUNT || !isEnabled()) return;
        }
        if (PlacementCoordinator.busyFor(this.owner)) {
            if (busy()) resetCycle(client, now);
            this.status = "Placement busy";
            return;
        }
        LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
        if (target == null || !target.isAlive()) {
            if (ridingSeat(client) && this.serverMounted) {
                releaseRotation();
                if (this.cycle.phase() != CushionCycle.Phase.SEATED)
                    this.cycle.enter(CushionCycle.Phase.SEATED, now, this.tickNumber);
            } else resetCycle(client, now);
            this.status = "No target";
            return;
        }
        if (this.plannedTarget != -1
                && this.plannedTarget != target.getId()
                && this.cycle.phase() != CushionCycle.Phase.SEATED) {
            resetCycle(client, now);
            return;
        }
        switch (this.cycle.phase()) {
            case SEARCH -> {
                if (this.tickNumber < this.retryAt) return;
                if (client.player.isPassenger()) {
                    this.status = "Leave current seat";
                    return;
                }
                if (!Targeting.isHoldingTriggerWeapon(client)) {
                    this.status = "Hold weapon";
                    return;
                }
                Plan next = findPlan(client, target);
                this.retryAt = this.tickNumber + RETRY.get();
                if (next == null) return;
                startPlan(client, next, target, now);
            }
            case TURN_PLACE, TURN_MOUNT, PLACE, MOUNT -> {
                if (this.cycle.elapsed(now, TIMEOUT.get())) fail(client, now, "Turn timeout");
            }
            case WAIT_ENTITY -> {
                if (this.ownsRotation && !SilentPacketRotation.isUseDone()) {
                    if (this.cycle.elapsed(now, TIMEOUT.get())) fail(client, now, "Use timeout");
                    return;
                }
                releaseRotation();
                Entity seat = findSeat(client, this.plan.position());
                if (seat != null) {
                    if (!CushionCycle.ticksElapsed(
                            this.tickNumber, this.placedAt, MOUNT_DELAY.get())) {
                        this.status = "Mount delay";
                        return;
                    }
                    this.seatId = seat.getId();
                    this.serverMounted = false;
                    turnToSeat(client, seat, now);
                } else if (this.cycle.elapsed(now, TIMEOUT.get()))
                    fail(client, now, "Place unconfirmed");
            }
            case WAIT_RIDE -> {
                if (this.cycle.acknowledgeRide(
                        ridingSeat(client), this.serverMounted, now, this.tickNumber)) {
                    releaseRotation();
                    this.lastSeat = client.player.getVehicle().position();
                    this.retryAt = this.tickNumber;
                    this.status = "Seated";
                } else if (this.cycle.elapsed(now, TIMEOUT.get()))
                    fail(client, now, "Ride unconfirmed");
            }
            case SEATED -> {
                if (!ridingSeat(client) || !this.serverMounted) {
                    fail(client, now, "Dismounted");
                    return;
                }
                this.status = "Seated";
                if (!this.cycle.mayRelocate(
                                this.tickNumber, INTERVAL.get(), ATTACK_BEFORE_MOVE.get())
                        || this.tickNumber < this.retryAt) return;
                Plan next = findPlan(client, target);
                this.retryAt = this.tickNumber + RETRY.get();
                if (next == null) return;
                this.plan = next;
                this.plannedTarget = target.getId();
                this.cycle.enter(CushionCycle.Phase.DISMOUNT, now, this.tickNumber);
                this.status = "Changing seat";
            }
            case DISMOUNT -> {
                /* Handled before target selection so target loss cannot strand an exit. */
            }
        }
    }

    private void startPlan(Minecraft client, Plan next, LivingEntity target, long now) {
        if (!next.existing()) {
            startPlacement(client, next, target, now);
            return;
        }
        if (SilentPacketRotation.isBusy()) return;
        Entity seat = client.level.getEntity(next.entityId());
        if (!reusable(client, seat, target, RADIUS.get(), this.lastSeat)) return;
        this.plan = next;
        this.plannedTarget = target.getId();
        this.seatId = next.entityId();
        this.serverMounted = false;
        turnToSeat(client, seat, now);
    }

    private void startPlacement(Minecraft client, Plan next, LivingEntity target, long now) {
        if (SilentPacketRotation.isBusy() || !this.hotbar.acquire(client, next.slot())) return;
        this.plan = next;
        this.plannedTarget = target.getId();
        this.cycle.enter(CushionCycle.Phase.TURN_PLACE, now);
        this.ownsRotation = true;
        this.status = "Placing";
        this.instantPlace = PLACE_TURN.get() == 0;
        SilentPacketRotation.beginRotation(
                client,
                next.hit().getLocation(),
                PLACE_TURN.get(),
                this.instantPlace
                        ? SilentPacketRotation.Mode.INSTANT
                        : SilentPacketRotation.Mode.SMOOTH,
                () -> {
                    if (this.cycle.phase() == CushionCycle.Phase.TURN_PLACE)
                        this.cycle.enter(CushionCycle.Phase.PLACE, System.nanoTime());
                });
    }

    private void turnToSeat(Minecraft client, Entity seat, long now) {
        if (!seatUsable(client, seat) || SilentPacketRotation.isBusy()) {
            fail(client, now, "Seat unreachable");
            return;
        }
        this.ownsRotation = true;
        this.cycle.enter(CushionCycle.Phase.TURN_MOUNT, now);
        this.status = this.plan != null && this.plan.existing() ? "Reusing seat" : "Mounting";
        this.instantMount = MOUNT_TURN.get() == 0;
        SilentPacketRotation.beginRotation(
                client,
                seat.getBoundingBox().getCenter(),
                MOUNT_TURN.get(),
                this.instantMount
                        ? SilentPacketRotation.Mode.INSTANT
                        : SilentPacketRotation.Mode.SMOOTH,
                () -> {
                    if (this.cycle.phase() == CushionCycle.Phase.TURN_MOUNT)
                        this.cycle.enter(CushionCycle.Phase.MOUNT, System.nanoTime());
                });
    }

    private void interact(Minecraft client) {
        // Applied spawns/passenger updates can arrive after TICK. Consume them here so a
        // zero-delay transition reaches this same interaction window, without another tick.
        advance(client);
        if (!isEnabled()
                || !valid(client)
                || this.player != client.player
                || this.level != client.level
                || PlacementCoordinator.busyFor(this.owner)
                || manualExit(client)) return;
        long now = System.nanoTime();
        if (this.cycle.phase() == CushionCycle.Phase.PLACE) {
            LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
            if (target == null
                    || target.getId() != this.plannedTarget
                    || !CushionPlacement.aroundTarget(
                            this.plan.position(), target.position(), RADIUS.get(), this.lastSeat)) {
                fail(client, now, "Target moved");
                return;
            }
            if (!this.hotbar.active() || !CushionAccess.isItem(client.player.getMainHandItem())) {
                fail(client, now, "Missing cushion");
                return;
            }
            // Another player may have placed/freed a suitable seat while we were turning.
            Plan existing = findExisting(client, target, RADIUS.get(), this.lastSeat);
            if (existing != null) {
                releaseRotation();
                this.hotbar.release(client);
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
                            this.plan.hit().getBlockPos(),
                            Direction.UP);
            if (actual == null || !validPlan(client, actual, client.player.getMainHandItem())) {
                fail(client, now, "Placement obstructed");
                return;
            }
            this.plan =
                    new Plan(
                            actual,
                            CushionAccess.placementPosition(
                                    client, client.player.getMainHandItem(), actual),
                            this.plan.slot());
            if (!aroundTarget(this.plan.position(), target.position(), RADIUS.get(), this.lastSeat)
                    || !Double.isFinite(
                            newSeatScore(
                                    client,
                                    target,
                                    this.plan.position(),
                                    RADIUS.get(),
                                    this.lastSeat))) {
                fail(client, now, "No attack path from seat");
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, actual, !this.instantPlace)) {
                // Keep the item until vanilla has used it, then restore the user's weapon
                // immediately.
                this.hotbar.release(client);
                GameAccess.syncCarriedItem(client.gameMode);
                this.placedAt = this.tickNumber;
                this.cycle.enter(CushionCycle.Phase.WAIT_ENTITY, now, this.tickNumber);
            }
        } else if (this.cycle.phase() == CushionCycle.Phase.MOUNT
                && (this.instantMount || SilentPacketRotation.isRotationPacketSent())) {
            // Dismount's synthetic sneak may still be present until vanilla updates input.
            // Cushion.interact returns PASS for secondary use; wait instead of sending it.
            if (client.player.isSecondaryUseActive()) {
                this.status = "Waiting for sneak release";
                return;
            }
            Entity seat = client.level.getEntity(this.seatId);
            LivingEntity target = SilentAuraRuntime.selectPlacementTarget(client);
            if (target == null
                    || target.getId() != this.plannedTarget
                    || !reusable(client, seat, target, RADIUS.get(), this.lastSeat)
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
            this.cycle.enter(CushionCycle.Phase.WAIT_RIDE, now);
        }
    }

    private Plan findPlan(Minecraft client, LivingEntity target) {
        return preferExisting(
                () -> findExisting(client, target, RADIUS.get(), this.lastSeat),
                () -> {
                    int slot = CushionPlacement.findSlot(client);
                    if (slot == -1) {
                        this.status = "No usable seat or cushions";
                        return null;
                    }
                    Plan next =
                            CushionPlacement.find(
                                    client, target, slot, RADIUS.get(), this.lastSeat);
                    if (next == null) this.status = "No reachable spot";
                    return next;
                });
    }

    private boolean ridingSeat(Minecraft client) {
        return client != null
                && client.player != null
                && client.player == this.player
                && client.player.getVehicle() != null
                && client.player.getVehicle().getId() == this.seatId
                && CushionAccess.isCushion(client.player.getVehicle());
    }

    private void releaseRotation() {
        if (!this.ownsRotation) return;
        SilentPacketRotation.reset();
        this.ownsRotation = false;
    }

    private void resetCycle(Minecraft client, long now) {
        releaseRotation();
        this.hotbar.release(client);
        this.cycle.enter(CushionCycle.Phase.SEARCH, now);
        this.plan = null;
        this.seatId = -1;
        this.plannedTarget = -1;
        this.serverMounted = false;
    }

    private void fail(Minecraft client, long now, String reason) {
        resetCycle(client, now);
        this.retryAt = this.tickNumber + RETRY.get();
        this.status = reason;
    }

    public void shutdown(Minecraft client) {
        resetCycle(client, System.nanoTime());
        this.player = null;
        this.level = null;
        this.lastSeat = null;
        this.retryAt = 0;
        this.placedAt = 0;
        this.status = "Idle";
    }

    @Override
    protected Setting[] defineSettings() {
        return CushionAura.settings();
    }
}
