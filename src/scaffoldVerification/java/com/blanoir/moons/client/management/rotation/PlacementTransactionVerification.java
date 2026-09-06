package com.blanoir.moons.client.management.rotation;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.module.impl.player.AutoBed;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exercises shared production packet observers and invocation completion. */
public final class PlacementTransactionVerification {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        verifyAutoBedOrder();
        SilentPacketRotation.init();
        RotationInteractionLock lock = (RotationInteractionLock) field("USE_LOCK").get(null);
        try {
            begin(lock);
            Packet<?> use = new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND,
                    new BlockHitResult(new Vec3(.5, 1, .5), Direction.UP, BlockPos.ZERO, false), 1);
            EventBus.PACKET_SEND_PRE.post(new PacketSendEvent.Pre(null, use, null));
            require(!lock.interactionPacketSent(), "PRE does not claim an interaction was sent");
            post(use);
            require(lock.interactionPacketSent(), "POST records an offhand use within an existing transaction");
            SilentPacketRotation.finishSimulatedUse(null);
            require(lock.locked(), "local return, including FAIL, retains the placement angle");

            // Multiple emitted uses can belong to the existing movement window.
            field("simulatedUseRunning").setBoolean(null, true);
            post(use);
            SilentPacketRotation.finishSimulatedUse(null);
            require(lock.locked(), "second compatible click retains the same window");
            Packet<?> look = new ServerboundMovePlayerPacket.Rot(42, 76, false, false);
            var pre = new PacketSendEvent.Pre(null, look, null);
            pre.cancel();
            EventBus.PACKET_SEND_PRE.post(pre);
            require(lock.locked(), "cancelled movement cannot close the window");
            require(!field("sentRotationValid").getBoolean(null), "PRE cannot confirm rotation");
            post(new ServerboundMovePlayerPacket.Rot(42, 75, false, false));
            require(lock.locked(), "different closing angle cannot unlock");
            post(look);
            require(!lock.locked() && SilentPacketRotation.isUseDone(), "matching POST closes the window");

            begin(lock);
            SilentPacketRotation.finishSimulatedUse(null);
            require(!lock.locked(), "no actual use packet needs no extra movement wait");

            begin(lock);
            post(use);
            SilentPacketRotation.finishSimulatedUse(null);
            SilentPacketRotation.reset();
            require(lock.locked() && SilentPacketRotation.shouldApplyRotation(), "reset defers angle release");
            post(look);
            require(!lock.locked() && !SilentPacketRotation.shouldApplyRotation(), "closing POST completes deferred reset");
            System.out.println("PLACEMENT_TRANSACTIONS_VERIFIED assertions=" + assertions);
        } finally {
            lock.clear();
            SilentPacketRotation.reset();
        }
    }

    private static void verifyAutoBedOrder() throws Exception {
        Field used = AutoBed.class.getDeclaredField("usedBeforeMovement");
        Field pending = AutoBed.class.getDeclaredField("afterUseMovement");
        Method defer = AutoBed.class.getDeclaredMethod("deferAfterUse", Runnable.class);
        Method finish = AutoBed.class.getDeclaredMethod("finishUseMovement");
        used.setAccessible(true);
        pending.setAccessible(true);
        defer.setAccessible(true);
        finish.setAccessible(true);
        int[] calls = {0};
        Runnable nextTurn = () -> calls[0]++;
        try {
            require(!(boolean) defer.invoke(null, nextTurn), "no UseOn introduces no deferral");
            used.setBoolean(null, true);
            require((boolean) defer.invoke(null, nextTurn), "turn after UseOn waits for motion boundary");
            require(calls[0] == 0, "turn cannot replace angle before same-tick movement");
            finish.invoke(null);
            require(calls[0] == 1 && !used.getBoolean(null), "same-tick motion boundary resumes without next-tick wait");
            finish.invoke(null);
            require(calls[0] == 1, "deferred turn executes only once");
        } finally {
            used.setBoolean(null, false);
            pending.set(null, null);
        }
    }

    private static void begin(RotationInteractionLock lock) throws Exception {
        lock.clear();
        SilentPacketRotation.reset();
        RotationLease lease = (RotationLease) field("ROTATION_LEASE").get(null);
        require(lease.acquire(new RotationRequest(42, 76, 1, .35F, null)) && lease.pin(), "transaction owns rotation");
        lock.begin(42, 76);
        field("holdingRotation").setBoolean(null, true);
        field("simulatedUseYaw").setFloat(null, 42);
        field("simulatedUsePitch").setFloat(null, 76);
        field("simulatedUseRunning").setBoolean(null, true);
    }

    private static void post(Packet<?> packet) throws Exception {
        Method observer = SilentPacketRotation.class.getDeclaredMethod("onPacketSent", PacketSendEvent.Post.class);
        observer.setAccessible(true);
        observer.invoke(null, new PacketSendEvent.Post(null, packet, null));
    }

    private static Field field(String name) throws Exception {
        Field field = SilentPacketRotation.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
