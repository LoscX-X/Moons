package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.network.PacketThread;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/** Correlates a manual jab with the server's Lunge sound and subsequent self velocity. */
final class AutoSpearMotion {
    private static final Pending PENDING = new Pending();
    private static LocalPlayer player;
    private static ClientLevel level;
    private static Item spear;
    private static int slot;

    private AutoSpearMotion() {}

    static void init() {
        // Both vanilla air jabs and assisted jabs emit STAB, regardless of held-use state.
        EventBus.PACKET_SEND_POST.register("AutoSpear.jabSent", AutoSpearMotion::sent);
        // APPLY observes packets on the client thread, including vanilla bundle children.
        EventBus.PACKET_RECEIVE_APPLY.register("AutoSpear.motion", AutoSpearMotion::receive);
        EventBus.PLAYER_UPDATE.register(
                "AutoSpear.boost", EventPriority.HIGHEST, event -> apply(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoSpear.motionContext", event -> reset());
    }

    private static void sent(PacketSendEvent.Post event) {
        if (event.thread() != PacketThread.CLIENT
                || !(event.packet() instanceof ServerboundPlayerActionPacket packet)
                || packet.getAction() != ServerboundPlayerActionPacket.Action.STAB) return;
        Minecraft client = Minecraft.getInstance();
        if (!AutoSpear.isEnabled()
                || !ClientReady.aliveGameplay(client)
                || client.player.isSpectator()
                || client.getConnection() == null
                || event.connection() != client.getConnection().getConnection()) return;
        request(client);
    }

    private static void request(Minecraft client) {
        AutoSpearImpact.stop(client);
        reset();
        if (AutoSpear.motionMultiply() <= 1.0 && !AutoSpear.fakeLagEnabled()
                && !AutoSpear.impactBurstEnabled()) return;
        ItemStack stack = client.player.getMainHandItem();
        if (!stack.has(DataComponents.PIERCING_WEAPON)) return;
        boolean lunge =
                stack.getEnchantments().entrySet().stream()
                        .anyMatch(
                                entry ->
                                        entry.getKey().is(Enchantments.LUNGE)
                                                && entry.getIntValue() > 0);
        if (!lunge) return;
        player = client.player;
        level = client.level;
        spear = stack.getItem();
        slot = player.getInventory().getSelectedSlot();
        PENDING.request(player.position(), System.nanoTime());
    }

    private static boolean valid(Minecraft client) {
        return AutoSpear.isEnabled()
                && (AutoSpear.motionMultiply() > 1.0 || AutoSpear.fakeLagEnabled()
                        || AutoSpear.impactBurstEnabled())
                && ClientReady.interaction(client)
                && client.player == player
                && client.level == level
                && !player.isDeadOrDying()
                && !player.isSpectator()
                && player.getInventory().getSelectedSlot() == slot
                // Lunge durability updates may replace the inventory stack before velocity arrives.
                && player.getMainHandItem().is(spear);
    }

    private static void receive(PacketReceiveEvent.Apply event) {
        if (player == null) return;
        Minecraft client = Minecraft.getInstance();
        if (!valid(client) || event.listener() != client.getConnection()) {
            reset();
            return;
        }
        var packet = event.packet();
        if (packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundDamageEventPacket damage
                        && damage.entityId() == player.getId()
                || packet instanceof ClientboundExplodePacket explosion
                        && explosion.playerKnockback().isPresent()) {
            reset();
        } else if (packet instanceof ClientboundSoundPacket sound
                && sound.getSource() == SoundSource.PLAYERS
                && (sound.getSound().equals(SoundEvents.LUNGE_1)
                        || sound.getSound().equals(SoundEvents.LUNGE_2)
                        || sound.getSound().equals(SoundEvents.LUNGE_3))) {
            // Velocity packets contain no cause; require nearby Lunge confirmation first.
            PENDING.confirm(new Vec3(sound.getX(), sound.getY(), sound.getZ()), System.nanoTime());
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.id() == player.getId()) {
            PENDING.velocity(motion.movement(), AutoSpear.motionMultiply(), System.nanoTime());
        }
    }

    private static void apply(Minecraft client) {
        if (player == null) return;
        if (!valid(client)) {
            reset();
            return;
        }
        // The packet APPLY hook precedes vanilla's handler. Scale at player tick
        // head, after vanilla set the velocity and before movement consumes it.
        Vec3 boosted = PENDING.take(player.getDeltaMovement(), System.nanoTime());
        if (boosted != null) {
            // Targeted bursts keep the original Lunge until the approach is ready.
            // Air jabs and non-held use retain the ordinary Motion multiply behavior.
            if (!AutoSpearImpact.arm(client)) {
                player.setDeltaMovement(boosted);
                AutoSpearFakeLag.start(client);
            }
        }
        if (!PENDING.active(System.nanoTime())) reset();
    }

    static void reset() {
        PENDING.clear();
        player = null;
        level = null;
        spear = null;
        slot = -1;
    }

    /** One bounded request; an unrelated velocity or interruption consumes it without boosting. */
    static final class Pending {
        private Vec3 origin;
        private long deadline;
        private boolean confirmed;
        private Vec3 incoming;
        private double multiplier;

        void request(Vec3 position, long now) {
            clear();
            origin = position;
            deadline = now + 1_000_000_000L;
        }

        boolean active(long now) {
            return origin != null && now <= deadline;
        }

        void confirm(Vec3 position, long now) {
            if (active(now) && position.distanceToSqr(origin) <= 4.0) confirmed = true;
        }

        void velocity(Vec3 movement, double factor, long now) {
            if (!active(now) || !confirmed || incoming != null) {
                clear();
                return;
            }
            incoming = movement;
            multiplier = factor;
            confirmed = false;
        }

        Vec3 take(Vec3 current, long now) {
            if (incoming == null) return null;
            Vec3 result =
                    active(now) && incoming.equals(current)
                            ? new Vec3(current.x * multiplier, current.y, current.z * multiplier)
                            : null;
            clear();
            return result;
        }

        void clear() {
            origin = null;
            incoming = null;
            confirmed = false;
            deadline = 0;
            multiplier = 1.0;
        }
    }
}
