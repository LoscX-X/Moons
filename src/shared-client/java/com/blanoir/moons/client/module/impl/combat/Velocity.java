package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.config.settings.ModeSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.movement.MoveInputEvent;
import com.blanoir.moons.client.event.movement.StrafeEvent;
import com.blanoir.moons.client.module.impl.movement.JumpReset;
import com.blanoir.moons.client.module.impl.network.Backtrack;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.math.MathUtils;
import com.blanoir.moons.client.management.input.CombatInputController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/** Myau Vanilla plus OpenZen Jump handling, with Moons' original JumpReset retained. */
public final class Velocity {
    public enum Mode {
        VANILLA,
        JUMP,
        GRIM2371,
        JUMP_RESET
    }

    private static final ModeSetting<Mode> MODE = new ModeSetting.Builder<Mode>()
            .name("velocity.mode")
            .defaultValue(Mode.JUMP_RESET)
            .option(Mode.VANILLA, "vanilla")
            .option(Mode.JUMP, "jump")
            .option(Mode.GRIM2371, "grim2371", "grim")
            .option(Mode.JUMP_RESET, "jumpreset", "jump_reset")
            .build();
    private static final DoubleSetting CHANCE = percent("velocity.chance", 100.0D);
    private static final DoubleSetting HORIZONTAL = percent("velocity.horizontal", 0.0D);
    private static final DoubleSetting VERTICAL = percent("velocity.vertical", 100.0D);
    private static final DoubleSetting EXPLOSION_HORIZONTAL =
            percent("velocity.explosionHorizontal", 100.0D);
    private static final DoubleSetting EXPLOSION_VERTICAL =
            percent("velocity.explosionVertical", 100.0D);
    private static final BooleanSetting FAKE_CHECK = new BooleanSetting.Builder()
            .name("velocity.fakeCheck")
            .defaultValue(true)
            .build();
    private static final BooleanSetting OTHER_ATTACKS = new BooleanSetting.Builder()
            .name("velocity.otherAttacks")
            .defaultValue(false)
            .build();
    private static final BooleanSetting ROTATE = new BooleanSetting.Builder()
            .name("velocity.jump.rotate")
            .defaultValue(false)
            .build();
    private static final BooleanSetting FOLLOW_DIRECTION = new BooleanSetting.Builder()
            .name("velocity.jump.followDirection")
            .defaultValue(false)
            .build();
    private static final IntSetting ROTATE_TICKS = new IntSetting.Builder()
            .name("velocity.jump.rotateTicks")
            .defaultValue(12)
            .range(3, 20)
            .build();

    private static volatile boolean active = JumpReset.isEnabled();
    private static volatile PlayerSnapshot playerSnapshot = PlayerSnapshot.EMPTY;
    private static boolean initialized;
    private static boolean allowNext = true;
    private static boolean pendingDamageKnown;
    private static boolean pendingDamageAllowed;
    private static double chanceCounter;

    private enum JumpPhase {
        IDLE,
        AIR,
        GROUND
    }

    private static final ConcurrentLinkedDeque<Packet<ClientGamePacketListener>> JUMP_PACKET_QUEUE =
            new ConcurrentLinkedDeque<>();
    private static JumpPhase jumpPhase = JumpPhase.IDLE;
    private static boolean jumpSuspending;
    private static int jumpDelayTicks;
    private static int forcedJumpTicks;
    private static Float pendingRotationYaw;
    private static Float activeRotationYaw;
    private static int rotationHeldTicks;
    private static boolean ownsPacketRotation;

