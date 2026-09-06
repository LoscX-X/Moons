package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.module.impl.combat.SilentAura;
import com.blanoir.moons.client.module.impl.world.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * One tick-domain movement view for every server-only rotation producer.
 *
 * <p>Input remapping, moveRelative and jump impulse must use the same owner and
 * yaw. Resolving those independently allowed a higher-priority block action to
 * preempt SilentAura between hooks, producing a locally valid direction that
 * Grim simulated against a different packet yaw.
 */
public final class MoveFix {
    public enum Source {
        NONE,
        MANUAL_USE,
        BLOCK_INTERACTION,
        SCAFFOLD,
        SILENT_AURA
    }

    public record State(boolean active, float yaw, Source source, int playerTick) {
        private static State inactive(int tick) {
            return new State(false, 0.0F, Source.NONE, tick);
        }
    }

    private static LocalPlayer sampledPlayer;
    private static State sampled = State.inactive(Integer.MIN_VALUE);

    private MoveFix() {
    }

    /** Samples the final owner immediately after vanilla rebuilds keyboard input. */
    public static State capture(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) {
            sampledPlayer = null;
            sampled = State.inactive(Integer.MIN_VALUE);
            return sampled;
        }
        sampledPlayer = player;
        sampled = resolve(player.tickCount);
        return sampled;
    }

    /** Returns the keyboard sample for this tick, or creates one for non-input movement. */
    public static State current(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) return State.inactive(Integer.MIN_VALUE);
        if (sampledPlayer == player && sampled.playerTick() == player.tickCount) {
            return sampled;
        }
        return capture(client);
    }

    private static State resolve(int tick) {
        var manual = RotationLease.manualRotation();
        if (manual != null) return new State(true, manual.yaw(), Source.MANUAL_USE, tick);
        RotationLease.Submission committed = RotationLease.submission();
        if (committed != null) {
            Source source = switch (committed.lease().owner()) {
                case "SilentAura" -> Source.SILENT_AURA;
                case "Scaffold" -> Source.SCAFFOLD;
                default -> Source.BLOCK_INTERACTION;
            };
            return new State(committed.correctMovement(), committed.rotation().yaw(), source, tick);
        }
        // Must exactly match RuntimeEventAdapter.applyPacketRotation precedence.
        if (SilentPacketRotation.shouldCorrectMovement()) {
            return new State(true, SilentPacketRotation.getMovementYaw(),
                    Source.BLOCK_INTERACTION, tick);
        }
        if (Scaffold.shouldCorrectMovement()) {
            return new State(true, Scaffold.getMovementYaw(), Source.SCAFFOLD, tick);
        }
        if (SilentAura.shouldCorrectMovement()) {
            return new State(true, SilentAura.getMovementYaw(), Source.SILENT_AURA, tick);
        }
        return State.inactive(tick);
    }
}
