package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.config.Settings;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.module.impl.combat.velocity.VelocityPacketListener;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.utils.math.RandomMath;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Normal knockback scaling and the original JumpReset behavior. */
public final class Velocity {
    public enum Mode {
        NORMAL,
        JUMP_RESET
    }

    private static final ModeSetting<Mode> MODE =
            new ModeSetting.Builder<Mode>()
                    .name("velocity.mode")
                    .defaultValue(Mode.JUMP_RESET)
                    .option(Mode.NORMAL, "normal")
                    .option(Mode.JUMP_RESET, "jumpreset")
                    .build();
    private static final DoubleSetting CHANCE = percent("velocity.chance", 100.0D);
    private static final DoubleSetting HORIZONTAL = percent("velocity.horizontal", 0.0D);
    private static final DoubleSetting VERTICAL = percent("velocity.vertical", 100.0D);
    private static final DoubleSetting EXPLOSION_HORIZONTAL =
            percent("velocity.explosionHorizontal", 100.0D);
    private static final DoubleSetting EXPLOSION_VERTICAL =
            percent("velocity.explosionVertical", 100.0D);
    private static final BooleanSetting FAKE_CHECK =
            new BooleanSetting.Builder().name("velocity.fakeCheck").defaultValue(true).build();
    private static final BooleanSetting OTHER_ATTACKS =
            new BooleanSetting.Builder().name("velocity.otherAttacks").defaultValue(false).build();
    private static volatile boolean active = JumpReset.isEnabled();
    private static volatile PlayerSnapshot playerSnapshot = PlayerSnapshot.EMPTY;
    private static boolean initialized;
    private static boolean allowNext = true;
    private static boolean pendingDamageKnown;
    private static boolean pendingDamageAllowed;
    private static final RandomMath.PercentAccumulator CHANCE_SAMPLER =
            new RandomMath.PercentAccumulator();

    private Velocity() {}

    public static void initPacketListeners() {
        EventBus.PACKET_RECEIVE_BUNDLE.register(
                "Velocity.bundleExpansion",
                event -> {
                    if (isEnabled() && normalMode()) event.requestExpansion();
                });
        VelocityPacketListener.init();
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        String storedMode = Settings.getString("velocity.mode", "jumpreset").trim();
        if (storedMode.equalsIgnoreCase("vanilla")) MODE.set(Mode.NORMAL);
        else if (storedMode.equalsIgnoreCase("jump") || storedMode.equalsIgnoreCase("grim2371")) {
            MODE.set(Mode.JUMP_RESET);
        }
        JumpReset.bindModeCheck(Velocity::jumpResetMode);
        EventBus.TICK.register("Velocity.tick", event -> snapshot(event.client()));
    }

