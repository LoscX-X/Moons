package com.blanoir.moons.client.module.impl.player.blockin;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.management.lease.HotbarLease;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.world.placement.BlockPlacementUtils;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Plans a single fixed enclosure and serializes placement through the shared interaction lock. */
public final class BlockInRuntime {
    private static final int MAX_ATTEMPTS = 3;
    private static final int STALL_TICKS = 120;
    private static final int ACTION_TIMEOUT = 40;
    private static final HotbarLease HOTBAR =
            new HotbarLease("BlockIn", HotbarLease.PRIORITY_PLACEMENT);
    private static final CombatInputController.Owner INPUT = CombatInputController.Owner.BLOCK_IN;
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final Map<BlockPos, Pending> pending = new HashMap<>();
    private static BlockInPlanner.Layout layout;
    private static BlockInPlanner.Plan plan;
    private static Phase phase = Phase.IDLE;
    private static boolean initialized, ownsRotation, ownsInput, jumping, monitoring;
    private static int phaseTick, progressTick, jumpTick, jumps;
    private static long nextPlaceAt;
    private static String status = "Idle";

    private enum Phase {
        IDLE,
        TURNING,
        READY,
        USING
    }

    private record Pending(int started, int settle) {}

    private BlockInRuntime() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        PlacementCoordinator.register(PlacementCoordinator.Owner.BLOCK_IN, BlockInRuntime::busy);
        EventBus.CLIENT_CONTEXT_CHANGED.register("BlockIn.context", event -> shutdown(null));
        EventBus.PLAYER_UPDATE.register("BlockIn.update", event -> tick(event.client()));
    }

    public static boolean busy() {
        return layout != null && !monitoring;
    }

    public static String statusText() {
        return status;
    }

    public static int setEnabled(Minecraft client, boolean enabled) {
        cleanup(client);
        BlockInConfig.enabled(enabled);
        status = enabled ? "Waiting" : "Idle";
        ClientChat.send(client, "BlockIn " + (enabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static void restart(Minecraft client) {
        cleanup(client);
        status = BlockInConfig.enabled() ? "Waiting" : "Idle";
    }

    public static void shutdown(Minecraft client) {
        cleanup(client);
        BlockInConfig.enabled(false);
        status = "Idle";
    }

    private static void tick(Minecraft client) {
        if (!BlockInConfig.enabled()) {
            if (layout != null || ownsRotation) cleanup(client);
            return;
        }
        if (!ClientReady.aliveGameplay(client)
                || client.player.isPassenger()
                || client.player.getAbilities().flying
                || client.player.isFallFlying()) {
            if (layout != null) stop(client, "Interrupted");
            return;
        }
        int tick = client.player.tickCount;
        if (layout == null) {
            if (!client.player.onGround()) {
                status = "Waiting for ground";
                return;
            }
            if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.BLOCK_IN)
                    || SilentPacketRotation.isBusy()) {
                status = "Waiting for placement";
                return;
            }
            layout =
                    BlockInPlanner.layout(
                            client.player.getBoundingBox(),
                            BlockInConfig.floor(),
                            BlockInConfig.roof());
            progressTick = tick;
            jumpTick = tick - 20;
            status = "Building";
        }
        if (!layout.contains(client.player.getBoundingBox())) {
            stop(client, "Left enclosure");
            return;
        }
        if (jumping && (!client.player.onGround() || tick - jumpTick >= 3)) releaseJump(client);
        observe(client, tick);
        if (phase != Phase.IDLE) {
            if (tick - phaseTick > ACTION_TIMEOUT) {
                stop(client, "Placement timed out");
                return;
            }
            advance(client);
            if (phase != Phase.IDLE) return;
        }
        if (layout.targets().stream().allMatch(pos -> BlockInPlanner.solid(client, pos))
                && pending.isEmpty()) {
            releaseControls(client);
            status = "Complete";
            if (BlockInConfig.autoDisable()) {
                cleanup(client);
                BlockInConfig.enabled(false);
            } else {
                // Keep the original footprint when monitoring for new holes.
                attempts.clear();
                progressTick = tick;
                monitoring = true;
            }
            return;
        }
        if (monitoring) {
            if (PlacementCoordinator.busyFor(PlacementCoordinator.Owner.BLOCK_IN)
                    || SilentPacketRotation.isBusy()) return;
            monitoring = false;
            progressTick = tick;
        }
        if (tick - progressTick > STALL_TICKS) {
            stop(client, "Unreachable or blocked");
            return;
        }
        if (System.nanoTime() - nextPlaceAt < 0
                || PlacementCoordinator.busyFor(PlacementCoordinator.Owner.BLOCK_IN)
                || SilentPacketRotation.isBusy()) return;
        int slot = BlockInPlanner.materialSlot(client, BlockInConfig.priorities());
        if (slot < 0) {
            if (pending.isEmpty()) stop(client, "No usable blocks");
            else status = "Confirming";
            return;
        }
        BlockInPlanner.Plan candidate =
                BlockInPlanner.next(client, layout, BlockInRuntime::available);
        if (candidate == null) {
            status = pending.isEmpty() ? "Waiting for a face" : "Confirming";
            if (!jumping
                    && jumps < Math.max(3, layout.caps().size() * 2)
                    && tick - jumpTick >= 20
                    && BlockInPlanner.canJumpForCap(client, layout, BlockInRuntime::available)) {
                jumping = true;
                jumps++;
                jumpTick = tick;
                ownsInput = true;
                CombatInputController.suppressSprint(client, INPUT);
                CombatInputController.forceJump(client, INPUT);
                status = "Roof support";
            }
            return;
        }
        if (!HOTBAR.acquire(client, slot)) {
            status = "Waiting for slot";
            return;
        }
        if (!BlockInPlanner.validate(client, candidate, candidate.hit())) {
            attempts.merge(candidate.position(), 1, Integer::sum);
            HOTBAR.release(client);
            return;
        }
        ownsInput = true;
        CombatInputController.suppressAttack(client, INPUT);
        plan = candidate;
        phase = Phase.TURNING;
        phaseTick = tick;
        ownsRotation = true;
        status = "Placing";
        boolean accepted =
                SilentPacketRotation.beginRotation(
                        client,
                        plan.hit().getLocation(),
                        1,
                        SilentPacketRotation.Mode.INSTANT,
                        () -> {
                            if (phase == Phase.TURNING) phase = Phase.READY;
                        });
        if (!accepted) {
            finishAction(client);
            return;
        }
        advance(client);
    }

    private static void advance(Minecraft client) {
        if (phase == Phase.READY) {
            BlockHitResult actual =
                    BlockPlacementUtils.traceOutline(
                            client,
                            client.player.getEyePosition(),
                            SilentPacketRotation.getInteractionLookVector(client),
                            client.player.blockInteractionRange(),
                            ClipContext.Fluid.NONE);
            if (!HOTBAR.active()
                    || HOTBAR.leasedSlot() != client.player.getInventory().getSelectedSlot()
                    || !BlockPlacementUtils.matchesFace(actual, plan.hit())
                    || !BlockInPlanner.validate(client, plan, actual)) {
                attempts.merge(plan.position(), 1, Integer::sum);
                finishAction(client);
                return;
            }
            if (SilentPacketRotation.invokeUseInPlayerUpdate(client, actual, false)) {
                attempts.merge(plan.position(), 1, Integer::sum);
                pending.put(
                        plan.position(), new Pending(client.player.tickCount, settleTicks(client)));
                nextPlaceAt = System.nanoTime() + BlockInConfig.nextDelayMs() * 1_000_000L;
                phase = Phase.USING;
                phaseTick = client.player.tickCount;
            }
        }
        if (phase == Phase.USING && SilentPacketRotation.isUseDone()) finishAction(client);
    }

    private static boolean available(BlockPos pos) {
        return !pending.containsKey(pos) && attempts.getOrDefault(pos, 0) < MAX_ATTEMPTS;
    }

    private static void observe(Minecraft client, int tick) {
        Iterator<Map.Entry<BlockPos, Pending>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int elapsed = tick - entry.getValue().started();
            // Local prediction is provisional. Observe through a latency-based settle
            // window before declaring the enclosure complete; reverted cells can retry.
            if (elapsed < entry.getValue().settle()) continue;
            if (BlockInPlanner.solid(client, entry.getKey())) {
                iterator.remove();
                progressTick = tick;
            } else if (elapsed >= ACTION_TIMEOUT) iterator.remove();
        }
    }

    private static int settleTicks(Minecraft client) {
        var connection = client.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(client.player.getUUID());
        int latency = info == null ? 0 : Math.max(0, info.getLatency());
        return Math.clamp((int) Math.ceil(latency / 50.0) + 3, 8, ACTION_TIMEOUT);
    }

    private static void finishAction(Minecraft client) {
        if (ownsRotation) SilentPacketRotation.reset();
        ownsRotation = false;
        HOTBAR.release(client);
        phase = Phase.IDLE;
        plan = null;
    }

    private static void releaseJump(Minecraft client) {
        if (!jumping) return;
        CombatInputController.releaseJump(client, INPUT);
        CombatInputController.releaseSprint(client, INPUT);
        jumping = false;
    }

    private static void releaseControls(Minecraft client) {
        finishAction(client);
        releaseJump(client);
        if (ownsInput) CombatInputController.releaseAll(client, INPUT);
        ownsInput = false;
    }

    private static void cleanup(Minecraft client) {
        releaseControls(client);
        layout = null;
        attempts.clear();
        pending.clear();
        nextPlaceAt = 0;
        jumps = 0;
        monitoring = false;
    }

    private static void stop(Minecraft client, String reason) {
        cleanup(client);
        BlockInConfig.enabled(false);
        status = reason;
        ClientChat.send(client, "BlockIn: " + reason + ".");
    }
}
