package com.blanoir.moons.client.module.impl.render.xray;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.MoonsConfig;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.module.impl.combat.SilentAura;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

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

    private static WorldClient activeLevel;
    private static BlockPos lastCrosshairBlock;
    private static boolean crosshairBlockProbed;
    private static BlockPos staticOrigin;
    private static int staticIntervalCounter;
    private static int packetsSent;

    private XrayDestroyPacketMode() {}

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
            ClientChat.send(
                    client, "Packet Xray is already " + (newEnabled ? "enabled." : "disabled."));
            return;
        }

        ENABLED.set(newEnabled);
        resetAllState();
        ClientChat.send(
                client,
                newEnabled
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

    public static int setPacketIntervalTicks(Minecraft client, int ticks) {
        PACKET_INTERVAL_TICKS.set(ticks);
        staticIntervalCounter = 0;
        ClientChat.send(
                client,
                "Packet Xray static scan interval set to "
                        + PACKET_INTERVAL_TICKS.get()
                        + " ticks.");
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

        if (activeLevel != client.theWorld) {
            activeLevel = client.theWorld;
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
        if (client.objectMouseOver == null
                || client.objectMouseOver.typeOfHit
                        != MovingObjectPosition.MovingObjectType.BLOCK) {
            resetCrosshairState();
            return;
        }

        BlockPos surfaceBlock = client.objectMouseOver.getBlockPos();
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

        MovingObjectPosition target = deepestTargetOnViewRay(client);
        crosshairBlockProbed = true;
        if (target != null) {
            sendProbe(client, target.getBlockPos(), target.sideHit);
        }
    }

    private static MovingObjectPosition deepestTargetOnViewRay(Minecraft client) {
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        Vec3 view = client.thePlayer.getLook(1.0F).normalize();
        double reach = client.playerController.getBlockReachDistance();

        BlockPos previous = null;
        MovingObjectPosition deepest = null;
        for (double distance = 0.0D; distance <= reach; distance += RAY_STEP) {
            BlockPos pos =
                    new BlockPos(
                            eye.addVector(
                                    view.xCoord * distance,
                                    view.yCoord * distance,
                                    view.zCoord * distance));
            if (previous != null && pos.equals(previous)) {
                continue;
            }
            previous = pos;
            if (!isProbeableBlock(client, pos)) {
                continue;
            }
            MovingObjectPosition hit = raycastTarget(client, pos, null);
            if (hit != null) {
                deepest = hit;
            }
        }
        return deepest;
    }

    private static void tickStaticScan(Minecraft client) {
        BlockPos playerBlock = client.thePlayer.getPosition();
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
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        double reach = client.playerController.getBlockReachDistance();
        int radius = (int) Math.ceil(reach);
        double candidateRangeSqr = (reach + 1.5D) * (reach + 1.5D);

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (eye.squareDistanceTo(
                                            new Vec3(
                                                    pos.getX() + .5,
                                                    pos.getY() + .5,
                                                    pos.getZ() + .5))
                                    > candidateRangeSqr
                            || !isProbeableBlock(client, pos)) {
                        continue;
                    }

                    // The ray already determines the hit face. Repeating the
                    // identical shape clip for all six faces adds no candidates.
                    MovingObjectPosition hit = raycastTarget(client, pos, null);
                    if (hit != null) {
                        STATIC_TARGETS.addLast(new ProbeTarget(hit.getBlockPos(), hit.sideHit));
                    }
                }
            }
        }
    }

    private static void sendProbe(Minecraft client, BlockPos pos, EnumFacing direction) {
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        var currentGameMode = client == null ? null : client.playerController;
        var connectionSnapshot = client == null ? null : client.getNetHandler();
        if (client == null
                || currentPlayer == null
                || currentLevel == null
                || currentGameMode == null
                || connectionSnapshot == null
                || currentGameMode.isSpectator()
                || !currentLevel.getWorldBorder().contains(pos)
                || !isProbeableBlock(client, pos)) {
            return;
        }

        MovingObjectPosition verifiedHit = raycastTarget(client, pos, direction);
        if (verifiedHit == null) {
            return;
        }

        connectionSnapshot.addToSendQueue(
                new C07PacketPlayerDigging(
                        C07PacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, direction));
        connectionSnapshot.addToSendQueue(
                new C07PacketPlayerDigging(
                        C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK, pos, EnumFacing.DOWN));
        connectionSnapshot.addToSendQueue(new C0APacketAnimation());
        packetsSent++;
    }

    private static MovingObjectPosition raycastTarget(
            Minecraft client, BlockPos pos, EnumFacing requiredDirection) {
        if (!isProbeableBlock(client, pos)) {
            return null;
        }

        IBlockState state = client.theWorld.getBlockState(pos);
        Vec3 eye = client.thePlayer.getPositionEyes(1.0F);
        double reach = client.playerController.getBlockReachDistance();
        Vec3 end =
                eye.addVector(
                        client.thePlayer.getLook(1.0F).xCoord * reach,
                        client.thePlayer.getLook(1.0F).yCoord * reach,
                        client.thePlayer.getLook(1.0F).zCoord * reach);
        MovingObjectPosition hit =
                state.getBlock().collisionRayTrace(client.theWorld, pos, eye, end);

        return isValidProbeHit(hit, pos, requiredDirection, eye, reach) ? hit : null;
    }

    static boolean isValidProbeHit(
            MovingObjectPosition hit,
            BlockPos pos,
            EnumFacing requiredDirection,
            Vec3 eye,
            double reach) {
        // clipWithInteractionOverride returns null for blocks outside the ray.
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(pos)
                && (requiredDirection == null || hit.sideHit == requiredDirection)
                && hit.hitVec.squareDistanceTo(eye) <= reach * reach + DISTANCE_EPSILON;
    }

    private static boolean isProbeableBlock(Minecraft client, BlockPos pos) {
        var currentLevel = client == null ? null : client.theWorld;
        if (currentLevel == null
                || pos == null
                || (pos.getY() < 0 || pos.getY() >= 256)
                || !currentLevel.isBlockLoaded(pos)) {
            return false;
        }

        IBlockState state = currentLevel.getBlockState(pos);
        return state.getBlock() != net.minecraft.init.Blocks.air
                && !state.getBlock().getMaterial().isLiquid()
                && state.getBlock().getBlockHardness(currentLevel, pos) >= 0
                && state.getBlock().getCollisionBoundingBox(currentLevel, pos, state) != null;
    }

    private static boolean canProbe(Minecraft client) {
        var currentPlayer = client == null ? null : client.thePlayer;
        var currentLevel = client == null ? null : client.theWorld;
        var currentGameMode = client == null ? null : client.playerController;
        if (client == null
                || currentPlayer == null
                || currentLevel == null
                || currentGameMode == null
                || client.getNetHandler() == null
                || client.isIntegratedServerRunning()
                || currentPlayer.dimension == 1
                || MinecraftClientAccess.screen(client) != null
                || client.gameSettings.keyBindAttack.isKeyDown()
                || client.gameSettings.keyBindUseItem.isKeyDown()
                || currentPlayer.isUsingItem()
                || currentGameMode.getIsHittingBlock()
                || (client.objectMouseOver != null
                        && client.objectMouseOver.typeOfHit
                                == MovingObjectPosition.MovingObjectType.ENTITY)
                || SilentAura.hasLockedTarget(client)) {
            return false;
        }

        return currentPlayer.getHeldItem() == null
                || currentPlayer.getHeldItem().getItem() instanceof ItemPickaxe;
    }

    private static boolean isStationary(Minecraft client) {
        return Math.hypot(client.thePlayer.motionX, client.thePlayer.motionZ) <= 0.05D
                && !client.gameSettings.keyBindForward.isKeyDown()
                && !client.gameSettings.keyBindBack.isKeyDown()
                && !client.gameSettings.keyBindLeft.isKeyDown()
                && !client.gameSettings.keyBindRight.isKeyDown()
                && !client.gameSettings.keyBindJump.isKeyDown();
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

    private record ProbeTarget(BlockPos pos, EnumFacing direction) {}
}
