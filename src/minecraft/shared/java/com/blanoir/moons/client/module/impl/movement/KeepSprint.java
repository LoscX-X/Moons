package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.combat.AttackSlowdownTracker;
import com.blanoir.moons.client.utils.math.RandomMath;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Attack slowdown control. */
public final class KeepSprint {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("keepsprint.enabled").defaultValue(false).build();

    private static final DoubleSetting MOTION_MIN =
            new DoubleSetting.Builder()
                    .name("keepsprint.motion.min")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static final DoubleSetting MOTION_MAX =
            new DoubleSetting.Builder()
                    .name("keepsprint.motion.max")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static final DoubleSetting HURT_MOTION_MIN =
            new DoubleSetting.Builder()
                    .name("keepsprint.hurtMotion.min")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static final DoubleSetting HURT_MOTION_MAX =
            new DoubleSetting.Builder()
                    .name("keepsprint.hurtMotion.max")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static final IntSetting HURT_TIME_MIN =
            new IntSetting.Builder()
                    .name("keepsprint.hurtTime.min")
                    .defaultValue(1)
                    .range(1, 10)
                    .build();

    private static final IntSetting HURT_TIME_MAX =
            new IntSetting.Builder()
                    .name("keepsprint.hurtTime.max")
                    .defaultValue(10)
                    .range(1, 10)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("keepsprint.chance")
                    .defaultValue(100.0D)
                    .range(0.0D, 100.0D)
                    .build();

    private static boolean sprinting;

    private KeepSprint() {}

    public static void init() {
        EventBus.TICK_END.register(
                "KeepSprint.tickEnd",
                event -> {
                    Minecraft client = event.client();
                    if (client.player != null) {
                        sprinting = client.player.isSprinting();
                    }
                });
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static double attackMotionMultiplier() {
        if (!ENABLED.get() || !RandomMath.chancePercent(CHANCE.get())) {
            return 0.6D;
        }

        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;
        int hurtTime = currentPlayer == null ? 0 : currentPlayer.hurtTime;
        boolean hurt = hurtTime >= HURT_TIME_MIN.get() && hurtTime <= HURT_TIME_MAX.get();
        double min = hurt ? HURT_MOTION_MIN.get() : MOTION_MIN.get();
        double max = hurt ? HURT_MOTION_MAX.get() : MOTION_MAX.get();
        return RandomMath.between(min, max) / 100.0D;
    }

    /** Called immediately around vanilla Player.causeExtraKnockback. */
    public static void beginAttackSlowdown(Object owner) {
        Minecraft client = Minecraft.getInstance();
        if (ENABLED.get() && owner instanceof Player player && client.player == player) {
            AttackSlowdownTracker.capture(player);
        }
    }

    /** Replaces vanilla's 0.6 horizontal multiplier and sprint reset. */
    public static void finishAttackSlowdown(Object owner) {
        if (!(owner instanceof Player player)) return;
        if (!ENABLED.get()) {
            AttackSlowdownTracker.discard(player);
            return;
        }
        // Consume the remembered sprint state after the first
        // slowdown in a tick. Later attack calls are restored with 1.0 so the
        // same vanilla 0.6 slowdown cannot be compounded.
        double multiplier = sprinting ? attackMotionMultiplier() : 1.0D;
        if (AttackSlowdownTracker.replaceVanillaSlowdown(player, multiplier)) {
            sprinting = false;
        }
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        ClientChat.send(client, "KeepSprint " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setMotion(Minecraft ignoredClient, double min, double max) {
        MOTION_MIN.set(Math.min(min, max));
        MOTION_MAX.set(Math.max(min, max));
        return 1;
    }

    public static int setHurtMotion(Minecraft ignoredClient, double min, double max) {
        HURT_MOTION_MIN.set(Math.min(min, max));
        HURT_MOTION_MAX.set(Math.max(min, max));
        return 1;
    }

    public static int setHurtTime(Minecraft ignoredClient, int min, int max) {
        HURT_TIME_MIN.set(Math.min(min, max));
        HURT_TIME_MAX.set(Math.max(min, max));
        return 1;
    }

    public static int setChance(Minecraft ignoredClient, double value) {
        CHANCE.set(value);
        return 1;
    }
}
