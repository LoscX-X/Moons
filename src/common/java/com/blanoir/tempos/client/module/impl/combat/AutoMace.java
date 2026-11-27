package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.access.PacketAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.IntSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.input.PhysicalInput;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.lease.HotbarLease;
import com.blanoir.tempos.client.manager.lease.RotationLease;
import com.blanoir.tempos.client.manager.placement.PlacementCoordinator;
import com.blanoir.tempos.client.manager.rotation.RotationRequest;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.player.HotbarQueries;
import com.blanoir.tempos.client.utils.raytrace.EntityRaycast;
import com.blanoir.tempos.client.utils.raytrace.LineOfSight;
import com.blanoir.tempos.client.utils.rotation.Rotation;
import com.blanoir.tempos.client.utils.rotation.aim.AimAngles;

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
@ModuleInfo(
        category = ModuleCategory.COMBAT,
        description = "Coordinates a wind charge or pearl with a falling mace attack.")
public final class AutoMace extends ClientFeature {
    public static final AutoMace INSTANCE = new AutoMace();

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("automace.enabled").defaultValue(false).build();
    private final DoubleSetting range =
            new DoubleSetting.Builder()
                    .name("automace.range")
                    .defaultValue(3.0)
                    .range(1.0, 3.0)
                    .build();
    private final IntSetting cooldownSetting =
            new IntSetting.Builder()
                    .name("automace.cooldown")
                    .defaultValue(40)
                    .range(20, 200)
                    .build();
    private final BooleanSetting requireAttack =
            new BooleanSetting.Builder().name("automace.requireAttack").defaultValue(true).build();
    private final AutoMaceCycle cycle = new AutoMaceCycle();
    private final HotbarLease hotbar = new HotbarLease("AutoMace", HotbarLease.PRIORITY_PLACEMENT);
    private final RotationLease rotation =
            new RotationLease(
                    "AutoMace",
                    RotationLease.PRIORITY_BLOCK_INTERACTION,
                    this::isBusy,
                    this::publishRotation);

    private LocalPlayer player;
    private ClientLevel level;
    private Player target;
    private Rotation aim;
    private int cooldown;
    private boolean attacking;
    private boolean using;
    private boolean useSubmitted;

    private AutoMace() {
        super("automace", "AutoMace");
    }

