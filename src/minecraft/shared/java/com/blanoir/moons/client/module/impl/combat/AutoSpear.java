package com.blanoir.moons.client.module.impl.combat;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.DoubleSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.event.EventPriority;
import com.blanoir.moons.client.event.network.PacketSendEvent;
import com.blanoir.moons.client.management.input.CombatInputController;
import com.blanoir.moons.client.utils.client.ClientReady;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.PiercingWeapon;

/** Allows manual spear jabs while maintaining the main-hand charge attack. */
public final class AutoSpear {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autospear.enabled").defaultValue(false).build();
    private static final BooleanSetting IGNORE_COOLDOWN =
            new BooleanSetting.Builder()
                    .name("autospear.ignoreCooldown")
                    .defaultValue(false)
                    .build();
    private static final BooleanSetting FULL_HOLD =
            new BooleanSetting.Builder().name("autospear.fullHold").defaultValue(false).build();
    private static final DoubleSetting MOTION_MULTIPLY =
            new DoubleSetting.Builder()
                    .name("autospear.motionMultiply")
                    .defaultValue(1.0)
                    .range(1.0, 5.0)
                    .build();
    private static final BooleanSetting IMPACT_BURST =
            new BooleanSetting.Builder().name("autospear.impactBurst").defaultValue(false).build();
    private static final DoubleSetting IMPACT_MULTIPLY =
            new DoubleSetting.Builder()
                    .name("autospear.impactMultiply")
                    .defaultValue(8.0)
                    .range(1.0, 20.0)
                    .build();
    // Retain the original Blink keys so existing presets carry over to FakeLag.
    private static final BooleanSetting FAKE_LAG =
            new BooleanSetting.Builder().name("autospear.blink").defaultValue(false).build();
    private static final IntSetting FAKE_LAG_DELAY =
            new IntSetting.Builder()
                    .name("autospear.blinkDurationMs")
                    .defaultValue(150)
                    .range(50, 500)
                    .build();
    private static final IntSetting FAKE_LAG_DURATION =
            new IntSetting.Builder()
                    .name("autospear.fakeLagDurationMs")
                    .defaultValue(500)
                    .range(100, 2000)
                    .build();

    private static LocalPlayer heldPlayer;
    private static ClientLevel heldLevel;
    private static ItemStack heldStack;
    private static int heldSlot = -1;
    private static boolean restoreUse;
    private static boolean releasing;

    private AutoSpear() {}

