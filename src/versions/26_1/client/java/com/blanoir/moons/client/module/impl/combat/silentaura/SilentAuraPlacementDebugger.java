package com.blanoir.moons.client.module.impl.combat.silentaura;

import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.action.AttackInputEvent;
import com.blanoir.moons.client.event.action.UseInputEvent;
import com.blanoir.moons.client.event.combat.AttackEntityEvent;
import com.blanoir.moons.client.event.movement.PlayerUpdateEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.impl.player.AutoLava;
import com.blanoir.moons.client.module.impl.player.AutoWeb;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Short packet-order flight recorder for reproducing Grim RotationPlace flags. */
public final class SilentAuraPlacementDebugger {
    private static final int MAX_TICK_LINES = 10;
    private static final int CAPTURE_TICKS_AFTER_ACTION = 40;
    private static final ArrayDeque<TickTrace> HISTORY = new ArrayDeque<>();
    private static boolean initialized;
    private static int captureUntilTick = Integer.MIN_VALUE;
    private static int lastSelectedSlot = -1;
    private static float lastMoveYaw;
    private static float lastMovePitch;
    private static boolean lastMoveValid;
    private static int lastSentSlot = -1;
    private static String diagnosis = "place=waiting for attack/use";

    private SilentAuraPlacementDebugger() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        EventBus.PLAYER_UPDATE.register(
                "SilentAuraPlacementDebugger.playerUpdate",
                SilentAuraPlacementDebugger::onPlayerUpdate);
        EventBus.ATTACK_INPUT_PRE.register(
                "SilentAuraPlacementDebugger.attackInputPre",
                SilentAuraPlacementDebugger::onAttackInputPre);
        EventBus.ATTACK_INPUT_POST.register(
                "SilentAuraPlacementDebugger.attackInputPost",
                SilentAuraPlacementDebugger::onAttackInputPost);
        EventBus.ATTACK_ENTITY_POST.register(
                "SilentAuraPlacementDebugger.attack",
                SilentAuraPlacementDebugger::onAttack);
        EventBus.USE_INPUT_PRE.register(
                "SilentAuraPlacementDebugger.usePre",
                SilentAuraPlacementDebugger::onUsePre);
        EventBus.USE_INPUT_POST.register(
                "SilentAuraPlacementDebugger.usePost",
                SilentAuraPlacementDebugger::onUsePost);
        EventBus.PACKET_SEND_POST.register(
                "SilentAuraPlacementDebugger.packetPost",
                SilentAuraPlacementDebugger::onPacketPost);
    }

    private static void onAttackInputPre(AttackInputEvent.Pre event) {
        Minecraft client = event.client();
        if (!enabled(client)) return;
        arm(client);
        TickTrace trace = trace(client.player.tickCount);
        trace.attackInput = true;
        trace.attackStartedNanos = System.nanoTime();
        trace.add("CLICK(c=" + charge(client)
                + ",agg=" + aggregateDamage(client)
                + ",item=" + itemDamage(client)
                + ",effects=" + damageEffects(client)
                + ",slot=" + client.player.getInventory().getSelectedSlot()
                + ":" + heldItem(client)
                + ",ground=" + client.player.onGround()
                + ",fall=" + decimal(client.player.fallDistance) + ")");
    }

    private static void onAttackInputPost(AttackInputEvent.Post event) {
        Minecraft client = event.client();
        if (!enabled(client)) return;
        TickTrace trace = trace(client.player.tickCount);
        if (!trace.attackInput) return;
        trace.add("CLICK_END");
        String risk = trace.attackPackets == 1 ? "ok" : "RISK";
        diagnosis = "ATK " + risk + " t=" + trace.tick
                + " attacks=" + trace.attackPackets
                + " swings=" + trace.swingPackets
                + " client=" + client.player.getInventory().getSelectedSlot()
                + ":" + heldItem(client)
                + " trackedServerSlot=" + trackedSlot()
                + " chargeNow=" + charge(client);
        System.out.println("[Moons/AttackDebug] " + diagnosis
                + " order=" + trace.render());
    }

    private static void onPlayerUpdate(PlayerUpdateEvent event) {
        Minecraft client = event.client();
        if (!capturing(client)) return;
        TickTrace trace = trace(client.player.tickCount);
        trace.add("PU");
        int slot = client.player.getInventory().getSelectedSlot();
        if (slot != lastSelectedSlot) {
            lastSelectedSlot = slot;
            trace.add("held=" + slot + ":" + heldItem(client));
        }
    }

    private static void onAttack(AttackEntityEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled(client) || event.attacker() != client.player) return;
        arm(client);
        trace(client.player.tickCount).add("ATTACK#" + event.target().getId());
    }

    private static void onUsePre(UseInputEvent.Pre event) {
        Minecraft client = event.client();
        if (!enabled(client)) return;
        arm(client);
        boolean automated = SilentPacketRotation.isUseRotationLocked();
        trace(client.player.tickCount).add(
                "USE_PRE(" + (automated ? "auto" : "manual")
                        + "," + auraState(client) + "," + heldItem(client) + ")");
    }

    private static void onUsePost(UseInputEvent.Post event) {
        Minecraft client = event.client();
        if (!capturing(client)) return;
        trace(client.player.tickCount).add("USE_END");
    }

    private static void onPacketPost(PacketSendEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled(client)) return;
        Packet<?> packet = event.packet();
        if (packet instanceof ServerboundSetCarriedItemPacket carried) {
            lastSentSlot = carried.getSlot();
            if (capturing(client)) {
                trace(client.player.tickCount).add("SLOT(" + carried.getSlot()
                        + ":" + heldItem(client) + ")");
            }
            return;
        }
        if (!capturing(client)) return;
        int tick = client.player.tickCount;
        TickTrace trace = trace(tick);
        if (packet instanceof ServerboundMovePlayerPacket movement) {
            float fallbackYaw = lastMoveValid ? lastMoveYaw : client.player.getYRot();
            float fallbackPitch = lastMoveValid ? lastMovePitch : client.player.getXRot();
            lastMoveYaw = movement.getYRot(fallbackYaw);
            lastMovePitch = movement.getXRot(fallbackPitch);
            lastMoveValid = true;
            trace.movementSent = true;
            trace.add("MOVE(" + angle(lastMoveYaw) + "," + angle(lastMovePitch) + ")");
            evaluateUseRotations(client, trace, tick, lastMoveYaw, lastMovePitch);
            return;
        }
        if (packet instanceof ServerboundUseItemOnPacket) {
            trace.add("USE_ON[" + (trace.movementSent ? "POST" : "PRE") + "]");
            return;
        }
        if (packet instanceof ServerboundUseItemPacket use) {
            recordUseItemPacket(client, trace, tick, use.getYRot(), use.getXRot());
            return;
        }
        if (packet instanceof ServerboundAttackPacket attack) {
            trace.attackPackets++;
            trace.add("ATTACK_PKT#" + trace.attackPackets
                    + "(id=" + attack.entityId()
                    + ",+" + elapsedMicros(trace) + "us"
                    + ",c=" + charge(client)
                    + ",slot=" + client.player.getInventory().getSelectedSlot()
                    + ":" + heldItem(client)
                    + ",agg=" + aggregateDamage(client)
                    + ",item=" + itemDamage(client)
                    + ",server=" + trackedSlot() + ")");
        } else if (packet instanceof ServerboundSwingPacket) {
            trace.swingPackets++;
            trace.add("SWING#" + trace.swingPackets
                    + "(+" + elapsedMicros(trace) + "us)");
        }
    }

    private static void recordUseItemPacket(
            Minecraft client, TickTrace trace, int tick, float yaw, float pitch) {
        UseRotation previous = trace.useRotations.isEmpty()
                ? null : trace.useRotations.get(trace.useRotations.size() - 1);
        UseRotation current = new UseRotation(yaw, pitch);
        trace.useRotations.add(current);
        trace.add("USE_ITEM(" + angle(yaw) + "," + angle(pitch) + ")["
                + (trace.movementSent ? "POST" : "PRE") + "]");
        if (previous != null
                && (!same(previous.yaw(), yaw) || !same(previous.pitch(), pitch))) {
            diagnoseMismatch(client, trace, tick, yaw, pitch,
                    previous.yaw(), previous.pitch(), "previous-use");
            return;
        }
        if (trace.movementSent) {
            evaluateUseRotation(client, trace, tick, current,
                    lastMoveYaw, lastMovePitch, "movement");
        }
    }

    private static void evaluateUseRotations(
            Minecraft client, TickTrace trace, int tick,
            float movementYaw, float movementPitch) {
        for (UseRotation use : trace.useRotations) {
            evaluateUseRotation(client, trace, tick, use,
                    movementYaw, movementPitch, "movement");
        }
    }

    private static void evaluateUseRotation(
            Minecraft client, TickTrace trace, int tick, UseRotation use,
            float expectedYaw, float expectedPitch, String expectedSource) {
        if (!same(use.yaw(), expectedYaw) || !same(use.pitch(), expectedPitch)) {
            diagnoseMismatch(client, trace, tick, use.yaw(), use.pitch(),
                    expectedYaw, expectedPitch, expectedSource);
            return;
        }
        String state = auraState(client);
        diagnosis = "BADJ ok t=" + tick + " item=" + heldItem(client)
                + " aura=" + state
                + " use=" + angle(use.yaw()) + "/" + angle(use.pitch())
                + " move=" + angle(expectedYaw) + "/" + angle(expectedPitch);
        System.out.println("[Moons/RotationPlaceDebug] " + diagnosis
                + " order=" + trace.render());
    }

    private static void diagnoseMismatch(
            Minecraft client, TickTrace trace, int tick,
            float useYaw, float usePitch,
            float expectedYaw, float expectedPitch,
            String expectedSource) {
        diagnosis = "BADJ MISMATCH t=" + tick + " item=" + heldItem(client)
                + " aura=" + auraState(client)
                + " use=" + precise(useYaw) + "/" + precise(usePitch)
                + " " + expectedSource + "="
                + precise(expectedYaw) + "/" + precise(expectedPitch)
                + " delta=" + precise(useYaw - expectedYaw)
                + "/" + precise(usePitch - expectedPitch)
                + " cam=" + angle(client.player.getYRot())
                + "/" + angle(client.player.getXRot());
        System.out.println("[Moons/RotationPlaceDebug] " + diagnosis
                + " order=" + trace.render());
    }

    private static boolean same(float first, float second) {
        return Float.floatToIntBits(first) == Float.floatToIntBits(second);
    }

    public static synchronized String[] debugLines() {
        List<String> lines = new ArrayList<>(MAX_TICK_LINES + 3);
        lines.add(diagnosis);
        lines.add("web=" + AutoWeb.debugState());
        lines.add("lava=" + AutoLava.debugState());
        for (TickTrace trace : HISTORY) {
            lines.add(trace.render());
        }
        return lines.toArray(String[]::new);
    }

    private static synchronized TickTrace trace(int tick) {
        TickTrace current = HISTORY.peekLast();
        if (current != null && current.tick == tick) return current;
        current = new TickTrace(tick);
        HISTORY.addLast(current);
        while (HISTORY.size() > MAX_TICK_LINES) HISTORY.removeFirst();
        return current;
    }

    private static synchronized void arm(Minecraft client) {
        captureUntilTick = Math.max(
                captureUntilTick, client.player.tickCount + CAPTURE_TICKS_AFTER_ACTION);
    }

    private static boolean capturing(Minecraft client) {
        return enabled(client) && client.player.tickCount <= captureUntilTick;
    }

    private static boolean enabled(Minecraft client) {
        return SilentAuraConfig.debugger()
                && client != null && client.player != null && client.level != null;
    }

    private static String auraState(Minecraft client) {
        if (SilentPacketRotation.shouldApplyRotation()) return "place-lease";
        if (SilentAuraRuntime.activationHeld(client)) return "tracking";
        if (SilentAuraRuntime.shouldApplyRotation()) return "returning";
        return "idle";
    }

    private static String heldItem(Minecraft client) {
        ItemStack stack = client.player.getMainHandItem();
        return stack.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String angle(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String charge(Minecraft client) {
        return decimal(client.player.getAttackStrengthScale(0.0F));
    }

    private static String aggregateDamage(Minecraft client) {
        return decimal(client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
    }

    private static String itemDamage(Minecraft client) {
        ItemStack weapon = client.player.getMainHandItem();
        double base = client.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        MobEffectInstance strength = client.player.getEffect(MobEffects.STRENGTH);
        if (strength != null) base += 3.0D * (strength.getAmplifier() + 1.0D);
        MobEffectInstance weakness = client.player.getEffect(MobEffects.WEAKNESS);
        if (weakness != null) base -= 4.0D * (weakness.getAmplifier() + 1.0D);
        ItemAttributeModifiers modifiers = weapon.getOrDefault(
                DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double value = modifiers.compute(
                Attributes.ATTACK_DAMAGE,
                base,
                EquipmentSlot.MAINHAND);
        return decimal(Attributes.ATTACK_DAMAGE.value().sanitizeValue(value));
    }

    private static String damageEffects(Minecraft client) {
        MobEffectInstance strength = client.player.getEffect(MobEffects.STRENGTH);
        MobEffectInstance weakness = client.player.getEffect(MobEffects.WEAKNESS);
        return "str" + effectLevel(strength) + "/weak" + effectLevel(weakness);
    }

    private static int effectLevel(MobEffectInstance effect) {
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    private static String trackedSlot() {
        return lastSentSlot < 0 ? "unknown" : Integer.toString(lastSentSlot);
    }

    private static long elapsedMicros(TickTrace trace) {
        return trace.attackStartedNanos == 0L ? -1L
                : Math.max(0L, (System.nanoTime() - trace.attackStartedNanos) / 1_000L);
    }

    private static String precise(float value) {
        return String.format(Locale.ROOT, "%.9f[0x%08X]",
                value, Float.floatToRawIntBits(value));
    }

    private static final class TickTrace {
        private final int tick;
        private final List<String> events = new ArrayList<>();
        private final List<UseRotation> useRotations = new ArrayList<>();
        private boolean movementSent;
        private boolean attackInput;
        private long attackStartedNanos;
        private int attackPackets;
        private int swingPackets;

        private TickTrace(int tick) {
            this.tick = tick;
        }

        private void add(String event) {
            if (events.size() < 24) events.add(event);
        }

        private String render() {
            return "t" + tick + " " + String.join(" > ", events);
        }
    }

    private record UseRotation(float yaw, float pitch) {
    }
}
