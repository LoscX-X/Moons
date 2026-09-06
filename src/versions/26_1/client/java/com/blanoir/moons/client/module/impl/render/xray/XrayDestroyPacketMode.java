package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.module.impl.combat.SilentAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * Anti-xray packet probe.
 *
 * <p>Every probe mirrors a legitimate vanilla mining transition:
 * START_DESTROY_BLOCK(sequence) -> ABORT_DESTROY_BLOCK -> SWING. Targets are
 * selected from the current block-interaction ray and revalidated immediately
 * before the packets are sent.</p>
 */
public final class XrayDestroyPacketMode {
    private static final double RAY_STEP = 0.125D;
    private static final double DISTANCE_EPSILON = 1.0E-4D;

    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder()
                    .name("xray.destroyPacket.enabled")
                    .defaultValue(false)
                    .build();
    private static final BooleanSetting STATIC_SCAN =
            new BooleanSetting.Builder()
                    .name("xray.destroyPacket.staticScan")
                    .defaultValue(false)
                    .build();
    private static final IntSetting PACKET_INTERVAL_TICKS =
            new IntSetting.Builder()
                    .name("xray.destroyPacket.intervalTicks")
                    .defaultValue(MoonsConfig.DESTROY_PACKET_INTERVAL_TICKS)
                    .range(1, 10)
                    .build();

    private static final ArrayDeque<ProbeTarget> STATIC_TARGETS = new ArrayDeque<>();

    private static ClientLevel activeLevel;
    private static BlockPos lastCrosshairBlock;
    private static boolean crosshairBlockProbed;
    private static BlockPos staticOrigin;
    private static int staticIntervalCounter;
    private static int packetsSent;

    private XrayDestroyPacketMode() {
    }

    public static void init() {
        EventBus.TICK.register("XrayDestroyPacketMode.tick", XrayDestroyPacketMode::tick);
    }

    public static boolean isEnabled() {
        return OreScanner.isAutoScanEnabled() && ENABLED.get();
    }

    public static boolean isPacketScanEnabled() {
        return ENABLED.get();
    }

    public static void resetScan() {
        resetAllState();
    }

    public static void setEnabled(Minecraft client, boolean newEnabled) {
        if (ENABLED.get() == newEnabled) {
            ClientChat.send(client, "Packet Xray is already " + (newEnabled ? "enabled." : "disabled."));
            return;
        }

        ENABLED.set(newEnabled);
        resetAllState();
        ClientChat.send(client, newEnabled
                ? "Packet Xray enabled: START -> ABORT -> SWING."
                : "Packet Xray disabled.");
    }

    public static String statusText() {
        if (!isEnabled()) {
            return "disabled";
        }
        return "enabled, mode="
                + (STATIC_SCAN.get() ? "static" : "crosshair")
                + ", sent="
                + packetsSent
                + ", interval="
                + PACKET_INTERVAL_TICKS.get()
                + "t";
    }

    public static boolean isStaticScanEnabled() {
        return STATIC_SCAN.get();
    }

    public static int setStaticScanEnabled(Minecraft client, boolean enabled) {
        STATIC_SCAN.set(enabled);
        resetTargetState();
        ClientChat.send(client, "Packet Xray static scan " + (enabled ? "enabled." : "disabled."));
        return 1;
    }

    public static int packetIntervalTicks() {
        return PACKET_INTERVAL_TICKS.get();
    }

    public static int setPacketIntervalTicks(Minecraft client, int ticks) {
        PACKET_INTERVAL_TICKS.set(ticks);
        staticIntervalCounter = 0;
        ClientChat.send(client, "Packet Xray static scan interval set to "
                + PACKET_INTERVAL_TICKS.get() + " ticks.");
        return 1;
    }

    private static void tick(TickEvent event) {
        if (!isEnabled()) {
            return;
        }

        Minecraft client = event.client();
        if (!OreScanner.isClientWorldReady(client)) {
            activeLevel = null;
            resetTargetState();
            return;
        }

        if (activeLevel != client.level) {
            activeLevel = client.level;
            resetTargetState();
        }

        if (!canProbe(client)) {
            resetTargetState();
            return;
        }

        if (STATIC_SCAN.get() && isStationary(client)) {
            tickStaticScan(client);
            return;
        }

        resetStaticState();
        tickCrosshairScan(client);
    }

