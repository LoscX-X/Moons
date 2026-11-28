package com.blanoir.tempos.client.module.impl.combat.velocity;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.config.settings.BooleanSetting;
import com.blanoir.tempos.client.config.settings.DoubleSetting;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.movement.MovementInputUpdatedEvent;
import com.blanoir.tempos.client.event.tick.TickEvent;
import com.blanoir.tempos.client.manager.input.CombatInputController;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.module.impl.combat.SprintReset;
import com.blanoir.tempos.client.module.impl.combat.Velocity;
import com.blanoir.tempos.client.utils.math.RandomChance;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;

public final class JumpReset extends SubFeature.Switchable<Velocity> {
    private static final int RESET_COOLDOWN_TICKS = 2;

    private boolean fallDamageVelocity = false;
    private int fallDamageVelocityTicks = 0;
    private int resetCooldownTicks = 0;
    private int pendingDamageTick = Integer.MIN_VALUE;
    private BooleanSupplier modeCheck = () -> false;
    private BooleanSupplier assist = () -> false;

    private final BooleanSetting enabled =
            new BooleanSetting.Builder().name("jumpreset.enabled").defaultValue(false).build();

    private final DoubleSetting chance =
            new DoubleSetting.Builder()
                    .name("jumpreset.chance")
                    .defaultValue(0.5D)
                    .min(0.0D)
                    .max(1.0D)
                    .build();

    public JumpReset(Velocity parent) {
        super(parent);
    }

    public void initPacketListeners() {
        VelocityPacketListener.initJumpReset(this);
    }

    @Override
    public void registerEvents() {
        EventBus.TICK.register("JumpReset.tick", this::tick);
        EventBus.MOVEMENT_INPUT_UPDATED.register("JumpReset.input", this::input);
        EventBus.PACKET_RECEIVE_APPLY.register("JumpReset.damage", event -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.level != null
                    && event.packet() instanceof ClientboundDamageEventPacket damage
                    && damage.entityId() == client.player.getId()) {
                pendingDamageTick = client.level.getEntity(damage.sourceCauseId()) instanceof Player
                        ? client.player.tickCount : Integer.MIN_VALUE;
            }
        });
        EventBus.CLIENT_CONTEXT_CHANGED.register("JumpReset.context", event -> clear(Minecraft.getInstance()));
    }

    public void bindModeCheck(BooleanSupplier check) {
        modeCheck = check;
    }

    public void bindAssist(BooleanSupplier check) {
        assist = check;
    }

    @Override
    public boolean isActive() {
        return enabled.get();
    }

    @Override
    public int setActive(Minecraft client, boolean value) {
        enabled.set(value);
        return 1;
    }

    public String statusText() {
        return enabled.get() ? "enabled" : "disabled";
    }

    public int setChance(Minecraft ignoredClient, double newChance) {
        chance.set(newChance);
        return 1;
    }

    public void handleEntityVelocity(int entityId, Vec3 velocity) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;

        if (currentPlayer == null || entityId != currentPlayer.getId()) {
            return;
        }

        fallDamageVelocity = velocity.x == 0.0D && velocity.z == 0.0D && velocity.y < 0.0D;
        fallDamageVelocityTicks = fallDamageVelocity ? 3 : 0;
    }

    private void tick(TickEvent event) {
        Minecraft client = event.client();
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) {
            clear(client);
            return;
        }

        releaseForcedJumpKey(client);

        if (resetCooldownTicks > 0) {
            resetCooldownTicks--;
        }

        if (fallDamageVelocityTicks > 0) {
            fallDamageVelocityTicks--;
        } else {
            fallDamageVelocity = false;
        }

        if ((long) currentPlayer.tickCount - pendingDamageTick > 2) pendingDamageTick = Integer.MIN_VALUE;
    }

    private void input(MovementInputUpdatedEvent event) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        boolean configured = enabled.get() && modeCheck.getAsBoolean();
        boolean assisted = assist.getAsBoolean() && (!parent().isEnabled() || modeCheck.getAsBoolean());
        if (player == null || pendingDamageTick == Integer.MIN_VALUE) return;
        if ((!configured && !assisted) || MinecraftClientAccess.screen(client) != null) {
            pendingDamageTick = Integer.MIN_VALUE;
            return;
        }
        // Damage packets identify the attacker on the client. Sample before this movement step,
        // while floor support still exists, rather than waiting for the next hurtTime transition.
        if (!canStartJumpReset(client, assisted) || player.hurtTime < 8) return;
        pendingDamageTick = Integer.MIN_VALUE;
        if (configured && !chancePassed()) return;
        Input input = event.input().keyPresses;
        if (input.jump() || input.shift()) return;
        if (assisted) SprintReset.INSTANCE.deferAssistForJump(client);
        forceJumpKey(client);
        event.input().keyPresses = new Input(input.forward(), input.backward(), input.left(), input.right(),
                true, input.shift(), input.sprint());
        resetCooldownTicks = RESET_COOLDOWN_TICKS;
    }

    private void clear(Minecraft client) {
        resetCooldownTicks = 0;
        fallDamageVelocity = false;
        fallDamageVelocityTicks = 0;
        pendingDamageTick = Integer.MIN_VALUE;
        releaseForcedJumpKey(client);
    }

    private boolean canStartJumpReset(Minecraft client, boolean assisted) {
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer != null) {
            return currentPlayer.onGround()
                    && (assisted || currentPlayer.isSprinting())
                    && !currentPlayer.isPassenger() && !currentPlayer.isUsingItem()
                    && !currentPlayer.isInWater() && !currentPlayer.isInLava()
                    && resetCooldownTicks <= 0
                    && !fallDamageVelocity;
        }
        return false;
    }

    private boolean chancePassed() {
        return RandomChance.chance(chance.get());
    }

    private static void forceJumpKey(Minecraft client) {
        // Use the shared input owner so another combat module cannot release this press.
        CombatInputController.pressJumpPhysical(client, CombatInputController.Owner.JUMP_RESET);
    }

    private static void releaseForcedJumpKey(Minecraft client) {
        CombatInputController.releaseJumpPhysical(client, CombatInputController.Owner.JUMP_RESET);
    }
}
