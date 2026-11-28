package com.blanoir.tempos.client.module.impl.movement;

import com.blanoir.tempos.client.access.GameAccess;
import com.blanoir.tempos.client.chat.ClientChat;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.config.settings.ModeSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.movement.MovementInputUpdatedEvent;
import com.blanoir.tempos.client.lifecycle.ClientReady;
import com.blanoir.tempos.client.manager.combat.CombatGeometry;
import com.blanoir.tempos.client.manager.combat.CombatModuleCoordinator;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.manager.rotation.MoveFix;
import com.blanoir.tempos.client.manager.targeting.Targeting;
import com.blanoir.tempos.client.module.framework.ClientFeature;
import com.blanoir.tempos.client.module.framework.ModuleCategory;
import com.blanoir.tempos.client.module.framework.ModuleInfo;
import com.blanoir.tempos.client.module.framework.ModuleModel.Setting;
import com.blanoir.tempos.client.module.impl.combat.SprintReset;
import com.blanoir.tempos.client.module.impl.combat.TriggerBot;
import com.blanoir.tempos.client.module.impl.combat.Velocity;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraConfig;
import com.blanoir.tempos.client.module.impl.combat.silentaura.SilentAuraRuntime;
import com.blanoir.tempos.client.module.impl.movement.strafe.Legit2Strafe;
import com.blanoir.tempos.client.module.impl.movement.strafe.LegitStrafe;
import com.blanoir.tempos.client.module.impl.movement.strafe.MovementSimulation;
import com.blanoir.tempos.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.tempos.client.utils.combat.CombatReach;
import com.blanoir.tempos.client.utils.prediction.CooldownPrediction;
import com.blanoir.tempos.client.utils.prediction.MotionPrediction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Owns this tick's directional input while the selected combat target is in front. */
@ModuleInfo(
        category = ModuleCategory.MOVEMENT,
        description = "Controls combat spacing and strafing through ordinary movement input.")
public final class Strafe extends ClientFeature {
    public static final Strafe INSTANCE = new Strafe();

    private enum Mode {
        LEGIT,
        LEGIT2
    }

    private enum CombatRules {
        AUTO,
        LEGACY,
        LATEST
    }

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("strafe.enabled").defaultValue(false).build();
    private final ModeSetting<Mode> mode =
            new ModeSetting.Builder<Mode>()
                    .name("strafe.mode")
                    .defaultValue(Mode.LEGIT)
                    .option(Mode.LEGIT, "legit")
                    .option(Mode.LEGIT2, "legit2")
                    .build();
    private final ModeSetting<CombatRules> combatRules =
            new ModeSetting.Builder<CombatRules>()
                    .name("strafe.combatRules")
                    .defaultValue(CombatRules.AUTO)
                    .option(CombatRules.AUTO, "auto")
                    .option(CombatRules.LEGACY, "legacy")
                    .option(CombatRules.LATEST, "latest")
                    .build();
    private final DoubleSetting range =
            new DoubleSetting.Builder().name("strafe.range").defaultValue(4).range(2, 6).build();
    private final DoubleSetting margin =
            new DoubleSetting.Builder()
                    .name("strafe.margin")
                    .defaultValue(0.15)
                    .range(0.05, 0.6)
                    .build();
    private final BooleanSetting resetAssist =
            new BooleanSetting.Builder().name("strafe.resetAssist").defaultValue(true).build();
    private final LegitStrafe controller = new LegitStrafe();
    private final Legit2Strafe legit2 = new Legit2Strafe();
    private final MotionPrediction motion =
            new MotionPrediction(MotionPrediction.Parameters.builder().verticalScale(1).build());
    private LivingEntity target;
    private int lastFacingTick;
    private Vec3 lastPosition;

    private Strafe() {
        super("strafe", "Strafe [Beta]");
    }

