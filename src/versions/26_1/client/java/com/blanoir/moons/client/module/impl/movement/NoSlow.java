package com.blanoir.moons.client.module.impl.movement;

import com.blanoir.moons.client.access.GameAccess;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.network.PacketReceiveEvent;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.event.tick.TickEndEvent;
import com.blanoir.moons.client.event.tick.TickEvent;
import com.blanoir.moons.client.chat.ClientChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * LiquidBounce Grim2371-style NoSlow.
 *
 * <p>The timing is deliberately split by the event boundary that owns it:</p>
 * <ol>
 *     <li>Tick START chooses the next FULL/VANILLA movement phase and creates one
 *     predicted {@link ServerboundUseItemPacket}.</li>
 *     <li>SEND_PRE only classifies that exact packet. It never bypasses the rest
 *     of the packet pipeline.</li>
 *     <li>SEND_POST confirms that the packet really left the client. Only then
 *     may the input hook use the selected movement phase.</li>
 *     <li>The item-use multiplier hook applies full or vanilla movement later in
 *     the same client tick.</li>
 *     <li>Tick END closes the phase and discards packets that were cancelled by
 *     an earlier listener.</li>
 *     <li>RECEIVE_NETWORK classifies reset packets; RECEIVE_APPLY clears state on
 *     the client thread after vanilla's network handoff.</li>
 * </ol>
 */
public final class NoSlow {
    private static final Object LOCK = new Object();

    private static final BooleanSetting ENABLED = booleanSetting("noslow.enabled", false);
    private static final BooleanSetting BOW = booleanSetting("noslow.bow", false);
    private static final BooleanSetting KEEP_SPRINTING =
            booleanSetting("noslow.keepSprinting", true);
    private static final BooleanSetting CROSSBOW = booleanSetting("noslow.crossbow", false);
    private static final BooleanSetting FOOD = booleanSetting("noslow.food", true);
    private static final BooleanSetting POTION = booleanSetting("noslow.potion", true);
    private static final BooleanSetting SHIELD = booleanSetting("noslow.shield", true);

    /** Packets created at Tick START and awaiting the ordered SEND_PRE/SEND_POST pair. */
    private static final Map<Packet<?>, MovementPhase> PENDING_USE_PACKETS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<Packet<?>> PRE_OBSERVED_PACKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Set<Packet<?>> RESET_ON_APPLY_PACKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static boolean initialized;
    private static MovementPhase nextPhase = MovementPhase.FULL;
    private static boolean fullMovementThisTick;
    /** True after our USE_ITEM was written and before the next movement boundary. */
    private static boolean syntheticUseBeforeMovement;
    /** A vanilla release that would otherwise share an input tick with our USE_ITEM. */
    private static Packet<?> deferredRelease;
    /**
     * Number of later movement boundaries required before another synthetic
     * USE_ITEM may be emitted. A replayed release lives after the current
     * movement packet, so the following movement packet must close that input
     * tick before a new use packet is safe.
     */
    private static int blockedMovementBoundaries;
    private static final Set<Packet<?>> REPLAYED_RELEASE_PACKETS =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private NoSlow() {
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // Registration order is the packet order. No synthetic packet skips these channels.
        EventBus.TICK.register("NoSlow.tick", NoSlow::onTickStart);
        EventBus.PACKET_SEND_PRE.register("NoSlow.packetSendPre", NoSlow::onSendPre);
        EventBus.PACKET_SEND_POST.register("NoSlow.packetSendPost", NoSlow::onSendPost);
        EventBus.PACKET_RECEIVE_PRE.register("NoSlow.packetReceivePre", NoSlow::onReceiveNetwork);
        EventBus.PACKET_RECEIVE_APPLY.register("NoSlow.packetReceiveApply", NoSlow::onReceiveApply);
        EventBus.TICK_END.register("NoSlow.tickEnd", NoSlow::onTickEnd);
    }