    @Override
    public void registerEvents() {
        PlacementCoordinator.register(PlacementCoordinator.Owner.AUTO_MACE, this::isBusy);
        EventBus.PLAYER_UPDATE.register(
                "AutoMace.update", EventPriority.HIGH, e -> tick(e.client()));
        EventBus.TICK.register(
                "AutoMace.cleanup",
                e -> {
                    if (isBusy() && (!this.enabled.get() || !ready(e.client()))) reset(e.client());
                });
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoMace.context", e -> reset(null));
        EventBus.ATTACK_INPUT_PRE.register(
                "AutoMace.attack",
                EventPriority.HIGHEST,
                e -> {
                    if (isBusy() && !this.attacking) e.cancel();
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
                    if (this.using
                            && this.player != null
                            && e.connection() == this.player.connection.getConnection()
                            && e.packet() instanceof ServerboundUseItemPacket packet
                            && this.aim != null
                            && PacketAccess.useItemYaw(packet) == this.aim.yaw()
                            && PacketAccess.useItemPitch(packet) == this.aim.pitch())
                        this.useSubmitted = true;
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "AutoMace.teleport",
                e -> {
                    if (isBusy()
                            && e.listener() == Minecraft.getInstance().getConnection()
                            && e.packet() instanceof ClientboundPlayerPositionPacket) {
                        if (this.cycle.phase() == AutoMaceCycle.Phase.WAIT_TELEPORT)
                            this.cycle.positionReply();
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

    private void tick(Minecraft client) {
        if (this.cooldown > 0) this.cooldown--;
        if (!this.enabled.get() || !ready(client)) {
            if (isBusy()) reset(client);
            return;
        }
        if (!isBusy()) {
            tryBegin(client);
            return;
        }
        if (client.player != this.player
                || client.level != this.level
                || this.cycle.expired()
                || !Targeting.isValidTargetPlayer(client, this.target)
                || this.level.getEntity(this.target.getId()) != this.target
                || this.player.isUsingItem()
                || !this.hotbar.active()
                || !this.rotation.active()
                || this.player.getInventory().getSelectedSlot() != this.hotbar.leasedSlot()) {
            finish(client);
            return;
        }
        switch (this.cycle.phase()) {
            case AIM -> {
                if (!this.player.onGround()
                        || !CombatReach.within(client, this.target, this.range.get())) {
                    finish(client);
                } else if (this.rotation.confirmed()) {
                    launch(client);
                }
            }
            case WAIT_TELEPORT -> {
                if (this.cycle.confirmTeleport(this.player.position())) aimAtTarget(client);
            }
            case FALL -> fall(client);
            default -> {}
        }
    }

    private void tryBegin(Minecraft client) {
        if (this.cooldown > 0
                || !client.player.onGround()
                || client.player.isUsingItem()
                || PlacementCoordinator.busy()
                || HotbarLease.isHeld()
                || this.requireAttack.get()
                        && !PhysicalInput.isGameplayKeyDown(client, client.options.keyAttack)
                || availableSlot(client, Items.WIND_CHARGE) < 0
                || availableSlot(client, Items.ENDER_PEARL) < 0
                || HotbarQueries.firstItem(client, Items.MACE) < 0) return;
        Player closest = null;
        double distance = Double.MAX_VALUE;
        for (Player candidate : client.level.players()) {
            if (!Targeting.isValidTargetPlayer(client, candidate)
                    || !CombatReach.within(client, candidate, this.range.get())
                    || !LineOfSight.canRayTraceTo(
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
        this.aim = new Rotation(client.player.getYRot(), -90);
        if (!this.rotation.acquire(
                new RotationRequest(this.aim.yaw(), this.aim.pitch(), 1, .1F, null))) return;
        if (!this.hotbar.acquire(client, availableSlot(client, Items.WIND_CHARGE))) {
            this.rotation.release();
            return;
        }
        this.player = client.player;
        this.level = client.level;
        this.target = closest;
        this.cycle.begin();
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

    private void launch(Minecraft client) {
        int wind = availableSlot(client, Items.WIND_CHARGE);
        int pearl = availableSlot(client, Items.ENDER_PEARL);
        if (wind < 0
                || pearl < 0
                || HotbarQueries.firstItem(client, Items.MACE) < 0
                || !clearAbove(client)) {
            finish(client);
            return;
        }
        Vec3 inherited = this.player.getDeltaMovement().multiply(1, 0, 1);
        Vec3 impact =
                AutoMaceCycle.firstImpact(
                        this.player.getEyePosition(),
                        inherited,
                        Vec3.directionFromRotation(this.aim.pitch(), this.aim.yaw()),
                        0);
        if (impact == null || !this.rotation.pin()) {
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
            this.cycle.launched(this.player.position(), impact);
        } finally {
            this.rotation.unpin();
        }
    }

    private boolean use(Minecraft client, int slot, Item expected) {
        if (!this.player.getInventory().getItem(slot).is(expected)
                || !this.hotbar.acquire(client, slot)) return false;
        float yaw = this.player.getYRot(), pitch = this.player.getXRot();
        this.useSubmitted = false;
        this.using = true;
        try {
            this.player.setYRot(this.aim.yaw());
            this.player.setXRot(this.aim.pitch());
            var result = client.gameMode.useItem(this.player, InteractionHand.MAIN_HAND);
            if (result.consumesAction())
                MinecraftClientAccess.animatePlacement(
                        this.player, InteractionHand.MAIN_HAND, true);
            return result.consumesAction() && this.useSubmitted;
        } finally {
            this.using = false;
            this.player.setYRot(yaw);
            this.player.setXRot(pitch);
        }
    }

    private void aimAtTarget(Minecraft client) {
        Rotation next =
                AimAngles.rotationTo(
                        this.player.getEyePosition(), this.target.getBoundingBox().getCenter());
        if (this.rotation.acquire(new RotationRequest(next.yaw(), next.pitch(), 1, .35F, null)))
            this.aim = next;
    }

    private void fall(Minecraft client) {
        if (this.player.onGround()) {
            finish(client);
            return;
        }
        if (this.player.getDeltaMovement().y >= 0) {
            aimAtTarget(client);
            return;
        }
        int mace = HotbarQueries.firstItem(client, Items.MACE);
        if (mace < 0 || !this.hotbar.acquire(client, mace)) {
            finish(client);
            return;
        }
        // Check the last confirmed rotation against current geometry before refreshing it.
        double range = CombatReach.entityInteractionRange(client, this.range.get());
        if (MaceItem.canSmashAttack(this.player)
                && this.rotation.confirmed()
                && CombatReach.within(client, this.target, range)
                && EntityRaycast.findEntityOnRay(
                                client,
                                this.player.getEyePosition(),
                                Vec3.directionFromRotation(this.aim.pitch(), this.aim.yaw()),
                                range,
                                entity -> entity.isPickable() && !entity.isSpectator(),
                                false)
                        == this.target) {
            publishRotation();
            this.attacking = true;
            boolean dispatched;
            try {
                dispatched = CombatInputController.attackTargetNow(client, this.target, true);
            } finally {
                this.attacking = false;
            }
            if (dispatched) {
                finish(client);
                return;
            }
        }
        aimAtTarget(client);
    }

    private void publishRotation() {
        if (this.aim != null) this.rotation.commit(this.aim, true, true);
    }

    private void finish(Minecraft client) {
        reset(client);
        this.cooldown = this.cooldownSetting.get();
    }

    public void reset(Minecraft client) {
        this.cycle.reset();
        this.rotation.cancelPending();
        this.rotation.unpin();
        this.rotation.release();
        this.hotbar.release(client);
        this.player = null;
        this.level = null;
        this.target = null;
        this.aim = null;
        this.attacking = false;
        this.using = false;
        this.useSubmitted = false;
        this.cooldown = 0;
    }

    public boolean isBusy() {
        return this.cycle.active();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled.get();
    }

    @Override
    public String statusTag() {
        return this.cycle.phase().name();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        this.enabled.set(value);
        reset(client);
        ClientChat.send(client, "AutoMace " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public int setRange(Minecraft client, double value) {
        this.range.set(value);
        return 1;
    }

    public int setCooldown(Minecraft client, int value) {
        this.cooldownSetting.set(value);
        return 1;
    }

    public int setRequireAttack(Minecraft client, boolean value) {
        this.requireAttack.set(value);
        return 1;
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            requireAttack.describeStored(
                    "require_attack", "Hold attack to start", this::setRequireAttack),
            range.describeStored("range", "Target range", .1, this::setRange),
            cooldownSetting.describeStored(
                    "cooldown", "Combo cooldown (ticks)", 5, this::setCooldown)
        };
    }
}