    public static boolean isEnabled() {
        return active;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        active = enabled;
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
        }
        return JumpReset.setEnabled(enabled);
    }

    public static String statusTag() {
        return normalMode() ? "Normal" : "JumpReset";
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static int setMode(Minecraft client, String value) {
        // Preserve configurations exported before the ordinary mode was named Normal.
        MODE.deserialize(
                value != null && value.trim().equalsIgnoreCase("vanilla") ? "normal" : value);
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    public static boolean normalMode() {
        return MODE.get() == Mode.NORMAL;
    }

    public static boolean jumpResetMode() {
        return MODE.get() == Mode.JUMP_RESET;
    }

    public static int setChance(Minecraft ignoredClient, double value) {
        CHANCE.set(value);
        return 1;
    }

    public static int setHorizontal(Minecraft ignoredClient, double value) {
        HORIZONTAL.set(value);
        return 1;
    }

    public static int setVertical(Minecraft ignoredClient, double value) {
        VERTICAL.set(value);
        return 1;
    }

    public static int setExplosionHorizontal(Minecraft ignoredClient, double value) {
        EXPLOSION_HORIZONTAL.set(value);
        return 1;
    }

    public static int setExplosionVertical(Minecraft ignoredClient, double value) {
        EXPLOSION_VERTICAL.set(value);
        return 1;
    }

    public static int setFakeCheck(Minecraft ignoredClient, boolean value) {
        FAKE_CHECK.set(value);
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    public static boolean fakeCheckEnabled() {
        return FAKE_CHECK.get();
    }

    public static int setOtherAttacks(Minecraft ignoredClient, boolean value) {
        OTHER_ATTACKS.set(value);
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    /** Records whether the damage source is a player before the following hurt/velocity packets. */
    public static synchronized void handleDamageEvent(int entityId, int sourceCauseId) {
        PlayerSnapshot snapshot = playerSnapshot;
        if (!active || !normalMode() || entityId != snapshot.entityId()) return;
        pendingDamageKnown = true;
        pendingDamageAllowed =
                OTHER_ATTACKS.get() || snapshot.playerEntityIds().contains(sourceCauseId);
    }

    /** Uses entity-status opcode 2 to gate fake-check. */
    public static synchronized void handleEntityStatus(int entityId, byte eventId) {
        if (active && normalMode() && eventId == 2 && entityId == playerSnapshot.entityId()) {
            // With fake-check enabled, only a matching accepted damage event arms
            // the next velocity. This makes player-only vs other attacks explicit.
            allowNext = !(pendingDamageKnown && pendingDamageAllowed);
            clearPendingDamage();
        }
    }

    /**
     * Returns a replacement set-motion vector, or {@code null} when vanilla should receive
     * the original packet. Chance is sampled through a deterministic accumulator.
     */
    public static synchronized Vec3 transformEntityVelocity(int entityId, Vec3 incoming) {
        PlayerSnapshot snapshot = playerSnapshot;
        if (!active || !normalMode() || entityId != snapshot.entityId()) return null;
        if (FAKE_CHECK.get() && allowNext) return null;

        allowNext = true;
        if (!CHANCE_SAMPLER.test(CHANCE.get())) return null;

        Vec3 current = snapshot.movement();
        double horizontal = HORIZONTAL.get();
        double vertical = VERTICAL.get();
        Vec3 transformed =
                new Vec3(
                        horizontal > 0.0D ? incoming.x * horizontal / 100.0D : current.x,
                        vertical > 0.0D ? incoming.y * vertical / 100.0D : current.y,
                        horizontal > 0.0D ? incoming.z * horizontal / 100.0D : current.z);
        return transformed.equals(incoming) ? null : transformed;
    }

    /**
     * Explosion motion is additive in modern vanilla. This scales the final velocity,
     * so convert that final value back into an additive packet vector for an equivalent result.
     */
    public static synchronized Vec3 transformExplosionVelocity(Vec3 knockback) {
        if (!active || !normalMode()) return null;
        if (knockback.equals(Vec3.ZERO) || FAKE_CHECK.get() && allowNext) return null;

        allowNext = true;
        Vec3 current = playerSnapshot.movement();
        double horizontal = EXPLOSION_HORIZONTAL.get();
        Vec3 transformed = getTransformed(knockback, horizontal, current);
        return transformed.equals(knockback) ? null : transformed;
    }

    private static @NonNull Vec3 getTransformed(Vec3 knockback, double horizontal, Vec3 current) {
        double vertical = EXPLOSION_VERTICAL.get();
        double desiredX =
                horizontal > 0.0D ? (current.x + knockback.x) * horizontal / 100.0D : current.x;
        double desiredY =
                vertical > 0.0D ? (current.y + knockback.y) * vertical / 100.0D : current.y;
        double desiredZ =
                horizontal > 0.0D ? (current.z + knockback.z) * horizontal / 100.0D : current.z;
        return new Vec3(desiredX - current.x, desiredY - current.y, desiredZ - current.z);
    }

    private static synchronized void snapshot(Minecraft client) {
        var currentLevel = client == null ? null : client.level;
        LocalPlayer player = client == null ? null : client.player;
        PlayerSnapshot previous = playerSnapshot;
        if (client == null || player == null) {
            playerSnapshot = PlayerSnapshot.EMPTY;
            allowNext = true;
            clearPendingDamage();
            return;
        }
        int entityId = player.getId();
        Set<Integer> playerIds =
                currentLevel == null
                        ? Set.of(entityId)
                        : currentLevel.players().stream()
                                .map(entity -> entity.getId())
                                .collect(Collectors.toUnmodifiableSet());
        playerSnapshot = new PlayerSnapshot(entityId, player.getDeltaMovement(), playerIds);
        if (previous.entityId() != entityId) {
            allowNext = true;
            clearPendingDamage();
        }
    }

    private static DoubleSetting percent(String key, double fallback) {
        return new DoubleSetting.Builder()
                .name(key)
                .defaultValue(fallback)
                .range(0.0D, 100.0D)
                .build();
    }

    private static void clearPendingDamage() {
        pendingDamageKnown = false;
        pendingDamageAllowed = false;
    }

    private record PlayerSnapshot(int entityId, Vec3 movement, Set<Integer> playerEntityIds) {
        private static final PlayerSnapshot EMPTY =
                new PlayerSnapshot(Integer.MIN_VALUE, Vec3.ZERO, Set.of());
    }
}
