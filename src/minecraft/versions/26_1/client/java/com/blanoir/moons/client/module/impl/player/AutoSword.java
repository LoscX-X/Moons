/*
 * AutoSword for Moons.
 *
 * Switches to a sword in the hotbar when attacking an enemy. The preferred slot
 * defaults to the first hotbar slot; if that slot has no sword, any other sword
 * in the hotbar is used. The switch happens at the start of
 * MultiPlayerGameMode#attack, so the held-item packet is sent before the attack
 * packet.
 */
package com.blanoir.moons.client.module.impl.player;

import com.blanoir.moons.client.chat.ClientChat;
import com.blanoir.moons.client.config.settings.BooleanSetting;
import com.blanoir.moons.client.config.settings.IntSetting;
import com.blanoir.moons.client.event.EventBus;
import com.blanoir.moons.client.management.targeting.Targeting;
import com.blanoir.moons.client.utils.client.ClientReady;
import com.blanoir.moons.client.utils.player.HotbarQueries;
import com.blanoir.moons.client.utils.world.placement.PlacementCoordinator;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class AutoSword {
    private static final BooleanSetting ENABLED =
            new BooleanSetting.Builder().name("autosword.enabled").defaultValue(false).build();

    private static final IntSetting SWORD_SLOT =
            new IntSetting.Builder().name("autosword.slot").defaultValue(0).range(0, 8).build();

    private static final BooleanSetting SWITCH_BACK =
            new BooleanSetting.Builder().name("autosword.switchback").defaultValue(false).build();

    private static final IntSetting SWITCH_BACK_TICKS =
            new IntSetting.Builder()
                    .name("autosword.switchbackdelay")
                    .defaultValue(20)
                    .range(1, 100)
                    .build();

    private static int originalSlot = -1;
    private static int activeSwordSlot = -1;
    private static int restoreInTicks = -1;

    private AutoSword() {}

    public static void init() {
        EventBus.TICK.register(
                "AutoSword.tick",
                event -> {
                    Minecraft client = event.client();
                    tick(client);
                });
    }

    /** Called from MultiPlayerGameMode#attack before the attack packet is sent. */
    public static void onAttack(Entity target) {
        Minecraft client = Minecraft.getInstance();
        if (!ENABLED.get()
                || !ClientReady.gameplay(client)
                || PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)
                || !Targeting.isEnemyPlayer(client, target)) {
            return;
        }

        Inventory inventory = client.player.getInventory();
        if (isSword(inventory.getSelectedItem())) {
            if (originalSlot != -1) {
                restoreInTicks = -1;
            }
            return;
        }

        int slot = findSwordSlot(client);
        if (slot == -1 || slot == inventory.getSelectedSlot()) {
            return;
        }

        if (originalSlot == -1) {
            originalSlot = inventory.getSelectedSlot();
        }
        activeSwordSlot = slot;
        inventory.setSelectedSlot(slot);
        restoreInTicks = -1;
    }

    private static void tick(Minecraft client) {
        if (!ENABLED.get() || !ClientReady.gameplay(client)) {
            restore(client);
            return;
        }

        if (PlacementCoordinator.busy(PlacementCoordinator.Owner.ANTI_WEB)) {
            return;
        }

        if (originalSlot == -1) {
            return;
        }

        int selected = client.player.getInventory().getSelectedSlot();
        if (selected != activeSwordSlot) {
            if (selected != originalSlot) {
                // The player manually took over the hotbar.
                resetTracking();
            }
            return;
        }

        if (!SWITCH_BACK.get()) {
            return;
        }

        if (restoreInTicks < 0) {
            restoreInTicks = SWITCH_BACK_TICKS.get();
        }
        if (--restoreInTicks <= 0) {
            restore(client);
        }
    }

    private static int findSwordSlot(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        if (isSword(inventory.getItem(SWORD_SLOT.get()))) {
            return SWORD_SLOT.get();
        }
        return HotbarQueries.firstMatch(inventory, AutoSword::isSword);
    }

    private static boolean isSword(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.SWORDS);
    }

    private static void restore(Minecraft client) {
        var currentPlayer = client == null ? null : client.player;
        if (originalSlot != -1
                && client != null
                && currentPlayer != null
                && currentPlayer.getInventory().getSelectedSlot() == activeSwordSlot) {
            currentPlayer.getInventory().setSelectedSlot(originalSlot);
        }
        resetTracking();
    }

    private static void resetTracking() {
        originalSlot = -1;
        activeSwordSlot = -1;
        restoreInTicks = -1;
    }

    public static String statusText() {
        return ENABLED.get() ? "enabled" : "disabled";
    }

    public static int showStatus(Minecraft client) {
        ClientChat.send(
                client,
                "AutoSword: "
                        + statusText()
                        + ", slot: "
                        + SWORD_SLOT.get()
                        + ", switchBack: "
                        + toggleText(SWITCH_BACK.get())
                        + ", switchBackDelay: "
                        + SWITCH_BACK_TICKS.get()
                        + "t"
                        + ". Usage: .moons autosword <enable|disable|slot 0-8|switchback enable|disable|switchbackdelay 1-100>");
        return 1;
    }

    public static int setEnabled(Minecraft client, boolean newEnabled) {
        ENABLED.set(newEnabled);
        if (!newEnabled) {
            restore(client);
        }
        ClientChat.send(client, "AutoSword " + statusText() + ".");
        return 1;
    }

    public static int setSlot(Minecraft client, int value) {
        SWORD_SLOT.set(value);
        ClientChat.send(client, "AutoSword slot set to " + SWORD_SLOT.get() + ".");
        return 1;
    }

    public static int setSwitchBack(Minecraft client, boolean value) {
        SWITCH_BACK.set(value);
        ClientChat.send(client, "AutoSword switchBack " + toggleText(SWITCH_BACK.get()) + ".");
        return 1;
    }

    public static int setSwitchBackDelay(Minecraft client, int value) {
        SWITCH_BACK_TICKS.set(value);
        ClientChat.send(
                client, "AutoSword switchBackDelay set to " + SWITCH_BACK_TICKS.get() + " ticks.");
        return 1;
    }

    private static String toggleText(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
