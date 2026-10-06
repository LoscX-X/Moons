package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.access.MinecraftClientAccess;
import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.manager.placement.PlacementCoordinator;
import com.blanoir.moons.client.manager.rotation.SilentPacketRotation;
import com.blanoir.moons.client.module.impl.world.scaffold.Scaffold;
import com.blanoir.moons.client.utils.math.RandomMath;
import com.blanoir.moons.client.utils.player.HotbarQueries;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** Uses a hotbar player head for servers that implement UHC head consumables. */
public final class AutoHead {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autohead.enabled").defaultValue(false).build();
    private static final IntSetting HEALTH =
            new IntSetting.Builder().name("autohead.health").defaultValue(15).range(1, 20).build();
    private static final IntSetting MIN_DELAY =
            new IntSetting.Builder()
                    .name("autohead.delay.min")
                    .defaultValue(500)
                    .range(50, 5000)
                    .build();
    private static final IntSetting MAX_DELAY =
            new IntSetting.Builder()
                    .name("autohead.delay.max")
                    .defaultValue(1000)
                    .range(50, 5000)
                    .build();
    private static boolean initialized;
    private static long nextUseNanos;

    private AutoHead() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        EventBus.TICK.register("AutoHead.tick", event -> tick(event.client()));
        EventBus.CLIENT_CONTEXT_CHANGED.register("AutoHead.context", event -> nextUseNanos = 0);
    }

    private static void tick(Minecraft client) {
        if (!isEnabled()
                || client == null
                || client.thePlayer == null
                || client.theWorld == null
                || client.playerController == null
                || client.getNetHandler() == null) return;
        if (!client.thePlayer.isEntityAlive()
                || client.thePlayer.isSpectator()
                || MinecraftClientAccess.currentScreen(client) != null
                || client.thePlayer.isUsingItem()
                || client.gameSettings.keyBindUseItem.isKeyDown()
                || client.playerController.getIsHittingBlock()
                || Scaffold.isEnabled()
                || PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)
                || SilentPacketRotation.isBusy()
                || client.thePlayer.getHealth() > HEALTH.get()
                || client.thePlayer.getAbsorptionAmount() > 0) return;

        long now = System.nanoTime();
        if (now < nextUseNanos) return;
        var inventory = client.thePlayer.inventory;
        int slot =
                HotbarQueries.firstMatch(
                        inventory,
                        stack ->
                                stack != null
                                        && stack.getItem() == Items.skull
                                        && stack.getMetadata() == 3);
        if (slot < 0) return;

        int min = Math.min(MIN_DELAY.get(), MAX_DELAY.get());
        int max = Math.max(MIN_DELAY.get(), MAX_DELAY.get());
        nextUseNanos = now + RandomMath.betweenInclusive(min, max) * 1_000_000L;
        var connection = client.getNetHandler();
        int originalSlot = inventory.currentItem;
        // Keep the local held item intact, with ordered select/use/restore packets.
        connection.addToSendQueue(new C09PacketHeldItemChange(slot));
        try {
            connection.addToSendQueue(
                    new C08PacketPlayerBlockPlacement(inventory.getStackInSlot(slot)));
        } finally {
            connection.addToSendQueue(new C09PacketHeldItemChange(originalSlot));
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static int setEnabled(Minecraft client, boolean value) {
        ENABLED.set(value);
        nextUseNanos = 0;
        ClientChat.send(client, "AutoHead " + (value ? "enabled" : "disabled") + ".");
        return 1;
    }

    public static int setHealth(Minecraft client, int value) {
        HEALTH.set(value);
        return 1;
    }

    public static int setDelay(Minecraft client, int min, int max) {
        MIN_DELAY.set(Math.min(min, max));
        MAX_DELAY.set(Math.max(min, max));
        return 1;
    }
}
