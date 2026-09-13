package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

import java.util.List;

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
    private static final MovementInputCorrection INPUT_CORRECTION = new MovementInputCorrection();
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("movefix.enabled").defaultValue(true).build();
    private static final ModeSetting<Algorithm> ALGORITHM =
            new ModeSetting.Builder<Algorithm>()
                    .name("movefix.algorithm")
                    .defaultValue(Algorithm.LEGACY)
                    .option(Algorithm.LEGACY, "legacy")
                    .option(Algorithm.STABLE, "stable")
                    .build();

    private enum Algorithm {
        LEGACY,
        STABLE
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static String algorithm() {
        return ALGORITHM.serialized();
    }

    public static List<String> algorithmOptions() {
        return ALGORITHM.optionIds();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        reset();
        return 1;
    }

    public static int setAlgorithm(Minecraft client, String value) {
        ALGORITHM.deserialize(value);
        reset();
        return 1;
    }

    private MoveFix() {}

    /** Samples the final owner immediately after vanilla rebuilds keyboard input. */
    public static State capture(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        if (player == null) {
            INPUT_CORRECTION.reset();
            sampledPlayer = null;
            sampled = State.inactive(Integer.MIN_VALUE);
            return sampled;
        }
        if (sampledPlayer != player) INPUT_CORRECTION.reset();
        sampledPlayer = player;
        sampled = resolve(player.tickCount);
        if (!sampled.active()) INPUT_CORRECTION.reset();
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
                        enabled() && decision.correctMovement(),
                        decision.rotation().yaw(),
                        decision.owner(),
                        tick);
    }

    public static void reset() {
        INPUT_CORRECTION.reset();
        sampledPlayer = null;
        sampled = State.inactive(Integer.MIN_VALUE);
    }

    public static Input correctInput(Input input, float cameraYaw, State state) {
        if (!state.active()) return input;
        int forward = input.forward() == input.backward() ? 0 : input.forward() ? 1 : -1;
        int sideways = input.left() == input.right() ? 0 : input.left() ? 1 : -1;
        MovementInputCorrection.Direction direction;
        if (ALGORITHM.get() == Algorithm.STABLE) {
            direction =
                    INPUT_CORRECTION.correct(
                            cameraYaw,
                            state.yaw(),
                            forward,
                            sideways,
                            state.owner(),
                            state.playerTick());
        } else {
            INPUT_CORRECTION.reset();
            float radians = Mth.wrapDegrees(cameraYaw - state.yaw()) * Mth.DEG_TO_RAD;
            direction =
                    new MovementInputCorrection.Direction(
                            Math.round(forward * Mth.cos(radians) + sideways * Mth.sin(radians)),
                            Math.round(sideways * Mth.cos(radians) - forward * Mth.sin(radians)));
        }
        return new Input(
                direction.forward() > 0,
                direction.forward() < 0,
                direction.sideways() > 0,
                direction.sideways() < 0,
                input.jump(),
                input.shift(),
                input.sprint());
    }
}