    public static void init() {
        AutoSpearFakeLag.init();
        AutoSpearMotion.init();
        AutoSpearImpact.init();
        EventBus.TICK.register("AutoSpear.tick", event -> tick(event.client()));
        EventBus.TICK_END.register("AutoSpear.hold", event -> refreshHold(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register(
                "AutoSpear.context", event -> discardHold(event.client()));
    }

    public static void initPacketListeners() {
        EventBus.PACKET_SEND_PRE.register(
                "AutoSpear.fullHold", EventPriority.HIGHEST, AutoSpear::beforeSend);
        AutoSpearFakeLag.initPacketListeners();
    }

    private static void tick(Minecraft client) {
        refreshHold(client);
        if (!ENABLED.get()
                || !ClientReady.aliveGameplay(client)
                || !client.isWindowActive()
                || client.isPaused()
                || client.player.isSpectator()
                || !client.player.isUsingItem()
                || client.player.getUsedItemHand() != InteractionHand.MAIN_HAND
                || !client.options.keyUse.isDown()) return;

        ItemStack stack = client.player.getMainHandItem();
        if (stack.getUseAnimation() != ItemUseAnimation.SPEAR) return;
        PiercingWeapon weapon = stack.get(DataComponents.PIERCING_WEAPON);
        if (weapon == null || !stack.isItemEnabled(client.level.enabledFeatures())) return;

        // Vanilla discards attack clicks while using an item. Consume them here
        // before handleKeybinds, including quick presses released between ticks.
        // A held button never repeats; blocked clicks are never queued for later.
        boolean clicked = false;
        while (client.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        if (!clicked) return;
        // Only bypass the local gate. The server still validates the STAB cooldown.
        if (!IGNORE_COOLDOWN.get() && client.player.cannotAttackWithItem(stack, 0)) return;

        // This sends STAB and updates the local attack timer. Ordinary entity
        // attack packets are rejected for piercing weapons. Keep use active;
        // the server applies Lunge through the post-piercing enchantment effects.
        MinecraftClientAccess.piercingAttack(client, weapon);
    }

    private static boolean usingSpear(Minecraft client) {
        return ClientReady.interaction(client)
                && client.player.isUsingItem()
                && client.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && client.player.getUseItem() == client.player.getMainHandItem()
                && client.player.getUseItem().getUseAnimation() == ItemUseAnimation.SPEAR
                && client.player.getUseItem().has(DataComponents.PIERCING_WEAPON);
    }

    private static boolean sameHold(Minecraft client) {
        return heldPlayer != null
                && ClientReady.interaction(client)
                && client.player == heldPlayer
                && client.level == heldLevel
                && client.player.getInventory().getSelectedSlot() == heldSlot
                && client.player.getMainHandItem() == heldStack
                && !heldStack.isEmpty();
    }

    private static void rememberHold(Minecraft client) {
        heldPlayer = client.player;
        heldLevel = client.level;
        heldStack = client.player.getMainHandItem();
        heldSlot = client.player.getInventory().getSelectedSlot();
    }

    private static void refreshHold(Minecraft client) {
        if (!ENABLED.get()
                || !FULL_HOLD.get()
                || !ClientReady.interaction(client)
                || client.player.isDeadOrDying()
                || client.player.isSpectator()) {
            discardHold(client);
            return;
        }
        if (heldPlayer != null && !sameHold(client)) discardHold(client);
        if (heldPlayer == null && usingSpear(client)) rememberHold(client);
        if (!sameHold(client)) return;

        // Cancelling the packet does not cancel vanilla's local releaseUsingItem.
        // Restore only that suppressed release, never a server-forced interruption.
        if (restoreUse && !client.player.isUsingItem()) {
            client.player.startUsingItem(InteractionHand.MAIN_HAND);
        }
        restoreUse = false;
        if (!usingSpear(client)) {
            discardHold(client);
            return;
        }
        CombatInputController.holdUse(client, CombatInputController.Owner.AUTO_SPEAR);
    }

    private static void beforeSend(PacketSendEvent.Pre event) {
        if (releasing
                || !ENABLED.get()
                || !FULL_HOLD.get()
                || !(event.packet() instanceof ServerboundPlayerActionPacket packet)
                || packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM)
            return;
        Minecraft client = Minecraft.getInstance();
        if (!usingSpear(client) || client.player.isDeadOrDying() || client.player.isSpectator())
            return;
        rememberHold(client);
        restoreUse = true;
        event.cancel();
    }

    private static void discardHold(Minecraft client) {
        heldPlayer = null;
        heldLevel = null;
        heldStack = null;
        heldSlot = -1;
        restoreUse = false;
        CombatInputController.releaseUse(client, CombatInputController.Owner.AUTO_SPEAR);
    }

    /** Releases our native use when the option/module is disabled or unloaded. */
    public static void reset(Minecraft client) {
        AutoSpearFakeLag.flush(client);
        AutoSpearMotion.reset();
        AutoSpearImpact.stop(client);
        boolean release = sameHold(client) && (!client.player.isUsingItem() || usingSpear(client));
        releasing = true;
        try {
            if (release) client.gameMode.releaseUsingItem(client.player);
        } finally {
            discardHold(client);
            releasing = false;
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    static double motionMultiply() {
        return MOTION_MULTIPLY.get();
    }

    public static boolean impactBurstEnabled() {
        return IMPACT_BURST.get();
    }

    static double impactMultiply() {
        return IMPACT_MULTIPLY.get();
    }

    public static int setImpactBurst(Minecraft client, boolean value) {
        AutoSpearImpact.stop(client);
        AutoSpearMotion.reset();
        IMPACT_BURST.set(value);
        return 1;
    }

    public static int setImpactMultiply(Minecraft client, double value) {
        AutoSpearImpact.stop(client);
        IMPACT_MULTIPLY.set(value);
        return 1;
    }

    public static boolean fakeLagEnabled() {
        return FAKE_LAG.get();
    }

    static int fakeLagDelayMs() {
        return FAKE_LAG_DELAY.get();
    }

    static int fakeLagDurationMs() {
        return FAKE_LAG_DURATION.get();
    }

    public static int setFakeLag(Minecraft client, boolean value) {
        FAKE_LAG.set(value);
        AutoSpearFakeLag.flush(client);
        AutoSpearMotion.reset();
        return 1;
    }

    public static int setFakeLagDelay(Minecraft client, int value) {
        FAKE_LAG_DELAY.set(value);
        AutoSpearFakeLag.flush(client);
        return 1;
    }

    public static int setFakeLagDuration(Minecraft client, int value) {
        FAKE_LAG_DURATION.set(value);
        AutoSpearFakeLag.flush(client);
        return 1;
    }

    public static int setMotionMultiply(Minecraft ignoredClient, double value) {
        MOTION_MULTIPLY.set(value);
        AutoSpearMotion.reset();
        return 1;
    }

    public static int setIgnoreCooldown(Minecraft ignoredClient, boolean value) {
        IGNORE_COOLDOWN.set(value);
        return 1;
    }

    public static int setFullHold(Minecraft client, boolean value) {
        FULL_HOLD.set(value);
        if (!value) reset(client);
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        if (!value) reset(client);
        ClientChat.send(client, "SpearAssist " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }
}