    private Velocity() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        JumpReset.bindModeCheck(Velocity::jumpResetMode);
        EventBus.TICK.register("Velocity.tick", event -> tick(event.client()));
        EventBus.MOVE_INPUT.register("Velocity.onMoveInput", Velocity::onMoveInput);
        EventBus.STRAFE.register("Velocity.onStrafe", Velocity::onStrafe);
        VelocityGrim2371.initCore();
    }

    /** RECEIVE_PRE tail; called after the shared network classifiers are registered. */
    public static void initIncomingTail() {
        VelocityGrim2371.initReceiveTail();
    }

    public static void shutdown() {
        VelocityGrim2371.disable(Minecraft.getInstance());
    }

    public static boolean isEnabled() {
        return active;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        boolean wasEnabled = active;
        active = enabled;
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
            resetJumpMode(client, false);
        }
        if (grim2371Mode()) {
            if (enabled && !wasEnabled) VelocityGrim2371.enable();
            else if (!enabled && wasEnabled) VelocityGrim2371.disable(client);
        }
        return JumpReset.setEnabled(client, enabled);
    }

    public static String statusTag() {
        return switch (MODE.get()) {
            case VANILLA -> "Vanilla";
            case JUMP -> "Jump";
            case GRIM2371 -> "Grim2371";
            case JUMP_RESET -> "JumpReset";
        };
    }

    public static List<String> modeOptions() {
        return MODE.optionIds();
    }

    public static int setMode(Minecraft client, String value) {
        boolean wasJump = MODE.get() == Mode.JUMP;
        boolean wasGrim2371 = MODE.get() == Mode.GRIM2371;
        MODE.deserialize(value);
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
            if (wasJump || MODE.get() != Mode.JUMP) {
                resetJumpMode(client, false);
            }
        }
        boolean isGrim2371 = MODE.get() == Mode.GRIM2371;
        if (wasGrim2371 && !isGrim2371) VelocityGrim2371.disable(client);
        else if (!wasGrim2371 && isGrim2371 && active) VelocityGrim2371.enable();
        return 1;
    }

    /** Used by packet bundle expansion; both packet-driven modes need individual sub-packets. */
    public static boolean vanillaOrJumpMode() {
        Mode mode = MODE.get();
        return mode == Mode.VANILLA || mode == Mode.JUMP;
    }

    /** Modes whose damage/velocity packets must be expanded out of bundles. */
    public static boolean packetDrivenMode() {
        Mode mode = MODE.get();
        return mode == Mode.VANILLA || mode == Mode.JUMP || mode == Mode.GRIM2371;
    }

    public static boolean vanillaMode() {
        return MODE.get() == Mode.VANILLA;
    }

    public static boolean jumpMode() {
        return MODE.get() == Mode.JUMP;
    }

    public static boolean jumpResetMode() {
        return MODE.get() == Mode.JUMP_RESET;
    }

    public static boolean grim2371Mode() {
        return MODE.get() == Mode.GRIM2371;
    }

    public static int setChance(Minecraft client, double value) {
        CHANCE.set(value);
        return 1;
    }

    public static int setHorizontal(Minecraft client, double value) {
        HORIZONTAL.set(value);
        return 1;
    }

    public static int setVertical(Minecraft client, double value) {
        VERTICAL.set(value);
        return 1;
    }

    public static int setExplosionHorizontal(Minecraft client, double value) {
        EXPLOSION_HORIZONTAL.set(value);
        return 1;
    }

    public static int setExplosionVertical(Minecraft client, double value) {
        EXPLOSION_VERTICAL.set(value);
        return 1;
    }

    public static int setFakeCheck(Minecraft client, boolean value) {
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

    public static int setOtherAttacks(Minecraft client, boolean value) {
        OTHER_ATTACKS.set(value);
        synchronized (Velocity.class) {
            allowNext = true;
            clearPendingDamage();
        }
        return 1;
    }

    public static int setRotate(Minecraft client, boolean value) {
        ROTATE.set(value);
        if (!value && !FOLLOW_DIRECTION.get()) clearJumpRotation(client);
        return 1;
    }

    public static int setFollowDirection(Minecraft client, boolean value) {
        FOLLOW_DIRECTION.set(value);
        if (!value && !ROTATE.get()) clearJumpRotation(client);
        return 1;
    }

    public static int setRotateTicks(Minecraft client, int value) {
        ROTATE_TICKS.set(value);
        return 1;
    }

    public static boolean jumpRotationEnabled() {
        return ROTATE.get() || FOLLOW_DIRECTION.get();
    }

    /** Records whether the damage source is a player before the following hurt/velocity packets. */
    public static synchronized void handleDamageEvent(int entityId, int sourceCauseId) {
        PlayerSnapshot snapshot = playerSnapshot;
        if (!active || !vanillaMode() || entityId != snapshot.entityId()) return;
        pendingDamageKnown = true;
        pendingDamageAllowed = OTHER_ATTACKS.get()
                || snapshot.playerEntityIds().contains(sourceCauseId);
    }

    /** Mirrors Myau's S19 opcode-2 gate used by fake-check. */
    public static synchronized void handleEntityStatus(int entityId, byte eventId) {
        if (active && vanillaMode()
                && eventId == 2 && entityId == playerSnapshot.entityId()) {
            // With fake-check enabled, only a matching accepted damage event arms
            // the next velocity. This makes player-only vs other attacks explicit.
            allowNext = !(pendingDamageKnown && pendingDamageAllowed);
            clearPendingDamage();
        }
    }

    /**
     * Returns a replacement set-motion vector, or {@code null} when vanilla should receive
     * the original packet. Myau's deterministic chance accumulator is intentionally preserved.
     */
    public static synchronized Vec3 transformEntityVelocity(int entityId, Vec3 incoming) {
        PlayerSnapshot snapshot = playerSnapshot;
        if (!active || !vanillaMode() || entityId != snapshot.entityId()) return null;
        if (FAKE_CHECK.get() && allowNext) return null;

        allowNext = true;
        chanceCounter = (chanceCounter % 100.0D) + CHANCE.get();
        if (chanceCounter < 100.0D) return null;

        Vec3 current = snapshot.movement();
        double horizontal = HORIZONTAL.get();
        double vertical = VERTICAL.get();
        Vec3 transformed = new Vec3(
                horizontal > 0.0D ? incoming.x * horizontal / 100.0D : current.x,
                vertical > 0.0D ? incoming.y * vertical / 100.0D : current.y,
                horizontal > 0.0D ? incoming.z * horizontal / 100.0D : current.z
        );
        return transformed.equals(incoming) ? null : transformed;
    }

    /**
     * Explosion motion is additive in modern vanilla. Myau scales the final setVelocity value,
     * so convert that final value back into an additive packet vector for an equivalent result.
     */
    public static synchronized Vec3 transformExplosionVelocity(Vec3 knockback) {
        if (!active || !vanillaMode()) return null;
        if (knockback.equals(Vec3.ZERO) || FAKE_CHECK.get() && allowNext) return null;

        allowNext = true;
        Vec3 current = playerSnapshot.movement();
        double horizontal = EXPLOSION_HORIZONTAL.get();
        double vertical = EXPLOSION_VERTICAL.get();
        double desiredX = horizontal > 0.0D
                ? (current.x + knockback.x) * horizontal / 100.0D : current.x;
        double desiredY = vertical > 0.0D
                ? (current.y + knockback.y) * vertical / 100.0D : current.y;
        double desiredZ = horizontal > 0.0D
                ? (current.z + knockback.z) * horizontal / 100.0D : current.z;
        Vec3 transformed = new Vec3(
                desiredX - current.x,
                desiredY - current.y,
                desiredZ - current.z
        );
        return transformed.equals(knockback) ? null : transformed;
    }

    /**
     * OpenZen Jump Reset interception. While suspended it queues every incoming packet except
     * system chat and world-time updates. The original implementation does not require positive Y.
     */
    public static synchronized boolean handleJumpIncoming(Packet<?> packet) {
        PlayerSnapshot snapshot = playerSnapshot;
        if (!active || !jumpMode() || snapshot.entityId() == Integer.MIN_VALUE) return false;

        if (jumpSuspending
                && !(packet instanceof ClientboundSystemChatPacket)
                && !(packet instanceof ClientboundSetTimePacket)) {
            queueJumpPacket(packet);
            return true;
        }

        if (!(packet instanceof ClientboundSetEntityMotionPacket motion)
                || motion.id() != snapshot.entityId()) {
            return false;
        }

        Float knockbackYaw = null;
        if (jumpRotationEnabled()) {
            Vec3 movement = motion.movement();
            knockbackYaw = MathUtils.yawTo(Vec3.ZERO, movement.scale(-1.0D));
        }

        jumpSuspending = true;
        forcedJumpTicks = 0;
        queueJumpPacket(packet);
        if (snapshot.onGround()) {
            jumpPhase = JumpPhase.GROUND;
            jumpDelayTicks = 10;
            pendingRotationYaw = knockbackYaw;
        } else {
            jumpPhase = JumpPhase.AIR;
            jumpDelayTicks = 20;
            pendingRotationYaw = null;
            if (knockbackYaw != null) activateJumpRotation(Minecraft.getInstance(), knockbackYaw);
        }
        return true;
    }

    private static void onMoveInput(MoveInputEvent ignored) {
        // Kept registered as the input-stage boundary used by the previous Myau Jump mode.
        // OpenZen's replacement drives the jump key from the game tick instead.
    }

    private static synchronized void onStrafe(StrafeEvent event) {
        if (active && jumpMode() && FOLLOW_DIRECTION.get() && activeRotationYaw != null) {
            event.setForward(1.0F);
            event.setStrafe(0.0F);
        }
    }

    private static synchronized void tick(Minecraft client) {
        snapshot(client);
        LocalPlayer player = client == null ? null : client.player;
        if (player == null || !active || !jumpMode()) {
            if (jumpSuspending || forcedJumpTicks > 0 || activeRotationYaw != null) {
                resetJumpMode(client, false);
            }
            return;
        }

        // OpenZen suspends this mode while Backtrack is actively delaying packets.
        if (Backtrack.isLagging()) {
            if (jumpSuspending) flushJumpQueue(client);
            resetJumpMode(client, false);
            return;
        }

        // OpenZen's GameTick input section runs before its Tick state machine.
        if (jumpSuspending && jumpPhase == JumpPhase.GROUND) {
            CombatInputController.releaseJump(client, CombatInputController.Owner.VELOCITY_JUMP);
        } else if (forcedJumpTicks > 0) {
            CombatInputController.forceJump(client, CombatInputController.Owner.VELOCITY_JUMP);
            forcedJumpTicks--;
        } else {
            CombatInputController.releaseJump(client, CombatInputController.Owner.VELOCITY_JUMP);
        }

        if (jumpSuspending) {
            if (jumpPhase == JumpPhase.AIR) {
                if (player.onGround()) {
                    flushJumpQueue(client);
                    resetJumpStateOnly();
                } else if (jumpDelayTicks > 0) {
                    jumpDelayTicks--;
                } else {
                    flushJumpQueue(client);
                    resetJumpStateOnly();
                }
            } else if (jumpPhase == JumpPhase.GROUND) {
                if (jumpDelayTicks > 0) {
                    jumpDelayTicks--;
                } else {
                    flushJumpQueue(client);
                    Float yaw = pendingRotationYaw;
                    resetJumpStateOnly();
                    if (yaw != null) activateJumpRotation(client, yaw);
                    forcedJumpTicks = 1;
                }
            }
        }

        if (activeRotationYaw != null) rotationHeldTicks++;
        if (activeRotationYaw != null && (player.hurtTime == 0
                || rotationHeldTicks > ROTATE_TICKS.get()
                || !jumpRotationEnabled())) {
            clearJumpRotation(client);
        }
    }

    private static synchronized void snapshot(Minecraft client) {
        LocalPlayer player = client == null ? null : client.player;
        PlayerSnapshot previous = playerSnapshot;
        if (player == null) {
            playerSnapshot = PlayerSnapshot.EMPTY;
            synchronized (Velocity.class) {
                allowNext = true;
                clearPendingDamage();
            }
            return;
        }
        int entityId = player.getId();
        Set<Integer> playerIds = client.level == null
                ? Set.of(entityId)
                : client.level.players().stream()
                .map(entity -> entity.getId())
                .collect(Collectors.toUnmodifiableSet());
        playerSnapshot = new PlayerSnapshot(
                entityId, player.getDeltaMovement(), playerIds, player.onGround());
        if (previous.entityId() != entityId) {
            synchronized (Velocity.class) {
                allowNext = true;
                clearPendingDamage();
                resetJumpMode(client, false);
            }
            VelocityGrim2371.clearForWorldChange(client);
        }
    }

    @SuppressWarnings("unchecked")
    private static void queueJumpPacket(Packet<?> packet) {
        JUMP_PACKET_QUEUE.add((Packet<ClientGamePacketListener>) packet);
    }

    private static void flushJumpQueue(Minecraft client) {
        ClientPacketListener listener = client == null ? null : client.getConnection();
        if (listener == null) {
            JUMP_PACKET_QUEUE.clear();
            return;
        }
        Packet<ClientGamePacketListener> packet;
        while ((packet = JUMP_PACKET_QUEUE.poll()) != null) {
            try {
                packet.handle(listener);
            } catch (Exception ignored) {
                JUMP_PACKET_QUEUE.clear();
                return;
            }
        }
    }

    private static void activateJumpRotation(Minecraft client, float yaw) {
        if (client == null || client.player == null || !jumpRotationEnabled()) return;
        // Velocity is not part of the player-module busy interlock. Do not
        // replace a block interaction (or its accepted pending rotation) that
        // already owns the shared silent-rotation controller.
        if (SilentPacketRotation.isBusy() && !ownsPacketRotation) return;
        activeRotationYaw = yaw;
        rotationHeldTicks = 0;
        float pitch = client.player.getXRot();
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRadians);
        Vec3 direction = new Vec3(
                -Math.sin(yawRadians) * cosPitch,
                -Math.sin(pitchRadians),
                Math.cos(yawRadians) * cosPitch
        );
        ownsPacketRotation = SilentPacketRotation.beginRotation(
                client, client.player.getEyePosition().add(direction.scale(8.0D)), 1, () -> { });
    }

    private static void clearJumpRotation(Minecraft client) {
        activeRotationYaw = null;
        pendingRotationYaw = null;
        rotationHeldTicks = 0;
        if (ownsPacketRotation) {
            SilentPacketRotation.reset();
            ownsPacketRotation = false;
        }
    }

    private static void resetJumpStateOnly() {
        jumpSuspending = false;
        jumpDelayTicks = 0;
        jumpPhase = JumpPhase.IDLE;
        pendingRotationYaw = null;
    }

    private static void resetJumpMode(Minecraft client, boolean flush) {
        if (flush) flushJumpQueue(client);
        else JUMP_PACKET_QUEUE.clear();
        resetJumpStateOnly();
        forcedJumpTicks = 0;
        CombatInputController.releaseJump(client, CombatInputController.Owner.VELOCITY_JUMP);
        clearJumpRotation(client);
    }

    private static boolean touchingCobweb(Minecraft client, LocalPlayer player) {
        if (client.level == null) return false;
        AABB box = player.getBoundingBox().deflate(1.0E-5D);
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.floor(box.maxX);
        int maxY = (int) Math.floor(box.maxY);
        int maxZ = (int) Math.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (client.level.getBlockState(new BlockPos(x, y, z)).is(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
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

    private record PlayerSnapshot(
            int entityId,
            Vec3 movement,
            Set<Integer> playerEntityIds,
            boolean onGround
    ) {
        private static final PlayerSnapshot EMPTY =
                new PlayerSnapshot(Integer.MIN_VALUE, Vec3.ZERO, Set.of(), false);
    }
}
