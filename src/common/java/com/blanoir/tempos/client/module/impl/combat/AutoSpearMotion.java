package com.blanoir.tempos.client.module.impl.combat;

import com.blanoir.tempos.client.access.MinecraftClientAccess;
import com.blanoir.tempos.client.event.EventBus;
import com.blanoir.tempos.client.event.EventPriority;
import com.blanoir.tempos.client.event.network.PacketReceiveEvent;
import com.blanoir.tempos.client.event.network.PacketSendEvent;
import com.blanoir.tempos.client.event.network.PacketThread;
import com.blanoir.tempos.client.module.framework.SubFeature;
import com.blanoir.tempos.client.lifecycle.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
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
final class AutoSpearMotion extends SubFeature<AutoSpear> {
    private final Pending pending = new Pending();
    private LocalPlayer player;
    private ClientLevel level;
    private Item spear;
    private int slot;

    AutoSpearMotion(AutoSpear parent) {
        super(parent);
    }

    @Override
    public void registerEvents() {
        // Both vanilla air jabs and assisted jabs emit STAB, regardless of held-use state.
        EventBus.PACKET_SEND_POST.register("AutoSpear.jabSent", this::sent);
        // APPLY observes packets on the client thread, including vanilla bundle children.
        EventBus.PACKET_RECEIVE_APPLY.register("AutoSpear.motion", this::receive);
        EventBus.PLAYER_UPDATE.register(
                "AutoSpear.boost", EventPriority.HIGHEST, event -> apply(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoSpear.motionContext", event -> reset());
    }

    private void sent(PacketSendEvent.Post event) {
        if (event.thread() != PacketThread.CLIENT
                || !(event.packet() instanceof ServerboundPlayerActionPacket packet)
                || packet.getAction() != ServerboundPlayerActionPacket.Action.STAB) return;
        Minecraft client = Minecraft.getInstance();
        if (!parent().isEnabled()
                || !ClientReady.aliveGameplay(client)
                || client.player.isSpectator()
                || client.getConnection() == null
                || event.connection() != client.getConnection().getConnection()) return;
        request(client);
    }

    private void request(Minecraft client) {
        parent().stopImpact(client);
        reset();
        if (parent().motionMultiply() <= 1.0
                && !parent().fakeLagEnabled()
                && !parent().impactBurstEnabled()) return;
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
        pending.request(player.position(), System.nanoTime());
    }

    private boolean valid(Minecraft client) {
        return parent().isEnabled()
                && (parent().motionMultiply() > 1.0
                        || parent().fakeLagEnabled()
                        || parent().impactBurstEnabled())
                && ClientReady.interaction(client)
                && client.player == player
                && client.level == level
                && !player.isDeadOrDying()
                && !player.isSpectator()
                && player.getInventory().getSelectedSlot() == slot
                // Lunge durability updates may replace the inventory stack before velocity arrives.
                && player.getMainHandItem().is(spear);
    }

    private void receive(PacketReceiveEvent.Apply event) {
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
                || MinecraftClientAccess.playerImpulse(packet, player.getId()).isPresent()) {
            reset();
        } else if (packet instanceof ClientboundSoundPacket sound
                && sound.getSource() == SoundSource.PLAYERS
                && (sound.getSound().equals(SoundEvents.LUNGE_1)
                        || sound.getSound().equals(SoundEvents.LUNGE_2)
                        || sound.getSound().equals(SoundEvents.LUNGE_3))) {
            // Velocity packets contain no cause; require nearby Lunge confirmation first.
            pending.confirm(new Vec3(sound.getX(), sound.getY(), sound.getZ()), System.nanoTime());
        } else if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.id() == player.getId()) {
            pending.velocity(motion.movement(), parent().motionMultiply(), System.nanoTime());
        }
    }

    private void apply(Minecraft client) {
        if (player == null) return;
        if (!valid(client)) {
            reset();
            return;
        }
        // The packet APPLY hook precedes vanilla's handler. Scale at player tick
        // head, after vanilla set the velocity and before movement consumes it.
        Vec3 boosted = pending.take(player.getDeltaMovement(), System.nanoTime());
        if (boosted != null) {
            // Targeted bursts keep the original Lunge until the approach is ready.
            // Air jabs and non-held use retain the ordinary Motion multiply behavior.
            if (!parent().armImpact(client)) {
                player.setDeltaMovement(boosted);
                parent().startPacketDelay(client);
            }
        }
        if (!pending.active(System.nanoTime())) reset();
    }

    void reset() {
        pending.clear();
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