    private static void tickCrosshairScan(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult crosshair)
                || crosshair.getType() != HitResult.Type.BLOCK) {
            resetCrosshairState();
            return;
        }

        BlockPos surfaceBlock = crosshair.getBlockPos().immutable();
        if (!isProbeableBlock(client, surfaceBlock)) {
            resetCrosshairState();
            return;
        }

        if (!surfaceBlock.equals(lastCrosshairBlock)) {
            lastCrosshairBlock = surfaceBlock;
            crosshairBlockProbed = false;
        }
        if (crosshairBlockProbed) {
            return;
        }

        BlockHitResult target = deepestTargetOnViewRay(client);
        crosshairBlockProbed = true;
        if (target != null) {
            sendProbe(client, target.getBlockPos(), target.getDirection());
        }
    }

    private static BlockHitResult deepestTargetOnViewRay(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 view = client.player.getViewVector(1.0F).normalize();
        double reach = client.player.blockInteractionRange();

        BlockPos previous = null;
        BlockHitResult deepest = null;
        for (double distance = 0.0D; distance <= reach; distance += RAY_STEP) {
            BlockPos pos = BlockPos.containing(eye.add(view.scale(distance)));
            if (pos.equals(previous)) {
                continue;
            }
            previous = pos;
            if (!isProbeableBlock(client, pos)) {
                continue;
            }
            BlockHitResult hit = raycastTarget(client, pos, null);
            if (hit != null) {
                deepest = hit;
            }
        }
        return deepest;
    }

    private static void tickStaticScan(Minecraft client) {
        BlockPos playerBlock = client.player.blockPosition().immutable();
        if (!playerBlock.equals(staticOrigin)) {
            STATIC_TARGETS.clear();
            staticOrigin = playerBlock;
            staticIntervalCounter = 0;
        }

        if (++staticIntervalCounter < PACKET_INTERVAL_TICKS.get()) {
            return;
        }
        staticIntervalCounter = 0;
        // An empty ray must obey the interval too; do not rebuild every tick.
        if (STATIC_TARGETS.isEmpty()) rebuildStaticTargets(client, playerBlock);

        ProbeTarget target = STATIC_TARGETS.pollFirst();
        if (target != null) {
            sendProbe(client, target.pos(), target.direction());
        }
    }

    private static void rebuildStaticTargets(Minecraft client, BlockPos origin) {
        STATIC_TARGETS.clear();
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        int radius = (int) Math.ceil(reach);
        double candidateRangeSqr = (reach + 1.5D) * (reach + 1.5D);

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > candidateRangeSqr
                            || !isProbeableBlock(client, pos)) {
                        continue;
                    }

                    // The ray already determines the hit face. Repeating the
                    // identical shape clip for all six faces adds no candidates.
                    BlockHitResult hit = raycastTarget(client, pos, null);
                    if (hit != null) {
                        STATIC_TARGETS.addLast(new ProbeTarget(
                                hit.getBlockPos().immutable(), hit.getDirection()));
                    }
                }
            }
        }
    }

    private static void sendProbe(Minecraft client, BlockPos pos, Direction direction) {
        if (!OreScanner.isClientWorldReady(client)
                || client.gameMode == null
                || client.getConnection() == null
                || client.player.blockActionRestricted(
                        client.level, pos, client.gameMode.getPlayerMode())
                || !client.level.getWorldBorder().isWithinBounds(pos)
                || !isProbeableBlock(client, pos)) {
            return;
        }

        BlockHitResult verifiedHit = raycastTarget(client, pos, direction);
        if (verifiedHit == null) {
            return;
        }

        GameAccess.withPredictionSequence(client.level, sequence ->
                client.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        pos,
                        direction,
                        sequence)));
        client.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                pos,
                Direction.DOWN,
                0));
        client.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        packetsSent++;
    }

    private static BlockHitResult raycastTarget(
            Minecraft client,
            BlockPos pos,
            Direction requiredDirection
    ) {
        if (!isProbeableBlock(client, pos)) {
            return null;
        }

        BlockState state = client.level.getBlockState(pos);
        Vec3 eye = client.player.getEyePosition();
        double reach = client.player.blockInteractionRange();
        Vec3 end = eye.add(client.player.getViewVector(1.0F).normalize().scale(reach));
        BlockHitResult hit = client.level.clipWithInteractionOverride(
                eye, end, pos, state.getShape(client.level, pos), state);

        return isValidProbeHit(hit, pos, requiredDirection, eye, reach) ? hit : null;
    }

    static boolean isValidProbeHit(BlockHitResult hit, BlockPos pos,
                                   Direction requiredDirection, Vec3 eye, double reach) {
        // clipWithInteractionOverride returns null for blocks outside the ray.
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos)
                && (requiredDirection == null || hit.getDirection() == requiredDirection)
                && hit.getLocation().distanceToSqr(eye) <= reach * reach + DISTANCE_EPSILON;
    }

    private static boolean isProbeableBlock(Minecraft client, BlockPos pos) {
        if (client.level == null
                || pos == null
                || !client.level.isInWorldBounds(pos)
                || !client.level.isLoaded(pos)) {
            return false;
        }

        BlockState state = client.level.getBlockState(pos);
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && state.getDestroySpeed(client.level, pos) >= 0.0F
                && !state.getCollisionShape(client.level, pos).isEmpty()
                && !state.getShape(client.level, pos).isEmpty();
    }

    private static boolean canProbe(Minecraft client) {
        if (client.player == null
                || client.level == null
                || client.gameMode == null
                || client.getConnection() == null
                || client.hasSingleplayerServer()
                || client.level.dimension().equals(Level.END)
                || MinecraftClientAccess.screen(client) != null
                || client.options.keyAttack.isDown()
                || client.options.keyUse.isDown()
                || client.player.isUsingItem()
                || client.gameMode.isDestroying()
                || client.hitResult instanceof EntityHitResult
                || SilentAura.hasLockedTarget(client)) {
            return false;
        }

        return client.player.getMainHandItem().isEmpty()
                || client.player.getMainHandItem().is(ItemTags.PICKAXES);
    }

    private static boolean isStationary(Minecraft client) {
        return client.player.getDeltaMovement().horizontalDistance() <= 0.05D
                && !client.options.keyUp.isDown()
                && !client.options.keyDown.isDown()
                && !client.options.keyLeft.isDown()
                && !client.options.keyRight.isDown()
                && !client.options.keyJump.isDown();
    }

    private static void resetAllState() {
        activeLevel = null;
        packetsSent = 0;
        resetTargetState();
    }

    private static void resetTargetState() {
        resetCrosshairState();
        resetStaticState();
    }

    private static void resetCrosshairState() {
        lastCrosshairBlock = null;
        crosshairBlockProbed = false;
    }

    private static void resetStaticState() {
        STATIC_TARGETS.clear();
        staticOrigin = null;
        staticIntervalCounter = 0;
    }

    private record ProbeTarget(BlockPos pos, Direction direction) {
    }
}