    @Override
    public void registerEvents() {
        Velocity.INSTANCE.bindJumpResetAssist(this::resetAssistActive);
        SprintReset.INSTANCE.bindAssist(this::resetAssistActive);
        EventBus.MOVEMENT_INPUT_UPDATED.register("Strafe.input", this::move);
        EventBus.CLIENT_CONTEXT_CHANGED.register("Strafe.context", event -> reset());
        EventBus.ATTACK_ENTITY_POST.register(
                "Strafe.attack",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (isEnabled()
                            && ClientReady.aliveGameplay(client)
                            && event.attacker() == client.player
                            && event.target() instanceof LivingEntity living) {
                        track(living, client.player.tickCount);
                        if (mode.get() == Mode.LEGIT2) {
                            legit2.attacked(
                                    client.player.tickCount, latency(client, client.player));
                        }
                    }
                });
        EventBus.PACKET_RECEIVE_APPLY.register(
                "Strafe.trade",
                event -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!isEnabled()
                            || mode.get() != Mode.LEGIT2
                            || !ClientReady.aliveGameplay(client)
                            || event.listener() != client.getConnection()
                            || target == null
                            || client.level.getEntity(target.getId()) != target
                            || !(event.packet() instanceof ClientboundDamageEventPacket damage))
                        return;
                    int tick = client.player.tickCount;
                    if (damage.entityId() == target.getId()
                            && damage.sourceCauseId() == client.player.getId()
                            && damage.sourceDirectId() == client.player.getId())
                        legit2.confirmedHit(tick);
                    else if (damage.entityId() == client.player.getId()
                            && damage.sourceCauseId() == target.getId()
                            && damage.sourceDirectId() == target.getId()) legit2.incomingHit(tick);
                });
    }

    private void move(MovementInputUpdatedEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (CombatModuleCoordinator.automationSuspended()
                || !isEnabled()
                || !ClientReady.aliveGameplay(client)) {
            reset();
            return;
        }
        var player = client.player;
        var input = event.input().keyPresses;
        if (input.shift()
                || player.isSpectator()
                || player.isPassenger()
                || player.getAbilities().flying
                || player.isFallFlying()
                || player.isInWater()
                || player.isInLava()
                || player.onClimbable()
                || player.isUsingItem()
                || Scaffold.INSTANCE.isEnabled()) {
            reset();
            return;
        }
        MoveFix.State movement = MoveFix.current(client);
        if (!movement.owner().isEmpty() && !movement.owner().equals("SilentAura")) {
            reset();
            return;
        }
        boolean aura = SilentAuraRuntime.activationHeld(client);
        Vec3 look = player.getViewVector(1);
        if (aura) {
            var rotation = SilentAuraRuntime.attackRotation(client);
            if (!rotation.valid()) {
                reset();
                return;
            }
            look = rotation.look();
        }
        Vec3 combatLook = look;
        CombatGeometry.withOriginalPositions(
                () -> moveToward(client, event, movement, aura, combatLook));
    }

    private void moveToward(
            Minecraft client,
            MovementInputUpdatedEvent event,
            MoveFix.State movement,
            boolean aura,
            Vec3 look) {
        var player = client.player;
        int tick = player.tickCount;
        LivingEntity selected = selectTarget(client, aura, look);
        if (selected != null) track(selected, tick);
        if (target == null
                || !eligible(client, target, aura)
                || tick < lastFacingTick
                || tick - lastFacingTick > 6
                || !facing(player.getEyePosition(), look, target)) {
            reset();
            return;
        }
        Vec3 position = target.position();
        if (lastPosition != null && position.distanceToSqr(lastPosition) > 4) {
            reset();
            return;
        }
        motion.observe(tick, position, target.getDeltaMovement());
        lastPosition = position;
        Vec3 velocity = motion.displacement(1);
        Vec3 interpolationDelta = target.getPositionCodec().getBase().subtract(position);
        // A teleport is not a plausible combat interpolation interval.
        if (interpolationDelta.lengthSqr() > 4) interpolationDelta = Vec3.ZERO;
        double latencyTicks = latency(client, player) / 100.0;
        double reach = CombatReach.vanillaEntityInteractionRange(player);
        double targetReach =
                target instanceof Player opponent
                        ? CombatReach.vanillaEntityInteractionRange(opponent)
                        : reach;
        boolean legacy =
                mode.get() == Mode.LEGIT2
                        ? legacyRules(client)
                        : aura && SilentAuraConfig.legacyCombat();
        int attackIn =
                legacy
                        ? 0
                        : CooldownPrediction.ticksUntilThreshold(
                                player.getAttackStrengthScale(0),
                                0.9,
                                player.getCurrentItemAttackStrengthDelay());
        int preferred = (player.getId() + target.getId()) % 2 == 0 ? 1 : -1;
        var frame =
                new LegitStrafe.Frame(
                        new LegitStrafe.Body(
                                player.getBoundingBox(),
                                player.getEyePosition(),
                                player.getDeltaMovement(),
                                player.onGround()),
                        new LegitStrafe.Body(
                                target.getBoundingBox(),
                                target.getEyePosition(),
                                velocity,
                                target.onGround()),
                        interpolationDelta,
                        look,
                        movement.active() ? movement.yaw() : player.getYRot(),
                        reach,
                        targetReach,
                        MovementSimulation.sample(client, player),
                        event.input().keyPresses.jump(),
                        latencyTicks,
                        margin.get(),
                        attackIn,
                        aura,
                        CombatInputController.isForwardSuppressed(),
                        preferred);
        LivingEntity opponent = target;
        LegitStrafe.Collision collision =
                (local, box, requested) ->
                        Entity.collideBoundingBox(
                                local ? player : opponent, requested, box, client.level, List.of());
        var direction =
                mode.get() == Mode.LEGIT2
                        ? legit2.choose(frame, collision, tick)
                        : controller.choose(frame, collision);
        // This event follows MoveFix. Evaluate and apply once in its captured movement yaw.
        event.input().keyPresses =
                direction.apply(
                        event.input().keyPresses, CombatInputController.isSprintSuppressed());
        GameAccess.moveVector(
                (ClientInput) event.input(),
                new Vec2(direction.sideways(), direction.forward()).normalized());
    }

    private LivingEntity selectTarget(Minecraft client, boolean aura, Vec3 look) {
        if (aura) return SilentAuraRuntime.currentTarget(client);
        Entity selected =
                TriggerBot.INSTANCE.isEnabled()
                        ? TriggerBot.INSTANCE.movementTarget(client, range.get())
                        : CombatGeometry.findTargetOnRay(
                                client,
                                client.player.getEyePosition(),
                                look,
                                range.get(),
                                EntitySelector.CAN_BE_PICKED,
                                false);
        return selected instanceof LivingEntity living && eligible(client, living, false)
                ? living
                : null;
    }

    private boolean eligible(Minecraft client, LivingEntity living, boolean aura) {
        if (!living.isAlive()
                || living.isRemoved()
                || client.level.getEntity(living.getId()) != living
                || living.getBoundingBox().distanceToSqr(client.player.getEyePosition())
                        > range.get() * range.get()
                || !client.player.hasLineOfSight(living)) return false;
        if (aura) return living == SilentAuraRuntime.currentTarget(client);
        return TriggerBot.INSTANCE.isEnabled()
                ? TriggerBot.INSTANCE.isMovementTarget(client, living)
                : Targeting.isConfiguredTarget(client, living, true, true, List.of());
    }

    private static boolean facing(Vec3 eye, Vec3 look, LivingEntity target) {
        Vec3 toward = target.getBoundingBox().getCenter().subtract(eye).normalize();
        return look.dot(toward) >= 0.75;
    }

    private void track(LivingEntity living, int tick) {
        if (target != living) {
            motion.reset();
            controller.reset();
            legit2.reset();
            lastPosition = null;
        }
        target = living;
        lastFacingTick = tick;
    }

    private boolean resetAssistActive() {
        Minecraft client = Minecraft.getInstance();
        return !CombatModuleCoordinator.automationSuspended()
                && isEnabled()
                && resetAssist.get()
                && target != null
                && ClientReady.aliveGameplay(client)
                && !client.player.isShiftKeyDown()
                && !client.player.isUsingItem()
                && !Scaffold.INSTANCE.isEnabled()
                && client.player.tickCount - lastFacingTick <= 2
                && eligible(client, target, SilentAuraRuntime.activationHeld(client));
    }

    private boolean legacyRules(Minecraft client) {
        return switch (combatRules.get()) {
            case LEGACY -> true;
            case LATEST -> false;
            case AUTO ->
                    SilentAuraRuntime.activationHeld(client) && SilentAuraConfig.legacyCombat();
        };
    }

    private static int latency(Minecraft client, Player player) {
        var connection = client.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
        return info == null ? 0 : Math.clamp(info.getLatency(), 0, 1000);
    }

    public void reset() {
        target = null;
        lastPosition = null;
        motion.reset();
        controller.reset();
        legit2.reset();
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public int setEnabled(Minecraft client, boolean value) {
        enabled.set(value);
        reset();
        ClientChat.send(client, "Strafe " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    @Override
    protected String statusTag() {
        return mode.serialized();
    }

    @Override
    protected Setting[] defineSettings() {
        return new Setting[] {
            mode.describeStored(
                    "mode",
                    "Mode",
                    (client, value) -> {
                        mode.deserialize(value);
                        reset();
                        return 1;
                    }),
            combatRules
                    .describeStored(
                            "combat_rules",
                            "Combat rules",
                            (client, value) -> {
                                combatRules.deserialize(value);
                                reset();
                                return 1;
                            })
                    .visibleWhen(() -> mode.get() == Mode.LEGIT2),
            range.describe(
                    "range",
                    "Activation range",
                    0.1,
                    (client, value) -> {
                        range.set(value);
                        return 1;
                    }),
            margin.describe(
                    "margin",
                    "Reach margin",
                    0.01,
                    (client, value) -> {
                        margin.set(value);
                        return 1;
                    }),
            resetAssist.describeStored(
                    "reset_assist",
                    "Reset assist",
                    (client, value) -> {
                        resetAssist.set(value);
                        return 1;
                    })
        };
    }
}
