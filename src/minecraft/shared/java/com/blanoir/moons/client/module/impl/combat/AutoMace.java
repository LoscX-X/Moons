package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.access.PacketAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.management.lease.RotationLease;
import com.blanoir.moons.client.management.rotation.RotationRequest;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.combat.CombatReach;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.raytrace.RaytraceUtils;
import com.blanoir.moons.client.utils.rotation.Rotation;
import com.blanoir.moons.client.utils.rotation.aim.AimSolverD;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

/** Upward wind/pearl collision followed by a single, ray-checked falling mace attack. */
public final class AutoMace {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("automace.enabled").defaultValue(false).build();
    private static final DoubleSetting RANGE =
            new DoubleSetting.Builder()
                    .name("automace.range")
                    .defaultValue(3.0)
                    .range(1.0, 3.0)
                    .build();
    private static final IntSetting COOLDOWN =
            new IntSetting.Builder()
                    .name("automace.cooldown")
                    .defaultValue(40)
                    .range(20, 200)
                    .build();
    private static final BooleanSetting REQUIRE_ATTACK =
            new BooleanSetting.Builder().name("automace.requireAttack").defaultValue(true).build();
    private static final AutoMaceCycle CYCLE = new AutoMaceCycle();
    private static final HotbarLease HOTBAR =
            new HotbarLease("AutoMace", HotbarLease.PRIORITY_PLACEMENT);
    private static final RotationLease ROTATION =
            new RotationLease(
                    "AutoMace",
                    RotationLease.PRIORITY_BLOCK_INTERACTION,
                    AutoMace::isBusy,
                    AutoMace::publishRotation);

    private static LocalPlayer player;
    private static ClientLevel level;
    private static Player target;
    private static Rotation aim;
    private static int cooldown;
    private static boolean attacking;
    private static boolean using;
    private static boolean useSubmitted;

    private AutoMace() {}

