package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.access.MinecraftClientAccess;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.utils.math.RandomMath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;

import java.util.function.BooleanSupplier;

public final class JumpReset {
    private static final int RESET_COOLDOWN_TICKS = 2;
    private static final int HURT_TIME_TRIGGER = 9;

    private static boolean fallDamageVelocity = false;
    private static int fallDamageVelocityTicks = 0;
    private static int resetCooldownTicks = 0;
    private static int lastHurtTime = 0;
    private static BooleanSupplier modeCheck = () -> false;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("jumpreset.enabled")
                    .defaultValue(false)
                    .build();

    private static final DoubleSetting CHANCE =
            new DoubleSetting.Builder()
                    .name("jumpreset.chance")
                    .defaultValue(0.5D)
                    .min(0.0D)
                    .max(1.0D)
                    .build();

    private JumpReset() {
    }

    public static void init() {
        EventBus.TICK.register("JumpReset.tick",
                JumpReset::tick
        );
    }

    public static void bindModeCheck(BooleanSupplier check) {
        modeCheck = check;
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }
    public static int setEnabled(boolean value) {
        ENABLED.set(value);
        return 1;
    }
    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int setChance(Minecraft ignoredClient, double newChance) {
        CHANCE.set(newChance);
        return 1;
    }

    public static void handleEntityVelocity(int entityId, Vec3 velocity) {
        Minecraft client = Minecraft.getInstance();
        var currentPlayer = client == null ? null : client.player;

        if (currentPlayer == null || entityId != currentPlayer.getId()) {
            return;
        }

        fallDamageVelocity = velocity.x == 0.0D && velocity.z == 0.0D && velocity.y < 0.0D;
        fallDamageVelocityTicks = fallDamageVelocity ? 3 : 0;
    }

    private static void tick(TickEvent event) {
        Minecraft client = event.client();
        var currentPlayer = client == null ? null : client.player;
        if (client == null || currentPlayer == null || client.level == null) {
            resetCooldownTicks = 0;
            fallDamageVelocity = false;
            fallDamageVelocityTicks = 0;
            lastHurtTime = 0;
            releaseForcedJumpKey(client);
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

        if (!ENABLED.get() || !modeCheck.getAsBoolean()
                || MinecraftClientAccess.screen(client) != null) {
            return;
        }

        int hurtTime = currentPlayer.hurtTime;
        boolean newKnockbackTick = hurtTime == HURT_TIME_TRIGGER && lastHurtTime != HURT_TIME_TRIGGER;
        lastHurtTime = hurtTime;

        if (newKnockbackTick) {
            // Ignore environmental hurt (fall, fire, etc.); jump-reset only PvP knockback.
            if (currentPlayer.getLastHurtByMob() instanceof Player
                    && canStartJumpReset(client) && chancePassed()) {
                // The reset must be present in the movement input generated for
                // the knockback tick. Waiting after hurtTime 9 misses the useful
                // timing and produces the heavy, delayed feel of the old code.
                forceJumpKey(client);
                resetCooldownTicks = RESET_COOLDOWN_TICKS;
            }
        }
    }

    private static boolean canStartJumpReset(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (currentPlayer != null) {
            return currentPlayer.onGround()
                    && currentPlayer.isSprinting()
                    && resetCooldownTicks <= 0
                    && !fallDamageVelocity;
        }
        return false;
    }

    private static boolean chancePassed() {
        return RandomMath.chance(CHANCE.get());
    }

    private static void forceJumpKey(Minecraft client) {
        // A real keyboard/mouse press event, not a synthetic KeyMapping state
        // flip, so the knockback-tick jump is indistinguishable from user input.
        CombatInputController.pressJumpPhysical(client, CombatInputController.Owner.JUMP_RESET);
    }

    private static void releaseForcedJumpKey(Minecraft client) {
        CombatInputController.releaseJumpPhysical(client, CombatInputController.Owner.JUMP_RESET);
    }

}
