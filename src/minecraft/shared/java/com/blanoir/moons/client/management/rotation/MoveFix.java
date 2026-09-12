package com.blanoir.moons.client.management.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * One tick-domain movement view for every server-only rotation producer.
 *
 * <p>Input remapping, moveRelative, jump impulse and minor-collision detection must use the same owner and
 * yaw. Resolving those independently allowed a higher-priority block action to
 * preempt SilentAura between hooks, producing a locally valid direction that
 * the server simulated against a different packet yaw.
 */
public final class MoveFix {
    public record State(boolean active, float yaw, String owner, int playerTick) {
        private static State inactive(int tick) {
            return new State(false, 0.0F, "", tick);
        }
    }

    private static LocalPlayer sampledPlayer;
    private static State sampled = State.inactive(Integer.MIN_VALUE);

    private MoveFix() {}

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
        RotationManager.Decision decision = RotationManager.forMovement();
        return decision == null
                ? State.inactive(tick)
                : new State(
                        decision.correctMovement(),
                        decision.rotation().yaw(),
                        decision.owner(),
                        tick);
    }

    public static void reset() {
        sampledPlayer = null;
        sampled = State.inactive(Integer.MIN_VALUE);
    }
}