    public static void shutdown() {
        synchronized (LOCK) {
            resetCycle();
            RESET_ON_APPLY_PACKETS.clear();
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static String hudTag() {
        return "Grim2371";
    }

    /**
     * INPUT MULTIPLIER phase. This runs after Tick START and the synchronous
     * SEND_PRE/SEND_POST chain for the generated use packet.
     */
    public static float itemUseSpeedMultiplier(LocalPlayer player, float vanillaMultiplier) {
        synchronized (LOCK) {
            if (!ENABLED.get() || !fullMovementThisTick || player == null
                    || !player.isUsingItem() || !isConfigured(player.getUseItem())) {
                return vanillaMultiplier;
            }
            return 1.0F;
        }
    }

    /** Sprint hooks share the exact movement phase used by the multiplier hook. */
    public static boolean shouldKeepSprinting(LocalPlayer player) {
        synchronized (LOCK) {
            return ENABLED.get() && KEEP_SPRINTING.get() && fullMovementThisTick
                    && player != null && player.isUsingItem()
                    && isConfigured(player.getUseItem());
        }
    }

    private static void onTickStart(TickEvent event) {
        synchronized (LOCK) {
            fullMovementThisTick = false;
            PENDING_USE_PACKETS.clear();
            PRE_OBSERVED_PACKETS.clear();

            Minecraft client = event.client();
            LocalPlayer player = client.player;
            var connection = client.getConnection();
            if (!ENABLED.get() || player == null || client.level == null
                    || connection == null || !player.isUsingItem()
                    || !isConfigured(player.getUseItem())
                    // Vanilla will release later in this same client tick when
                    // the key is up or the use duration reaches its last frame.
                    || !client.options.keyUse.isDown()
                    || player.getUseItemRemainingTicks() <= 1
                    || deferredRelease != null || blockedMovementBoundaries > 0) {
                resetMovementPhase();
                return;
            }

            MovementPhase requestedPhase = nextPhase;
            GameAccess.withPredictionSequence(client.level, sequence -> {
                ServerboundUseItemPacket packet = new ServerboundUseItemPacket(
                        player.getUsedItemHand(), sequence, player.getYRot(), player.getXRot());
                PENDING_USE_PACKETS.put(packet, requestedPhase);

                // Normal send: NoSlow PRE -> other PRE listeners -> actual write -> all POST listeners.
                connection.send(packet);
            });
        }
    }

    /** SEND_PRE phase: identify the packet, but do not advance movement state. */
    private static void onSendPre(PacketSendEvent.Pre event) {
        synchronized (LOCK) {
            if (isRelease(event.packet())) {
                if (REPLAYED_RELEASE_PACKETS.remove(event.packet())) return;
                if (syntheticUseBeforeMovement) {
                    // Grim PacketOrderI keeps USE_ITEM/right-click state until
                    // the next movement tick packet. Preserve vanilla's exact
                    // packet object and replay it only after that boundary.
                    if (deferredRelease == null) deferredRelease = event.packet();
                    event.cancel();
                    return;
                }
            }
            if (PENDING_USE_PACKETS.containsKey(event.packet())) {
                PRE_OBSERVED_PACKETS.add(event.packet());
            }
        }
    }

    /** SEND_POST phase: only a packet that passed PRE and was written advances the cycle. */
    private static void onSendPost(PacketSendEvent.Post event) {
        synchronized (LOCK) {
            MovementPhase sentPhase = PENDING_USE_PACKETS.remove(event.packet());
            if (sentPhase != null && PRE_OBSERVED_PACKETS.remove(event.packet())) {
                fullMovementThisTick = sentPhase == MovementPhase.FULL;
                nextPhase = sentPhase.next();
                syntheticUseBeforeMovement = true;
            }

            if (!(event.packet() instanceof ServerboundMovePlayerPacket)) return;

            syntheticUseBeforeMovement = false;
            if (blockedMovementBoundaries > 0) {
                blockedMovementBoundaries--;
                return;
            }

            Packet<?> release = deferredRelease;
            if (release == null) return;
            deferredRelease = null;
            blockedMovementBoundaries = 1;
            REPLAYED_RELEASE_PACKETS.add(release);
            // Normal send path: every module receives PRE and POST in the
            // globally registered order. Only this exact identity is exempt
            // from being deferred a second time.
            event.connection().send(release);
        }
    }

    /** RECEIVE_NETWORK phase: classify only; this callback may run on Netty. */
    private static void onReceiveNetwork(PacketReceiveEvent.Pre event) {
        if (isResetPacket(event.packet())) {
            RESET_ON_APPLY_PACKETS.add(event.packet());
        }
    }

    /** RECEIVE_APPLY phase: client-thread state mutation after vanilla's handoff. */
    private static void onReceiveApply(PacketReceiveEvent.Apply event) {
        if (!RESET_ON_APPLY_PACKETS.remove(event.packet()) && !isResetPacket(event.packet())) return;
        synchronized (LOCK) {
            resetCycle();
        }
    }

    /** Tick END closes the input phase and drops any packet cancelled during SEND_PRE. */
    private static void onTickEnd(TickEndEvent event) {
        synchronized (LOCK) {
            fullMovementThisTick = false;
            PENDING_USE_PACKETS.clear();
            PRE_OBSERVED_PACKETS.clear();
        }
    }

    public static int setEnabled(Minecraft client, boolean value) {
        synchronized (LOCK) {
            if (ENABLED.get() != value) {
                ENABLED.set(value);
                resetCycle();
            }
        }
        ClientChat.send(client, "NoSlow " + (value ? "enabled" : "disabled")
                + ". Mode: Grim2371.");
        return 1;
    }

    public static int setBow(Minecraft ignoredClient, boolean value) {
        BOW.set(value);
        return 1;
    }

    public static int setKeepSprinting(Minecraft ignoredClient, boolean value) {
        KEEP_SPRINTING.set(value);
        return 1;
    }

    public static int setCrossbow(Minecraft ignoredClient, boolean value) {
        CROSSBOW.set(value);
        return 1;
    }

    public static int setFood(Minecraft ignoredClient, boolean value) {
        FOOD.set(value);
        return 1;
    }

    public static int setPotion(Minecraft ignoredClient, boolean value) {
        POTION.set(value);
        return 1;
    }

    public static int setShield(Minecraft ignoredClient, boolean value) {
        SHIELD.set(value);
        return 1;
    }

    private static boolean isConfigured(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemUseAnimation animation = stack.getUseAnimation();
        return switch (animation) {
            case BOW -> BOW.get();
            case CROSSBOW -> CROSSBOW.get() && !CrossbowItem.isCharged(stack);
            case EAT -> FOOD.get();
            case DRINK -> POTION.get();
            case BLOCK -> SHIELD.get();
            default -> false;
        };
    }

    private static boolean isResetPacket(Packet<?> packet) {
        return packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundPlayerPositionPacket;
    }

    private static boolean isRelease(Packet<?> packet) {
        return packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
    }

    private static void resetCycle() {
        PENDING_USE_PACKETS.clear();
        PRE_OBSERVED_PACKETS.clear();
        REPLAYED_RELEASE_PACKETS.clear();
        deferredRelease = null;
        syntheticUseBeforeMovement = false;
        blockedMovementBoundaries = 0;
        fullMovementThisTick = false;
        resetMovementPhase();
    }

    private static void resetMovementPhase() {
        nextPhase = MovementPhase.FULL;
    }

    private static BooleanSetting booleanSetting(String key, boolean defaultValue) {
        return new BooleanSetting.Builder()
                .name(key)
                .defaultValue(defaultValue)
                .build();
    }

    private enum MovementPhase {
        FULL,
        VANILLA;

        private MovementPhase next() {
            return this == FULL ? VANILLA : FULL;
        }
    }
}