    public static void init() {
        PlacementCoordinator.register(PlacementCoordinator.Owner.AUTO_MACE, AutoMace::isBusy);
        EventBus.PLAYER_UPDATE.register(
                "AutoMace.update", EventPriority.HIGH, e -> tick(e.client()));
        EventBus.TICK.register(
                "AutoMace.cleanup",
                e -> {
                    if (isBusy() && (!ENABLED.get() || !ready(e.client()))) reset(e.client());
                });
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoMace.context", e -> reset(null));
        EventBus.ATTACK_INPUT_PRE.register(
                "AutoMace.attack",
                EventPriority.HIGHEST,
                e -> {
                    if (isBusy() && !attacking) e.cancel();
                });
        EventBus.USE_INPUT_PRE.register(
                "AutoMace.use",
                EventPriority.HIGHEST,
                e -> {
                    if (isBusy()) e.cancel();
                });
        EventBus.PACKET_SEND_POST.register(
                "AutoMace.useSubmitted",
                e -> {
                    if (using
                            && player != null
                            && e.connection() == player.connection.getConnection()
                            && e.packet() instanceof ServerboundUseItemPacket packet
                            && aim != null
                            && PacketAccess.useItemYaw(packet) == aim.yaw()
                            && PacketAccess.useItemPitch(packet) == aim.pitch())
                        useSubmitted = true;
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "AutoMace.teleport",
                e -> {
                    if (isBusy()
                            && e.listener() == Minecraft.getInstance().getConnection()
                            && e.packet() instanceof ClientboundPlayerPositionPacket) {
                        if (CYCLE.phase() == AutoMaceCycle.Phase.WAIT_TELEPORT)
                            CYCLE.positionReply();
                        else reset(Minecraft.getInstance());
                    }
                });
    }

    private static boolean ready(Minecraft client) {
        return ClientReady.aliveGameplay(client)
                && client.isWindowActive()
                && !client.isPaused()
                && !client.player.isSpectator()
                && !client.player.isPassenger()
                && !client.player.isFallFlying()
                && !client.player.getAbilities().flying
                && !client.player.isInWater()
                && !client.player.isInLava();
    }

    private static void tick(Minecraft client) {
        if (cooldown > 0) cooldown--;
        if (!ENABLED.get() || !ready(client)) {
            if (isBusy()) reset(client);
            return;
        }
        if (!isBusy()) {
            tryBegin(client);
            return;
        }
        if (client.player != player
                || client.level != level
                || CYCLE.expired()
                || !Targeting.isValidTargetPlayer(client, target)
                || level.getEntity(target.getId()) != target
                || player.isUsingItem()
                || !HOTBAR.active()
                || !ROTATION.active()
                || player.getInventory().getSelectedSlot() != HOTBAR.leasedSlot()) {
            finish(client);
            return;
        }
        switch (CYCLE.phase()) {
            case AIM -> {
                if (!player.onGround() || !CombatReach.within(client, target, RANGE.get())) {
                    finish(client);
                } else if (ROTATION.confirmed()) {
                    launch(client);
                }
            }
            case WAIT_TELEPORT -> {
                if (CYCLE.confirmTeleport(player.position())) aimAtTarget(client);
            }
            case FALL -> fall(client);
            default -> {}
        }
    }

    private static void tryBegin(Minecraft client) {
        if (cooldown > 0
                || !client.player.onGround()
                || client.player.isUsingItem()
                || PlacementCoordinator.busy()
                || HotbarLease.isHeld()
                || REQUIRE_ATTACK.get()
                        && !CombatInputController.isPhysicallyDown(client, client.options.keyAttack)
                || availableSlot(client, Items.WIND_CHARGE) < 0
                || availableSlot(client, Items.ENDER_PEARL) < 0
                || HotbarQueries.firstItem(client, Items.MACE) < 0) return;
        Player closest = null;
        double distance = Double.MAX_VALUE;
        for (Player candidate : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, candidate)
                    || !CombatReach.within(client, candidate, RANGE.get())
                    || !RaytraceUtils.canRayTraceTo(
                            client,
                            client.player.getEyePosition(),
                            candidate.getBoundingBox().getCenter())) continue;
            double current = candidate.distanceToSqr(client.player);
            if (current < distance) {
                closest = candidate;
                distance = current;
            }
        }
        if (closest == null || !clearAbove(client)) return;
        aim = new Rotation(client.player.getYRot(), -90);
        if (!ROTATION.acquire(new RotationRequest(aim.yaw(), aim.pitch(), 1, .1F, null))) return;
        if (!HOTBAR.acquire(client, availableSlot(client, Items.WIND_CHARGE))) {
            ROTATION.release();
            return;
        }
        player = client.player;
        level = client.level;
        target = closest;
        CYCLE.begin();
    }

    private static boolean clearAbove(Minecraft client) {
        // Clearance for both projectiles and the player's body after teleporting upward.
        return client.level.noCollision(
                client.player,
                client.player.getBoundingBox().expandTowards(0, 4.5, 0).deflate(.01));
    }

    private static int availableSlot(Minecraft client, Item item) {
        int slot = HotbarQueries.firstItem(client, item);
        return slot >= 0
                        && !client.player
                                .getCooldowns()
                                .isOnCooldown(client.player.getInventory().getItem(slot))
                ? slot
                : -1;
    }

    private static void launch(Minecraft client) {
        int wind = availableSlot(client, Items.WIND_CHARGE);
        int pearl = availableSlot(client, Items.ENDER_PEARL);
        if (wind < 0
                || pearl < 0
                || HotbarQueries.firstItem(client, Items.MACE) < 0
                || !clearAbove(client)) {
            finish(client);
            return;
        }
        Vec3 inherited = player.getDeltaMovement().multiply(1, 0, 1);
        Vec3 impact =
                AutoMaceCycle.firstImpact(
                        player.getEyePosition(),
                        inherited,
                        Vec3.directionFromRotation(aim.pitch(), aim.yaw()),
                        0);
        if (impact == null || !ROTATION.pin()) {
            finish(client);
            return;
        }
        try {
            // Same rotation and ordered native use calls in one client tick. Waiting for the
            // wind spawn reply introduces at least RTT delay and loses the collision window.
            publishRotation();
            if (!use(client, wind, Items.WIND_CHARGE) || !use(client, pearl, Items.ENDER_PEARL)) {
                finish(client);
                return;
            }
            CYCLE.launched(player.position(), impact);
        } finally {
            ROTATION.unpin();
        }
    }

    private static boolean use(Minecraft client, int slot, Item expected) {
        if (!player.getInventory().getItem(slot).is(expected) || !HOTBAR.acquire(client, slot))
            return false;
        float yaw = player.getYRot(), pitch = player.getXRot();
        useSubmitted = false;
        using = true;
        try {
            player.setYRot(aim.yaw());
            player.setXRot(aim.pitch());
            var result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            if (result.consumesAction())
                MinecraftClientAccess.animatePlacement(player, InteractionHand.MAIN_HAND, true);
            return result.consumesAction() && useSubmitted;
        } finally {
            using = false;
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private static void aimAtTarget(Minecraft client) {
        Rotation next =
                AimSolverD.rotationTo(player.getEyePosition(), target.getBoundingBox().getCenter());
        if (ROTATION.acquire(new RotationRequest(next.yaw(), next.pitch(), 1, .35F, null)))
            aim = next;
    }

    private static void fall(Minecraft client) {
        if (player.onGround()) {
            finish(client);
            return;
        }
        if (player.getDeltaMovement().y >= 0) {
            aimAtTarget(client);
            return;
        }
        int mace = HotbarQueries.firstItem(client, Items.MACE);
        if (mace < 0 || !HOTBAR.acquire(client, mace)) {
            finish(client);
            return;
        }
        // Check the last confirmed rotation against current geometry before refreshing it.
        double range = CombatReach.entityInteractionRange(client, RANGE.get());
        if (MaceItem.canSmashAttack(player)
                && ROTATION.confirmed()
                && CombatReach.within(client, target, range)
                && RaytraceUtils.findEntityOnRay(
                                client,
                                player.getEyePosition(),
                                Vec3.directionFromRotation(aim.pitch(), aim.yaw()),
                                range,
                                entity -> entity.isPickable() && !entity.isSpectator(),
                                false)
                        == target) {
            publishRotation();
            attacking = true;
            boolean dispatched;
            try {
                dispatched = CombatInputController.attackTargetNow(client, target, true);
            } finally {
                attacking = false;
            }
            if (dispatched) {
                finish(client);
                return;
            }
        }
        aimAtTarget(client);
    }

    private static void publishRotation() {
        if (aim != null) ROTATION.commit(aim, true, true);
    }

    private static void finish(Minecraft client) {
        reset(client);
        cooldown = COOLDOWN.get();
    }

    public static void reset(Minecraft client) {
        CYCLE.reset();
        ROTATION.cancelPending();
        ROTATION.unpin();
        ROTATION.release();
        HOTBAR.release(client);
        player = null;
        level = null;
        target = null;
        aim = null;
        attacking = false;
        using = false;
        useSubmitted = false;
        cooldown = 0;
    }

    public static boolean isBusy() {
        return CYCLE.active();
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String statusTag() {
        return CYCLE.phase().name();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset(client);
        ClientChat.send(client, "AutoMace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setRange(Minecraft client, double value) {
        RANGE.set(value);
        return 1;
    }

    public static int setCooldown(Minecraft client, int value) {
        COOLDOWN.set(value);
        return 1;
    }

    public static int setRequireAttack(Minecraft client, boolean value) {
        REQUIRE_ATTACK.set(value);
        return 1;
    }
}
